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

/**
 * OpenAI-compatible provider for both chat completions and embeddings.
 * Works with LiteLLM Proxy, vLLM, Ollama /v1/chat/completions, LocalAI, etc.
 *
 * Reranker configured via:
 *   moqui.agent.reranker.compat.baseUrl   (required)
 *   moqui.agent.reranker.compat.apiKey    (or env OPENAI_COMPATIBLE_API_KEY)
 *   moqui.agent.reranker.compat.apiKeyEnv (default: OPENAI_COMPATIBLE_API_KEY)
 *
 * Embedding configured via:
 *   moqui.agent.embedding.compat.baseUrl  (required)
 *   moqui.agent.embedding.compat.apiKey   (or env OPENAI_COMPATIBLE_API_KEY)
 *   moqui.agent.embedding.compat.apiKeyEnv
 *   moqui.agent.embedding.compat.includeDimensions (default: true)
 */
class OpenAiCompatibleAgentModelProvider implements AgentModelProvider {
    protected final ExecutionContext ec

    OpenAiCompatibleAgentModelProvider(ExecutionContext ec) {
        this.ec = ec
    }

    @Override
    Map generateJson(String systemPrompt, Map userPayload, Map options) {
        String baseUrl = (options.baseUrl ?: AgentConfigUtil.getString('moqui.agent.reranker.compat.baseUrl', '')).toString().trimRight('/')
        if (!baseUrl) throw new IllegalStateException('Missing moqui.agent.reranker.compat.baseUrl for openai_compatible provider')

        String apiKeyEnvName = AgentConfigUtil.getString('moqui.agent.reranker.compat.apiKeyEnv', 'OPENAI_COMPATIBLE_API_KEY')
        String apiKey = (options.apiKey
            ?: AgentConfigUtil.getString('moqui.agent.reranker.compat.apiKey', '')
            ?: AgentConfigUtil.getEnv(apiKeyEnvName, '')
        ).toString().trim()

        String model = (options.model ?: AgentConfigUtil.getString('moqui.agent.reranker.model', '')).toString()
        if (!model) throw new IllegalStateException('moqui.agent.reranker.model must be set for openai_compatible provider')

        int timeoutSeconds = Math.max((options.timeoutSeconds ?: 300) as Integer, 1)
        int maxOutputTokens = Math.max((options.maxOutputTokens ?: 1200) as Integer, 200)
        int maxAttempts = Math.max((options.maxAttempts ?: AgentConfigUtil.getInt('moqui.agent.openai.maxAttempts', 2, 1)) as Integer, 1)
        int retryBackoffMs = Math.max((options.retryBackoffMs ?: AgentConfigUtil.getInt('moqui.agent.openai.retryBackoffMs', 750, 0)) as Integer, 0)
        BigDecimal temperature = (options.temperature ?: 0) as BigDecimal
        boolean logPayload = Boolean.TRUE.equals(options.logPayload)

        String endpoint = "${baseUrl}/chat/completions"
        return callChatCompletions(systemPrompt, userPayload, apiKey, model, timeoutSeconds,
            maxOutputTokens, maxAttempts, retryBackoffMs, temperature, logPayload, endpoint)
    }

    @Override
    Map generateText(String systemPrompt, Map userPayload, Map options) {
        return generateJson(systemPrompt, userPayload, options)
    }

    protected Map callChatCompletions(String systemPrompt, Map userPayload, String apiKey, String model,
            int timeoutSeconds, int maxOutputTokens, int maxAttempts, int retryBackoffMs,
            BigDecimal temperature, boolean logPayload, String endpoint) {

        String userContent = JsonOutput.toJson(userPayload ?: [:])
        Map requestBody = [
            model      : model,
            messages   : [
                [role: 'system', content: systemPrompt],
                [role: 'user', content: userContent]
            ],
            max_tokens : maxOutputTokens,
            temperature: temperature
        ]
        // Request JSON output if supported; harmless otherwise
        requestBody.response_format = [type: 'json_object']

        String requestJson = JsonOutput.toJson(requestBody)
        if (logPayload) ec?.logger?.debug("OpenAiCompatible request → ${endpoint}: ${requestJson.take(2000)}")

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30)))
            .build()

        long startMs = System.currentTimeMillis()
        Throwable lastError = null
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header('Content-Type', 'application/json')
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                if (apiKey) reqBuilder.header('Authorization', "Bearer ${apiKey}")
                HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())

                if (resp.statusCode() in [408, 409, 429, 500, 502, 503, 504]) {
                    lastError = new IOException("HTTP ${resp.statusCode()}: ${resp.body()?.take(200)}")
                    if (attempt < maxAttempts && retryBackoffMs > 0) Thread.sleep(retryBackoffMs * attempt)
                    continue
                }
                if (resp.statusCode() != 200) throw new IllegalStateException("HTTP ${resp.statusCode()}: ${resp.body()?.take(400)}")

                Map parsed = new JsonSlurper().parseText(resp.body()) as Map
                String outputText = (parsed?.choices as List)?.first()?.message?.content as String ?: ''
                if (logPayload) ec?.logger?.debug("OpenAiCompatible response: ${outputText.take(1000)}")

                return [
                    outputText  : outputText,
                    provider    : 'openai_compatible',
                    model       : (parsed?.model ?: model).toString(),
                    latencyMillis: System.currentTimeMillis() - startMs,
                    rawResponse : logPayload ? resp.body() : null
                ]
            } catch (IOException e) {
                lastError = e
                if (attempt < maxAttempts && retryBackoffMs > 0) Thread.sleep(retryBackoffMs * attempt)
            }
        }
        throw new IOException("openai_compatible call failed after ${maxAttempts} attempt(s): ${lastError?.message}")
    }

    @Override
    Map embed(String inputText, Map options) {
        String baseUrl = (options?.baseUrl ?: AgentConfigUtil.getString('moqui.agent.embedding.compat.baseUrl', '')).toString().trimRight('/')
        if (!baseUrl) throw new IllegalStateException('Missing moqui.agent.embedding.compat.baseUrl for openai_compatible embedding provider')

        String apiKeyEnvName = AgentConfigUtil.getString('moqui.agent.embedding.compat.apiKeyEnv', 'OPENAI_COMPATIBLE_API_KEY')
        String apiKey = (options?.apiKey
            ?: AgentConfigUtil.getString('moqui.agent.embedding.compat.apiKey', '')
            ?: AgentConfigUtil.getEnv(apiKeyEnvName, '')
        ).toString().trim()

        String model = (options?.model ?: AgentConfigUtil.getString('moqui.agent.embedding.model', '')).toString()
        if (!model) throw new IllegalStateException('moqui.agent.embedding.model must be set for openai_compatible embedding provider')

        Integer dimensions = (options?.dimensions ?: null) as Integer
        boolean includeDimensions = AgentConfigUtil.getBoolean('moqui.agent.embedding.compat.includeDimensions', true)
        int timeoutSeconds = Math.max((options?.timeoutSeconds ?: 60) as Integer, 5)
        int maxAttempts = Math.max((options?.maxAttempts ?: AgentConfigUtil.getInt('moqui.agent.openai.maxAttempts', 2, 1)) as Integer, 1)
        int retryBackoffMs = Math.max((options?.retryBackoffMs ?: AgentConfigUtil.getInt('moqui.agent.openai.retryBackoffMs', 750, 0)) as Integer, 0)

        Map requestBody = [model: model, input: [inputText]]
        if (dimensions && includeDimensions) requestBody.dimensions = dimensions

        String requestJson = JsonOutput.toJson(requestBody)
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30)))
            .build()

        long startMs = System.currentTimeMillis()
        Throwable lastError = null
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create("${baseUrl}/embeddings"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header('Content-Type', 'application/json')
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                if (apiKey) reqBuilder.header('Authorization', "Bearer ${apiKey}")
                HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())

                if (resp.statusCode() in [408, 409, 429, 500, 502, 503, 504]) {
                    lastError = new IOException("HTTP ${resp.statusCode()}: ${resp.body()?.take(200)}")
                    if (attempt < maxAttempts && retryBackoffMs > 0) Thread.sleep(retryBackoffMs * attempt)
                    continue
                }
                if (resp.statusCode() != 200) throw new IllegalStateException("HTTP ${resp.statusCode()}: ${resp.body()?.take(400)}")

                Map parsed = new JsonSlurper().parseText(resp.body()) as Map
                List dataList = (parsed?.data ?: []) as List
                List embedding = dataList ? ((dataList.first()?.embedding ?: []) as List) : []

                return [
                    embedding     : embedding,
                    provider      : 'openai_compatible',
                    model         : (parsed?.model ?: model).toString(),
                    dimensions    : embedding ? embedding.size() : dimensions,
                    latencyMillis : System.currentTimeMillis() - startMs
                ]
            } catch (IOException e) {
                lastError = e
                if (attempt < maxAttempts && retryBackoffMs > 0) Thread.sleep(retryBackoffMs * attempt)
            }
        }
        throw new IOException("openai_compatible embed failed after ${maxAttempts} attempt(s): ${lastError?.message}")
    }

    @Override
    Map capabilities() {
        return [
            supportsChat : AgentConfigUtil.getBoolean('moqui.agent.provider.openai_compatible.supportsChat', true),
            supportsJson : AgentConfigUtil.getBoolean('moqui.agent.provider.openai_compatible.supportsJson', true),
            supportsEmbeddings : AgentConfigUtil.getBoolean('moqui.agent.provider.openai_compatible.supportsEmbeddings', true),
            supportsToolCalling : AgentConfigUtil.getBoolean('moqui.agent.provider.openai_compatible.supportsToolCalling', false),
            supportsProviderManagedContext : AgentConfigUtil.getBoolean('moqui.agent.provider.openai_compatible.supportsProviderManagedContext', false)
        ]
    }
}
