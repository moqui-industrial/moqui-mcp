/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.agent.model

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.agent.AgentConfigUtil
import org.moqui.context.ExecutionContext

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OpenAiAgentModelProvider implements AgentModelProvider {
    protected final ExecutionContext ec

    OpenAiAgentModelProvider(ExecutionContext ec) {
        this.ec = ec
    }

    @Override
    Map generateJson(String systemPrompt, Map userPayload, Map options) {
        String apiKey = (options.apiKey ?: AgentConfigUtil.getPropertyOrEnv('moqui.agent.reranker.apiKey', 'OPENAI_API_KEY', '')).toString()
        if (!apiKey) throw new IllegalStateException('Missing OpenAI API key for reranker')

        String model = (options.model ?: 'gpt-5').toString()
        int timeoutSeconds = Math.max((options.timeoutSeconds ?: 300) as Integer, 1)
        int maxOutputTokens = Math.max((options.maxOutputTokens ?: 1200) as Integer, 200)
        int maxAttempts = Math.max((options.maxAttempts ?: AgentConfigUtil.getInt('moqui.agent.openai.maxAttempts', 2, 1)) as Integer, 1)
        int retryBackoffMs = Math.max((options.retryBackoffMs ?: AgentConfigUtil.getInt('moqui.agent.openai.retryBackoffMs', 750, 0)) as Integer, 0)
        BigDecimal temperature = (options.temperature ?: 0) as BigDecimal
        boolean logPayload = Boolean.TRUE.equals(options.logPayload)
        try {
            return callResponses(systemPrompt, userPayload, apiKey, model, timeoutSeconds, maxOutputTokens, maxAttempts, retryBackoffMs, temperature, logPayload,
                (options.endpoint ?: 'https://api.openai.com/v1/responses').toString())
        } catch (Throwable responsesError) {
            try {
                String chatBaseUrl = (options.chatEndpoint ?: 'https://api.openai.com/v1/chat/completions').toString()
                Map fallbackResult = callChatCompletions(systemPrompt, userPayload, apiKey, model, timeoutSeconds, maxOutputTokens, maxAttempts, retryBackoffMs, temperature, logPayload,
                    chatBaseUrl)
                fallbackResult.fallbackEndpoint = 'chat_completions'
                fallbackResult.primaryFailureReason = responsesError.message
                return fallbackResult
            } catch (Throwable chatError) {
                throw new IllegalStateException("Responses error: ${responsesError.message}; Chat Completions error: ${chatError.message}")
            }
        }
    }

    @Override
    Map generateText(String systemPrompt, Map userPayload, Map options) {
        Map result = generateJson(systemPrompt, userPayload, options)
        return [
            provider : result.provider,
            model : result.model,
            outputText : result.outputText,
            rawResponse : result.rawResponse,
            latencyMillis : result.latencyMillis,
            endpointUsed : result.endpointUsed
        ]
    }

    protected Map callResponses(String systemPrompt, Map userPayload, String apiKey, String model, int timeoutSeconds,
            int maxOutputTokens, int maxAttempts, int retryBackoffMs, BigDecimal temperature, boolean logPayload, String endpoint) {
        Map payload = [
                model : model,
                input : [
                    [role: 'system', content: [[type: 'input_text', text: systemPrompt]]],
                    [role: 'user', content: [[type: 'input_text', text: JsonOutput.toJson(userPayload ?: [:])]]]
                ],
                max_output_tokens: maxOutputTokens,
                text : [
                    format: buildResponsesJsonSchemaFormat()
                ]
        ]
        if (!isGpt5Family(model) && temperature != null) payload.temperature = temperature

        long startMs = System.currentTimeMillis()
        Map httpResult = doJsonPost(endpoint, apiKey, payload, timeoutSeconds, maxAttempts, retryBackoffMs)
        long latencyMillis = System.currentTimeMillis() - startMs
        if (httpResult.statusCode < 200 || httpResult.statusCode >= 300) throw new IllegalStateException("OpenAI reranker error ${httpResult.statusCode}: ${httpResult.responseText}")

        Map responseMap = (Map) httpResult.responseMap
        if (!responseMap) throw new IllegalStateException("OpenAI reranker returned non-JSON body (content-type=${httpResult.contentType ?: 'unknown'}): ${abbreviate(httpResult.responseText)}")

        String outputText = extractOutputText(responseMap)
        if (!outputText) throw new IllegalStateException('OpenAI reranker returned empty output text')

        [
            provider : 'openai',
            model : model,
            outputText : outputText,
            rawResponse : logPayload ? responseMap : null,
            latencyMillis : latencyMillis,
            endpointUsed : 'responses'
        ]
    }

    protected Map callChatCompletions(String systemPrompt, Map userPayload, String apiKey, String model, int timeoutSeconds,
            int maxOutputTokens, int maxAttempts, int retryBackoffMs, BigDecimal temperature, boolean logPayload, String endpoint) {
        Map payload = [
                model : model,
                messages : [
                    [role: 'system', content: systemPrompt],
                    [role: 'user', content: JsonOutput.toJson(userPayload ?: [:])]
                ],
                max_completion_tokens: maxOutputTokens,
                response_format : buildChatJsonSchemaFormat()
        ]
        if (!isGpt5Family(model) && temperature != null) payload.temperature = temperature

        long startMs = System.currentTimeMillis()
        Map httpResult = doJsonPost(endpoint, apiKey, payload, timeoutSeconds, maxAttempts, retryBackoffMs)
        long latencyMillis = System.currentTimeMillis() - startMs
        if (httpResult.statusCode < 200 || httpResult.statusCode >= 300) throw new IllegalStateException("OpenAI chat completions reranker error ${httpResult.statusCode}: ${httpResult.responseText}")

        Map responseMap = (Map) httpResult.responseMap
        if (!responseMap) throw new IllegalStateException("OpenAI chat completions reranker returned non-JSON body (content-type=${httpResult.contentType ?: 'unknown'}): ${abbreviate(httpResult.responseText)}")

        String outputText = extractChatCompletionText(responseMap)
        if (!outputText) throw new IllegalStateException('OpenAI chat completions reranker returned empty output text')

        [
            provider : 'openai',
            model : model,
            outputText : outputText,
            rawResponse : logPayload ? responseMap : null,
            latencyMillis: latencyMillis,
            endpointUsed : 'chat_completions'
        ]
    }

    protected static Map doJsonPost(String endpoint, String apiKey, Map payload, int timeoutSeconds, int maxAttempts, int retryBackoffMs) {
        String requestBody = JsonOutput.toJson(payload ?: [:])
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .version(HttpClient.Version.HTTP_1_1)
            .build()
        Throwable lastError = null

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header('Authorization', "Bearer ${apiKey}")
                    .header('Accept', 'application/json')
                    .header('Content-Type', 'application/json')
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
                String contentType = response.headers().firstValue('content-type').orElse('')
                String responseText = response.body()
                Map responseMap = [:]
                if (looksLikeJson(responseText, contentType)) {
                    Object parsed = new JsonSlurper().parseText(responseText)
                    if (parsed instanceof Map) responseMap = (Map) parsed
                }

                if (shouldRetryStatus(response.statusCode()) && attempt < maxAttempts) {
                    sleepBeforeRetry(retryBackoffMs, attempt)
                    continue
                }

                return [
                    statusCode : response.statusCode(),
                    responseText: responseText,
                    responseMap : responseMap,
                    contentType : contentType
                ]
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
                lastError = ie
                break
            } catch (Throwable t) {
                lastError = t
                if (attempt >= maxAttempts) break
                sleepBeforeRetry(retryBackoffMs, attempt)
            }
        }

        if (lastError instanceof RuntimeException) throw (RuntimeException) lastError
        throw new IllegalStateException(lastError?.message ?: 'OpenAI request failed', lastError)
    }

    protected static boolean looksLikeJson(String responseText, String contentType) {
        String normalizedBody = (responseText ?: '').trim()
        String normalizedType = (contentType ?: '').toLowerCase()
        if (!normalizedBody) return false
        if (normalizedType.contains('application/json')) return true
        return normalizedBody.startsWith('{') || normalizedBody.startsWith('[')
    }

    protected static boolean shouldRetryStatus(int statusCode) {
        return statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode == 500 ||
            statusCode == 502 || statusCode == 503 || statusCode == 504
    }

    protected static void sleepBeforeRetry(int retryBackoffMs, int attempt) {
        if (retryBackoffMs <= 0) return
        try {
            Thread.sleep((long) retryBackoffMs * (long) attempt)
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt()
            throw new IllegalStateException('request interrupted', ie)
        }
    }

    protected static Map buildResponsesJsonSchemaFormat() {
        [
            type : 'json_schema',
            name : 'agent_prompt_rerank_result',
            strict : false,
            schema : buildRerankerResultSchema(),
            description: 'Return the selected reranked agent prompt candidates as JSON.'
        ]
    }

    protected static Map buildChatJsonSchemaFormat() {
        [
            type : 'json_schema',
            json_schema: [
                name : 'agent_prompt_rerank_result',
                strict : false,
                schema : buildRerankerResultSchema(),
                description: 'Return the selected reranked agent prompt candidates as JSON.'
            ]
        ]
    }

    protected static Map buildRerankerResultSchema() {
        [
            type : 'object',
            additionalProperties: false,
            properties : [
                selected : [
                    type : 'array',
                    items: [
                        type : 'object',
                        additionalProperties: false,
                        properties : [
                            documentId: [type: 'string'],
                            rank : [type: 'integer'],
                            confidence: [type: 'number'],
                            reasonCode: [type: 'string']
                        ],
                        required : ['documentId', 'rank', 'confidence', 'reasonCode']
                    ]
                ],
                needsClarification: [type: 'boolean'],
                clarificationReason: [type: ['string', 'null']]
            ],
            required : ['selected', 'needsClarification', 'clarificationReason']
        ]
    }

    protected static String abbreviate(String text) {
        String value = (text ?: '').trim()
        if (value.length() <= 240) return value
        return value.substring(0, 240) + '...'
    }

    protected static String extractOutputText(Map responseMap) {
        if (!responseMap) return null
        Object direct = responseMap.output_text
        if (direct instanceof String && direct) return direct as String
        return extractResponsesOutputText(responseMap.output)
    }

    protected static String extractChatCompletionText(Map responseMap) {
        if (!responseMap) return null
        List choices = (responseMap.choices instanceof List) ? (responseMap.choices as List) : []
        if (!choices) return null
        Map first = (choices.first() instanceof Map) ? (Map) choices.first() : [:]
        Map message = (first.message instanceof Map) ? (Map) first.message : [:]
        Object content = message.content
        if (content instanceof String) return content as String
        return extractFromContent(content)
    }

    protected static String extractResponsesOutputText(Object output) {
        if (output instanceof Collection) {
            for (Object itemObj in (Collection) output) {
                if (!(itemObj instanceof Map)) continue
                Map item = (Map) itemObj
                String fromContent = extractFromContent(item.content)
                if (fromContent) return fromContent
                String fromOutputText = extractFromContent(item.output_text)
                if (fromOutputText) return fromOutputText
                String fromText = extractFromContent(item.text)
                if (fromText) return fromText
                String fromSummary = extractFromContent(item.summary)
                if (fromSummary) return fromSummary
            }
        }
        return extractFromContent(output)
    }

    protected static String extractFromContent(Object value) {
        if (value == null) return null
        if (value instanceof String) return value as String
        if (value instanceof Map) {
            Map map = (Map) value
            if (map.text instanceof String && map.text) return map.text as String
            if (map.text instanceof Map && ((Map) map.text).value instanceof String) return (((Map) map.text).value as String)
            if (map.output_text instanceof String && map.output_text) return map.output_text as String
            if (map.content != null) {
                String found = extractFromContent(map.content)
                if (found) return found
            }
            if (map.summary != null) {
                String found = extractFromContent(map.summary)
                if (found) return found
            }
            return null
        }
        if (value instanceof Collection) {
            for (Object nested in (Collection) value) {
                String found = extractFromContent(nested)
                if (found) return found
            }
        }
        return null
    }

    @Override
    Map embed(String inputText, Map options) {
        String apiKey = (options?.apiKey ?: AgentConfigUtil.getPropertyOrEnv('moqui.agent.embedding.apiKey', 'OPENAI_API_KEY', '')).toString().trim()
        if (!apiKey) throw new IllegalStateException('Missing OpenAI API key for embedding')

        String model = (options?.model ?: AgentConfigUtil.getString('moqui.agent.embedding.model', 'text-embedding-3-large')).toString()
        Integer dimensions = (options?.dimensions ?: AgentConfigUtil.getInt('moqui.agent.embedding.dimensions', 3072, 1)) as Integer
        int timeoutSeconds = Math.max((options?.timeoutSeconds ?: 60) as Integer, 5)
        int maxAttempts = Math.max((options?.maxAttempts ?: AgentConfigUtil.getInt('moqui.agent.openai.maxAttempts', 2, 1)) as Integer, 1)
        int retryBackoffMs = Math.max((options?.retryBackoffMs ?: AgentConfigUtil.getInt('moqui.agent.openai.retryBackoffMs', 750, 0)) as Integer, 0)

        Map payload = [model: model, input: [inputText]]
        if (dimensions) payload.dimensions = dimensions

        long startMs = System.currentTimeMillis()
        Map httpResult = doJsonPost('https://api.openai.com/v1/embeddings', apiKey, payload, timeoutSeconds, maxAttempts, retryBackoffMs)
        long latencyMillis = System.currentTimeMillis() - startMs

        if (httpResult.statusCode < 200 || httpResult.statusCode >= 300)
            throw new IllegalStateException("OpenAI embeddings error ${httpResult.statusCode}: ${abbreviate(httpResult.responseText)}")

        Map responseMap = (Map) httpResult.responseMap
        if (!responseMap) throw new IllegalStateException("OpenAI embeddings returned non-JSON body: ${abbreviate(httpResult.responseText)}")

        List dataList = (responseMap.data ?: []) as List
        List embedding = dataList ? ((dataList.first()?.embedding ?: []) as List) : []

        return [
            embedding     : embedding,
            provider      : 'openai',
            model         : model,
            dimensions    : embedding ? embedding.size() : dimensions,
            latencyMillis : latencyMillis
        ]
    }

    @Override
    Map capabilities() {
        return [
            supportsChat : true,
            supportsJson : true,
            supportsEmbeddings : true,
            supportsToolCalling : true,
            supportsProviderManagedContext : false
        ]
    }

    protected static boolean isGpt5Family(String model) {
        String normalized = (model ?: '').toLowerCase()
        return normalized.startsWith('gpt-5')
    }
}
