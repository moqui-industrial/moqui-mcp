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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.agent.model.AgentModelFacade
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.service.ServiceDefinition

class AgentToolSupport {
    static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    static final String PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY = 'silverston_root_child_hierarchy'
    static final String PATTERN_SILVERSTON_SAME_SUBJECT_MULTI_ACTION = 'silverston_same_subject_multi_action'
    static final Set<String> SENSITIVE_LOG_FIELD_NAMES = [
        'password', 'passwd', 'token', 'accessToken', 'refreshToken', 'apiKey', 'secret',
        'email', 'emailAddress', 'phone', 'phoneNumber', 'paymentMethodId', 'card',
        'cardNumber', 'iban', 'address', 'postalAddress', 'cvv', 'securityCode'
    ].collect { it.toLowerCase() } as Set<String>
    static final Set<String> HIGH_RISK_VERBS = [
        'delete', 'cancel', 'reverse', 'post', 'void',
        'refund', 'close', 'complete', 'approve', 'bulk'
    ] as Set<String>
    static final Set<String> HIGH_RISK_ACTION_KINDS = [
        'delete', 'cancel', 'reverse', 'post', 'void',
        'refund', 'close', 'complete', 'approve', 'bulk_action'
    ] as Set<String>
    static final Set<String> HIGH_RISK_OPERATION_EFFECTS = [
        'delete', 'bulk_delete', 'bulk_update', 'status_transition',
        'financial_posting', 'financial_reversal', 'cancellation'
    ] as Set<String>

    static ArtifactExecutionInfo.AuthzAction getServiceAuthzAction(String serviceName) {
        String verb = ServiceDefinition.getVerbFromName(serviceName ?: '')
        return ServiceDefinition.getVerbAuthzActionEnum(verb)
    }

    static String getServiceAuthzActionEnumId(String serviceName) {
        getServiceAuthzAction(serviceName).name()
    }

    static boolean isHighRiskService(String serviceName, String operationEffect = null) {
        String verb = (ServiceDefinition.getVerbFromName(serviceName ?: '') ?: '').toLowerCase()
        if (verb && HIGH_RISK_VERBS.contains(verb)) return true

        String normalizedOperationEffect = (operationEffect ?: '').trim().toLowerCase()
        if (normalizedOperationEffect && HIGH_RISK_OPERATION_EFFECTS.contains(normalizedOperationEffect)) return true

        return false
    }

    static String determineRiskLevel(String serviceName, String operationEffect = null, Map document = null) {
        if (document?.riskLevelEnumId) return document.riskLevelEnumId as String
        if (document?.riskLevel) return document.riskLevel as String
        String actionKind = (document?.actionKind ?: '').toString().trim().toLowerCase()
        if (actionKind && HIGH_RISK_ACTION_KINDS.contains(actionKind)) return 'high'
        return isHighRiskService(serviceName, operationEffect) ? 'high' : 'normal'
    }

    static boolean checkArtifactAccess(ExecutionContext ec, String artifactTypeEnumId, String authzActionEnumId, String artifactName) {
        if (!artifactTypeEnumId || !authzActionEnumId || !artifactName) return false
        return ArtifactExecutionFacadeImpl.isPermitted("${artifactTypeEnumId}:${authzActionEnumId}:${artifactName}", ec)
    }

    static String resolveDefaultPartyContextValue(ExecutionContext ec, Map parameters, String fieldName) {
        if (!ec || !fieldName) return null
        if (parameters?.get(fieldName) != null) return parameters[fieldName] as String

        List<String> preferredKeys = fieldName == 'organizationPartyId' ?
            ['organizationPartyId', 'ownerPartyId'] :
            ['ownerPartyId', 'organizationPartyId']
        for (String key in preferredKeys) {
            Object existing = parameters?.get(key)
            if (existing) return existing as String
        }

        Map userCtx = (ec.user?.context instanceof Map) ? (Map) ec.user.context : [:]
        Object activeOrgId = userCtx.activeOrgId
        if (activeOrgId) return activeOrgId as String

        List userOrgIds = (userCtx.userOrgIds instanceof Collection) ? (userCtx.userOrgIds as List) : []
        if (userOrgIds.size() == 1 && userOrgIds[0]) return userOrgIds[0] as String

        try {
            String userPartyId = ec.user?.userId
            if (userPartyId) {
                def party = ec.entity.find('mantle.party.Party').condition('partyId', userPartyId).one()
                String partyOwnerId = party?.getString('ownerPartyId')
                if (partyOwnerId) return partyOwnerId
            }
        } catch (ArtifactAuthorizationException ignored) {
            // If the user cannot read Party directly, just skip this fallback.
        } catch (Throwable ignored) {
            // Best-effort fallback only.
        }

        return null
    }

    static String toJson(Object value) {
        value == null ? null : JsonOutput.toJson(value)
    }

    static List<String> tokenizeSearchText(String text) {
        if (!text) return [] as List<String>
        String expanded = text
            .replaceAll(/([a-z0-9])([A-Z])/, '$1 $2')
            .toLowerCase()
        List<String> rawTokens = expanded.split(/[^a-z0-9]+/).findAll { it } as List<String>
        List<String> out = []
        rawTokens.each { String tok ->
            out << tok
            if (tok.size() > 4 && tok.endsWith('ies')) out << (tok[0..-4] + 'y')
            else if (tok.size() > 4 && tok.endsWith('s')) out << tok[0..-2]
        }
        return out.unique()
    }

    static boolean containsAnyStem(String normalized, Collection<String> stems) {
        if (!normalized || !stems) return false
        for (String stem in stems) {
            if (!stem) continue
            if (normalized.contains(stem)) return true
        }
        return false
    }

    static Map classifySearchQuery(String queryText) {
        String normalized = (queryText ?: '').trim().toLowerCase()
        List<String> tokens = tokenizeSearchText(queryText)
        Set<String> tokenSet = tokens as Set<String>

        Set<String> uiTokens = [
            'create', 'add', 'new', 'update', 'edit', 'modify', 'change', 'set', 'delete', 'remove',
            'cancel', 'approve', 'post', 'reverse', 'complete', 'close', 'find', 'search', 'list',
            'show', 'view', 'open', 'display', 'execute', 'run',
            'crea', 'creare', 'nuovo', 'nuova', 'aggiorna', 'modifica', 'cambia', 'imposta',
            'elimina', 'cancella', 'annulla', 'approva', 'completa', 'chiudi', 'trova', 'cerca',
            'elenca', 'mostra', 'visualizza', 'apri', 'esegui'
        ] as Set<String>
        Set<String> knowledgeTokens = [
            'understand', 'how', 'what', 'which', 'why', 'configure', 'configuration', 'setup',
            'scenario', 'workflow', 'process', 'pattern', 'story', 'required', 'precondition',
            'reference', 'master', 'technical', 'architecture', 'invariant', 'sequence'
        ] as Set<String>

        boolean uiActionIntent = tokenSet.any { it in uiTokens }
        boolean knowledgeIntent = tokenSet.any { it in knowledgeTokens } ||
            normalized.contains('how do i') ||
            normalized.contains('what data') ||
            normalized.contains('which service') ||
            normalized.contains('which services') ||
            normalized.contains('what is required')

        boolean executablePromptIntent = normalized.contains('executable prompt') ||
            normalized.contains('related prompt') ||
            normalized.contains('which prompt')
        boolean workflowIntent = tokenSet.any { it in ['workflow', 'process', 'sequence', 'verified', 'test'] }
        boolean configurationIntent = tokenSet.any { it in ['configure', 'configuration', 'setup', 'configured', 'configuring'] } ||
            containsAnyStem(normalized, [' configur', 'configure', 'configured', 'configuring', 'setup of', 'set up '])
        boolean referenceIntent = tokenSet.any { it in ['reference', 'master', 'lookup', 'taxonomy'] } ||
            containsAnyStem(normalized, ['reference data', 'master data', 'lookup data'])
        boolean technicalIntent = tokenSet.any { it in ['technical', 'architecture', 'system', 'platform', 'infrastructure'] } ||
            containsAnyStem(normalized, ['technical configuration', 'system setup', 'platform setup'])
        if (technicalIntent && !referenceIntent) configurationIntent = false

        String intentType = 'unknown'
        if (knowledgeIntent && !uiActionIntent) intentType = 'knowledge'
        else if (uiActionIntent && !knowledgeIntent) intentType = 'ui_action'
        else if (uiActionIntent && knowledgeIntent) intentType = 'mixed'

        String knowledgeType = workflowIntent ? 'workflow' :
            technicalIntent ? 'technical' :
            configurationIntent ? 'configuration' :
            referenceIntent ? 'reference' :
            (intentType == 'knowledge' ? 'general' : 'none')

        return [
            intentType : intentType,
            knowledgeType : knowledgeType,
            executablePromptIntent : executablePromptIntent,
            workflowIntent : workflowIntent,
            configurationIntent : configurationIntent,
            referenceIntent : referenceIntent,
            technicalIntent : technicalIntent,
            uiActionIntent : uiActionIntent,
            knowledgeIntent : knowledgeIntent,
            tokens : tokens,
            normalizedQuery : normalized
        ]
    }

    static Map inferPromptParameters(String queryText, Map document = null) {
        Map inferred = [:]
        String text = (queryText ?: '').trim()
        if (!text) return inferred

        String preferredService = (document?.preferredService ?: '') as String
        String canonicalPrompt = ((document?.canonicalPrompt ?: '') as String).toLowerCase()
        String domainObject = ((document?.domainObject ?: '') as String).toLowerCase()
        String actionKind = ((document?.actionKind ?: '') as String).toLowerCase()

        boolean createProjectPrompt =
            preferredService == 'mantle.work.ProjectServices.create#Project' ||
                (actionKind == 'create' && domainObject == 'project') ||
                canonicalPrompt == 'create project'

        if (createProjectPrompt) {
            String projectName = extractProjectName(text)
            if (projectName) inferred.workEffortName = projectName

            Long priority = extractNumericPriority(text)
            if (priority != null) inferred.priority = priority
        }

        return inferred
    }

    protected static String extractProjectName(String text) {
        if (!text) return null
        List<String> patterns = [
            /(?i)\b(?:progetto|project)\s+([^,\n]+?)(?=(?:\s*,|\s+con\b|\s+with\b|\s+di\b|\s+for\b|$))/,
            /(?i)\bcommessa\s+([^,\n]+?)(?=(?:\s*,|\s+con\b|\s+with\b|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                String candidate = (matcher.group(1) ?: '').trim()
                candidate = candidate.replaceAll(/(?i)^(?:nuovo|nuova|new)\s+/, '').trim()
                if (candidate) return candidate
            }
        }
        return null
    }

    protected static Long extractNumericPriority(String text) {
        if (!text) return null
        def matcher = (text =~ /(?i)\b(?:priority|priorit[àa])\s*(\d+)\b/)
        if (matcher.find()) {
            try {
                return Long.valueOf(matcher.group(1))
            } catch (Throwable ignored) {
                return null
            }
        }
        return null
    }

    static boolean looksLikeProjectAggregatePrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasProject = normalizedText.contains('project') || normalizedText.contains('progetto') || normalizedText.contains('commessa')
        boolean hasMilestone = normalizedText.contains('milestone')
        boolean hasTask = normalizedText.contains('task') || normalizedText.contains('tasks')
        return hasProject && hasMilestone && hasTask
    }

    static boolean looksLikeAssetMoveStatusPrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasAsset = normalizedText.contains('asset ')
        boolean hasMove = normalizedText.contains('spost') || normalizedText.contains('move ')
        boolean hasLocation = normalizedText.contains('locazione') || normalizedText.contains('location')
        boolean hasStatus = normalizedText.contains('on hold') || normalizedText.contains('stato') || normalizedText.contains('status')
        return hasAsset && hasMove && hasLocation && hasStatus
    }

    static Map executeStructuredPromptPlan(ExecutionContext ec, String queryText, Map mergedParameters = null,
                                           Boolean confirmed = null, Boolean dryRun = null, String sessionId = null) {
        Map plan = inferStructuredPromptPlan(ec, queryText, mergedParameters)
        if (!plan?.planType) return [:]
        if (plan.missingFields) {
            return [
                compositeExecution : true,
                compositeType : plan.compositeType ?: plan.planType,
                success : false,
                messages : [],
                errors : ["Contesto mancante per ${plan.compositeType ?: plan.planType}: ${(plan.missingFields as List).join(', ')}"],
                executionResult : [
                    success : false,
                    operation : plan.operation ?: plan.compositeType ?: plan.planType,
                    planType : plan.planType,
                    missingFields : plan.missingFields,
                    parsedParameters : plan.parsedParameters ?: [:],
                    subject : plan.subject ?: [:]
                ]
            ]
        }
        if (plan.planType == 'same_subject_multi_action') {
            return executeSameSubjectMultiActionPlan(ec, plan, confirmed, dryRun, sessionId)
        }
        return [:]
    }

    static Map inferStructuredPromptPlan(ExecutionContext ec, String queryText, Map mergedParameters = null) {
        Map llmPlan = inferLlmStructuredPromptPlan(ec, queryText, mergedParameters)
        if (llmPlan?.planType) return llmPlan
        Map sameSubjectPlan = inferSameSubjectMultiActionPlan(ec, queryText, mergedParameters)
        if (sameSubjectPlan?.planType) return sameSubjectPlan
        return [:]
    }

    static Map inferSameSubjectMultiActionPlan(ExecutionContext ec, String queryText, Map mergedParameters = null) {
        Map assetPlan = inferAssetMoveStatusPlan(ec, queryText, mergedParameters)
        if (assetPlan?.planType) return assetPlan
        return [:]
    }

    static Map executeSameSubjectMultiActionPlan(ExecutionContext ec, Map plan, Boolean confirmed = null,
                                                 Boolean dryRun = null, String sessionId = null) {
        if (!(plan?.planType == 'same_subject_multi_action')) return [:]
        List<Map> actions = (plan.actions ?: []) as List<Map>
        Map subject = (plan.subject instanceof Map) ? (plan.subject as Map) : [:]
        Map context = collectNonNullEntries((plan.initialContext instanceof Map) ? new LinkedHashMap(plan.initialContext as Map) : [:])
        List<String> messages = []
        List<String> errors = []
        String effectiveSessionId = sessionId ?: ec.user.visitId

        if (Boolean.TRUE.equals(dryRun)) {
            return [
                compositeExecution : true,
                compositeType : plan.compositeType ?: 'same_subject_multi_action',
                success : true,
                messages : ["Dry run: prepared ${actions.size()} actions for ${subject.subjectType ?: 'subject'} ${subject.subjectId ?: ''}.".trim()],
                errors : [],
                executionResult : [
                    success : true,
                    dryRun : true,
                    operation : plan.operation ?: plan.compositeType,
                    planType : plan.planType,
                    subject : subject,
                    actions : actions.collect { Map action ->
                        [actionName: action.actionName, serviceName: action.serviceName, parameters: action.parameters]
                    }
                ]
            ]
        }

        List<Map> actionResults = []
        for (int index = 0; index < actions.size(); index++) {
            Map action = actions[index] as Map
            Map resolvedParameters = resolvePlanParameters(action.parameters as Map ?: [:], context, subject)
            try {
                Map guardedCall = ec.service.sync().name('org.moqui.agent.AgentRuntimeServices.call#ServiceGuarded').parameters([
                    serviceName : action.serviceName,
                    parameters : resolvedParameters,
                    confirmed : confirmed,
                    toolName : 'moqui_execute_agent_prompt',
                    sessionId : effectiveSessionId,
                    directToolCall : false
                ]).call()
                Map currentServiceResult = (guardedCall?.serviceResult instanceof Map) ? (guardedCall.serviceResult as Map) : [:]
                actionResults.add([
                    actionName : action.actionName,
                    serviceName : action.serviceName,
                    parameters : resolvedParameters,
                    serviceResult : currentServiceResult
                ])
                if (currentServiceResult.message) messages.add(currentServiceResult.message as String)
                context.putAll(collectNonNullEntries((action.contextUpdates instanceof Map) ?
                        resolvePlanParameters(action.contextUpdates as Map, currentServiceResult + context, subject) : [:]))
                context.putAll(collectNonNullEntries(currentServiceResult))
            } catch (Throwable t) {
                return [
                    compositeExecution : true,
                    compositeType : plan.compositeType ?: 'same_subject_multi_action',
                    success : false,
                    messages : messages,
                    errors : [t.message ?: t.toString()],
                    executionResult : [
                        success : false,
                        operation : plan.operation ?: plan.compositeType,
                        planType : plan.planType,
                        failedStep : action.actionName ?: "step${index + 1}",
                        subject : subject,
                        actions : actionResults
                    ]
                ]
            }
        }
        if (plan.compositeType == 'asset_move_status' && context.assetId && context.statusId) {
            messages.add("Updated Asset ${context.assetId} status to ${context.statusId}.")
        }

        Map sessionBusinessObjects = collectNonNullEntries((plan.sessionBusinessObjects instanceof Map) ?
                resolvePlanParameters(plan.sessionBusinessObjects as Map, context, subject) : context)
        try {
            ec.service.sync().name('org.moqui.agent.AgentRuntimeServices.update#AgentSessionContext').parameters([
                sessionId : effectiveSessionId,
                currentBusinessObjects : sessionBusinessObjects,
                lastExecutedArtifact : [
                    artifactTypeEnumId : 'AT_SERVICE',
                    artifactName : ((actions ? actions.last().serviceName : null) ?: plan.primaryService)
                ],
                lastResult : [
                    success : true,
                    operation : plan.operation ?: plan.compositeType,
                    subject : subject,
                    currentBusinessObjects : sessionBusinessObjects
                ]
            ]).call()
        } catch (Throwable ignored) {
            // Best-effort session enrichment only.
        }

        return [
            compositeExecution : true,
            compositeType : plan.compositeType ?: 'same_subject_multi_action',
            success : true,
            messages : messages,
            errors : errors,
            executionResult : [
                success : true,
                operation : plan.operation ?: plan.compositeType,
                planType : plan.planType,
                subject : subject,
                currentBusinessObjects : sessionBusinessObjects,
                actions : actionResults
            ]
        ]
    }

    protected static Map resolvePlanParameters(Map templateParameters, Map context, Map subject) {
        Map resolved = [:]
        (templateParameters ?: [:]).each { String key, Object value ->
            if (value instanceof String && (value as String).startsWith('{@') && (value as String).endsWith('}')) {
                String token = (value as String).substring(2, (value as String).length() - 1)
                if (token.startsWith('subject.')) {
                    resolved[key] = subject?.get(token.substring('subject.'.length()))
                } else if (token.startsWith('context.')) {
                    resolved[key] = context?.get(token.substring('context.'.length()))
                } else {
                    resolved[key] = context?.get(token)
                }
            } else {
                resolved[key] = value
            }
        }
        return collectNonNullEntries(resolved)
    }

    protected static Map inferAssetMoveStatusPlan(ExecutionContext ec, String queryText, Map mergedParameters = null) {
        if (!looksLikeAssetMoveStatusPrompt(queryText)) return [:]

        Map parsed = inferAssetMoveStatusParameters(ec, queryText, mergedParameters)
        List<String> missing = []
        if (!parsed.assetId) missing.add('assetId')
        if (!parsed.targetLocationSeqId) missing.add('locationSeqId')
        if (!parsed.statusId) missing.add('statusId')
        if (missing) {
            return [
                planType : 'same_subject_multi_action',
                compositeType : 'asset_move_status',
                aggregatePatternId : PATTERN_SILVERSTON_SAME_SUBJECT_MULTI_ACTION,
                success : false,
                subject : [subjectType:'Asset', subjectId:parsed.assetId, subjectIdField:'assetId'],
                missingFields : missing,
                parsedParameters : parsed
            ]
        }

        return [
            planType : 'same_subject_multi_action',
            compositeType : 'asset_move_status',
            aggregatePatternId : PATTERN_SILVERSTON_SAME_SUBJECT_MULTI_ACTION,
            operation : 'asset_move_status',
            subject : [
                subjectType : 'Asset',
                subjectId : parsed.assetId,
                subjectIdField : 'assetId'
            ],
            initialContext : parsed,
            sessionBusinessObjects : [
                assetId : '{@context.assetId}',
                facilityId : '{@context.facilityId}',
                locationSeqId : '{@context.targetLocationSeqId}',
                statusId : '{@context.statusId}'
            ],
            primaryService : 'mantle.product.AssetServices.move#Asset',
            actions : [
                [
                    actionName : 'moveAsset',
                    serviceName : 'mantle.product.AssetServices.move#Asset',
                    parameters : collectNonNullEntries([
                        assetId : '{@subject.subjectId}',
                        facilityId : parsed.facilityId,
                        locationSeqId : parsed.targetLocationSeqId
                    ]),
                    contextUpdates : [
                        assetId : '{@context.newAssetId}',
                        facilityId : '{@context.facilityId}',
                        targetLocationSeqId : '{@context.targetLocationSeqId}'
                    ]
                ],
                [
                    actionName : 'updateAssetStatus',
                    serviceName : 'update#mantle.product.asset.Asset',
                    parameters : [
                        assetId : '{@context.assetId}',
                        statusId : parsed.statusId
                    ],
                    contextUpdates : [
                        assetId : '{@context.assetId}',
                        statusId : '{@context.statusId}'
                    ]
                ]
            ]
        ]
    }

    protected static Map inferLlmStructuredPromptPlan(ExecutionContext ec, String queryText, Map mergedParameters = null) {
        if (!ec || !queryText) return [:]
        try {
            Map modelOptions = buildPromptPlannerModelOptions()
            if (!modelOptions.provider || modelOptions.provider == 'none') return [:]

            AgentModelFacade modelFacade = new AgentModelFacade(ec)
            Map plannerResponse = modelFacade.generateJson(buildPromptPlannerSystemPrompt(), [
                queryText : queryText,
                providedParameters : collectNonNullEntries((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:]),
                supportedPatterns : [
                    [
                        planType : 'same_subject_multi_action',
                        subjectEntity : 'Asset',
                        verbs : ['move', 'update_status']
                    ]
                ]
            ], modelOptions)
            String outputText = (plannerResponse?.outputText ?: '') as String
            if (!outputText) return [:]
            Map decomposition = parseModelJsonMap(outputText)
            if (!decomposition) return [:]

            Map translated = translateLlmDecompositionToExecutablePlan(ec, queryText, decomposition,
                    (mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
            if (translated?.planType) {
                translated.planningSource = 'llm'
                translated.rawDecomposition = decomposition
                return translated
            }
        } catch (Throwable t) {
            ec.logger.warn("LLM prompt planner fallback to deterministic mode for [${abbreviate(queryText, 240)}]: ${t.message}")
        }
        return [:]
    }

    protected static Map translateLlmDecompositionToExecutablePlan(ExecutionContext ec, String queryText, Map decomposition,
                                                                   Map mergedParameters = null) {
        if (!(decomposition instanceof Map)) return [:]
        String planType = (decomposition.planType ?: '') as String
        Map subject = (decomposition.subject instanceof Map) ? (decomposition.subject as Map) : [:]
        List<Map> actions = (decomposition.actions instanceof List) ? (decomposition.actions as List<Map>) : []
        String subjectEntity = ((subject.entityName ?: subject.entity ?: '') as String).trim()
        Set<String> normalizedVerbs = actions.collect { Map action ->
            (((action.verb ?: action.actionKind ?: '') as String).trim().toLowerCase())
        }.findAll { it } as Set<String>

        boolean assetMoveStatusPlan = (planType == 'same_subject_multi_action' || !planType) &&
                subjectEntity.equalsIgnoreCase('Asset') &&
                normalizedVerbs.any { it in ['move', 'relocate', 'transfer', 'spostare'] } &&
                normalizedVerbs.any { it in ['update_status', 'status', 'hold', 'set_status', 'change_status'] }
        if (!assetMoveStatusPlan) return [:]

        Map llmHints = [:]
        if (!subject?.id && subject?.identifier) llmHints.assetId = subject.identifier
        if (subject?.id) llmHints.assetId = subject.id
        actions.each { Map action ->
            String verb = ((action.verb ?: action.actionKind ?: '') as String).trim().toLowerCase()
            Map complements = (action.complements instanceof Map) ? (action.complements as Map) :
                    ((action.parameters instanceof Map) ? (action.parameters as Map) : [:])
            if (verb in ['move', 'relocate', 'transfer', 'spostare']) {
                if (complements.assetId) llmHints.assetId = complements.assetId
                if (complements.facilityId) llmHints.facilityId = complements.facilityId
                if (complements.facilityName) llmHints.facilityName = complements.facilityName
                if (complements.warehouseName && !llmHints.facilityName) llmHints.facilityName = complements.warehouseName
                if (complements.targetLocationSeqId) llmHints.targetLocationSeqId = complements.targetLocationSeqId
                if (complements.toLocationSeqId && !llmHints.targetLocationSeqId) llmHints.targetLocationSeqId = complements.toLocationSeqId
                if (complements.locationSeqId && !llmHints.targetLocationSeqId) llmHints.targetLocationSeqId = complements.locationSeqId
                if (complements.sourceLocationSeqId) llmHints.sourceLocationSeqId = complements.sourceLocationSeqId
                if (complements.fromLocationSeqId && !llmHints.sourceLocationSeqId) llmHints.sourceLocationSeqId = complements.fromLocationSeqId
            } else if (verb in ['update_status', 'status', 'hold', 'set_status', 'change_status']) {
                if (complements.statusId) llmHints.statusId = complements.statusId
                if (complements.statusDescription) llmHints.statusDescription = complements.statusDescription
                if (complements.status && !llmHints.statusDescription) llmHints.statusDescription = complements.status
            }
        }

        Map combinedParameters = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        combinedParameters.putAll(collectNonNullEntries(llmHints))
        return inferAssetMoveStatusPlan(ec, queryText, combinedParameters)
    }

    protected static Map buildPromptPlannerModelOptions() {
        String provider = AgentConfigUtil.getNormalizedString('moqui.agent.chat.provider', 'none')
        if (!provider || provider == 'none') return [provider:'none']
        Map options = [
            provider : provider,
            purpose : 'chat',
            model : AgentConfigUtil.getString('moqui.agent.chat.model',
                    AgentConfigUtil.getString('moqui.agent.reranker.model', 'gpt-5')),
            timeoutSeconds : AgentConfigUtil.getInt('moqui.agent.chat.timeoutSeconds', 60, 5),
            maxOutputTokens : AgentConfigUtil.getInt('moqui.agent.chat.maxOutputTokens', 1200, 200),
            temperature : AgentConfigUtil.getBigDecimal('moqui.agent.chat.temperature', '0'),
            maxAttempts : AgentConfigUtil.getInt('moqui.agent.openai.maxAttempts', 2, 1),
            retryBackoffMs : AgentConfigUtil.getInt('moqui.agent.openai.retryBackoffMs', 750, 0),
            logPayload : AgentConfigUtil.getBoolean('moqui.agent.chat.logPayload', false)
        ]
        if (provider == 'openai_compatible') {
            String compatBaseUrl = AgentConfigUtil.getString('moqui.agent.chat.compat.baseUrl',
                    AgentConfigUtil.getString('moqui.agent.reranker.compat.baseUrl', ''))
            if (compatBaseUrl) options.baseUrl = compatBaseUrl
            String compatApiKey = AgentConfigUtil.getString('moqui.agent.chat.compat.apiKey',
                    AgentConfigUtil.getString('moqui.agent.reranker.compat.apiKey', ''))
            if (compatApiKey) options.apiKey = compatApiKey
        } else if (provider == 'openai') {
            String apiKey = AgentConfigUtil.getString('moqui.agent.chat.apiKey',
                    AgentConfigUtil.getString('moqui.agent.reranker.apiKey', ''))
            if (apiKey) options.apiKey = apiKey
        }
        return options
    }

    protected static String buildPromptPlannerSystemPrompt() {
        return '''You are a deterministic Moqui prompt decomposition planner.
Given a natural language request, extract a structured plan.

Return JSON only with this shape:
{
  "planType": "same_subject_multi_action" | "aggregate_create_tree" | "single_action" | "unknown",
  "subject": {
    "entityName": "...",
    "id": "...",
    "displayName": "...",
    "priority": 3,
    "purpose": "..."
  },
  "actions": [
    {
      "verb": "...",
      "complements": {
        "fieldName": "value"
      }
    }
  ],
  "aggregate": {
    "assignee": {
      "fullName": "...",
      "role": "..."
    },
    "milestones": [
      {
        "name": "...",
        "priority": 3,
        "tasks": [
          {"name": "...", "priority": 3}
        ]
      }
    ]
  }
}

Rules:
- Identify the business subject entity first.
- Separate multiple verbs/actions that apply to the same subject.
- Complements should use stable names when obvious, such as assetId, facilityName, sourceLocationSeqId, targetLocationSeqId, statusDescription.
- For project/work-breakdown requests, use planType "aggregate_create_tree" and fill aggregate.milestones/tasks plus aggregate.assignee when present.
- Do not invent IDs unless explicit in the request.
- If uncertain, return best-effort extraction with "planType":"unknown".'''
    }

    protected static Map parseModelJsonMap(String text) {
        if (!text) return [:]
        String cleaned = text.trim()
                .replaceFirst(/^```(?:json)?\s*/, '')
                .replaceFirst(/\s*```$/, '')
                .trim()
        Object parsed = JSON_SLURPER.parseText(cleaned)
        return (parsed instanceof Map) ? (parsed as Map) : [:]
    }

    protected static String abbreviate(String text, int maxLength) {
        if (!text) return text
        if (text.length() <= maxLength) return text
        return text.substring(0, Math.max(0, maxLength - 3)) + '...'
    }

    static Map selectPreferredAggregateRootDocument(String queryText, List candidates) {
        if (!looksLikeProjectAggregatePrompt(queryText) || !(candidates instanceof List) || candidates.isEmpty()) return null
        return (candidates as List<Map>).find { Map candidate ->
            if (!Boolean.TRUE.equals(candidate?.runtimeExecutable)) return false
            String preferredService = (candidate.preferredService ?: '') as String
            String canonicalPrompt = ((candidate.canonicalPrompt ?: '') as String).toLowerCase()
            String domainObject = ((candidate.domainObject ?: '') as String).toLowerCase()
            String actionKind = ((candidate.actionKind ?: '') as String).toLowerCase()
            preferredService == 'mantle.work.ProjectServices.create#Project' ||
                    canonicalPrompt == 'create project' ||
                    (actionKind == 'create' && domainObject == 'project')
        } as Map
    }

    static boolean isProjectAggregateRootDocument(Map document) {
        if (!(document instanceof Map)) return false
        String preferredService = (document.preferredService ?: '') as String
        String canonicalPrompt = ((document.canonicalPrompt ?: '') as String).toLowerCase()
        String domainObject = ((document.domainObject ?: '') as String).toLowerCase()
        String actionKind = ((document.actionKind ?: '') as String).toLowerCase()
        return preferredService == 'mantle.work.ProjectServices.create#Project' ||
                canonicalPrompt == 'create project' ||
                (actionKind == 'create' && domainObject == 'project')
    }

    static Map inferProjectAggregatePlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        return inferRootChildHierarchyPlan(ec, queryText, document, mergedParameters)
    }

    static Map inferRootChildHierarchyPlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        Map inferredParameters = (mergedParameters instanceof Map) ? new LinkedHashMap(mergedParameters as Map) : [:]
        if (inferredParameters.isEmpty()) inferredParameters.putAll(inferPromptParameters(queryText, document))

        String preferredService = (document?.preferredService ?: '') as String
        String canonicalPrompt = ((document?.canonicalPrompt ?: '') as String).toLowerCase()
        String domainObject = ((document?.domainObject ?: '') as String).toLowerCase()
        String actionKind = ((document?.actionKind ?: '') as String).toLowerCase()
        boolean createProjectPrompt =
                preferredService == 'mantle.work.ProjectServices.create#Project' ||
                        (actionKind == 'create' && domainObject == 'project') ||
                        canonicalPrompt == 'create project'

        if (!createProjectPrompt) return [:]

        Map llmProjectPlan = inferLlmProjectAggregatePlan(ec, queryText, document, inferredParameters)
        if (llmProjectPlan?.rootNode) return llmProjectPlan

        String normalizedText = normalizePromptWhitespace(queryText)
        List<Map> milestones = extractMilestonePlan(normalizedText)
        if (!milestones) return [:]

        Map plan = [
                aggregateType : 'root_parent_tree',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId,
                project : inferredParameters,
                milestones : milestones,
                taskCount : milestones.collect { Map milestone -> ((milestone.tasks ?: []) as List).size() }.sum() ?: 0
        ]

        String purposeDescription = extractPurposeDescription(normalizedText)
        String purposeEnumId = resolveEnumerationIdByDescription(ec, 'WorkEffortPurpose', purposeDescription, 'WetProject')
        if (purposeEnumId) plan.project.purposeEnumId = purposeEnumId

        String assignToName = extractAssignedPersonName(normalizedText)
        if (assignToName) {
            String assignToPartyId = resolvePartyIdByPersonName(ec, assignToName)
            if (assignToPartyId) {
                plan.assignToPartyId = assignToPartyId
                plan.assignToName = assignToName
            }
        }

        String assignRoleTypeId = resolveRoleTypeIdByDescription(ec, extractAssignedRoleDescription(normalizedText))
        if (assignRoleTypeId) plan.assignRoleTypeId = assignRoleTypeId

        if (plan.project?.priority != null) {
            Long priority = plan.project.priority as Long
            milestones.each { Map milestone ->
                milestone.priority = priority
                ((milestone.tasks ?: []) as List<Map>).each { Map task -> task.priority = priority }
            }
        }

        plan.rootNode = buildProjectAggregateRootNode(plan)

        return plan
    }

    static Map executeProjectAggregatePlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null,
                                           Boolean confirmed = null, Boolean dryRun = null, String sessionId = null) {
        return executeRootChildHierarchyPlan(ec, queryText, document, mergedParameters, confirmed, dryRun, sessionId)
    }

    static Map executeRootChildHierarchyPlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null,
                                             Boolean confirmed = null, Boolean dryRun = null, String sessionId = null) {
        Map plan = inferRootChildHierarchyPlan(ec, queryText, document, mergedParameters)
        if (!plan?.rootNode) return [:]

        Map result = [
                compositeExecution : true,
                compositeType : 'root_child_hierarchy',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                success : false,
                messages : [],
                errors : [],
                aggregatePlan : plan
        ]

        if (Boolean.TRUE.equals(dryRun)) {
            result.success = true
            result.messages.add("Dry run: aggregate plan prepared with ${plan.milestones.size()} milestones and ${plan.taskCount ?: 0} tasks.")
            result.executionResult = [
                    success : true,
                    dryRun : true,
                    operation : 'composite_aggregate_create',
                    aggregateType : plan.aggregateType,
                    aggregatePatternId : plan.aggregatePatternId,
                    plan : plan
            ]
            return result
        }
        Map aggregateResult = executeAggregateRootNode(ec, plan.rootNode as Map, confirmed, sessionId)
        result.success = Boolean.TRUE.equals(aggregateResult?.success)
        result.executionLog = aggregateResult?.executionLog ?: []
        result.nodeCount = aggregateResult?.nodeCount ?: 0
        result.executionResult = aggregateResult?.executionResult as Map
        if (aggregateResult?.messages) result.messages.addAll(aggregateResult.messages as List)
        if (aggregateResult?.errors) result.errors.addAll(aggregateResult.errors as List)
        if (aggregateResult?.rootNodeContext?.workEffortId) result.projectWorkEffortId = aggregateResult.rootNodeContext.workEffortId
        return result
    }

    protected static Map inferLlmProjectAggregatePlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        if (!ec || !queryText) return [:]
        try {
            Map modelOptions = buildPromptPlannerModelOptions()
            if (!modelOptions.provider || modelOptions.provider == 'none') return [:]

            AgentModelFacade modelFacade = new AgentModelFacade(ec)
            Map plannerResponse = modelFacade.generateJson(buildPromptPlannerSystemPrompt(), [
                    queryText : queryText,
                    providedParameters : collectNonNullEntries((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:]),
                    supportedPatterns : [
                            [planType : 'same_subject_multi_action', subjectEntity : 'Asset', verbs : ['move', 'update_status']],
                            [planType : 'aggregate_create_tree', subjectEntity : 'Project', children : ['Milestone', 'Task']]
                    ]
            ], modelOptions)
            String outputText = (plannerResponse?.outputText ?: '') as String
            if (!outputText) return [:]
            Map decomposition = parseModelJsonMap(outputText)
            if (!decomposition) return [:]
            Map translated = translateLlmDecompositionToProjectAggregatePlan(ec, document, mergedParameters, decomposition)
            if (translated?.rootNode) {
                translated.planningSource = 'llm'
                translated.rawDecomposition = decomposition
                return translated
            }
        } catch (Throwable t) {
            ec.logger.warn("LLM project planner fallback to deterministic mode for [${abbreviate(queryText, 240)}]: ${t.message}")
        }
        return [:]
    }

    protected static Map translateLlmDecompositionToProjectAggregatePlan(ExecutionContext ec, Map document, Map mergedParameters, Map decomposition) {
        if (!(decomposition instanceof Map)) return [:]
        String planType = (decomposition.planType ?: '') as String
        Map subject = (decomposition.subject instanceof Map) ? (decomposition.subject as Map) : [:]
        String subjectEntity = ((subject.entityName ?: subject.entity ?: '') as String).trim()
        Map aggregate = (decomposition.aggregate instanceof Map) ? (decomposition.aggregate as Map) : [:]
        if (!(subjectEntity.equalsIgnoreCase('Project') || planType == 'aggregate_create_tree')) return [:]
        if (!aggregate && !(decomposition.actions instanceof List)) return [:]

        Map projectParams = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (!projectParams.workEffortName) {
            projectParams.workEffortName = (subject.displayName ?: subject.name ?: subject.id ?: null) as String
        }
        if (!projectParams.priority) {
            Object priorityValue = subject.priority ?: aggregate.priority
            if (priorityValue != null) {
                try { projectParams.priority = Long.valueOf(priorityValue.toString()) } catch (Throwable ignored) { }
            }
        }

        String purposeDescription = (subject.purpose ?: aggregate.purpose ?: null) as String
        String purposeEnumId = resolveEnumerationIdByDescription(ec, 'WorkEffortPurpose', purposeDescription, 'WetProject')
        if (purposeEnumId) projectParams.purposeEnumId = purposeEnumId

        List<Map> milestones = []
        List rawMilestones = (aggregate.milestones instanceof List) ? (aggregate.milestones as List) : []
        rawMilestones.each { Object rawMilestone ->
            if (!(rawMilestone instanceof Map)) return
            Map rawMilestoneMap = rawMilestone as Map
            Map milestone = [workEffortName : ((rawMilestoneMap.name ?: rawMilestoneMap.workEffortName ?: '') as String).trim(), tasks : []]
            if (!milestone.workEffortName) return
            Object milestonePriority = rawMilestoneMap.priority ?: projectParams.priority
            if (milestonePriority != null) {
                try { milestone.priority = Long.valueOf(milestonePriority.toString()) } catch (Throwable ignored) { }
            }
            List rawTasks = (rawMilestoneMap.tasks instanceof List) ? (rawMilestoneMap.tasks as List) : []
            rawTasks.each { Object rawTask ->
                if (!(rawTask instanceof Map)) return
                Map rawTaskMap = rawTask as Map
                String taskName = ((rawTaskMap.name ?: rawTaskMap.workEffortName ?: '') as String).trim()
                if (!taskName) return
                Map task = [workEffortName : taskName, purposeEnumId : 'WepTask']
                Object taskPriority = rawTaskMap.priority ?: milestone.priority ?: projectParams.priority
                if (taskPriority != null) {
                    try { task.priority = Long.valueOf(taskPriority.toString()) } catch (Throwable ignored) { }
                }
                milestone.tasks.add(task)
            }
            milestones.add(milestone)
        }
        if (!milestones) return [:]

        Map plan = [
                aggregateType : 'root_parent_tree',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId,
                project : collectNonNullEntries(projectParams),
                milestones : milestones,
                taskCount : milestones.collect { Map milestone -> ((milestone.tasks ?: []) as List).size() }.sum() ?: 0
        ]

        Map assignee = (aggregate.assignee instanceof Map) ? (aggregate.assignee as Map) : [:]
        String assignToName = (assignee.fullName ?: assignee.name ?: null) as String
        if (assignToName) {
            String assignToPartyId = resolvePartyIdByPersonName(ec, assignToName)
            if (assignToPartyId) {
                plan.assignToPartyId = assignToPartyId
                plan.assignToName = assignToName
            }
        }
        String roleDescription = (assignee.role ?: assignee.roleDescription ?: null) as String
        String assignRoleTypeId = resolveRoleTypeIdByDescription(ec, roleDescription)
        if (assignRoleTypeId) plan.assignRoleTypeId = assignRoleTypeId

        if (plan.project?.priority != null) {
            Long priority = plan.project.priority as Long
            milestones.each { Map milestone ->
                milestone.priority = milestone.priority ?: priority
                ((milestone.tasks ?: []) as List<Map>).each { Map task -> task.priority = task.priority ?: priority }
            }
        }

        plan.rootNode = buildProjectAggregateRootNode(plan)
        return plan
    }

    protected static Map buildProjectAggregateRootNode(Map plan) {
        List<Map> milestoneNodes = []
        int milestoneIndex = 0
        (plan.milestones as List<Map>).each { Map milestone ->
            milestoneIndex++
            String milestoneId = buildMilestoneId((plan.project?.workEffortId ?: '{@root.workEffortId}') as String, milestoneIndex)
            List<Map> taskNodes = ((milestone.tasks ?: []) as List<Map>).collect { Map task ->
                [
                        nodeType : 'task',
                        documentId : 'agent-prompt://task/tasksummary/createtask',
                        resultIdField : 'workEffortId',
                        contextKey : 'task',
                        parameters : collectNonNullEntries([
                                workEffortName : task.workEffortName,
                                priority : task.priority,
                                milestoneWorkEffortId : '{@parent.workEffortId}',
                                assignToPartyId : plan.assignToPartyId,
                                assignRoleTypeId : plan.assignRoleTypeId,
                                purposeEnumId : task.purposeEnumId
                        ]),
                        inheritRootFields : [rootWorkEffortId : 'workEffortId'],
                        inheritParentFields : [parentWorkEffortId : 'workEffortId']
                ]
            }

            milestoneNodes.add([
                    nodeType : 'milestone',
                    documentId : 'agent-prompt://project/editmilestones/createmilestone',
                    resultIdField : 'workEffortId',
                    contextKey : 'milestone',
                    parameters : collectNonNullEntries([
                            workEffortId : milestoneId,
                            workEffortName : milestone.workEffortName,
                            estimatedStartDate : milestone.estimatedStartDate,
                            estimatedCompletionDate : milestone.estimatedCompletionDate
                    ]),
                    inheritRootFields : [rootWorkEffortId : 'workEffortId'],
                    postCreateServiceName : milestone.priority != null ? 'update#mantle.work.effort.WorkEffort' : null,
                    postCreateParameters : milestone.priority != null ? [priority : milestone.priority] : null,
                    children : taskNodes
            ])
        }

        return [
                nodeType : 'project',
                documentId : plan.rootDocumentId,
                resultIdField : 'workEffortId',
                contextKey : 'project',
                parameters : plan.project,
                children : milestoneNodes
        ]
    }

    protected static Map executeAggregateRootNode(ExecutionContext ec, Map rootNode, Boolean confirmed, String sessionId) {
        List executionLog = []
        Map rootResult = executeAggregateNode(ec, rootNode, null, null, confirmed, sessionId, executionLog)
        int nodeCount = countAggregateNodes(rootNode)
        if (!Boolean.TRUE.equals(rootResult?.success)) {
            return [
                    success : false,
                    messages : rootResult?.messages ?: [],
                    errors : rootResult?.errors ?: [],
                    executionLog : executionLog,
                    nodeCount : nodeCount,
                    executionResult : rootResult?.executionResult,
                    rootNodeContext : rootResult?.nodeContext
            ]
        }

        Map rootContext = rootResult.nodeContext ?: [:]
        int milestoneCount = executionLog.count { Map entry -> entry.nodeType == 'milestone' }
        int taskCount = executionLog.count { Map entry -> entry.nodeType == 'task' }
        return [
                success : true,
                messages : (rootResult.messages ?: []) + ["Created aggregate root ${rootContext[rootNode.resultIdField ?: 'workEffortId']} with ${milestoneCount} milestones and ${taskCount} tasks."],
                errors : [],
                executionLog : executionLog,
                nodeCount : nodeCount,
                executionResult : [
                        success : true,
                        operation : 'composite_aggregate_create',
                        aggregateType : 'root_parent_tree',
                        rootId : rootContext[rootNode.resultIdField ?: 'workEffortId'],
                        milestoneCount : milestoneCount,
                        taskCount : taskCount
                ],
                rootNodeContext : rootContext
        ]
    }

    protected static Map executeAggregateNode(ExecutionContext ec, Map node, Map rootContext, Map parentContext,
                                              Boolean confirmed, String sessionId, List executionLog) {
        Map effectiveRootContext = rootContext ?: [:]
        Map effectiveParentContext = parentContext ?: [:]
        Map nodeParameters = buildAggregateNodeParameters(node, effectiveRootContext, effectiveParentContext)
        Map execResult = ec.service.sync().name('org.moqui.agent.AgentExecutionServices.execute#AgentPrompt')
                .parameters([
                        documentId : node.documentId,
                        parameters : nodeParameters,
                        confirmed : confirmed,
                        dryRun : false,
                        // Aggregate execution already propagates context explicitly through root/parent maps.
                        // Re-reading AgentSessionContext here leaks the previous node's workEffortId into child creates.
                        useSessionContext : false,
                        sessionId : sessionId
                ]).call()

        List messages = []
        if (execResult?.messages) messages.addAll(execResult.messages as List)
        List errors = []
        if (execResult?.errors) errors.addAll(execResult.errors as List)

        String resultIdField = (node.resultIdField ?: inferResultIdField(node.documentId as String)) as String
        String createdId = extractCreatedId(execResult, resultIdField)
        Map nodeContext = collectNonNullEntries(new LinkedHashMap(nodeParameters + [(resultIdField): createdId]))

        executionLog.add([
                nodeType : node.nodeType,
                documentId : node.documentId,
                idField : resultIdField,
                idValue : createdId,
                parameters : summarizeValue(nodeParameters),
                success : Boolean.TRUE.equals(execResult?.success)
        ])

        if (!Boolean.TRUE.equals(execResult?.success)) {
            return [
                    success : false,
                    messages : messages,
                    errors : errors,
                    executionResult : execResult,
                    nodeContext : nodeContext
            ]
        }

        applyAggregateNodePostCreate(ec, node, nodeContext)

        Map propagatedRootContext = effectiveRootContext ?: [:]
        if (!propagatedRootContext && nodeContext) propagatedRootContext = nodeContext

        for (Map childNode in ((node.children ?: []) as List<Map>)) {
            Map childResult = executeAggregateNode(ec, childNode, propagatedRootContext, nodeContext, confirmed, sessionId, executionLog)
            messages.addAll((childResult?.messages ?: []) as List)
            errors.addAll((childResult?.errors ?: []) as List)
            if (!Boolean.TRUE.equals(childResult?.success)) {
                return [
                        success : false,
                        messages : messages,
                        errors : errors,
                        executionResult : childResult?.executionResult,
                        nodeContext : nodeContext
                ]
            }
        }

        return [
                success : true,
                messages : messages,
                errors : errors,
                executionResult : execResult,
                nodeContext : nodeContext
        ]
    }

    protected static Map buildAggregateNodeParameters(Map node, Map rootContext, Map parentContext) {
        Map params = [:]
        if (node.parameters instanceof Map) {
            (node.parameters as Map).each { String key, Object value ->
                params[key] = resolveAggregateTemplateValue(value, rootContext, parentContext)
            }
        }
        ((node.inheritRootFields ?: [:]) as Map).each { String targetField, String sourceField ->
            Object sourceValue = rootContext?.get(sourceField)
            if (sourceValue != null) params[targetField] = sourceValue
        }
        ((node.inheritParentFields ?: [:]) as Map).each { String targetField, String sourceField ->
            Object sourceValue = parentContext?.get(sourceField)
            if (sourceValue != null) params[targetField] = sourceValue
        }
        return collectNonNullEntries(params)
    }

    protected static Object resolveAggregateTemplateValue(Object value, Map rootContext, Map parentContext) {
        if (!(value instanceof String)) return value
        String resolved = value as String
        rootContext?.each { String key, Object rootValue ->
            if (rootValue != null) resolved = resolved.replace("{@root.${key}}", rootValue.toString())
        }
        parentContext?.each { String key, Object parentValue ->
            if (parentValue != null) resolved = resolved.replace("{@parent.${key}}", parentValue.toString())
        }
        return resolved
    }

    protected static void applyAggregateNodePostCreate(ExecutionContext ec, Map node, Map nodeContext) {
        String postCreateServiceName = node.postCreateServiceName as String
        if (!postCreateServiceName) return
        Map serviceParameters = [:]
        String idField = (node.resultIdField ?: inferResultIdField(node.documentId as String)) as String
        if (idField && nodeContext?.get(idField) != null) serviceParameters[idField] = nodeContext[idField]
        if (node.postCreateParameters instanceof Map) serviceParameters.putAll(node.postCreateParameters as Map)
        serviceParameters = collectNonNullEntries(serviceParameters)
        if (serviceParameters) ec.service.sync().name(postCreateServiceName).parameters(serviceParameters).call()
    }

    protected static int countAggregateNodes(Map node) {
        if (!node) return 0
        int count = 1
        ((node.children ?: []) as List<Map>).each { Map childNode -> count += countAggregateNodes(childNode) }
        return count
    }

    protected static String inferResultIdField(String documentId) {
        String normalized = (documentId ?: '').toLowerCase()
        if (normalized.contains('orderpart')) return 'orderPartSeqId'
        if (normalized.contains('orderitem') || normalized.contains('/createitem')) return 'orderItemSeqId'
        if (normalized.contains('request')) return 'requestId'
        return 'workEffortId'
    }

    protected static String extractCreatedId(Map execResult, String idField) {
        if (!(execResult instanceof Map) || !idField) return null
        Map serviceResult = (execResult.serviceResult instanceof Map) ? (execResult.serviceResult as Map) : [:]
        if (serviceResult[idField]) return serviceResult[idField] as String
        Map updatedSessionContext = (execResult.updatedSessionContext instanceof Map) ? (execResult.updatedSessionContext as Map) : [:]
        Map lastResult = (updatedSessionContext.lastResult instanceof Map) ? (updatedSessionContext.lastResult as Map) : [:]
        if (lastResult[idField]) return lastResult[idField] as String
        return null
    }

    protected static Map collectNonNullEntries(Map sourceMap) {
        Map cleaned = [:]
        (sourceMap ?: [:]).each { String key, Object value ->
            if (key && value != null && (!(value instanceof String) || value != '')) cleaned[key] = value
        }
        return cleaned
    }

    protected static String normalizePromptWhitespace(String text) {
        return (text ?: '').replace('\n', ' ').replace('\r', ' ').replaceAll(/\s+/, ' ').trim()
    }

    protected static List<Map> extractMilestonePlan(String text) {
        if (!text) return []
        List<Map> milestones = []

        def milestoneMatcher = (text =~ /(?is)\b(?:con|with)\s+(\d+)\s+milestone\s+(.+?)(?=(?:\binoltre\b|\balso\b|\bsotto al primo milestone\b|$))/)
        if (milestoneMatcher.find()) {
            Integer count = safeInteger(milestoneMatcher.group(1))
            String rawNames = cleanupPromptSegment(milestoneMatcher.group(2))
            List<String> milestoneNames = splitNamedItems(rawNames, count)
            milestoneNames.each { String milestoneName ->
                milestones.add([workEffortName : milestoneName, tasks : []])
            }
        }

        if (!milestones) return []

        Map<Integer, String> ordinalWords = [1:'primo', 2:'secondo', 3:'terzo', 4:'quarto', 5:'quinto']
        ordinalWords.each { Integer ordinalIndex, String ordinalWord ->
            String nextOrdinal = ordinalWords[ordinalIndex + 1]
            String regex = nextOrdinal ?
                    "(?is)\\bsotto al ${ordinalWord} milestone\\b\\s*(?:i\\s+)?(\\d+)\\s+tasks?\\s*(?:con nome\\s+)?(.+?)(?=(?:,?\\s*e\\s*sotto al ${nextOrdinal} milestone\\b|\\bsotto al ${nextOrdinal} milestone\\b|" + '$' + "))" :
                    "(?is)\\bsotto al ${ordinalWord} milestone\\b\\s*(?:i\\s+)?(\\d+)\\s+tasks?\\s*(?:con nome\\s+)?(.+?)(?=" + '$' + ")"
            def taskMatcher = (text =~ regex)
            if (taskMatcher.find() && milestones.size() >= ordinalIndex) {
                Integer taskCount = safeInteger(taskMatcher.group(1))
                String rawTaskNames = cleanupPromptSegment(taskMatcher.group(2))
                List<String> taskNames = splitNamedItems(rawTaskNames, taskCount)
                milestones[ordinalIndex - 1].tasks = taskNames.collect { String taskName ->
                    [workEffortName : taskName, purposeEnumId : 'WepTask']
                }
            }
        }

        return milestones
    }

    protected static String cleanupPromptSegment(String text) {
        if (!text) return null
        String cleaned = normalizePromptWhitespace(text)
                .replaceAll(/(?i)\s+ed\s+/, ' e ')
                .replaceAll(/[.,;:]+$/, '')
                .trim()
        return cleaned
    }

    protected static List<String> splitNamedItems(String rawText, Integer expectedCount) {
        if (!rawText) return []
        List<String> parts = rawText.split(/\s*,\s*/).collect { cleanupPromptSegment(it) }.findAll { it } as List<String>
        if (!parts) parts = [cleanupPromptSegment(rawText)].findAll { it } as List<String>

        while (expectedCount && parts.size() < expectedCount) {
            int splitIndex = -1
            String selectedPart = null
            parts.eachWithIndex { String part, int index ->
                if (part?.toLowerCase()?.contains(' e ') && (selectedPart == null || part.size() > selectedPart.size())) {
                    splitIndex = index
                    selectedPart = part
                }
            }
            if (splitIndex < 0 || !selectedPart) break
            int lastSeparatorIndex = selectedPart.toLowerCase().lastIndexOf(' e ')
            if (lastSeparatorIndex <= 0) break
            String left = cleanupPromptSegment(selectedPart.substring(0, lastSeparatorIndex))
            String right = cleanupPromptSegment(selectedPart.substring(lastSeparatorIndex + 3))
            parts.remove(splitIndex)
            if (right) parts.add(splitIndex, right)
            if (left) parts.add(splitIndex, left)
        }

        return parts
    }

    protected static Integer safeInteger(String text) {
        if (!text) return null
        try {
            return Integer.valueOf(text.trim())
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static String extractPurposeDescription(String text) {
        if (!text) return null
        def matcher = (text =~ /(?i)\bpurpose\s+([^,\n]+?)(?=(?:\s+con\b|\s+with\b|,|\.|$))/)
        if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        return null
    }

    protected static String extractAssignedPersonName(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\bassegna(?:\s+tutti\s+i?\s*ta?sks?)?.*?\ba\s+([^,.\n]+?)(?=(?:\s+con\s+ruolo\b|,|\.|$))/,
                /(?i)\bassign(?:\s+all\s+tasks?)?.*?\bto\s+([^,.\n]+?)(?=(?:\s+with\s+role\b|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        }
        return null
    }

    protected static String extractAssignedRoleDescription(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\bcon\s+ruolo\s+([^,.\n]+?)(?=(?:,|\.|$))/,
                /(?i)\bwith\s+role\s+([^,.\n]+?)(?=(?:,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        }
        return null
    }

    protected static Map inferAssetMoveStatusParameters(ExecutionContext ec, String queryText, Map mergedParameters = null) {
        Map parameters = (mergedParameters instanceof Map) ? new LinkedHashMap(mergedParameters as Map) : [:]
        String normalizedText = normalizePromptWhitespace(queryText)

        if (!parameters.assetId) {
            def assetMatcher = (normalizedText =~ /(?i)\basset\s+([A-Z0-9_:-]+)\b/)
            if (assetMatcher.find()) parameters.assetId = assetMatcher.group(1)
        }

        if (!parameters.targetLocationSeqId) {
            List patterns = [
                /(?i)\balla\s+locazione\s+([A-Z0-9_-]+)/,
                /(?i)\bto\s+location\s+([A-Z0-9_-]+)/
            ]
            for (pattern in patterns) {
                def matcher = (normalizedText =~ pattern)
                if (matcher.find()) {
                    parameters.targetLocationSeqId = matcher.group(1)
                    break
                }
            }
        }

        if (!parameters.sourceLocationSeqId) {
            List patterns = [
                /(?i)\bdalla\s+locazione\s+([A-Z0-9_-]+)/,
                /(?i)\bfrom\s+location\s+([A-Z0-9_-]+)/
            ]
            for (pattern in patterns) {
                def matcher = (normalizedText =~ pattern)
                if (matcher.find()) {
                    parameters.sourceLocationSeqId = matcher.group(1)
                    break
                }
            }
        }

        String facilityName = parameters.facilityName as String
        if (!facilityName) {
            List patterns = [
                /(?i)\bdal\s+magazzino\s+(.+?)(?=(?:,\s*l'?asset\b|\s+l'?asset\b|,\s*asset\b|\s+asset\b))/,
                /(?i)\bfrom\s+warehouse\s+(.+?)(?=(?:,\s*the\s+asset\b|\s+the\s+asset\b|,\s*asset\b|\s+asset\b))/
            ]
            for (pattern in patterns) {
                def matcher = (normalizedText =~ pattern)
                if (matcher.find()) {
                    facilityName = cleanupPromptSegment(matcher.group(1))
                    parameters.facilityName = facilityName
                    break
                }
            }
        }

        if (!parameters.statusId) {
            String statusDescription = null
            List patterns = [
                /(?i)\bstato\s+([^,.\n]+?)(?=(?:,|\.|$))/,
                /(?i)\bstatus\s+([^,.\n]+?)(?=(?:,|\.|$))/,
                /(?i)\bon\s+hold\b/
            ]
            for (pattern in patterns) {
                def matcher = (normalizedText =~ pattern)
                if (matcher.find()) {
                    if (pattern.toString().toLowerCase().contains('on\\s+hold')) statusDescription = 'On Hold'
                    else statusDescription = cleanupPromptSegment(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group(0))
                    break
                }
            }
            if (statusDescription) {
                parameters.statusDescription = statusDescription
                parameters.statusId = resolveStatusIdByDescription(ec, statusDescription, 'Ast')
            }
        }

        if (!parameters.facilityId && facilityName) {
            parameters.facilityId = resolveFacilityIdByName(ec, facilityName)
        }

        if (parameters.assetId && (!parameters.facilityId || !parameters.sourceLocationSeqId)) {
            try {
                def asset = ec.entity.find('mantle.product.asset.Asset')
                        .condition('assetId', parameters.assetId as String)
                        .one()
                if (asset) {
                    if (!parameters.facilityId) parameters.facilityId = asset.facilityId
                    if (!parameters.sourceLocationSeqId) parameters.sourceLocationSeqId = asset.locationSeqId
                }
            } catch (Throwable ignored) { }
        }

        return collectNonNullEntries(parameters)
    }

    protected static String resolveEnumerationIdByDescription(ExecutionContext ec, String enumTypeId, String description, String parentEnumId = null) {
        if (!ec || !enumTypeId || !description) return null
        try {
            def find = ec.entity.find('moqui.basic.Enumeration')
                    .condition('enumTypeId', enumTypeId)
                    .condition('description', description)
            if (parentEnumId) find.condition('parentEnumId', parentEnumId)
            def enumeration = find.one()
            if (enumeration?.enumId) return enumeration.enumId as String
        } catch (Throwable ignored) { }
        return null
    }

    protected static String resolvePartyIdByPersonName(ExecutionContext ec, String fullName) {
        if (!ec || !fullName) return null
        List<String> nameParts = normalizePromptWhitespace(fullName).split(/\s+/).findAll { it } as List<String>
        if (nameParts.size() < 2) return null
        String firstName = nameParts.first()
        String lastName = nameParts.last()
        try {
            def person = ec.entity.find('mantle.party.Person')
                    .condition('firstName', firstName)
                    .condition('lastName', lastName)
                    .one()
            if (person?.partyId) return person.partyId as String
        } catch (Throwable ignored) { }
        return null
    }

    protected static String resolveRoleTypeIdByDescription(ExecutionContext ec, String description) {
        if (!description) return null
        try {
            def roleType = ec?.entity?.find('mantle.party.RoleType')
                    ?.condition('description', description)
                    ?.one()
            if (roleType?.roleTypeId) return roleType.roleTypeId as String
        } catch (Throwable ignored) { }
        if (description.equalsIgnoreCase('Project Manager')) return 'ProjectManager'
        return null
    }

    protected static String resolveFacilityIdByName(ExecutionContext ec, String facilityName) {
        if (!ec || !facilityName) return null
        String normalized = normalizePromptWhitespace(facilityName)
        try {
            def exact = ec.entity.find('mantle.facility.Facility')
                    .condition('facilityName', normalized)
                    .one()
            if (exact?.facilityId) return exact.facilityId as String

            List facilities = ec.entity.find('mantle.facility.Facility').list()
            String lowered = normalized.toLowerCase()
            def fuzzy = facilities.find { ev ->
                String candidate = (ev.facilityName ?: '') as String
                candidate && candidate.toLowerCase().contains(lowered)
            }
            if (fuzzy?.facilityId) return fuzzy.facilityId as String
        } catch (Throwable ignored) { }
        return null
    }

    protected static String resolveStatusIdByDescription(ExecutionContext ec, String description, String statusPrefix = null) {
        if (!ec || !description) return null
        String normalized = normalizePromptWhitespace(description)
        try {
            List statusList = ec.entity.find('moqui.basic.StatusItem').list()
            def exact = statusList.find { ev ->
                String statusId = ev.statusId as String
                String candidate = (ev.description ?: '') as String
                candidate?.equalsIgnoreCase(normalized) && (!statusPrefix || statusId?.startsWith(statusPrefix))
            }
            if (exact?.statusId) return exact.statusId as String

            String lowered = normalized.toLowerCase()
            def fuzzy = statusList.find { ev ->
                String statusId = ev.statusId as String
                String candidate = (ev.description ?: '') as String
                candidate && candidate.toLowerCase().contains(lowered) && (!statusPrefix || statusId?.startsWith(statusPrefix))
            }
            if (fuzzy?.statusId) return fuzzy.statusId as String
        } catch (Throwable ignored) { }
        return null
    }

    protected static String buildMilestoneId(String projectId, int ordinalIndex) {
        String padded = ordinalIndex < 10 ? "0${ordinalIndex}" : ordinalIndex.toString()
        return "${projectId}-MS-${padded}"
    }

    static Object fromJson(String text) {
        if (!text) return null
        JSON_SLURPER.parseText(text)
    }

    static Object sanitizeForLogging(Object value, String mode, boolean summaryMode = false) {
        String normalizedMode = (mode ?: '').trim().toLowerCase()
        if (normalizedMode == 'none') return null
        if (summaryMode || normalizedMode == 'summary') return summarizeValue(value)
        if (normalizedMode == 'full') return value
        if (normalizedMode == 'masked') return maskValue(value)
        return value
    }

    static Object summarizeValue(Object value) {
        if (value == null) return null
        if (value instanceof Map) {
            Map mapValue = (Map) value
            return [
                _summaryType : 'map',
                keyCount : mapValue.size(),
                keys : mapValue.keySet().collect { it as String }.sort().take(20)
            ]
        }
        if (value instanceof Collection) {
            Collection collectionValue = (Collection) value
            return [
                _summaryType : 'collection',
                size : collectionValue.size()
            ]
        }
        if (value.getClass().isArray()) {
            return [
                _summaryType : 'array',
                size : java.lang.reflect.Array.getLength(value)
            ]
        }
        String text = value.toString()
        return text.size() > 200 ? text.substring(0, 200) + '...' : text
    }

    static Object maskValue(Object value) {
        if (value == null) return null
        if (value instanceof Map) {
            Map masked = [:]
            ((Map) value).each { k, v ->
                String key = k?.toString() ?: ''
                masked[key] = isSensitiveFieldName(key) ? '***' : maskValue(v)
            }
            return masked
        }
        if (value instanceof Collection) return ((Collection) value).collect { maskValue(it) }
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value)
            List maskedList = []
            for (int i = 0; i < len; i++) maskedList.add(maskValue(java.lang.reflect.Array.get(value, i)))
            return maskedList
        }
        if (value instanceof CharSequence) {
            String text = value.toString()
            return text.size() <= 4 ? '***' : text.substring(0, Math.min(2, text.size())) + '***'
        }
        return value
    }

    static boolean isSensitiveFieldName(String fieldName) {
        String normalized = (fieldName ?: '').trim().toLowerCase()
        if (!normalized) return false
        return SENSITIVE_LOG_FIELD_NAMES.any { normalized.contains(it) }
    }

    static Map resolveServiceArtifact(Map document) {
        Map executableArtifact = resolveExecutableArtifact(document)
        if (executableArtifact && ((executableArtifact.executionChannel ?: '') == 'service' ||
                (executableArtifact.artifactTypeEnumId ?: '') == 'AT_SERVICE')) {
            if (!executableArtifact.authzActionEnumId && executableArtifact.artifactName) {
                executableArtifact = new LinkedHashMap(executableArtifact)
                executableArtifact.authzActionEnumId = getServiceAuthzActionEnumId(executableArtifact.artifactName as String)
            }
            return executableArtifact
        }

        return null
    }

    static String inferStateComparisonEntity(ExecutionContext ec, Map document, Collection fieldNames = null) {
        if (!ec || !(document instanceof Map)) return null

        List<String> candidateServices = []
        if (document.preferredService) candidateServices.add(document.preferredService as String)
        if (document.updateServiceName) candidateServices.add(document.updateServiceName as String)
        if (document.boundServices instanceof Collection) {
            candidateServices.addAll((document.boundServices as Collection).collect { it as String })
        }

        List<String> candidateFieldNames = (fieldNames instanceof Collection ? fieldNames : [])
            .collect { it as String }
            .findAll { it } as List<String>
        String fallbackEntityName = null
        String bestEntityName = null
        int bestFieldScore = -1

        for (String serviceName in candidateServices.findAll { it }) {
            int hashIdx = serviceName.indexOf('#')
            if (hashIdx < 0 || hashIdx >= serviceName.length() - 1) continue
            String entityName = serviceName.substring(hashIdx + 1)
            if (!entityName || !ec.entity.isEntityDefined(entityName)) continue
            if (!fallbackEntityName) fallbackEntityName = entityName
            if (!candidateFieldNames) continue

            def entityDef = ec.entity.getEntityDefinition(entityName)
            int fieldScore = candidateFieldNames.count { String fieldName -> entityDef.isField(fieldName) }
            if (fieldScore > bestFieldScore) {
                bestFieldScore = fieldScore
                bestEntityName = entityName
            }
            if (fieldScore == candidateFieldNames.size() && fieldScore > 0) return entityName
        }

        if (bestEntityName && bestFieldScore > 0) return bestEntityName
        return fallbackEntityName
    }

    static Map resolveScreenActionArtifact(Map document) {
        List transitionNames = (document?.transitionNames instanceof List) ? (List) document.transitionNames : []
        String transitionName = transitionNames ? (transitionNames.first() as String) : null
        String sourceWidgetPath = (document?.sourceWidgetPath ?: '') as String
        boolean looksLikeTransition = sourceWidgetPath.contains("/transition[") || transitionName
        if (!looksLikeTransition) return null

        String screenArtifactName = resolveScreenArtifactName(document)
        String sourceScreenPath = (document?.sourceScreenPath ?: '') as String
        if (!screenArtifactName && !sourceScreenPath) return null

        return [
            executionChannel : 'screen_transition',
            artifactTypeEnumId : transitionName ? 'AT_XML_SCREEN_TRANS' : 'AT_XML_SCREEN',
            artifactName : transitionName && screenArtifactName ? "${screenArtifactName}/${transitionName}" : (screenArtifactName ?: sourceScreenPath),
            parentArtifactName : screenArtifactName ?: sourceScreenPath,
            authzActionEnumId : 'AUTHZA_VIEW',
            transitionName : transitionName,
            sourceWidgetPath : sourceWidgetPath,
            preferredService : document?.preferredService
        ]
    }

    static String resolveScreenArtifactName(Map document) {
        List sourceArtifacts = (document?.sourceArtifacts instanceof List) ? (List) document.sourceArtifacts : []
        for (Object sourceArtifactObj in sourceArtifacts) {
            String sourceArtifact = null
            if (sourceArtifactObj instanceof Map) {
                sourceArtifact = (((Map) sourceArtifactObj).artifactName ?: ((Map) sourceArtifactObj).path ?: ((Map) sourceArtifactObj).location)?.toString()
            } else {
                sourceArtifact = sourceArtifactObj?.toString()
            }
            if (!sourceArtifact) continue
            if (sourceArtifact.startsWith('component://')) return sourceArtifact
            int runtimeComponentIdx = sourceArtifact.indexOf('/component/')
            if (runtimeComponentIdx >= 0) {
                String rel = sourceArtifact.substring(runtimeComponentIdx + '/component/'.length())
                return "component://${rel}"
            }
            int masterIdx = sourceArtifact.indexOf('/master/')
            if (masterIdx >= 0) {
                String rel = sourceArtifact.substring(masterIdx + '/master/'.length())
                if (rel.contains('/')) return "component://${rel}"
            }
        }
        return null
    }

    static boolean isScreenArtifactPermitted(ExecutionContext ec, Map executableArtifact) {
        if (!ec || !(executableArtifact instanceof Map)) return false
        String artifactTypeEnumId = (executableArtifact.artifactTypeEnumId ?: '') as String
        String artifactName = (executableArtifact.artifactName ?: '') as String
        if (artifactTypeEnumId && artifactName &&
                checkArtifactAccess(ec, artifactTypeEnumId, executableArtifact.authzActionEnumId as String ?: 'AUTHZA_VIEW', artifactName)) {
            return true
        }
        String parentArtifactName = (executableArtifact.parentArtifactName ?: '') as String
        return parentArtifactName ? checkArtifactAccess(ec, 'AT_XML_SCREEN', 'AUTHZA_VIEW', parentArtifactName) : false
    }

    static Map resolveExecutableArtifact(Map document) {
        Map screenActionArtifact = resolveScreenActionArtifact(document)
        if (screenActionArtifact) return screenActionArtifact

        if (document?.preferredService) {
            return [
                executionChannel : document.executionChannel ?: 'service',
                artifactTypeEnumId : 'AT_SERVICE',
                artifactName : document.preferredService,
                authzActionEnumId : document.authzActionEnumId ?: getServiceAuthzActionEnumId(document.preferredService as String)
            ]
        }

        List executableArtifacts = (document?.executableArtifacts instanceof List) ? (List) document.executableArtifacts : []
        return executableArtifacts ? new LinkedHashMap(executableArtifacts.first() as Map) : null
    }

    static Map normalizeSearchHit(Map hit) {
        Map source = (hit?._source instanceof Map) ? (Map) hit._source : [:]
        return [
            score : hit?._score,
            documentId : source.documentId ?: hit?._id,
            documentKind : source.documentKind,
            area : source.area,
            subArea : source.subArea,
            domainObject : source.domainObject,
            actionKind : source.actionKind,
            operationEffect : source.operationEffect,
            canonicalPrompt : source.canonicalPrompt,
            preferredService: source.preferredService,
            runtimeExecutable : source.runtimeExecutable,
            executionChannel : source.executionChannel,
            sourceScreenPath : source.sourceScreenPath,
            sourceDocument : source
        ]
    }

    static Map stripEmbedding(Map document) {
        if (!(document instanceof Map) || document.isEmpty()) return document
        Map sanitized = new LinkedHashMap(document)
        sanitized.remove('embedding')
        return sanitized
    }
}
