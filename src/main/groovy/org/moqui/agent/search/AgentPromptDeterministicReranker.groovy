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

import org.moqui.agent.AgentToolSupport

class AgentPromptDeterministicReranker {
    protected final Map baseContext
    protected final Map queryProfile

    AgentPromptDeterministicReranker(Map baseContext) {
        this.baseContext = baseContext ?: [:]
        this.queryProfile = AgentToolSupport.classifySearchQuery(baseContext.queryText as String)
    }

    Map getQueryProfile() { queryProfile }

    List rerankHybrid(List lexicalHits, List vectorHits) {
        Map<String, Map> mergedById = [:]
        Map lexicalNormalized = normalizeScoreList((lexicalHits ?: []).collect { (((it ?: [:])._score ?: 0.0d) as Number).doubleValue() })
        Map vectorNormalized = normalizeScoreList((vectorHits ?: []).collect { (((it ?: [:])._score ?: 0.0d) as Number).doubleValue() })

        (lexicalHits ?: []).eachWithIndex { Map hit, int idx ->
            String docId = (((hit?._source ?: [:]).documentId ?: hit?._id) as String)
            if (!docId) return
            mergedById.computeIfAbsent(docId) { [
                documentId : docId,
                sourceDocument : (hit?._source ?: [:]) as Map,
                lexicalScore : 0.0d,
                vectorScore : 0.0d
            ] }
            mergedById[docId].lexicalScore = lexicalNormalized[idx] ?: 0.0d
            mergedById[docId].rawLexicalScore = hit?._score
        }

        (vectorHits ?: []).eachWithIndex { Map hit, int idx ->
            String docId = (((hit?._source ?: [:]).documentId ?: hit?._id) as String)
            if (!docId) return
            mergedById.computeIfAbsent(docId) { [
                documentId : docId,
                sourceDocument : (hit?._source ?: [:]) as Map,
                lexicalScore : 0.0d,
                vectorScore : 0.0d
            ] }
            mergedById[docId].vectorScore = vectorNormalized[idx] ?: 0.0d
            mergedById[docId].rawVectorScore = hit?._score
        }

        List result = mergedById.values().collect { Map entry ->
            Map source = (entry.sourceDocument ?: [:]) as Map
            double structuredBoost = structuredBoostFor(source, true)
            double finalScore = 0.52d * ((entry.lexicalScore ?: 0.0d) as Double) +
                0.48d * ((entry.vectorScore ?: 0.0d) as Double) + structuredBoost
            Map normalizedHit = AgentToolSupport.normalizeSearchHit([_id: entry.documentId, _score: finalScore, _source: source])
            normalizedHit.lexicalScore = entry.lexicalScore
            normalizedHit.vectorScore = entry.vectorScore
            normalizedHit.structuredBoost = structuredBoost
            normalizedHit
        }
        result.sort { a, b -> ((b.score ?: 0.0d) as Double) <=> ((a.score ?: 0.0d) as Double) }
    }

    List rerankLexical(List lexicalHits) {
        Map lexicalNormalized = normalizeScoreList((lexicalHits ?: []).collect { (((it ?: [:])._score ?: 0.0d) as Number).doubleValue() })
        List reranked = []
        (lexicalHits ?: []).eachWithIndex { Map hit, int idx ->
            Map source = (hit?._source ?: [:]) as Map
            double structuredBoost = structuredBoostFor(source, false)
            double finalScore = 0.85d * ((lexicalNormalized[idx] ?: 0.0d) as Double) + structuredBoost
            Map normalizedHit = AgentToolSupport.normalizeSearchHit([_id: hit?._id, _score: finalScore, _source: source])
            normalizedHit.lexicalScore = lexicalNormalized[idx]
            normalizedHit.structuredBoost = structuredBoost
            reranked.add(normalizedHit)
        }
        reranked.sort { a, b -> ((b.score ?: 0.0d) as Double) <=> ((a.score ?: 0.0d) as Double) }
    }

    protected Map normalizeScoreList(List<Double> scores) {
        if (!scores) return [:]
        double minScore = scores.min() ?: 0.0d
        double maxScore = scores.max() ?: 0.0d
        double spread = maxScore - minScore
        Map normalized = [:]
        scores.eachWithIndex { Double score, int idx ->
            normalized[idx] = spread > 0.000001d ? ((score - minScore) / spread) : 1.0d
        }
        normalized
    }

    protected double structuredBoostFor(Map source, boolean hybridMode) {
        double structuredBoost = 0.0d
        String area = baseContext.area as String
        String subArea = baseContext.subArea as String
        String domainObject = baseContext.domainObject as String
        String actionKind = baseContext.actionKind as String
        String operationEffect = baseContext.operationEffect as String
        String currentScreen = baseContext.currentScreen as String
        List currentKeys = ((baseContext.currentBusinessObjectKeys ?: []) as List).findAll { it } as List
        List nonPrimaryChannels = (baseContext.nonPrimaryChannels ?: []) as List
        List hardExcludedChannels = (baseContext.hardExcludedChannels ?: []) as List
        boolean includeNonExecutable = Boolean.TRUE.equals(baseContext.includeNonExecutable)
        String normalizedQueryText = (queryProfile.normalizedQuery ?: '') as String
        String queryText = baseContext.queryText as String

        if (area && area == source.area) structuredBoost += 0.20d
        if (subArea && subArea == source.subArea) structuredBoost += 0.10d
        if (domainObject && domainObject == source.domainObject) structuredBoost += 0.10d
        if (actionKind && actionKind == source.actionKind) structuredBoost += 0.08d
        if (operationEffect && operationEffect == source.operationEffect) structuredBoost += 0.08d
        if (currentScreen && currentScreen == source.sourceScreenPath) structuredBoost += 0.15d
        if (Boolean.TRUE.equals(source.runtimeExecutable)) structuredBoost += hybridMode ? 0.05d : 0.04d

        List reqCtx = (source.executionRequiredContext instanceof List) ? source.executionRequiredContext as List : []
        int contextMatches = reqCtx.count { currentKeys.contains(it as String) }
        structuredBoost += Math.min(contextMatches * 0.05d, 0.20d)

        String executionChannel = (source.executionChannel ?: '') as String
        String sourceOperationEffect = (source.operationEffect ?: '') as String
        String sourceActionKind = (source.actionKind ?: '') as String
        String canonicalPrompt = (source.canonicalPrompt ?: '') as String
        String canonicalPromptLower = canonicalPrompt.toLowerCase()
        String domainObjectText = (source.domainObject ?: '') as String
        String subAreaText = (source.subArea ?: '') as String
        String documentKind = (source.documentKind ?: '') as String
        boolean knowledgeOnly = Boolean.TRUE.equals(source.knowledgeOnly)
        String knowledgeCategory = (source.knowledgeCategory ?: '') as String
        String resolutionPolicy = (source.resolutionPolicy ?: '') as String
        String primaryScreenPurpose = (source.primaryScreenPurpose ?: '') as String
        boolean mutationRequiresFieldDiff = Boolean.TRUE.equals(source.mutationRequiresFieldDiff)
        List sourceFieldNames = (source.fieldNames instanceof List) ? source.fieldNames as List : []
        List<String> sourceUiLabels = (source.uiLabels instanceof List) ?
            ((List) source.uiLabels).collect { (it ?: '').toString() } :
            []

        boolean readIntent = ['find','search','list','show','view','open','display','read',
                              'trova','cerca','elenca','mostra','visualizza','apri','leggi'].any { normalizedQueryText.contains(it) }
        boolean mutateIntent = ['create','update','cancel','approve','post','reverse','delete','complete','close',
                                'crea','creare','nuovo','nuova','aggiorna','modifica','cambia','imposta',
                                'annulla','approva','elimina','completa','chiudi'].any { normalizedQueryText.contains(it) }
        List<String> queryTokens = AgentToolSupport.tokenizeSearchText(queryText ?: '') as List<String>
        Set<String> queryTokenSet = queryTokens as Set<String>
        Set<String> domainNoiseTokens = [
            'how', 'what', 'which', 'why', 'understand', 'configure', 'configuration', 'setup',
            'workflow', 'process', 'pattern', 'story', 'reference', 'technical', 'business',
            'data', 'demo', 'required', 'precondition', 'exists', 'there', 'for', 'the', 'a', 'an',
            'i', 'do', 'is', 'are', 'of'
        ] as Set<String>
        List<String> domainQueryTokens = queryTokens.findAll { !(it in domainNoiseTokens) } as List<String>
        Set<String> domainQueryTokenSet = domainQueryTokens as Set<String>
        List<String> domainQueryBigrams = []
        for (int i = 0; i < Math.max(domainQueryTokens.size() - 1, 0); i++) {
            domainQueryBigrams << (domainQueryTokens[i] + ' ' + domainQueryTokens[i + 1])
        }
        List<String> queryBigrams = []
        for (int i = 0; i < Math.max(queryTokens.size() - 1, 0); i++) {
            queryBigrams << (queryTokens[i] + ' ' + queryTokens[i + 1])
        }

        boolean queryExplicitValueSignal = normalizedQueryText.contains(' to ') ||
            normalizedQueryText.contains('=') ||
            normalizedQueryText.contains('"') ||
            normalizedQueryText.contains("'") ||
            (normalizedQueryText ==~ /.*\b\d{4}-\d{2}-\d{2}\b.*/)
        String queryActionKind = inferQueryActionKind(queryTokens, queryExplicitValueSignal)
        String queryResolutionPolicyHint = queryActionKind == 'navigate' && queryTokens.any { it in ['edit','update','modify','change','set'] } ?
            'navigate_then_maybe_update' :
            (queryActionKind == 'list' ? 'retrieve_or_query' : (queryActionKind == 'detail' ? 'navigate_only' : 'execute_direct'))

        Closure<Integer> overlapCount = { String text ->
            if (!text) return 0
            Set<String> fieldTokens = (AgentToolSupport.tokenizeSearchText(text) as List<String>) as Set<String>
            queryTokenSet.intersect(fieldTokens).size()
        }

        boolean candidateFieldHint = sourceFieldNames.any { String fn -> overlapCount(fn) > 0 }
        List<String> candidateDomainTokenList = candidateDomainTokens(source, domainNoiseTokens)
        Set<String> candidateDomainTokenSet = candidateDomainTokenList as Set<String>
        int candidateDomainMatches = domainQueryTokenSet.intersect(candidateDomainTokenSet).size()
        double domainQueryCoverage = domainQueryTokenSet ? (candidateDomainMatches / (double) domainQueryTokenSet.size()) : 0.0d
        String candidateDomainExpanded = [(source.domainObject ?: ''), (source.subArea ?: ''), (source.area ?: '')]
            .findAll { it }
            .collect { String part -> part.replaceAll(/([a-z0-9])([A-Z])/, '$1 $2').toLowerCase() }
            .join(' ')
        String candidateDomainPhrase = candidateDomainTokenList.join(' ')
        boolean candidateDomainPhraseMatch = candidateDomainPhrase && normalizedQueryText.contains(candidateDomainPhrase)
        int domainBigramMatches = domainQueryBigrams.count { String phrase -> candidateDomainExpanded.contains(phrase) }
        String uiLabelText = sourceUiLabels.collect { it.toLowerCase() }.join(' ')
        int canonicalBigramMatches = queryBigrams.count { String phrase ->
            canonicalPromptLower.contains(phrase) || uiLabelText.contains(phrase)
        }
        boolean updateSynonymMatch =
            queryTokens.any { it in ['modify', 'change', 'set'] } &&
                (canonicalPromptLower.contains('update') || uiLabelText.contains('update'))
        boolean cancelSynonymMatch =
            queryTokens.any { it == 'void' } &&
                (canonicalPromptLower.contains('cancel') || uiLabelText.contains('cancel'))
        boolean queryProjectCreate =
            queryActionKind == 'create' &&
                queryTokens.any { it in ['project', 'progetto', 'commessa'] }
        boolean candidateProjectCreate =
            sourceActionKind == 'create' &&
                sourceOperationEffect == 'create' &&
                (domainObjectText ?: '').toLowerCase() == 'project'
        boolean candidateProjectRelated =
            ((domainObjectText ?: '').toLowerCase() in ['project', 'milestone', 'projecttasks', 'task']) ||
                canonicalPromptLower.contains('project') || canonicalPromptLower.contains('milestone')

        if (hybridMode) {
            if (!includeNonExecutable && executionChannel == 'unsupported') structuredBoost -= 0.50d
            if (queryProfile.intentType == 'knowledge') {
                if (knowledgeOnly) structuredBoost += 0.28d
                if (!knowledgeOnly && documentKind == 'screen_query_prompt') structuredBoost -= 0.14d
            } else if (queryProfile.intentType == 'ui_action') {
                if (knowledgeOnly) structuredBoost -= 0.30d
                if (!knowledgeOnly) structuredBoost += 0.12d
            } else if (queryProfile.intentType == 'mixed') {
                if (knowledgeOnly) structuredBoost += 0.04d
                else structuredBoost += 0.06d
            }
            if (Boolean.TRUE.equals(queryProfile.executablePromptIntent)) {
                if (!knowledgeOnly) structuredBoost += 0.16d
                else structuredBoost -= 0.18d
            }
            if (Boolean.TRUE.equals(queryProfile.workflowIntent)) {
                if (documentKind == 'test_workflow_story') structuredBoost += 0.20d
                if (documentKind == 'screen_query_prompt') structuredBoost -= 0.06d
            }
            if (Boolean.TRUE.equals(queryProfile.configurationIntent)) {
                if (knowledgeCategory == 'business_configuration') structuredBoost += 0.12d
                if (documentKind == 'generic_business_pattern') structuredBoost += 0.08d
                if (domainQueryTokenSet && knowledgeOnly) {
                    if (domainQueryCoverage >= 0.99d) structuredBoost += 0.26d
                    else if (candidateDomainMatches > 0) structuredBoost += Math.min(candidateDomainMatches * 0.05d, 0.10d)
                    if (domainQueryTokenSet.size() > 1 && candidateDomainMatches > 0 && domainQueryCoverage < 0.99d) structuredBoost -= 0.14d
                    if (candidateDomainPhraseMatch) structuredBoost += 0.10d
                    if (domainBigramMatches > 0) structuredBoost += Math.min(domainBigramMatches * 0.18d, 0.24d)
                    if (domainQueryBigrams && domainBigramMatches == 0 && candidateDomainMatches > 0) structuredBoost -= 0.18d
                }
            }
            if (Boolean.TRUE.equals(queryProfile.referenceIntent) && knowledgeCategory == 'reference_data') {
                structuredBoost += 0.16d
                if (domainQueryTokenSet && knowledgeOnly) {
                    if (domainQueryCoverage >= 0.99d) structuredBoost += 0.18d
                    else if (candidateDomainMatches > 0) structuredBoost += Math.min(candidateDomainMatches * 0.04d, 0.08d)
                    if (candidateDomainPhraseMatch) structuredBoost += 0.08d
                    if (domainBigramMatches > 0) structuredBoost += Math.min(domainBigramMatches * 0.14d, 0.18d)
                }
            }
            if (Boolean.TRUE.equals(queryProfile.technicalIntent) && knowledgeCategory == 'technical_configuration') {
                structuredBoost += 0.16d
                if (domainQueryTokenSet && knowledgeOnly) {
                    if (domainQueryCoverage >= 0.99d) structuredBoost += 0.18d
                    else if (candidateDomainMatches > 0) structuredBoost += Math.min(candidateDomainMatches * 0.04d, 0.08d)
                    if (candidateDomainPhraseMatch) structuredBoost += 0.08d
                    if (domainBigramMatches > 0) structuredBoost += Math.min(domainBigramMatches * 0.14d, 0.18d)
                }
            }
            if (Boolean.TRUE.equals(queryProfile.technicalIntent) && knowledgeCategory == 'reference_data') structuredBoost -= 0.18d
            if (Boolean.TRUE.equals(queryProfile.workflowIntent) && domainQueryTokenSet && knowledgeOnly) {
                if (domainQueryCoverage >= 0.99d) structuredBoost += 0.18d
                else if (candidateDomainMatches > 0) structuredBoost += Math.min(candidateDomainMatches * 0.04d, 0.08d)
                if (domainQueryTokenSet.size() > 1 && candidateDomainMatches > 0 && domainQueryCoverage < 0.99d) structuredBoost -= 0.10d
                if (candidateDomainPhraseMatch) structuredBoost += 0.08d
                if (domainBigramMatches > 0) structuredBoost += Math.min(domainBigramMatches * 0.12d, 0.16d)
            }
            if (readIntent && documentKind == 'screen_query_prompt') structuredBoost += 0.12d
            if (readIntent && ['read_query', 'read_detail', 'navigation'].contains(sourceOperationEffect)) structuredBoost += 0.12d
            if (readIntent && ['create','update','delete','status_transition','batch_update','financial_posting','cancellation'].contains(sourceOperationEffect)) structuredBoost -= 0.20d
            if (mutateIntent && ['create','update','delete','status_transition','batch_update'].contains(sourceOperationEffect)) structuredBoost += 0.12d
            if (mutateIntent && ['read_query', 'read_detail', 'navigation'].contains(sourceOperationEffect)) structuredBoost -= 0.18d
            if (queryActionKind != 'unresolved') {
                if (queryActionKind == sourceActionKind) structuredBoost += queryActionKind == 'update' ? 0.22d : 0.16d
                else if (queryActionKind in ['list', 'detail'] && sourceActionKind in ['list', 'detail', 'navigate']) structuredBoost += 0.06d
                else if (queryActionKind in ['create', 'update', 'delete', 'status'] && sourceActionKind in ['create', 'update', 'delete', 'status']) structuredBoost += 0.06d
                else if (queryActionKind == 'update' && resolutionPolicy == 'navigate_then_maybe_update' && sourceActionKind == 'navigate') structuredBoost += 0.12d
                else structuredBoost -= queryActionKind == 'update' && sourceActionKind == 'create' ? 0.18d : 0.10d
            }
            if (queryResolutionPolicyHint == resolutionPolicy) structuredBoost += 0.12d
            if (queryActionKind == 'navigate' && primaryScreenPurpose == 'edit') structuredBoost += 0.18d
            if (queryActionKind == 'navigate' && sourceActionKind == 'update') structuredBoost -= queryExplicitValueSignal ? 0.02d : 0.14d
            if (queryActionKind == 'update' && resolutionPolicy == 'navigate_then_maybe_update') structuredBoost += 0.14d
            if (queryActionKind == 'update' && mutationRequiresFieldDiff) structuredBoost += 0.05d
            if (queryExplicitValueSignal && candidateFieldHint) structuredBoost += 0.12d
            if (!queryExplicitValueSignal && queryActionKind == 'navigate' && candidateFieldHint) structuredBoost -= 0.04d
            int canonicalOverlap = overlapCount(canonicalPrompt) as int
            int domainOverlap = overlapCount(domainObjectText) as int
            int subAreaOverlap = overlapCount(subAreaText) as int
            if (canonicalPrompt && normalizedQueryText == canonicalPrompt.toLowerCase()) structuredBoost += 0.25d
            structuredBoost += Math.min(canonicalOverlap * 0.08d, 0.24d)
            structuredBoost += Math.min(domainOverlap * 0.06d, 0.18d)
            structuredBoost += Math.min(subAreaOverlap * 0.05d, 0.15d)
            structuredBoost += Math.min(canonicalBigramMatches * 0.10d, 0.20d)
            if (updateSynonymMatch) structuredBoost += 0.12d
            if (cancelSynonymMatch) structuredBoost += 0.14d
            if (queryProjectCreate) {
                if (candidateProjectCreate) structuredBoost += 0.34d
                if (canonicalPromptLower == 'create project') structuredBoost += 0.22d
                if (sourceOperationEffect in ['read_query', 'read_detail', 'navigation', 'unresolved_binding']) structuredBoost -= 0.26d
                if (sourceActionKind in ['list', 'detail', 'navigate', 'unresolved']) structuredBoost -= 0.18d
                if (candidateProjectRelated && !candidateProjectCreate) structuredBoost -= 0.08d
            }
            if (source.promptGroupId) structuredBoost += 0.05d
        } else {
            if (!includeNonExecutable && nonPrimaryChannels.contains(executionChannel)) structuredBoost -= 0.35d
            if (queryProfile.intentType == 'knowledge') {
                if (knowledgeOnly) structuredBoost += 0.24d
                if (!knowledgeOnly && documentKind == 'screen_query_prompt') structuredBoost -= 0.12d
            } else if (queryProfile.intentType == 'ui_action') {
                if (knowledgeOnly) structuredBoost -= 0.24d
                else structuredBoost += 0.10d
            }
            if (Boolean.TRUE.equals(queryProfile.executablePromptIntent)) {
                if (!knowledgeOnly) structuredBoost += 0.12d
                else structuredBoost -= 0.16d
            }
            if (Boolean.TRUE.equals(queryProfile.workflowIntent) && documentKind == 'test_workflow_story') structuredBoost += 0.16d
            if (Boolean.TRUE.equals(queryProfile.configurationIntent) && knowledgeCategory == 'business_configuration') {
                structuredBoost += 0.10d
                if (domainQueryTokenSet && knowledgeOnly) {
                    if (domainQueryCoverage >= 0.99d) structuredBoost += 0.22d
                    else if (candidateDomainMatches > 0) structuredBoost += Math.min(candidateDomainMatches * 0.04d, 0.08d)
                    if (domainQueryTokenSet.size() > 1 && candidateDomainMatches > 0 && domainQueryCoverage < 0.99d) structuredBoost -= 0.12d
                    if (candidateDomainPhraseMatch) structuredBoost += 0.08d
                    if (domainBigramMatches > 0) structuredBoost += Math.min(domainBigramMatches * 0.16d, 0.22d)
                    if (domainQueryBigrams && domainBigramMatches == 0 && candidateDomainMatches > 0) structuredBoost -= 0.16d
                }
            }
            if (Boolean.TRUE.equals(queryProfile.referenceIntent) && knowledgeCategory == 'reference_data') {
                structuredBoost += 0.12d
                if (domainQueryTokenSet && knowledgeOnly) {
                    if (domainQueryCoverage >= 0.99d) structuredBoost += 0.16d
                    else if (candidateDomainMatches > 0) structuredBoost += Math.min(candidateDomainMatches * 0.03d, 0.06d)
                    if (candidateDomainPhraseMatch) structuredBoost += 0.06d
                    if (domainBigramMatches > 0) structuredBoost += Math.min(domainBigramMatches * 0.12d, 0.16d)
                }
            }
            if (Boolean.TRUE.equals(queryProfile.technicalIntent) && knowledgeCategory == 'technical_configuration') {
                structuredBoost += 0.12d
                if (domainQueryTokenSet && knowledgeOnly) {
                    if (domainQueryCoverage >= 0.99d) structuredBoost += 0.16d
                    else if (candidateDomainMatches > 0) structuredBoost += Math.min(candidateDomainMatches * 0.03d, 0.06d)
                    if (candidateDomainPhraseMatch) structuredBoost += 0.06d
                    if (domainBigramMatches > 0) structuredBoost += Math.min(domainBigramMatches * 0.12d, 0.16d)
                }
            }
            if (Boolean.TRUE.equals(queryProfile.technicalIntent) && knowledgeCategory == 'reference_data') structuredBoost -= 0.14d
            if (Boolean.TRUE.equals(queryProfile.workflowIntent) && domainQueryTokenSet && knowledgeOnly) {
                if (domainQueryCoverage >= 0.99d) structuredBoost += 0.16d
                else if (candidateDomainMatches > 0) structuredBoost += Math.min(candidateDomainMatches * 0.03d, 0.06d)
                if (domainQueryTokenSet.size() > 1 && candidateDomainMatches > 0 && domainQueryCoverage < 0.99d) structuredBoost -= 0.10d
                if (candidateDomainPhraseMatch) structuredBoost += 0.06d
                if (domainBigramMatches > 0) structuredBoost += Math.min(domainBigramMatches * 0.10d, 0.14d)
            }
            if (readIntent && ['create','update','delete','batch_update','status_transition','financial_posting','cancellation'].contains(sourceOperationEffect)) structuredBoost -= 0.20d
            if (readIntent && ['create','update','delete','status'].contains(sourceActionKind)) structuredBoost -= 0.10d
            if (mutateIntent && 'read_query' == sourceOperationEffect) structuredBoost -= 0.12d
            if (queryActionKind == 'navigate' && resolutionPolicy == 'navigate_then_maybe_update') structuredBoost += 0.10d
            if (queryActionKind == 'navigate' && primaryScreenPurpose == 'edit') structuredBoost += 0.12d
            if (queryActionKind == 'navigate' && sourceActionKind == 'update') structuredBoost -= queryExplicitValueSignal ? 0.02d : 0.18d
            if (queryProjectCreate) {
                if (candidateProjectCreate) structuredBoost += 0.26d
                if (canonicalPromptLower == 'create project') structuredBoost += 0.18d
                if (sourceOperationEffect in ['read_query', 'read_detail', 'navigation', 'unresolved_binding']) structuredBoost -= 0.20d
                if (sourceActionKind in ['list', 'detail', 'navigate', 'unresolved']) structuredBoost -= 0.14d
                if (candidateProjectRelated && !candidateProjectCreate) structuredBoost -= 0.06d
            }
        }
        structuredBoost
    }

    protected static String inferQueryActionKind(List<String> queryTokens, boolean queryExplicitValueSignal) {
        if (queryTokens.any { it in ['find','search','list','show','view','display','trova','cerca','elenca','mostra','visualizza'] }) return 'list'
        if (queryTokens.any { it in ['open','inspect','details','detail','apri','dettaglio','dettagli'] }) return 'detail'
        if (queryTokens.any { it in ['create','add','new','place','crea','creare','nuovo','nuova','inserisci'] }) return 'create'
        if (queryTokens.any { it in ['update', 'modify', 'change', 'set','aggiorna','modifica','cambia','imposta'] }) return 'update'
        if (queryTokens.any { it == 'edit' }) {
            if (queryExplicitValueSignal) return 'update'
            if (queryTokens.any { it in ['assign', 'assignment', 'load', 'content', 'note', 'relationship'] }) return 'update'
            return 'navigate'
        }
        if (queryTokens.any { it in ['delete','remove','elimina','cancella','rimuovi'] }) return 'delete'
        if (queryTokens.any { it in ['approve','cancel','complete','close','reject','post','reverse','void',
                                     'approva','annulla','completa','chiudi','rifiuta'] }) return 'status'
        'unresolved'
    }

    protected static List<String> candidateDomainTokens(Map source, Set<String> domainNoiseTokens) {
        String candidateText = [(source.domainObject ?: ''), (source.subArea ?: ''), (source.area ?: '')]
            .findAll { it }
            .join(' ')
        List<String> candidateTokens = AgentToolSupport.tokenizeSearchText(candidateText) as List<String>
        candidateTokens.findAll { !(it in domainNoiseTokens) }.unique()
    }
}
