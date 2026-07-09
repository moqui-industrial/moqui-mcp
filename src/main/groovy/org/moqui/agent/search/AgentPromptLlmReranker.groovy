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
package org.moqui.agent.search

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.agent.model.AgentModelFacade
import org.moqui.agent.AgentConfigUtil
import org.moqui.context.ExecutionContext

import java.security.MessageDigest

class AgentPromptLlmReranker {
    protected final ExecutionContext ec
    protected final AgentPromptCandidateCompressor compressor = new AgentPromptCandidateCompressor()

    AgentPromptLlmReranker(ExecutionContext ec) {
        this.ec = ec
    }

    Map rerank(String queryText, Map queryProfile, List resultList, String indexName = null) {
        String provider = resolveProvider()
        boolean cacheEnabled = isRerankCacheEnabled()

        if (!provider || provider == 'none') {
            return [
                applied             : false,
                fallbackUsed        : true,
                provider            : provider ?: 'none',
                skippedReason       : 'provider_none',
                resultList          : resultList,
                llmRerankEnabled    : false,
                llmRerankApplied    : false,
                llmRerankCacheEnabled : cacheEnabled,
                llmRerankCacheHit   : false,
                rerankerSkippedReason : 'provider_none'
            ]
        }

        String configuredProvider = AgentConfigUtil.getNormalizedString('moqui.agent.reranker.provider', 'none')
        boolean rerankerApiKeyPresent = hasRerankerApiKey()
        String model = AgentConfigUtil.getString('moqui.agent.reranker.model', 'gpt-5')
        int maxCandidates = AgentConfigUtil.getInt('moqui.agent.reranker.maxCandidates', 8, 5)
        int timeoutMs = AgentConfigUtil.getInt('moqui.agent.reranker.timeoutMs', 30000, 1000)
        int maxOutputTokens = AgentConfigUtil.getInt('moqui.agent.reranker.maxOutputTokens', 1200, 200)
        BigDecimal temperature = AgentConfigUtil.getBigDecimal('moqui.agent.reranker.temperature', '0')
        boolean failOpen = AgentConfigUtil.getBoolean('moqui.agent.reranker.failOpen', true)
        boolean logPayload = AgentConfigUtil.getBoolean('moqui.agent.reranker.logPayload', false)

        List originalResults = (resultList ?: []) as List
        if (!originalResults) {
            return [
                applied             : false,
                fallbackUsed        : false,
                provider            : provider,
                configuredProvider  : configuredProvider,
                model               : model,
                resultList          : resultList,
                failureReason       : 'no_candidates',
                llmRerankEnabled    : true,
                llmRerankApplied    : false,
                llmRerankCacheEnabled : cacheEnabled,
                llmRerankCacheHit   : false
            ]
        }

        List candidateResults = originalResults.take(Math.min(maxCandidates, originalResults.size()))
        List compressedCandidates = []
        candidateResults.eachWithIndex { Map item, int idx ->
            Map compressed = compressor.compress(item ?: [:])
            compressed.rank = idx + 1
            compressed.score = item?.score
            compressedCandidates.add(compressed)
        }

        // --- cache lookup ---
        String cacheKey = null
        String cacheKeyHash = null
        if (cacheEnabled) {
            try {
                Map cacheInput = [
                    queryText     : queryText,
                    queryProfile  : queryProfile,
                    provider      : provider,
                    model         : model,
                    maxCandidates : maxCandidates,
                    candidates    : candidateResults,
                    indexName     : indexName
                ]
                cacheKey = makeRerankCacheKey(cacheInput)
                cacheKeyHash = shortHash(cacheKey)
                Map cached = getCachedRerankResult(cacheKey)
                if (cached != null) {
                    List finalResults = applySelectedOrder(cached.selected as List, candidateResults, originalResults)
                    return [
                        applied                  : true,
                        fallbackUsed             : false,
                        provider                 : cached.provider ?: provider,
                        configuredProvider       : configuredProvider,
                        rerankerApiKeyPresent     : rerankerApiKeyPresent,
                        model                    : cached.model ?: model,
                        resultList               : finalResults,
                        selectedDocumentId       : finalResults ? (finalResults.first() as Map).documentId : null,
                        reasonCode               : cached.reasonCode,
                        confidence               : cached.confidence,
                        candidateCount           : compressedCandidates.size(),
                        latencyMillis            : 0,
                        needsClarification       : cached.needsClarification,
                        clarificationReason      : cached.clarificationReason,
                        llmRerankEnabled         : true,
                        llmRerankApplied         : true,
                        llmRerankCacheEnabled    : true,
                        llmRerankCacheHit        : true,
                        llmRerankCacheKeyHash    : cacheKeyHash,
                        rerankerLatencyMillis    : 0
                    ]
                }
            } catch (Throwable t) {
                ec.logger.warn("Unable to read LLM rerank cache; continuing without cache: ${t.message}")
            }
        }

        // --- call provider ---
        String systemPrompt = '''
You are a deterministic reranker for Moqui ERP/MES agent documents.
You receive a user query and candidate documents already retrieved from OpenSearch.
Do not invent documents.
Do not execute anything.
Return only JSON.
Prefer executable screen_prompt documents for UI/action requests.
Prefer knowledgeOnly scenario/workflow/pattern documents for how-to/process/configuration/reference questions.
Respect runtimeExecutable and knowledgeOnly flags.
Use only documentIds from the candidate list, copied verbatim.
Return at most 5 selected candidates ordered best-first.
Keep reasonCode short and stable.
'''.trim()

        Map userPayload = [
            queryText   : queryText,
            queryIntent : [
                intentType    : queryProfile?.intentType,
                knowledgeType : queryProfile?.knowledgeType
            ],
            candidates  : compressedCandidates
        ]

        try {
            AgentModelFacade facade = new AgentModelFacade(ec)
            Map response = facade.generateJson(systemPrompt, userPayload, [
                model          : model,
                timeoutSeconds : Math.max((int) Math.ceil(timeoutMs / 1000.0d), 1),
                maxOutputTokens: maxOutputTokens,
                temperature    : temperature,
                logPayload     : logPayload
            ])

            Map parsed = parseJsonResponse(response.outputText as String)
            List selected = (parsed?.selected instanceof List) ? (parsed.selected as List) : []
            if (!selected) throw new IllegalStateException('LLM reranker returned no selected candidates')

            List finalResults = applySelectedOrder(selected, candidateResults, originalResults)
            Map firstDoc = finalResults ? (finalResults.first() as Map) : [:]
            String firstReason = firstDoc.llmRerankReasonCode
            Object firstConf = firstDoc.llmRerankConfidence

            Map result = [
                applied               : true,
                fallbackUsed          : false,
                provider              : response.provider ?: provider,
                configuredProvider    : configuredProvider,
                rerankerApiKeyPresent  : rerankerApiKeyPresent,
                model                 : response.model ?: model,
                resultList            : finalResults,
                selectedDocumentId    : firstDoc.documentId,
                reasonCode            : firstReason,
                confidence            : firstConf,
                candidateCount        : compressedCandidates.size(),
                latencyMillis         : response.latencyMillis,
                needsClarification    : parsed?.needsClarification,
                clarificationReason   : parsed?.clarificationReason,
                rawResponse           : logPayload ? response.rawResponse : null,
                llmRerankEnabled      : true,
                llmRerankApplied      : true,
                llmRerankCacheEnabled : cacheEnabled,
                llmRerankCacheHit     : false,
                llmRerankCacheKeyHash : cacheKeyHash,
                rerankerLatencyMillis : response.latencyMillis
            ]

            if (cacheEnabled && cacheKey && selected) {
                try {
                    Map toCache = [
                        selected            : selected,
                        needsClarification  : parsed?.needsClarification,
                        clarificationReason : parsed?.clarificationReason,
                        provider            : response.provider ?: provider,
                        model               : response.model ?: model,
                        promptVersion       : getRerankPromptVersion(),
                        reasonCode          : firstReason,
                        confidence          : firstConf,
                        createdDateMillis   : System.currentTimeMillis()
                    ]
                    putCachedRerankResult(cacheKey, toCache)
                } catch (Throwable t) {
                    ec.logger.warn("Unable to write LLM rerank cache; continuing: ${t.message}")
                }
            }

            return result
        } catch (Throwable t) {
            if (!failOpen) throw t
            return [
                applied               : false,
                fallbackUsed          : true,
                provider              : provider,
                configuredProvider    : configuredProvider,
                rerankerApiKeyPresent  : rerankerApiKeyPresent,
                model                 : model,
                resultList            : resultList,
                candidateCount        : compressedCandidates.size(),
                failureReason         : t.message,
                llmRerankEnabled      : true,
                llmRerankApplied      : false,
                llmRerankCacheEnabled : cacheEnabled,
                llmRerankCacheHit     : false,
                llmRerankCacheKeyHash : cacheKeyHash
            ]
        }
    }

    // --- cache helpers ---

    private boolean isRerankCacheEnabled() {
        return AgentConfigUtil.getBoolean('moqui.agent.reranker.cache.enabled', true)
    }

    private String getRerankCacheName() {
        return AgentConfigUtil.getString('moqui.agent.reranker.cache.name', 'moqui.agent.reranker.result')
    }

    private long getRerankCacheTtlMillis() {
        long ttlSeconds = AgentConfigUtil.getLong('moqui.agent.reranker.cache.ttlSeconds', 86400L)
        return Math.max(ttlSeconds, 1L) * 1000L
    }

    private String getRerankPromptVersion() {
        return AgentConfigUtil.getString('moqui.agent.reranker.promptVersion', '2026-05-llm-rerank-v1')
    }

    private String makeRerankCacheKey(Map input) {
        String provider = input.provider ?: resolveProvider()
        String model = input.model ?: AgentConfigUtil.getString('moqui.agent.reranker.model', 'gpt-5')
        Map queryProfile = (Map) input.queryProfile ?: [:]

        List candidateSig = ((List) input.candidates ?: []).collect { Object obj ->
            Map c = (Map) obj
            [
                documentId   : c.documentId,
                documentKind : c.documentKind,
                domainObject : c.domainObject,
                actionKind   : c.actionKind,
                score        : roundScore(c.score)
            ]
        }

        Map keyMap = [
            query         : normalizeQuery(input.queryText as String),
            intentType    : queryProfile.intentType,
            knowledgeType : queryProfile.knowledgeType,
            provider      : provider,
            model         : model,
            promptVersion : getRerankPromptVersion(),
            maxCandidates : input.maxCandidates,
            indexName     : input.indexName ?: '',
            candidates    : candidateSig
        ]

        return 'llm-rerank:' + sha256(stableJson(keyMap))
    }

    private Map getCachedRerankResult(String cacheKey) {
        def cache = ec.cache.getCache(getRerankCacheName())
        Map entry = (Map) cache.get(cacheKey)
        if (!entry) return null
        Long expireTimeMillis = entry.expireTimeMillis as Long
        if (expireTimeMillis && expireTimeMillis < System.currentTimeMillis()) {
            cache.remove(cacheKey)
            return null
        }
        return (Map) entry.value
    }

    private void putCachedRerankResult(String cacheKey, Map value) {
        def cache = ec.cache.getCache(getRerankCacheName())
        cache.put(cacheKey, [
            expireTimeMillis: System.currentTimeMillis() + getRerankCacheTtlMillis(),
            value           : value
        ])
    }

    private String normalizeQuery(String queryText) {
        return (queryText ?: '').trim().replaceAll(/\s+/, ' ').toLowerCase(Locale.ROOT)
    }

    private BigDecimal roundScore(Object score) {
        if (score == null) return null
        try { return new BigDecimal(score.toString()).setScale(4, BigDecimal.ROUND_HALF_UP) }
        catch (Throwable ignored) { return null }
    }

    private String stableJson(Object value) {
        return JsonOutput.toJson(value)
    }

    private String sha256(String text) {
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(text.getBytes('UTF-8'))
        return digest.collect { String.format('%02x', it) }.join('')
    }

    private String shortHash(String text) {
        return sha256(text).substring(0, 12)
    }

    // --- result assembly ---

    private List applySelectedOrder(List selected, List candidateResults, List originalResults) {
        Map<String, Integer> orderById = [:]
        Map<String, Map> metadataById = [:]
        selected.eachWithIndex { Object item, int idx ->
            Map row = (item instanceof Map) ? (Map) item : [:]
            String documentId = row.documentId as String
            if (!documentId) return
            orderById[documentId] = idx
            metadataById[documentId] = row
        }

        List selectedOrdered = []
        List remainder = []
        candidateResults.each { Map item ->
            String documentId = item?.documentId as String
            if (documentId && orderById.containsKey(documentId)) {
                Map annotated = new LinkedHashMap(item)
                Map meta = metadataById[documentId] ?: [:]
                annotated.llmRerankReasonCode = meta.reasonCode
                annotated.llmRerankConfidence = meta.confidence
                annotated.llmRerankRank = (orderById[documentId] as Integer) + 1
                selectedOrdered << annotated
            } else {
                remainder << item
            }
        }
        selectedOrdered.sort { a, b -> ((a.llmRerankRank ?: 9999) as Integer) <=> ((b.llmRerankRank ?: 9999) as Integer) }
        List tail = originalResults.size() > candidateResults.size() ? originalResults.drop(candidateResults.size()) : []
        return (selectedOrdered + remainder + tail) as List
    }

    // --- provider resolution ---

    protected static String resolveProvider() {
        String configured = AgentConfigUtil.getNormalizedString('moqui.agent.reranker.provider', 'none')
        if (!configured || configured == 'none') return 'none'
        if (configured == 'auto') return hasRerankerApiKey() ? 'openai' : 'none'
        return configured
    }

    protected static boolean hasRerankerApiKey() {
        String explicitApiKey = AgentConfigUtil.getString('moqui.agent.reranker.apiKey', '').trim()
        String envApiKey = AgentConfigUtil.getEnv('OPENAI_API_KEY', '').trim()
        return Boolean.TRUE.equals(explicitApiKey || envApiKey)
    }

    protected static Map parseJsonResponse(String text) {
        String cleaned = (text ?: '').trim()
        if (cleaned.startsWith('```')) {
            cleaned = cleaned.replaceFirst(/^```(?:json)?\s*/, '').replaceFirst(/\s*```$/, '')
        }
        Object parsed = new JsonSlurper().parseText(cleaned)
        return (parsed instanceof Map) ? (Map) parsed : [:]
    }
}
