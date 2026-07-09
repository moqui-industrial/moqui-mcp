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

class AgentArtifactSearchFacadeImpl implements AgentArtifactSearchFacade {
    protected final ExecutionContext ec
    protected final Object adapter
    protected final String defaultIndexName

    AgentArtifactSearchFacadeImpl(ExecutionContext ec, Map config = [:]) {
        this.ec = ec
        this.defaultIndexName = (config.indexName ?: AgentConfigUtil.getString('moqui.agent.rag.indexName', 'moqui_artifacts_v1')).toString()
        this.adapter = new ElasticBackendAdapter(ec)
    }

    @Override
    void indexArtifactDocuments(List<Map> docs, String indexName) {
        String effectiveIndex = indexName ?: defaultIndexName
        createIndex(effectiveIndex, [:])
        adapter.bulkIndex(effectiveIndex, docs ?: [])
    }

    @Override
    Map searchArtifactDocuments(Map querySpec, String indexName) {
        adapter.search(indexName ?: defaultIndexName, querySpec ?: [:])
    }

    boolean indexExists(String indexName) {
        adapter.indexExists(indexName ?: defaultIndexName)
    }

    @Override
    Map getArtifactDocument(String indexName, String docId) {
        adapter.getDocument(indexName ?: defaultIndexName, docId)
    }

    @Override
    void deleteIndex(String indexName) {
        adapter.deleteIndex(indexName ?: defaultIndexName)
    }

    @Override
    void createIndex(String indexName, Map templateSpec) {
        adapter.createIndex(indexName ?: defaultIndexName, templateSpec ?: [:])
    }
}
