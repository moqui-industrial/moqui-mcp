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
package org.moqui.agent

import org.moqui.context.ExecutionContext

class AgentConfigUtil {
    static final Set<String> SUPPORTED_SEARCH_ENGINES = ['opensearch', 'elasticsearch'] as Set<String>
    static final Set<String> SUPPORTED_SEARCH_MODES = ['bm25', 'vector', 'hybrid', 'hybrid_rerank', 'hybrid_llm_rerank'] as Set<String>
    static final Set<String> SUPPORTED_EMBEDDING_PROVIDERS = ['none', 'openai', 'openai_compatible'] as Set<String>
    static final Set<String> SUPPORTED_RERANKER_PROVIDERS = ['none', 'auto', 'openai', 'openai_compatible'] as Set<String>

    static String getString(String propertyName, String defaultValue) {
        return System.getProperty(propertyName, defaultValue)?.toString()
    }

    static String getNormalizedString(String propertyName, String defaultValue) {
        return getString(propertyName, defaultValue)?.trim()?.toLowerCase()
    }

    static boolean getBoolean(String propertyName, boolean defaultValue) {
        return Boolean.parseBoolean(getString(propertyName, Boolean.toString(defaultValue)))
    }

    static int getInt(String propertyName, int defaultValue, int minValue) {
        int value = parseInt(propertyName, defaultValue)
        return Math.max(value, minValue)
    }

    static int parseInt(String propertyName, int defaultValue) {
        return Integer.parseInt(getString(propertyName, Integer.toString(defaultValue)))
    }

    static long getLong(String propertyName, long defaultValue) {
        return Long.parseLong(getString(propertyName, Long.toString(defaultValue)))
    }

    static BigDecimal getBigDecimal(String propertyName, String defaultValue) {
        return new BigDecimal(getString(propertyName, defaultValue))
    }

    static String getPropertyOrEnv(String propertyName, String envName, String defaultValue = '') {
        String value = System.getProperty(propertyName, '')
        if (value) return value.toString()
        String envValue = System.getenv(envName) ?: ''
        return envValue ? envValue.toString() : defaultValue
    }

    static String getEnv(String envName, String defaultValue = '') {
        String envValue = System.getenv(envName) ?: ''
        return envValue ? envValue.toString() : defaultValue
    }

    static Map validateRuntimeConfiguration(ExecutionContext ec, Map options = [:]) {
        List<String> errors = []
        List<String> warnings = []

        String searchEngine = getNormalizedString('moqui.agent.searchEngine', 'opensearch')
        String searchMode = getNormalizedString('moqui.agent.search.mode', 'hybrid')
        String embeddingProvider = getNormalizedString('moqui.agent.embedding.provider', 'openai')
        String rerankerProvider = getNormalizedString('moqui.agent.reranker.provider', 'none')
        String promptIndexName = getString('moqui.agent.prompt.indexName', 'moqui_agent_prompts_v1')
        String ragIndexName = getString('moqui.agent.rag.indexName', 'moqui_artifacts_v1')
        Integer batchSize = safeParseInt('moqui.agent.index.batchSize', 250, errors)
        Integer findMaxLimit = safeParseInt('moqui.agent.find.maxLimit', 100, errors)
        Integer rerankerMaxCandidates = safeParseInt('moqui.agent.reranker.maxCandidates', 8, errors)
        Integer rerankerTimeoutMs = safeParseInt('moqui.agent.reranker.timeoutMs', 30000, errors)
        Integer rerankerMaxOutputTokens = safeParseInt('moqui.agent.reranker.maxOutputTokens', 1200, errors)
        Integer telemetrySummaryLength = safeParseInt('moqui.agent.elasticTelemetry.maxSummaryLength', 2000, errors)

        boolean requireEmbedding = Boolean.TRUE.equals(options.requireEmbedding)
        boolean requireReranker = Boolean.TRUE.equals(options.requireReranker)
        boolean checkIndexes = Boolean.TRUE.equals(options.checkIndexes)

        if (!SUPPORTED_SEARCH_ENGINES.contains(searchEngine)) {
            errors.add("Unsupported moqui.agent.searchEngine [${searchEngine}]")
        }
        if (!SUPPORTED_SEARCH_MODES.contains(searchMode)) {
            errors.add("Unsupported moqui.agent.search.mode [${searchMode}]")
        }
        if (embeddingProvider == 'ollama') {
            errors.add('Embedding provider [ollama] is no longer supported; use provider=openai_compatible with an OpenAI-compatible Ollama endpoint')
        } else if (!SUPPORTED_EMBEDDING_PROVIDERS.contains(embeddingProvider)) {
            errors.add("Unsupported moqui.agent.embedding.provider [${embeddingProvider}]")
        }
        if (!SUPPORTED_RERANKER_PROVIDERS.contains(rerankerProvider)) {
            errors.add("Unsupported moqui.agent.reranker.provider [${rerankerProvider}]")
        }
        if (!promptIndexName?.trim()) errors.add('Property moqui.agent.prompt.indexName must not be blank')
        if (!ragIndexName?.trim()) errors.add('Property moqui.agent.rag.indexName must not be blank')
        if (batchSize != null && batchSize < 1) errors.add('Property moqui.agent.index.batchSize must be >= 1')
        if (findMaxLimit != null && findMaxLimit < 1) errors.add('Property moqui.agent.find.maxLimit must be >= 1')
        if (rerankerMaxCandidates != null && rerankerMaxCandidates < 1) errors.add('Property moqui.agent.reranker.maxCandidates must be >= 1')
        if (rerankerTimeoutMs != null && rerankerTimeoutMs < 1000) errors.add('Property moqui.agent.reranker.timeoutMs must be >= 1000')
        if (rerankerMaxOutputTokens != null && rerankerMaxOutputTokens < 200) errors.add('Property moqui.agent.reranker.maxOutputTokens must be >= 200')
        if (telemetrySummaryLength != null && telemetrySummaryLength < 1) errors.add('Property moqui.agent.elasticTelemetry.maxSummaryLength must be >= 1')

        String embeddingApiKey = getPropertyOrEnv('moqui.agent.embedding.apiKey', 'OPENAI_API_KEY', '').trim()
        String rerankerApiKey = getPropertyOrEnv('moqui.agent.reranker.apiKey', 'OPENAI_API_KEY', '').trim()
        String embeddingModel = getString('moqui.agent.embedding.model', 'text-embedding-3-large')
        Integer embeddingDimensions = safeParseInt('moqui.agent.embedding.dimensions', 3072, errors)
        String embeddingCompatBaseUrl = getString('moqui.agent.embedding.compat.baseUrl', '').trim()
        String rerankerModel = getString('moqui.agent.reranker.model', 'gpt-5')
        String rerankerCompatBaseUrl = getString('moqui.agent.reranker.compat.baseUrl', '').trim()

        if (requireEmbedding) {
            if (embeddingProvider == 'none') {
                warnings.add('Embedding provider is disabled; vector/hybrid search will fall back to lexical retrieval')
            } else if (embeddingProvider == 'openai' && !embeddingApiKey) {
                warnings.add('OpenAI embedding provider is configured but no API key is available; vector/hybrid search will fall back')
            } else if (embeddingProvider == 'openai_compatible') {
                String compatBaseUrl = getString('moqui.agent.embedding.compat.baseUrl', '').trim()
                if (!compatBaseUrl) warnings.add('openai_compatible embedding requires moqui.agent.embedding.compat.baseUrl; vector/hybrid search will fall back')
            }
        }

        if (requireReranker) {
            if (rerankerProvider == 'none') {
                warnings.add('Reranker provider is disabled; hybrid_llm_rerank will fall back to deterministic reranking')
            } else if (rerankerProvider == 'openai' && !rerankerApiKey) {
                warnings.add('OpenAI reranker provider is configured but no API key is available; hybrid_llm_rerank will fall back')
            } else if (rerankerProvider == 'openai_compatible') {
                String compatBaseUrl = getString('moqui.agent.reranker.compat.baseUrl', '').trim()
                if (!compatBaseUrl) {
                    warnings.add('openai_compatible reranker requires moqui.agent.reranker.compat.baseUrl; hybrid_llm_rerank will fall back')
                }
            }
        }

        if (checkIndexes && ec != null) {
            try {
                def elasticClient = ec.factory.elastic.getDefault()
                if (!elasticClient.indexExists(promptIndexName)) warnings.add("Prompt index does not exist yet: ${promptIndexName}")
                if (!elasticClient.indexExists(ragIndexName)) warnings.add("Artifact index does not exist yet: ${ragIndexName}")
            } catch (Throwable t) {
                warnings.add("Unable to validate Elastic/OpenSearch indexes: ${t.message}")
            }
        }

        String status = errors ? 'error' : (warnings ? 'warn' : 'ok')
        return [
            status : status,
            errors : errors,
            warnings : warnings,
            details : [
                searchEngine : searchEngine,
                searchMode : searchMode,
                embeddingProvider : embeddingProvider,
                rerankerProvider : rerankerProvider,
                promptIndexName : promptIndexName,
                ragIndexName : ragIndexName,
                batchSize : batchSize,
                findMaxLimit : findMaxLimit,
                rerankerMaxCandidates : rerankerMaxCandidates,
                rerankerTimeoutMs : rerankerTimeoutMs,
                rerankerMaxOutputTokens : rerankerMaxOutputTokens,
                embeddingModel                  : embeddingModel,
                embeddingDimensions             : embeddingDimensions,
                embeddingCompatBaseUrlPresent   : Boolean.valueOf(embeddingCompatBaseUrl as boolean),
                embeddingApiKeyPresent          : Boolean.valueOf(embeddingApiKey as boolean),
                rerankerModel                   : rerankerModel,
                rerankerCompatBaseUrlPresent    : Boolean.valueOf(rerankerCompatBaseUrl as boolean),
                rerankerApiKeyPresent           : Boolean.valueOf(rerankerApiKey as boolean)
            ]
        ]
    }

    static void assertRuntimeConfiguration(ExecutionContext ec, Map options = [:]) {
        Map validation = validateRuntimeConfiguration(ec, options)
        List errors = (validation.errors ?: []) as List
        if (!errors.isEmpty()) throw new IllegalStateException(errors.join('; '))
    }

    protected static Integer safeParseInt(String propertyName, int defaultValue, List<String> errors) {
        try {
            return parseInt(propertyName, defaultValue)
        } catch (Throwable t) {
            errors.add("Property ${propertyName} must be an integer value")
            return null
        }
    }
}
