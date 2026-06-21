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
import org.moqui.context.ExecutionContext
import org.moqui.jcache.MCache
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ElasticBackendAdapter {
    protected final static Logger logger = LoggerFactory.getLogger(ElasticBackendAdapter.class)
    protected final ExecutionContext ec

    ElasticBackendAdapter(ExecutionContext ec) {
        this.ec = ec
    }

    Map health() {
        Map info = ec.factory.elastic.getDefault().serverInfo ?: [:]
        String engine = AgentConfigUtil.getString('moqui.agent.searchEngine', 'opensearch')
        info + [engine: engine]
    }

    boolean indexExists(String indexName) {
        MCache indexExistsCache = ec.cache.getLocalCache('agent.search.index.exists')
        Boolean cachedValue = (Boolean) indexExistsCache.get(indexName)
        if (cachedValue != null) return cachedValue
        boolean exists = ec.factory.elastic.getDefault().indexExists(indexName)
        indexExistsCache.put(indexName, exists)
        return exists
    }

    void createIndex(String indexName, Map templateSpec) {
        if (!indexExists(indexName)) {
            Map effectiveSpec = templateSpec ?: [:]
            Map mappings = new LinkedHashMap()
            Object rawMappings = effectiveSpec.get('mappings')
            if (rawMappings instanceof Map) mappings.putAll((Map) rawMappings)
            Map settings = new LinkedHashMap()
            Object rawSettings = effectiveSpec.get('settings')
            if (rawSettings instanceof Map) settings.putAll((Map) rawSettings)
            try {
                ec.factory.elastic.getDefault().createIndex(indexName, null, mappings, null, settings)
            } catch (Throwable t) {
                String message = t.message ?: ''
                boolean knnUnsupported = message.contains('unknown setting [index.knn]') ||
                    message.contains('knn_vector') ||
                    message.contains('Failed to parse mapping')
                if (!knnUnsupported) throw t

                logger.warn("Elastic-like index ${indexName} does not support k-NN/vector settings, retrying with lexical-only mapping: ${message}")

                Map fallbackSettings = new LinkedHashMap()
                Object rawIndexSettings = settings.get('index')
                Map fallbackIndexSettings = new LinkedHashMap()
                if (rawIndexSettings instanceof Map) fallbackIndexSettings.putAll((Map) rawIndexSettings)
                fallbackIndexSettings.remove('knn')
                fallbackIndexSettings.remove('knn.algo_param.ef_search')
                if (!fallbackIndexSettings.isEmpty()) fallbackSettings.put('index', fallbackIndexSettings)

                Map fallbackMappings = new LinkedHashMap()
                Object rawProperties = mappings.get('properties')
                Map fallbackProperties = new LinkedHashMap()
                if (rawProperties instanceof Map) fallbackProperties.putAll((Map) rawProperties)
                fallbackProperties.remove('embedding')
                fallbackMappings.put('properties', fallbackProperties)

                ec.factory.elastic.getDefault().createIndex(indexName, null, fallbackMappings, null, fallbackSettings)
            }
            ec.cache.getLocalCache('agent.search.index.exists').put(indexName, true)
        }
    }

    void deleteIndex(String indexName) {
        if (indexExists(indexName)) {
            ec.factory.elastic.getDefault().deleteIndex(indexName)
            ec.cache.getLocalCache('agent.search.index.exists').put(indexName, false)
        }
    }

    void bulkIndex(String indexName, List<Map> docs) {
        if (!docs) return
        List<Map> normalizedDocs = docs.collect { Map doc ->
            Map copy = new LinkedHashMap(doc ?: [:])
            if (!copy.doc_id) copy.doc_id = copy.documentId ?: copy._id
            copy
        }
        ec.factory.elastic.getDefault().bulkIndex(indexName, 'doc_id', normalizedDocs)
    }

    Map search(String indexName, Map querySpec) {
        ec.factory.elastic.getDefault().search(indexName, querySpec ?: [:])
    }

    Map getDocument(String indexName, String docId) {
        try {
            // URL-encode so IDs containing :// (e.g. agent-prompt://...) don't break the REST path
            String encodedDocId = java.net.URLEncoder.encode(docId, 'UTF-8')
            return ec.factory.elastic.getDefault().getSource(indexName, encodedDocId)
        } catch (Throwable ignored) {
            Map termResult = search(indexName, [
                size : 1,
                query: [term: [documentId: [value: docId]]]
            ]) ?: [:]
            List termHits = (((termResult.hits ?: [:]).hits ?: []) as List)
            if (termHits) return ((termHits.first()?._source ?: [:]) as Map)

            Map docIdResult = search(indexName, [
                size : 1,
                query: [term: [doc_id: [value: docId]]]
            ]) ?: [:]
            List docIdHits = (((docIdResult.hits ?: [:]).hits ?: []) as List)
            if (docIdHits) return ((docIdHits.first()?._source ?: [:]) as Map)

            return [:]
        }
    }
}
