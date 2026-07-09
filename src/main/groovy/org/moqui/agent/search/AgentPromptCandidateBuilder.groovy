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

class AgentPromptCandidateBuilder {
    Map build(Map context) {
        String queryText = context.queryText as String
        String area = context.area as String
        String subArea = context.subArea as String
        String domainObject = context.domainObject as String
        String actionKind = context.actionKind as String
        String operationEffect = context.operationEffect as String
        String currentScreen = context.currentScreen as String
        Map currentBusinessObjects = (context.currentBusinessObjects instanceof Map) ? (Map) context.currentBusinessObjects : [:]
        String indexName = context.indexName as String
        int size = Math.min(((context.limit ?: 8) as Integer), 25)
        String requestedMode = (context.mode ?: AgentConfigUtil.getNormalizedString('moqui.agent.search.mode', 'hybrid')).toString().toLowerCase()
        int llmMaxCandidates = AgentConfigUtil.getInt('moqui.agent.reranker.maxCandidates', 20, 1)
        int candidateSize = (requestedMode in ['hybrid_rerank', 'hybrid_llm_rerank']) ?
            Math.min(Math.max(Math.max(size * 4, 20), llmMaxCandidates), 50) : size
        boolean searchDebugEnabled = AgentConfigUtil.getBoolean('moqui.agent.search.debug', false)
        boolean includeNonExecutable = Boolean.TRUE.equals(context.includeNonExecutable)
        String effectiveIndex = indexName ?: AgentConfigUtil.getString('moqui.agent.prompt.indexName', 'moqui_agent_prompts_v1')

        Map sourceFilter = [
            includes: [
                'documentId', 'documentKind', 'area', 'subArea', 'domainObject', 'promptGroupId',
                'actionKind', 'operationEffect', 'canonicalPrompt', 'preferredService',
                'runtimeExecutable', 'executionChannel', 'sourceScreenPath', 'executionRequiredContext',
                'fieldNames', 'uiLabels', 'englishPromptVariants', 'italianPromptVariants',
                'machineVariants', 'embeddingText', 'title', 'summary',
                'knowledgeOnly', 'knowledgeCategory', 'sourceKind', 'verifiedByTest',
                'scenarioName', 'workflowName', 'patternName', 'businessQuestions', 'relatedEntities',
                'requiredEntities', 'optionalEntities', 'relatedAgentPrompts', 'serviceSequence',
                'resolutionPolicy', 'primaryScreenPurpose', 'mutationRequiresFieldDiff',
                'stateComparisonEntity', 'stateComparisonPkFields', 'updateTransitionName', 'updateServiceName'
            ],
            excludes: ['embedding']
        ]

        List filterList = []
        if (area) filterList.add([term: [area: area]])
        if (subArea) filterList.add([term: [subArea: subArea]])
        if (domainObject) filterList.add([term: [domainObject: domainObject]])
        if (actionKind) filterList.add([term: [actionKind: actionKind]])
        if (operationEffect) filterList.add([term: [operationEffect: operationEffect]])
        if (currentScreen) {
            filterList.add([
                bool: [
                    should: [
                        [term: [sourceScreenPath: currentScreen]],
                        [match: [sourceScreenPath: currentScreen]]
                    ],
                    minimum_should_match: 1
                ]
            ])
        }

        List shouldList = []
        if (queryText) {
            shouldList.add([
                multi_match: [
                    query : queryText,
                    type : 'best_fields',
                    fields: [
                        'canonicalPrompt^5',
                        'englishPromptVariants^3',
                        'italianPromptVariants^3',
                        'machineVariants^2',
                        'uiLabels^2',
                        'embeddingText',
                        'title^2',
                        'summary',
                        'scenarioName^4',
                        'workflowName^4',
                        'patternName^4',
                        'businessQuestions^2',
                        'relatedEntities^2'
                    ]
                ]
            ])
        } else {
            shouldList.add([match_all: [:]])
        }

        List currentBusinessObjectKeys = []
        if (currentBusinessObjects instanceof Map) {
            currentBusinessObjectKeys.addAll(currentBusinessObjects.keySet().collect { it as String })
        }
        currentBusinessObjectKeys.findAll { it }.each { String key ->
            shouldList.add([term: [executionRequiredContext: key]])
            shouldList.add([term: [fieldNames: key]])
            shouldList.add([match: [embeddingText: [query: key, boost: 0.5]]])
            shouldList.add([match: [canonicalPrompt: [query: key, boost: 0.5]]])
        }

        Map lexicalQuerySpec = [
                _source: sourceFilter,
                size : candidateSize,
                query : [
                    bool: [
                        filter : filterList,
                        should : shouldList,
                        minimum_should_match: 1
                    ]
                ]
        ]

        [
            queryText : queryText,
            area : area,
            subArea : subArea,
            domainObject : domainObject,
            actionKind : actionKind,
            operationEffect : operationEffect,
            currentScreen : currentScreen,
            currentBusinessObjects : currentBusinessObjects,
            currentBusinessObjectKeys : currentBusinessObjectKeys,
            effectiveIndex : effectiveIndex,
            size : size,
            requestedMode : requestedMode,
            candidateSize : candidateSize,
            searchDebugEnabled : searchDebugEnabled,
            includeNonExecutable : includeNonExecutable,
            sourceFilter : sourceFilter,
            filterList : filterList,
            shouldList : shouldList,
            lexicalQuerySpec : lexicalQuerySpec,
            nonPrimaryChannels : ['screen_transition', 'read_query', 'unsupported'],
            hardExcludedChannels : ['unsupported']
        ]
    }
}
