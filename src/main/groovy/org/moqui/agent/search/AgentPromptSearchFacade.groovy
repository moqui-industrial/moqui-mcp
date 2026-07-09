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

import org.moqui.agent.AgentConfigUtil
import org.moqui.agent.AgentToolSupport
import org.moqui.context.ExecutionContext

class AgentPromptSearchFacade {
    protected final ExecutionContext ec
    protected final ElasticBackendAdapter adapter
    protected final AgentPromptCandidateBuilder candidateBuilder = new AgentPromptCandidateBuilder()

    AgentPromptSearchFacade(ExecutionContext ec, ElasticBackendAdapter adapter = null) {
        this.ec = ec
        this.adapter = adapter ?: new ElasticBackendAdapter(ec)
    }

    Map search(Map context) {
        Map prepared = candidateBuilder.build(context ?: [:])
        AgentConfigUtil.assertRuntimeConfiguration(ec, [
            requireEmbedding: (prepared.requestedMode as String) in ['vector', 'hybrid', 'hybrid_rerank', 'hybrid_llm_rerank'],
            requireReranker: (prepared.requestedMode as String) == 'hybrid_llm_rerank'
        ])
        Map configValidation = AgentConfigUtil.validateRuntimeConfiguration(ec, [
            requireEmbedding: (prepared.requestedMode as String) in ['vector', 'hybrid', 'hybrid_rerank', 'hybrid_llm_rerank'],
            requireReranker: (prepared.requestedMode as String) == 'hybrid_llm_rerank'
        ])
        String effectiveIndex = prepared.effectiveIndex as String
        if (!adapter.indexExists(effectiveIndex)) {
            return [
                resultList : [],
                topDocument : null,
                searchDebug : [indexName: effectiveIndex, mode: prepared.requestedMode, found: 0, warning: 'Index does not exist',
                    configStatus: configValidation.status, configWarnings: configValidation.warnings]
            ]
        }

        AgentPromptDeterministicReranker reranker = new AgentPromptDeterministicReranker(prepared)
        AgentPromptLlmReranker llmReranker = new AgentPromptLlmReranker(ec)
        List lexicalHits = []
        String modeUsed = prepared.requestedMode as String
        Map embeddingResult = [:]
        List vectorHits = []
        Map searchDebug = [:]

        if (modeUsed in ['bm25', 'hybrid', 'hybrid_rerank', 'hybrid_llm_rerank']) {
            Map lexicalSearchResult = adapter.search(effectiveIndex, prepared.lexicalQuerySpec) ?: [:]
            lexicalHits = (((lexicalSearchResult.hits ?: [:]).hits ?: []) as List)
        }

        if (modeUsed in ['vector', 'hybrid', 'hybrid_rerank', 'hybrid_llm_rerank']) {
            embeddingResult = ec.service.sync().name('org.moqui.agent.AgentDocumentServices.get#QueryEmbedding').parameters([
                queryText : prepared.queryText,
                embeddingProvider : AgentConfigUtil.getString('moqui.agent.embedding.provider', 'none'),
                embeddingModel : AgentConfigUtil.getString('moqui.agent.embedding.model', 'text-embedding-3-large'),
                embeddingDimensions : AgentConfigUtil.getInt('moqui.agent.embedding.dimensions', 3072, 1)
            ]).call()
            List embedding = (embeddingResult.embedding instanceof List) ? embeddingResult.embedding as List : []
            if (embedding) {
                Map vectorQuerySpec = [
                    _source: prepared.sourceFilter,
                    size : prepared.candidateSize,
                    query : [
                        knn: [
                            embedding: [
                                vector: embedding,
                                k : prepared.candidateSize
                            ]
                        ]
                    ]
                ]
                if (prepared.filterList) vectorQuerySpec.query.knn.embedding.filter = [bool: [filter: prepared.filterList]]
                try {
                    Map vectorSearchResult = adapter.search(effectiveIndex, vectorQuerySpec) ?: [:]
                    vectorHits = (((vectorSearchResult.hits ?: [:]).hits ?: []) as List)
                    if (prepared.searchDebugEnabled) searchDebug = [vectorQuerySpec: vectorQuerySpec]
                } catch (Throwable t) {
                    modeUsed = 'bm25_fallback'
                    if (prepared.searchDebugEnabled) searchDebug = [vectorQuerySpec: vectorQuerySpec, vectorError: t.message]
                }
            } else {
                modeUsed = 'bm25_fallback'
            }
        }

        List resultList
        if (modeUsed == 'vector') {
            resultList = vectorHits.collect { Map hit -> AgentToolSupport.normalizeSearchHit(hit) }
        } else if (modeUsed in ['hybrid', 'hybrid_rerank', 'hybrid_llm_rerank']) {
            resultList = reranker.rerankHybrid(lexicalHits, vectorHits)
        } else {
            if ((prepared.requestedMode as String) == 'vector' && modeUsed == 'bm25_fallback' && !lexicalHits) {
                Map lexicalSearchResult = adapter.search(effectiveIndex, prepared.lexicalQuerySpec) ?: [:]
                lexicalHits = (((lexicalSearchResult.hits ?: [:]).hits ?: []) as List)
            }
            resultList = reranker.rerankLexical(lexicalHits)
        }

        Map llmRerankResult = [:]
        if ((prepared.requestedMode as String) == 'hybrid_llm_rerank' && modeUsed != 'bm25_fallback') {
            llmRerankResult = llmReranker.rerank(prepared.queryText as String, reranker.queryProfile, resultList, effectiveIndex)
            resultList = (llmRerankResult.resultList ?: resultList) as List
            if (Boolean.TRUE.equals(llmRerankResult.applied)) {
                modeUsed = 'hybrid_llm_rerank'
            } else if (Boolean.TRUE.equals(llmRerankResult.fallbackUsed)) {
                modeUsed = 'hybrid_rerank_fallback'
            }
        }

        if (!Boolean.TRUE.equals(prepared.includeNonExecutable)) {
            resultList = resultList.findAll { Map item ->
                !(prepared.hardExcludedChannels as List).contains((item.executionChannel ?: '') as String)
            }
        }
        int size = prepared.size as Integer
        if (size > 0 && resultList.size() > size) resultList = resultList.subList(0, size)
        Map topDocument = resultList ? (resultList.first() as Map) : null

        Map searchDebugSummary = [
            indexName                  : effectiveIndex,
            modeRequested              : prepared.requestedMode,
            modeUsed                   : modeUsed,
            found                      : resultList.size(),
            queryIntentType            : reranker.queryProfile?.intentType,
            queryKnowledgeType         : reranker.queryProfile?.knowledgeType,
            embeddingCacheHit          : embeddingResult?.cacheHit,
            embeddingProvider          : embeddingResult?.embeddingProvider ?: embeddingResult?.provider ?: embeddingResult?.providerUsed,
            embeddingModel             : embeddingResult?.embeddingModel ?: embeddingResult?.model ?: embeddingResult?.modelUsed,
            rerankerProvider           : llmRerankResult?.provider,
            rerankerConfiguredProvider : llmRerankResult?.configuredProvider,
            rerankerApiKeyPresent       : llmRerankResult?.rerankerApiKeyPresent,
            rerankerModel              : llmRerankResult?.model,
            rerankerApplied            : llmRerankResult?.applied,
            rerankerFallbackUsed       : llmRerankResult?.fallbackUsed,
            rerankerCandidateCount     : llmRerankResult?.candidateCount,
            rerankerSelectedDocumentId : llmRerankResult?.selectedDocumentId,
            rerankerReasonCode         : llmRerankResult?.reasonCode,
            rerankerConfidence         : llmRerankResult?.confidence,
            rerankerLatencyMillis      : llmRerankResult?.latencyMillis ?: llmRerankResult?.rerankerLatencyMillis,
            rerankerFailureReason      : llmRerankResult?.failureReason,
            rerankerSkippedReason      : llmRerankResult?.rerankerSkippedReason,
            llmRerankEnabled           : llmRerankResult?.llmRerankEnabled,
            llmRerankApplied           : llmRerankResult?.llmRerankApplied,
            llmRerankCacheEnabled      : llmRerankResult?.llmRerankCacheEnabled,
            llmRerankCacheHit          : llmRerankResult?.llmRerankCacheHit,
            llmRerankCacheKeyHash      : llmRerankResult?.llmRerankCacheKeyHash
        ]

        if (prepared.searchDebugEnabled) {
            searchDebugSummary = (searchDebug ?: [:]) + searchDebugSummary + [lexicalQuerySpec: prepared.lexicalQuerySpec]
        }
        if (configValidation.warnings) {
            searchDebugSummary.configStatus = configValidation.status
            searchDebugSummary.configWarnings = configValidation.warnings
        }

        [resultList: resultList, topDocument: topDocument, searchDebug: searchDebugSummary]
    }
}
