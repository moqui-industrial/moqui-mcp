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

class AgentPromptCandidateCompressor {
    Map compress(Map source) {
        if (!source) return [:]
        [
            documentId : source.documentId,
            documentKind : source.documentKind,
            canonicalPrompt : source.canonicalPrompt,
            area : source.area,
            subArea : source.subArea,
            domainObject : source.domainObject,
            actionKind : source.actionKind,
            operationEffect : source.operationEffect,
            executionChannel : source.executionChannel,
            runtimeExecutable : source.runtimeExecutable,
            knowledgeOnly : source.knowledgeOnly,
            knowledgeCategory : source.knowledgeCategory,
            sourceKind : source.sourceKind,
            scenarioName : source.scenarioName,
            workflowName : source.workflowName,
            patternName : source.patternName,
            preferredService : source.preferredService,
            resolutionPolicy : source.resolutionPolicy,
            primaryScreenPurpose : source.primaryScreenPurpose,
            fieldNames : ((source.fieldNames instanceof List) ? (source.fieldNames as List).take(8) : []),
            relatedEntities : ((source.relatedEntities instanceof List) ? (source.relatedEntities as List).take(8) : []),
            requiredEntities : ((source.requiredEntities instanceof List) ? (source.requiredEntities as List).take(8) : []),
            businessQuestions : ((source.businessQuestions instanceof List) ? (source.businessQuestions as List).take(3) : []),
            summary : truncateText(source.summary, 240)
        ]
    }

    protected static String truncateText(Object value, int maxLength) {
        String text = value?.toString()
        if (!text) return null
        return text.size() > maxLength ? text.substring(0, maxLength) : text
    }
}
