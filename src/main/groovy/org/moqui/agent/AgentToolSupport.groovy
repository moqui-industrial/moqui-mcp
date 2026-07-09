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
import java.util.Calendar

class AgentToolSupport {
    static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    static final String PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY = 'silverston_root_child_hierarchy'
    static final String PATTERN_SILVERSTON_SAME_SUBJECT_MULTI_ACTION = 'silverston_same_subject_multi_action'
    static final Set<String> AUTHZ_BYPASS_USER_IDS = ['EX_JOHN_DOE'] as Set<String>
    static final Set<String> AUTHZ_BYPASS_USERNAMES = ['john.doe'] as Set<String>
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
    static final Map<String, Map<String, Object>> DATA_DOCUMENT_LOOKUP_SPECS = [
            party : [
                    indexName : 'mantle',
                    documentType : 'MantleParty',
                    idField : 'partyId',
                    exactFields : ['partyId', 'pseudoId', 'organizationName', 'combinedName', 'username', 'userId', 'idValue'],
                    canonicalFields : ['partyId', 'pseudoId', 'combinedName', 'organizationName', 'username', 'userId', 'idValue'],
                    textFields : ['organizationName', 'combinedName', 'username', 'userFullName', 'emailAddress', 'idValue', 'partyId', 'pseudoId'],
                    fallbackEntityName : 'mantle.party.Party',
                    fallbackExactFields : ['partyId', 'pseudoId']
            ],
            person : [
                    indexName : 'mantle',
                    documentType : 'MantleParty',
                    idField : 'partyId',
                    exactFields : ['partyId', 'pseudoId', 'combinedName', 'firstName', 'lastName', 'userId', 'username', 'idValue'],
                    canonicalFields : ['partyId', 'pseudoId', 'combinedName', 'firstName', 'lastName', 'userId', 'username', 'idValue'],
                    textFields : ['combinedName', 'firstName', 'lastName', 'userFullName', 'emailAddress', 'username', 'userId'],
                    fallbackEntityName : 'mantle.party.Person',
                    fallbackExactFields : ['partyId']
            ],
            product : [
                    indexName : 'mantle',
                    documentType : 'MantleProduct',
                    idField : 'productId',
                    exactFields : ['productId', 'pseudoId', 'name', 'idValue'],
                    canonicalFields : ['productId', 'pseudoId', 'idValue'],
                    textFields : ['name', 'description', 'productId', 'pseudoId', 'idValue'],
                    fallbackEntityName : 'mantle.product.Product',
                    fallbackExactFields : ['productId', 'pseudoId']
            ],
            facility : [
                    indexName : 'mantle',
                    documentType : 'MantleFacility',
                    idField : 'facilityId',
                    exactFields : ['facilityId', 'pseudoId', 'name'],
                    canonicalFields : ['facilityId', 'pseudoId', 'name'],
                    textFields : ['name', 'description', 'facilityId', 'pseudoId', 'ownerName'],
                    fallbackEntityName : 'mantle.facility.Facility',
                    fallbackExactFields : ['facilityId', 'pseudoId', 'facilityName']
            ],
            asset : [
                    indexName : 'mantle_inventory',
                    documentType : 'MantleInventoryAsset',
                    idField : 'assetId',
                    exactFields : ['assetId', 'productId', 'facilityId', 'locationSeqId'],
                    canonicalFields : ['assetId', 'productId'],
                    textFields : ['assetId', 'productId', 'facilityId', 'locationSeqId'],
                    fallbackEntityName : 'mantle.product.asset.Asset',
                    fallbackExactFields : ['assetId']
            ],
            glAccount : [
                    indexName : 'mantle_accounting',
                    documentType : 'MantleGlAccount',
                    idField : 'glAccountId',
                    exactFields : ['glAccountId', 'accountCode', 'accountCodeCanonical', 'accountName'],
                    canonicalFields : ['glAccountId', 'accountCodeCanonical'],
                    textFields : ['accountName', 'accountCode', 'glAccountId', 'accountCodeCanonical'],
                    fallbackEntityName : 'mantle.ledger.account.GlAccount',
                    fallbackExactFields : ['glAccountId', 'accountCode']
            ],
            budget : [
                    indexName : 'mantle',
                    documentType : 'BudgetAndTimePeriod',
                    idField : 'budgetId',
                    exactFields : ['budgetId', 'description', 'partyId', 'timePeriodId', 'budgetTypeEnumId'],
                    canonicalFields : ['budgetId', 'description'],
                    textFields : ['description', 'budgetId', 'partyId', 'timePeriodId', 'budgetTypeEnumId'],
                    fallbackEntityName : 'mantle.other.budget.BudgetAndTimePeriod',
                    fallbackExactFields : ['budgetId', 'description']
            ],
            emplPositionClass : [
                    indexName : 'mantle',
                    documentType : 'EmplPositionClass',
                    idField : 'emplPositionClassId',
                    exactFields : ['emplPositionClassId', 'title', 'description'],
                    canonicalFields : ['emplPositionClassId', 'title'],
                    textFields : ['title', 'description', 'emplPositionClassId'],
                    fallbackEntityName : 'mantle.humanres.position.EmplPositionClass',
                    fallbackExactFields : ['emplPositionClassId', 'title']
            ],
            emplPosition : [
                    indexName : 'mantle',
                    documentType : 'EmplPosition',
                    idField : 'emplPositionId',
                    exactFields : ['emplPositionId', 'pseudoId', 'description', 'statusId'],
                    canonicalFields : ['emplPositionId', 'pseudoId', 'description'],
                    textFields : ['description', 'statusId', 'emplPositionClassId', 'organizationPartyId', 'budgetId', 'partyId'],
                    fallbackEntityName : 'mantle.humanres.position.EmplPosition',
                    fallbackExactFields : ['emplPositionId', 'pseudoId']
            ]
    ].asImmutable()

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
        if (isPrivilegedAgentUser(ec)) return true
        return ArtifactExecutionFacadeImpl.isPermitted("${artifactTypeEnumId}:${authzActionEnumId}:${artifactName}", ec)
    }

    static boolean isPrivilegedAgentUser(ExecutionContext ec) {
        if (!ec?.user) return false
        Set<String> userGroups = (ec.user.userGroupIdSet ?: []) as Set<String>
        if (!userGroups.intersect(['ADMIN', 'MCP_DEBUG'] as Set<String>).isEmpty()) return true

        String userId = (ec.user.userId ?: '') as String
        if (userId && AUTHZ_BYPASS_USER_IDS.contains(userId)) return true

        String username = ''
        try { username = (ec.user.username ?: '') as String } catch (Throwable ignored) { }
        return username && AUTHZ_BYPASS_USERNAMES.contains(username)
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

        if (fieldName == 'organizationPartyId') {
            String defaultOrgId = resolveDefaultOrganizationPartyId(ec)
            if (defaultOrgId) return defaultOrgId
        }

        return null
    }

    protected static String resolveDefaultOrganizationPartyId(ExecutionContext ec) {
        if (!ec) return null
        List<String> roleTypeIds = ['OrgEmployer', 'OrgInternal']
        for (String roleTypeId in roleTypeIds) {
            try {
                def partyRole = ec.entity.find('mantle.party.PartyRole').disableAuthz()
                        .condition('roleTypeId', roleTypeId)
                        .limit(1)
                        .one()
                if (partyRole?.partyId) return partyRole.partyId as String
            } catch (Throwable ignored) { }
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
        boolean createRequestPrompt =
            preferredService == 'mantle.request.RequestServices.create#Request' ||
                (actionKind == 'create' && domainObject == 'request') ||
                canonicalPrompt == 'create request'
        boolean createFacilityPrompt =
            preferredService == 'create#mantle.facility.Facility' ||
                (actionKind == 'create' && domainObject == 'facility') ||
                canonicalPrompt == 'create facility'
        boolean createOrderPrompt =
            preferredService == 'mantle.order.OrderServices.create#Order' ||
                (actionKind == 'create' && domainObject == 'order') ||
                canonicalPrompt == 'create order'

        if (createProjectPrompt) {
            String projectName = extractProjectName(text)
            if (projectName) inferred.workEffortName = projectName

            Long priority = extractNumericPriority(text)
            if (priority != null) inferred.priority = priority
        } else if (createRequestPrompt) {
            String requestName = extractRequestName(text)
            if (requestName) inferred.requestName = requestName

            String description = extractRequestDescription(text)
            if (description) inferred.description = description

            Long priority = extractNumericPriority(text)
            if (priority != null) inferred.priority = priority

            String requestTypeEnumId = inferRequestTypeEnumId(text)
            if (requestTypeEnumId) inferred.requestTypeEnumId = requestTypeEnumId

            String responseRequiredDate = extractResponseRequiredDateText(text)
            if (responseRequiredDate) inferred.responseRequiredDate = responseRequiredDate

        } else if (createFacilityPrompt) {
            String facilityName = extractFacilityName(text)
            if (facilityName) inferred.facilityName = facilityName

            String facilityTypeEnumId = inferFacilityTypeEnumId(text)
            if (facilityTypeEnumId) inferred.facilityTypeEnumId = facilityTypeEnumId
        } else if (createOrderPrompt) {
            Long priority = extractNumericPriority(text)
            if (priority != null) inferred.priority = priority
            String estimatedDeliveryDate = extractDateTextByCue(text, ['data consegna', 'delivery date'])
            if (estimatedDeliveryDate) inferred.estimatedDeliveryDate = estimatedDeliveryDate
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

    protected static Integer extractYearNumber(String text) {
        if (!text) return null
        List patterns = [
                /(?i)\b(?:anno|year|fiscal\s+year|esercizio)\s+(20\d{2})\b/,
                /(?i)\b(20\d{2})\b/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                try {
                    Integer year = Integer.valueOf(matcher.group(1))
                    if (year >= 2000 && year <= 2100) return year
                } catch (Throwable ignored) { }
            }
        }
        return null
    }

    static boolean looksLikeProjectAggregatePrompt(String queryText) {
        return looksLikeRootChildHierarchyPrompt(queryText)
    }

    static boolean looksLikeRootChildHierarchyPrompt(String queryText) {
        return looksLikeProjectHierarchyPrompt(queryText) ||
                looksLikeRequestHierarchyPrompt(queryText) ||
                looksLikeBudgetHierarchyPrompt(queryText) ||
                looksLikeFacilityHierarchyPrompt(queryText) ||
                looksLikeOrderHierarchyPrompt(queryText)
    }

    static boolean looksLikeProjectHierarchyPrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasProject = normalizedText.contains('project') || normalizedText.contains('progetto') || normalizedText.contains('commessa')
        boolean hasMilestone = normalizedText.contains('milestone')
        boolean hasTask = normalizedText.contains('task') || normalizedText.contains('tasks')
        return hasProject && hasMilestone && hasTask
    }

    static boolean looksLikeRequestHierarchyPrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasRequest = normalizedText.contains('request') || normalizedText.contains('richiest')
        boolean hasItem = normalizedText.contains('item') || normalizedText.contains('items') ||
                normalizedText.contains('riga') || normalizedText.contains('righe') ||
                normalizedText.contains('linea') || normalizedText.contains('linee')
        return hasRequest && hasItem
    }

    static boolean looksLikeBudgetHierarchyPrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasBudget = normalizedText.contains('budget')
        boolean hasItem = normalizedText.contains('item') || normalizedText.contains('items') ||
                normalizedText.contains('riga') || normalizedText.contains('righe') ||
                normalizedText.contains('linea') || normalizedText.contains('linee') ||
                normalizedText.contains('dettagl')
        return hasBudget && hasItem
    }

    static boolean looksLikeFacilityHierarchyPrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasFacility = normalizedText.contains('facility') || normalizedText.contains('magazzin') ||
                normalizedText.contains('warehouse') || normalizedText.contains('deposit')
        boolean hasHierarchy = normalizedText.contains('child') || normalizedText.contains('children') ||
                normalizedText.contains('figli') || normalizedText.contains('sotto') ||
                normalizedText.contains('sub') || normalizedText.contains('linea') || normalizedText.contains('linee')
        return hasFacility && hasHierarchy
    }

    static boolean looksLikeOrderHierarchyPrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasOrder = normalizedText.contains('order') || normalizedText.contains('ordine')
        boolean hasPartOrItems = normalizedText.contains('part') || normalizedText.contains('item') ||
                normalizedText.contains('items') || normalizedText.contains('prodott') ||
                normalizedText.contains('righe')
        return hasOrder && hasPartOrItems
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

    static boolean looksLikeSupportRequestCreatePrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasRequestConcept = normalizedText.contains('request') || normalizedText.contains('richiest') ||
                normalizedText.contains('support') || normalizedText.contains('ticket')
        boolean hasCreateIntent = normalizedText.contains('crea') || normalizedText.contains('create') ||
                normalizedText.contains('apri') || normalizedText.contains('open')
        return hasRequestConcept && hasCreateIntent
    }

    static boolean looksLikeAssetStatusQueryPrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasAsset = normalizedText.contains('asset ')
        boolean asksLocation = normalizedText.contains('dove si trova') || normalizedText.contains('where is') ||
                normalizedText.contains('locazione') || normalizedText.contains('location')
        boolean asksStatus = normalizedText.contains('stato') || normalizedText.contains('status')
        return hasAsset && (asksLocation || asksStatus)
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

    static Map executeDirectReadPromptPlan(ExecutionContext ec, String queryText, Map mergedParameters = null) {
        if (!looksLikeAssetStatusQueryPrompt(queryText)) return [:]

        Map parameters = inferAssetMoveStatusParameters(ec, queryText, mergedParameters)
        if (!parameters.assetId) {
            String normalizedText = normalizePromptWhitespace(queryText)
            def assetMatcher = (normalizedText =~ /(?i)\basset\s+([A-Z0-9_:-]+)\b/)
            if (assetMatcher.find()) parameters.assetId = resolveAssetIdByToken(ec, assetMatcher.group(1))
        }
        if (!parameters.assetId) {
            return [
                compositeExecution : true,
                compositeType : 'asset_status_query',
                success : false,
                messages : [],
                errors : ['Contesto mancante per asset_status_query: assetId'],
                executionResult : [success:false, operation:'asset_status_query', missingFields:['assetId']]
            ]
        }

        try {
            def asset = ec.entity.find('mantle.product.asset.Asset').disableAuthz()
                    .condition('assetId', parameters.assetId)
                    .one()
            if (!asset) {
                return [
                    compositeExecution : true,
                    compositeType : 'asset_status_query',
                    success : false,
                    messages : [],
                    errors : ["Asset non trovato: ${parameters.assetId}"],
                    executionResult : [success:false, operation:'asset_status_query', assetId:parameters.assetId, notFound:true]
                ]
            }

            String statusId = asset.statusId as String
            String facilityId = asset.facilityId as String
            String locationSeqId = asset.locationSeqId as String
            String facilityName = null
            String statusDescription = null
            try {
                if (facilityId) facilityName = ec.entity.find('mantle.facility.Facility').disableAuthz().condition('facilityId', facilityId).useCache(true).one()?.facilityName as String
                if (statusId) statusDescription = ec.entity.find('moqui.basic.StatusItem').disableAuthz().condition('statusId', statusId).useCache(true).one()?.description as String
            } catch (Throwable ignored) { }

            Map resultMap = [
                success : true,
                operation : 'asset_status_query',
                assetId : parameters.assetId,
                assetName : asset.assetName,
                facilityId : facilityId,
                facilityName : facilityName,
                locationSeqId : locationSeqId,
                statusId : statusId,
                statusDescription : statusDescription
            ]
            return [
                compositeExecution : true,
                compositeType : 'asset_status_query',
                success : true,
                messages : ["Asset ${parameters.assetId} is in ${facilityName ?: facilityId ?: 'unknown facility'} location ${locationSeqId ?: 'unknown'} with status ${statusDescription ?: statusId ?: 'unknown'}."],
                errors : [],
                executionResult : resultMap
            ]
        } catch (Throwable t) {
            return [
                compositeExecution : true,
                compositeType : 'asset_status_query',
                success : false,
                messages : [],
                errors : [t.message ?: t.toString()],
                executionResult : [success:false, operation:'asset_status_query', assetId:parameters.assetId]
            ]
        }
    }

    static Map inferStructuredPromptPlan(ExecutionContext ec, String queryText, Map mergedParameters = null) {
        if (looksLikeEmploymentPositionPrompt(queryText)) {
            Map employmentPositionPlan = inferEmploymentPositionPlan(ec, queryText, mergedParameters)
            if (employmentPositionPlan?.planType) return employmentPositionPlan
        }
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

    static Map inferEmploymentPositionPlan(ExecutionContext ec, String queryText, Map mergedParameters = null) {
        String normalizedText = normalizePromptWhitespace(queryText)
        if (!normalizedText || !looksLikeEmploymentPositionPrompt(normalizedText)) return [:]

        Map budgetContext = [:]
        String budgetDescription = extractBudgetDescription(normalizedText)
        if (budgetDescription) budgetContext = resolveBudgetContextByDescription(ec, budgetDescription)

        String personName = extractEmployeePersonName(normalizedText) ?: extractAssignedPersonName(normalizedText)
        String firstName = null
        String lastName = null
        if (personName) {
            List<String> nameParts = normalizePromptWhitespace(personName).split(/\s+/).findAll { it } as List<String>
            if (nameParts) {
                firstName = nameParts.first()
                lastName = nameParts.size() > 1 ? nameParts.last() : nameParts.first()
            }
        }

        Map initialContext = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (budgetContext?.budgetId) initialContext.budgetId = budgetContext.budgetId
        if (budgetContext?.organizationPartyId) initialContext.organizationPartyId = budgetContext.organizationPartyId
        if (budgetContext?.timePeriodId) initialContext.timePeriodId = budgetContext.timePeriodId
        if (budgetContext?.budgetItemSeqId) initialContext.budgetItemSeqId = budgetContext.budgetItemSeqId

        Map positionParams = new LinkedHashMap(initialContext)
        String positionDescription = extractPositionDescription(normalizedText) ?: extractFallbackPositionLabel(normalizedText)
        if (positionDescription) positionParams.description = positionDescription

        String positionClassDescription = extractPositionClassDescription(normalizedText)
        if (!positionClassDescription && normalizedText.contains('project manager')) positionClassDescription = 'Project Manager'
        if (positionClassDescription) {
            String positionClassId = resolveEmplPositionClassIdByToken(ec, positionClassDescription)
            if (positionClassId) positionParams.emplPositionClassId = positionClassId
        }

        String statusId = inferEmplPositionStatusId(normalizedText)
        if (statusId) positionParams.statusId = statusId
        if (normalizedText.contains('full time') || normalizedText.contains('full-time') || normalizedText.contains('tempo pieno')) positionParams.fullTimeFlag = 'Y'
        if (normalizedText.contains('straordin') || normalizedText.contains('overtime')) positionParams.overtimeFlag = 'Y'
        Integer standardHoursPerWeek = extractStandardHoursPerWeek(normalizedText)
        if (standardHoursPerWeek != null) positionParams.standardHoursPerWeek = standardHoursPerWeek
        if (budgetContext?.budgetId) positionParams.budgetId = budgetContext.budgetId
        if (budgetContext?.budgetItemSeqId) positionParams.budgetItemSeqId = budgetContext.budgetItemSeqId
        if (budgetContext?.organizationPartyId) positionParams.organizationPartyId = budgetContext.organizationPartyId
        positionParams = collectNonNullEntries(positionParams)
        if (!positionParams.description) return [:]

        List<Map> actions = []
        Map<String, Object> planContext = new LinkedHashMap(initialContext)
        String resolvedPersonId = personName ? resolvePartyIdByPersonName(ec, personName) : null
        if (resolvedPersonId) planContext.personPartyId = resolvedPersonId
        if (!resolvedPersonId && firstName && lastName) {
            actions.add([
                    actionName : 'createPerson',
                    serviceName : 'mantle.party.PartyServices.create#Person',
                    parameters : collectNonNullEntries([
                            firstName : firstName,
                            lastName : lastName,
                            roleTypeId : 'Employee'
                    ]),
                    contextUpdates : [
                            personPartyId : '{@context.partyId}'
                    ]
            ])
        }

        actions.add([
                actionName : 'createPosition',
                serviceName : 'create#mantle.humanres.position.EmplPosition',
                parameters : positionParams,
                contextUpdates : [
                        emplPositionId : '{@context.emplPositionId}'
                ]
        ])

        String employerPartyId = (budgetContext?.organizationPartyId ?: positionParams.organizationPartyId ?: resolveDefaultPartyContextValue(ec, initialContext, 'organizationPartyId')) as String
        if (employerPartyId) {
            if (!positionParams.organizationPartyId) positionParams.organizationPartyId = employerPartyId
            if (!initialContext.organizationPartyId) initialContext.organizationPartyId = employerPartyId
        }
        actions.add([
                actionName : 'createEmployment',
                serviceName : 'mantle.humanres.EmploymentServices.create#Employment',
                parameters : collectNonNullEntries([
                        fromPartyId : resolvedPersonId ?: '{@context.personPartyId}',
                        toPartyId : employerPartyId ?: '{@context.organizationPartyId}',
                        emplPositionId : '{@context.emplPositionId}',
                        fromDate : ec?.user?.nowTimestamp
                ])
        ])

        if (!actions) return [:]
        return [
                planType : 'same_subject_multi_action',
                compositeType : 'employment_position_assignment',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                operation : 'employment_position_assignment',
                subject : [
                        subjectType : 'EmplPosition',
                        subjectId : positionParams.description,
                        subjectIdField : 'description'
                ],
                initialContext : planContext,
                sessionBusinessObjects : collectNonNullEntries([
                        budgetId : '{@context.budgetId}',
                        organizationPartyId : '{@context.organizationPartyId}',
                        personPartyId : '{@context.personPartyId}',
                        emplPositionId : '{@context.emplPositionId}'
                ]),
                primaryService : 'create#mantle.humanres.position.EmplPosition',
                actions : actions
        ]
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
                    ],
                    [
                        planType : 'same_subject_multi_action',
                        subjectEntity : 'EmplPosition',
                        verbs : ['create', 'assign', 'hire']
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

    protected static String simpleEntityName(String entityName) {
        if (!entityName) return null
        String trimmed = entityName.toString().trim()
        if (!trimmed) return null
        int lastDot = trimmed.lastIndexOf('.')
        return lastDot >= 0 ? trimmed.substring(lastDot + 1) : trimmed
    }

    protected static String normalizeAggregateTypeFromPattern(String patternType, String rootEntityName, String patternName = null) {
        String normalized = (patternType ?: '').toString().trim().toLowerCase()
        if (normalized) return normalized
        String lowerPatternName = (patternName ?: '').toString().toLowerCase()
        if (lowerPatternName.contains('order')) return 'header_part_item'
        if (lowerPatternName.contains('budget')) return 'root_seq_child'
        if (lowerPatternName.contains('request')) return 'root_seq_child'
        if (lowerPatternName.contains('facility')) return 'parent_child_tree'
        if (lowerPatternName.contains('party')) return 'party_specialization'
        if (lowerPatternName.contains('workeffort')) return 'root_parent_tree'
        return (simpleEntityName(rootEntityName) ?: 'aggregate').toLowerCase()
    }

    protected static List<Map> loadAggregatePatternRegistry(ExecutionContext ec) {
        if (!ec?.entity) return []
        try {
            def patternList = ec.entity.find('moqui.agent.AgentAggregatePattern').disableAuthz()
                    .condition('active', 'Y')
                    .orderBy('patternName')
                    .list()
            List<Map> registry = []
            patternList.each { patternEv ->
                String patternId = patternEv.getString('agentAggregatePatternId')
                List<Map> members = []
                try {
                    members = ec.entity.find('moqui.agent.AgentAggregatePatternMember').disableAuthz()
                            .condition('agentAggregatePatternId', patternId)
                            .orderBy('patternMemberSeqId')
                            .list()
                            .collect { it.getMap() }
                } catch (Throwable ignored) { }
                Map rootMember = members.find { ((it.memberRoleType ?: '') as String).equalsIgnoreCase('root') } ?: [:]
                List<Map> childMembers = members.findAll { !(((it.memberRoleType ?: '') as String).equalsIgnoreCase('root')) }
                registry.add(collectNonNullEntries([
                        aggregatePatternId : patternId,
                        patternName : patternEv.getString('patternName'),
                        patternType : patternEv.getString('patternType'),
                        silverstonChapterNumber : patternEv.get('silverstonChapterNumber'),
                        silverstonPatternKind : patternEv.getString('silverstonPatternKind'),
                        rootEntityName : patternEv.getString('rootEntityName'),
                        rootEntitySimpleName : simpleEntityName(patternEv.getString('rootEntityName')),
                        rootPkFieldNames : patternEv.getString('rootPkFieldNames'),
                        rootReferenceFieldName : patternEv.getString('rootReferenceFieldName'),
                        parentReferenceFieldName : patternEv.getString('parentReferenceFieldName'),
                        sequenceFieldNames : patternEv.getString('sequenceFieldNames'),
                        description : patternEv.getString('description'),
                        usageNotes : patternEv.getString('usageNotes'),
                        memberCount : members.size(),
                        rootMemberName : rootMember.memberName,
                        childMemberNames : childMembers.collect { it.memberName }.findAll { it },
                        childEntityNames : childMembers.collect { it.entityName }.findAll { it },
                        memberNames : members.collect { it.memberName }.findAll { it },
                        memberEntityNames : members.collect { it.entityName }.findAll { it },
                        supportedAggregateType : normalizeAggregateTypeFromPattern(patternEv.getString('patternType'), patternEv.getString('rootEntityName'), patternEv.getString('patternName'))
                ]))
            }
            return registry
        } catch (Throwable t) {
            ec.logger.warn("Unable to load aggregate pattern registry: ${t.message}")
            return []
        }
    }

    protected static List<Map> buildSupportedAggregatePatternHints(ExecutionContext ec) {
        List<Map> supportedPatterns = [
                [planType : 'same_subject_multi_action', subjectEntity : 'Asset', verbs : ['move', 'update_status']]
        ]
        loadAggregatePatternRegistry(ec).each { Map pattern ->
            List<String> childNames = (pattern.childEntityNames ?: []) as List<String>
            if (!childNames) childNames = ((pattern.memberNames ?: []) as List<String>).findAll { it && it != pattern.rootMemberName }
            supportedPatterns.add(collectNonNullEntries([
                    planType : 'aggregate_create_tree',
                    subjectEntity : pattern.rootEntitySimpleName ?: pattern.rootEntityName,
                    aggregateType : pattern.supportedAggregateType ?: pattern.patternType,
                    children : childNames.collect { simpleEntityName(it) ?: it }.findAll { it },
                    patternId : pattern.aggregatePatternId,
                    patternName : pattern.patternName,
                    rootEntityName : pattern.rootEntityName,
                    memberEntityNames : pattern.memberEntityNames,
                    memberNames : pattern.memberNames,
                    silverstonChapterNumber : pattern.silverstonChapterNumber,
                    silverstonPatternKind : pattern.silverstonPatternKind
            ]))
        }
        if (supportedPatterns.size() == 1) {
            supportedPatterns.addAll([
                    [planType : 'aggregate_create_tree', subjectEntity : 'Project', aggregateType : 'root_parent_tree', children : ['Milestone', 'Task']],
                    [planType : 'aggregate_create_tree', subjectEntity : 'Request', aggregateType : 'root_seq_child', children : ['RequestItem']],
                    [planType : 'aggregate_create_tree', subjectEntity : 'OrderHeader', aggregateType : 'header_part_item', children : ['OrderPart', 'OrderItem']],
                    [planType : 'aggregate_create_tree', subjectEntity : 'Budget', aggregateType : 'root_seq_child', children : ['BudgetItem', 'BudgetItemDetail']],
                    [planType : 'aggregate_create_tree', subjectEntity : 'Facility', aggregateType : 'parent_child_tree', children : ['Facility']],
                    [planType : 'aggregate_create_tree', subjectEntity : 'Party', aggregateType : 'party_specialization', children : ['Person', 'Organization']]
            ])
        }
        return supportedPatterns
    }

    protected static Map detectUnsupportedMultiDomainWorkflow(String queryText) {
        if (!queryText) return [:]
        String normalized = normalizePromptWhitespace(queryText).toLowerCase()
        if (!normalized) return [:]

        if (looksLikeEmploymentPositionPrompt(queryText) || looksLikeAssetMoveStatusPrompt(queryText)) return [:]
        if (looksLikeRootChildHierarchyPrompt(queryText)) return [:]

        List<Map> domainFamilies = [
                [family: 'project', tokens: ['project', 'progetto', 'commessa']],
                [family: 'request', tokens: ['request', 'richiest', 'support', 'ticket']],
                [family: 'budget', tokens: ['budget']],
                [family: 'order', tokens: ['order', 'ordine']],
                [family: 'facility', tokens: ['facility', 'warehouse', 'magazzin', 'deposit']],
                [family: 'asset', tokens: ['asset']],
                [family: 'hr', tokens: ['emplposition', 'employment', 'impiegat', 'dipendent', 'personale']],
                [family: 'party', tokens: ['party', 'person', 'organization', 'persona', 'azienda']]
        ]

        List<String> matchedFamilies = []
        domainFamilies.each { Map family ->
            List<String> tokens = (family.tokens ?: []) as List<String>
            boolean matched = tokens.any { String token -> token && normalized.contains(token) }
            if (matched) matchedFamilies.add(family.family as String)
        }
        matchedFamilies = matchedFamilies.unique()

        if (matchedFamilies.size() <= 1) return [:]

        Set<String> supportedPatterns = [] as Set<String>
        if (matchedFamilies.contains('project') && normalized.contains('milestone') && normalized.contains('task')) supportedPatterns.add('project')
        if (matchedFamilies.contains('request') && normalized.contains('item')) supportedPatterns.add('request')
        if (matchedFamilies.contains('budget') && (normalized.contains('item') || normalized.contains('detail'))) supportedPatterns.add('budget')
        if (matchedFamilies.contains('order') && normalized.contains('part') && normalized.contains('item')) supportedPatterns.add('order')
        if (matchedFamilies.contains('facility') && normalized.contains('child')) supportedPatterns.add('facility')
        if (matchedFamilies.contains('party') && (normalized.contains('person') || normalized.contains('organization'))) supportedPatterns.add('party')
        if (supportedPatterns || looksLikeEmploymentPositionPrompt(queryText)) return [:]

        String message = "La richiesta combina più domini o gerarchie non modellate come un unico pattern supportato (${matchedFamilies.join(', ')}). " +
                "Stai uscendo dalla gerarchia di una root entity e delle sue child entities e stai chiedendo un workflow multi-dominio. " +
                "Spezzala in richieste atomiche o usa un workflow esplicitamente supportato."
        return [
                blocked : true,
                message : message,
                matchedFamilies : matchedFamilies
        ]
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
- For request/item requests, use planType "aggregate_create_tree" and fill aggregate.items with child descriptions and quantities when present.
- For budget/item requests, use planType "aggregate_create_tree" and fill aggregate.items with child purposes, amounts, and optional glAccountId when present.
- For facility hierarchy requests, use planType "aggregate_create_tree" and fill aggregate.children with child facility names/types when present.
- For order requests, use planType "aggregate_create_tree" and fill aggregate.parts and each part's items when present. Item complements should prefer productId or pseudoId plus quantity.
- For HR position requests, use planType "same_subject_multi_action" and model the sequence create person if needed, create position, create employment/assignment. Prefer EmplPosition, Party, and Employment as the business entities.
- If the request spans multiple domains or multiple root entities and is not one of the supported aggregate patterns, return "planType":"unknown". Do not invent a workflow across unrelated domains.
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
        return selectPreferredHierarchyRootDocument(queryText, candidates)
    }

    static Map selectPreferredHierarchyRootDocument(String queryText, List candidates) {
        if (!looksLikeRootChildHierarchyPrompt(queryText) || !(candidates instanceof List) || candidates.isEmpty()) return null
        if (looksLikeRequestHierarchyPrompt(queryText)) {
            Map requestCandidate = (candidates as List<Map>).find { Map candidate -> isRequestHierarchyRootDocument(candidate) } as Map
            if (requestCandidate) return requestCandidate
        }
        if (looksLikeBudgetHierarchyPrompt(queryText)) {
            Map budgetCandidate = (candidates as List<Map>).find { Map candidate -> isBudgetHierarchyRootDocument(candidate) } as Map
            if (budgetCandidate) return budgetCandidate
        }
        if (looksLikeProjectHierarchyPrompt(queryText)) {
            Map projectCandidate = (candidates as List<Map>).find { Map candidate -> isProjectHierarchyRootDocument(candidate) } as Map
            if (projectCandidate) return projectCandidate
        }
        if (looksLikeFacilityHierarchyPrompt(queryText)) {
            Map facilityCandidate = (candidates as List<Map>).find { Map candidate -> isFacilityHierarchyRootDocument(candidate) } as Map
            if (facilityCandidate) return facilityCandidate
        }
        if (looksLikeOrderHierarchyPrompt(queryText)) {
            Map orderCandidate = (candidates as List<Map>).find { Map candidate -> isOrderHierarchyRootDocument(candidate) } as Map
            if (orderCandidate) return orderCandidate
        }
        return (candidates as List<Map>).find { Map candidate ->
            isHierarchyRootDocument(candidate)
        } as Map
    }

    static boolean isProjectAggregateRootDocument(Map document) {
        return isHierarchyRootDocument(document)
    }

    static boolean isHierarchyRootDocument(Map document) {
        return isProjectHierarchyRootDocument(document) ||
                isRequestHierarchyRootDocument(document) ||
                isBudgetHierarchyRootDocument(document) ||
                isFacilityHierarchyRootDocument(document) ||
                isOrderHierarchyRootDocument(document)
    }

    static boolean isProjectHierarchyRootDocument(Map document) {
        if (!(document instanceof Map)) return false
        String preferredService = (document.preferredService ?: '') as String
        String canonicalPrompt = ((document.canonicalPrompt ?: '') as String).toLowerCase()
        String domainObject = ((document.domainObject ?: '') as String).toLowerCase()
        String actionKind = ((document.actionKind ?: '') as String).toLowerCase()
        return Boolean.TRUE.equals(document.runtimeExecutable) && (
                preferredService == 'mantle.work.ProjectServices.create#Project' ||
                canonicalPrompt == 'create project' ||
                (actionKind == 'create' && domainObject == 'project'))
    }

    static boolean isRequestHierarchyRootDocument(Map document) {
        if (!(document instanceof Map)) return false
        String preferredService = (document.preferredService ?: '') as String
        String canonicalPrompt = ((document.canonicalPrompt ?: '') as String).toLowerCase()
        String domainObject = ((document.domainObject ?: '') as String).toLowerCase()
        String actionKind = ((document.actionKind ?: '') as String).toLowerCase()
        return Boolean.TRUE.equals(document.runtimeExecutable) && (
                preferredService == 'mantle.request.RequestServices.create#Request' ||
                canonicalPrompt == 'create request' ||
                (actionKind == 'create' && domainObject == 'request'))
    }

    static boolean isBudgetHierarchyRootDocument(Map document) {
        if (!(document instanceof Map)) return false
        String preferredService = (document.preferredService ?: '') as String
        String canonicalPrompt = ((document.canonicalPrompt ?: '') as String).toLowerCase()
        String domainObject = ((document.domainObject ?: '') as String).toLowerCase()
        String actionKind = ((document.actionKind ?: '') as String).toLowerCase()
        return Boolean.TRUE.equals(document.runtimeExecutable) && (
                preferredService == 'mantle.other.BudgetServices.create#Budget' ||
                canonicalPrompt == 'create budget' ||
                (actionKind == 'create' && domainObject == 'budget'))
    }

    static boolean isFacilityHierarchyRootDocument(Map document) {
        if (!(document instanceof Map)) return false
        String preferredService = (document.preferredService ?: '') as String
        String canonicalPrompt = ((document?.canonicalPrompt ?: '') as String).toLowerCase()
        String domainObject = ((document?.domainObject ?: '') as String).toLowerCase()
        String actionKind = ((document?.actionKind ?: '') as String).toLowerCase()
        return Boolean.TRUE.equals(document.runtimeExecutable) && (
                preferredService == 'create#mantle.facility.Facility' ||
                canonicalPrompt == 'create facility' ||
                (actionKind == 'create' && domainObject == 'facility'))
    }

    static boolean isOrderHierarchyRootDocument(Map document) {
        if (!(document instanceof Map)) return false
        String preferredService = (document.preferredService ?: '') as String
        String canonicalPrompt = ((document?.canonicalPrompt ?: '') as String).toLowerCase()
        String domainObject = ((document?.domainObject ?: '') as String).toLowerCase()
        String actionKind = ((document?.actionKind ?: '') as String).toLowerCase()
        return Boolean.TRUE.equals(document.runtimeExecutable) && (
                preferredService == 'mantle.order.OrderServices.create#Order' ||
                canonicalPrompt == 'create order' ||
                (actionKind == 'create' && domainObject == 'order'))
    }

    static Map inferProjectAggregatePlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        return inferRootChildHierarchyPlan(ec, queryText, document, mergedParameters)
    }

    static Map inferRootChildHierarchyPlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        Map inferredParameters = (mergedParameters instanceof Map) ? new LinkedHashMap(mergedParameters as Map) : [:]
        if (inferredParameters.isEmpty()) inferredParameters.putAll(inferPromptParameters(queryText, document))

        String aggregateKind = detectRootChildHierarchyAggregateKind(queryText, document)
        if (!aggregateKind) return [:]

        Map llmHierarchyPlan = inferLlmRootChildHierarchyPlan(ec, queryText, document, inferredParameters)
        if (llmHierarchyPlan?.rootNode) return llmHierarchyPlan

        if (aggregateKind == 'request') return inferRequestRootSeqPlan(ec, queryText, document, inferredParameters)
        if (aggregateKind == 'budget') return inferBudgetRootSeqPlan(ec, queryText, document, inferredParameters)
        if (aggregateKind == 'facility') return inferFacilityParentChildPlan(ec, queryText, document, inferredParameters)
        if (aggregateKind == 'order') return inferOrderHeaderPartItemPlan(ec, queryText, document, inferredParameters)

        String normalizedText = normalizePromptWhitespace(queryText)
        if (!inferredParameters.workEffortName) {
            String projectName = extractProjectName(normalizedText)
            if (projectName) inferredParameters.workEffortName = projectName
        }
        if (inferredParameters.priority == null) {
            Long priority = extractNumericPriority(normalizedText)
            if (priority != null) inferredParameters.priority = priority
        }
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

        plan.rootNode = buildRootChildHierarchyRootNode(plan)

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
            if (plan.aggregateType == 'root_parent_tree') {
                result.messages.add("Dry run: aggregate plan prepared with ${plan.milestones.size()} milestones and ${plan.taskCount ?: 0} tasks.")
            } else if (plan.aggregateType == 'root_seq_child') {
                result.messages.add("Dry run: aggregate plan prepared with ${plan.childCount ?: 0} child items.")
            } else {
                result.messages.add("Dry run: aggregate plan prepared for ${plan.aggregateType ?: 'hierarchy'} with ${plan.childCount ?: 0} child nodes.")
            }
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
        return inferLlmRootChildHierarchyPlan(ec, queryText, document, mergedParameters)
    }

    protected static Map inferLlmRootChildHierarchyPlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        if (!ec || !queryText) return [:]
        try {
            Map modelOptions = buildPromptPlannerModelOptions()
            if (!modelOptions.provider || modelOptions.provider == 'none') return [:]

            AgentModelFacade modelFacade = new AgentModelFacade(ec)
            Map plannerResponse = modelFacade.generateJson(buildPromptPlannerSystemPrompt(), [
                    queryText : queryText,
                    providedParameters : collectNonNullEntries((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:]),
                    supportedPatterns : buildSupportedAggregatePatternHints(ec)
            ], modelOptions)
            String outputText = (plannerResponse?.outputText ?: '') as String
            if (!outputText) return [:]
            Map decomposition = parseModelJsonMap(outputText)
            if (!decomposition) return [:]
            Map translated = translateLlmDecompositionToRootChildHierarchyPlan(ec, document, mergedParameters, decomposition)
            if (translated?.rootNode) {
                translated.planningSource = 'llm'
                translated.rawDecomposition = decomposition
                return translated
            }
        } catch (Throwable t) {
            ec.logger.warn("LLM hierarchy planner fallback to deterministic mode for [${abbreviate(queryText, 240)}]: ${t.message}")
        }
        return [:]
    }

    protected static Map translateLlmDecompositionToProjectAggregatePlan(ExecutionContext ec, Map document, Map mergedParameters, Map decomposition) {
        return translateLlmDecompositionToRootChildHierarchyPlan(ec, document, mergedParameters, decomposition)
    }

    protected static Map translateLlmDecompositionToRootChildHierarchyPlan(ExecutionContext ec, Map document, Map mergedParameters, Map decomposition) {
        if (!(decomposition instanceof Map)) return [:]
        String planType = (decomposition.planType ?: '') as String
        Map subject = (decomposition.subject instanceof Map) ? (decomposition.subject as Map) : [:]
        String subjectEntity = ((subject.entityName ?: subject.entity ?: '') as String).trim()
        Map aggregate = (decomposition.aggregate instanceof Map) ? (decomposition.aggregate as Map) : [:]
        if (!(subjectEntity.equalsIgnoreCase('Project') || subjectEntity.equalsIgnoreCase('Request') || planType == 'aggregate_create_tree')) return [:]
        if (!aggregate && !(decomposition.actions instanceof List)) return [:]

        if (subjectEntity.equalsIgnoreCase('Request')) {
            return translateLlmDecompositionToRequestHierarchyPlan(ec, document, mergedParameters, subject, aggregate)
        }
        if (subjectEntity.equalsIgnoreCase('Budget')) {
            return translateLlmDecompositionToBudgetHierarchyPlan(ec, document, mergedParameters, subject, aggregate)
        }
        if (subjectEntity.equalsIgnoreCase('Facility')) {
            return translateLlmDecompositionToFacilityHierarchyPlan(ec, document, mergedParameters, subject, aggregate)
        }
        if (subjectEntity.equalsIgnoreCase('OrderHeader') || subjectEntity.equalsIgnoreCase('Order')) {
            return translateLlmDecompositionToOrderHierarchyPlan(ec, document, mergedParameters, subject, aggregate)
        }

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

        plan.rootNode = buildRootChildHierarchyRootNode(plan)
        return plan
    }

    protected static Map buildProjectAggregateRootNode(Map plan) {
        return buildRootChildHierarchyRootNode(plan)
    }

    protected static Map buildRootChildHierarchyRootNode(Map plan) {
        if (plan.aggregateType == 'root_seq_child' && plan.request instanceof Map) {
            return buildRequestRootSeqHierarchyNode(plan)
        }
        if (plan.aggregateType == 'root_seq_child' && plan.budget instanceof Map) {
            return buildBudgetRootSeqHierarchyNode(plan)
        }
        if (plan.aggregateType == 'parent_child_tree' && plan.facility instanceof Map) {
            return buildFacilityParentChildHierarchyNode(plan)
        }
        if (plan.aggregateType == 'header_part_item' && plan.order instanceof Map) {
            return buildOrderHeaderPartItemHierarchyNode(plan)
        }
        List<Map> milestoneNodes = []
        int milestoneIndex = 0
        (plan.milestones as List<Map>).each { Map milestone ->
            milestoneIndex++
            String milestoneId = buildMilestoneId((plan.project?.workEffortId ?: '{@root.workEffortId}') as String, milestoneIndex)
            List<Map> taskNodes = ((milestone.tasks ?: []) as List<Map>).collect { Map task ->
                [
                        nodeType : 'task',
                        documentId : 'agent-prompt://task/tasksummary/createtask',
                        serviceName : 'mantle.work.TaskServices.create#Task',
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
                    serviceName : 'mantle.work.ProjectServices.create#Milestone',
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
                serviceName : 'mantle.work.ProjectServices.create#Project',
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
        if (rootNode?.nodeType == 'request') {
            int requestItemCount = executionLog.count { Map entry -> entry.nodeType == 'request_item' }
            return [
                    success : true,
                    messages : (rootResult.messages ?: []) + ["Created aggregate root ${rootContext[rootNode.resultIdField ?: 'requestId']} with ${requestItemCount} request items."],
                    errors : [],
                    executionLog : executionLog,
                    nodeCount : nodeCount,
                    executionResult : [
                            success : true,
                            operation : 'composite_aggregate_create',
                            aggregateType : 'root_seq_child',
                            rootId : rootContext[rootNode.resultIdField ?: 'requestId'],
                            requestItemCount : requestItemCount
                    ],
                    rootNodeContext : rootContext
            ]
        }
        if (rootNode?.nodeType == 'budget') {
            Map<String, Integer> persistedBudgetCounts = getPersistedBudgetCounts(ec, rootContext[rootNode.resultIdField ?: 'budgetId'] as String)
            int plannedBudgetItemCount = executionLog.count { Map entry -> entry.nodeType == 'budget_item' }
            int plannedBudgetItemDetailCount = executionLog.count { Map entry -> entry.nodeType == 'budget_item_detail' }
            int budgetItemCount = persistedBudgetCounts.budgetItemCount ?: 0
            int budgetItemDetailCount = persistedBudgetCounts.budgetItemDetailCount ?: 0
            if (plannedBudgetItemCount > 0 && budgetItemCount < plannedBudgetItemCount) {
                return [
                        success : false,
                        messages : (rootResult.messages ?: []) + [
                                "Budget ${rootContext[rootNode.resultIdField ?: 'budgetId']} was created but only ${budgetItemCount} of ${plannedBudgetItemCount} planned budget items were persisted."
                        ],
                        errors : ["Budget items did not persist as expected."],
                        executionLog : executionLog,
                        nodeCount : nodeCount,
                        executionResult : [
                                success : false,
                                operation : 'composite_aggregate_create',
                                aggregateType : 'root_seq_child',
                                rootId : rootContext[rootNode.resultIdField ?: 'budgetId'],
                                budgetItemCount : budgetItemCount,
                                budgetItemDetailCount : budgetItemDetailCount,
                                expectedBudgetItemCount : plannedBudgetItemCount,
                                expectedBudgetItemDetailCount : plannedBudgetItemDetailCount
                        ],
                        rootNodeContext : rootContext
                ]
            }
            return [
                    success : true,
                    messages : (rootResult.messages ?: []) + ["Created budget ${rootContext[rootNode.resultIdField ?: 'budgetId']} with ${budgetItemCount} budget items and ${budgetItemDetailCount} budget item details."],
                    errors : [],
                    executionLog : executionLog,
                    nodeCount : nodeCount,
                    executionResult : [
                            success : true,
                            operation : 'composite_aggregate_create',
                            aggregateType : 'root_seq_child',
                            rootId : rootContext[rootNode.resultIdField ?: 'budgetId'],
                            budgetItemCount : budgetItemCount,
                            budgetItemDetailCount : budgetItemDetailCount
                    ],
                    rootNodeContext : rootContext
            ]
        }
        if (rootNode?.nodeType == 'facility') {
            int childFacilityCount = executionLog.count { Map entry -> entry.nodeType == 'facility_child' }
            return [
                    success : true,
                    messages : (rootResult.messages ?: []) + ["Created facility hierarchy root ${rootContext[rootNode.resultIdField ?: 'facilityId']} with ${childFacilityCount} child facilities."],
                    errors : [],
                    executionLog : executionLog,
                    nodeCount : nodeCount,
                    executionResult : [
                            success : true,
                            operation : 'composite_aggregate_create',
                            aggregateType : 'parent_child_tree',
                            rootId : rootContext[rootNode.resultIdField ?: 'facilityId'],
                            childFacilityCount : childFacilityCount
                    ],
                    rootNodeContext : rootContext
            ]
        }
        if (rootNode?.nodeType == 'order_header') {
            Map placeResult = placeAggregateOrderIfNeeded(ec, rootContext, confirmed, sessionId)
            if (!Boolean.TRUE.equals(placeResult.success)) {
                return [
                        success : false,
                        messages : (rootResult.messages ?: []) + ((placeResult.messages ?: []) as List),
                        errors : (placeResult.errors ?: []) as List,
                        executionLog : executionLog,
                        nodeCount : nodeCount,
                        executionResult : placeResult.executionResult,
                        rootNodeContext : rootContext
                ]
            }
            Map<String, Integer> persistedOrderCounts = getPersistedOrderCounts(ec, rootContext[rootNode.resultIdField ?: 'orderId'] as String)
            int orderPartCount = persistedOrderCounts.orderPartCount ?: (executionLog.count { Map entry -> entry.nodeType == 'order_part' } ?: 0)
            int orderItemCount = persistedOrderCounts.orderItemCount ?: (executionLog.count { Map entry -> entry.nodeType == 'order_item' } ?: 0)
            return [
                    success : true,
                    messages : (rootResult.messages ?: []) +
                            ((placeResult.messages ?: []) as List) +
                            ["Created order ${rootContext[rootNode.resultIdField ?: 'orderId']} with ${orderPartCount} parts and ${orderItemCount} items."],
                    errors : [],
                    executionLog : executionLog,
                    nodeCount : nodeCount,
                    executionResult : [
                            success : true,
                            operation : 'composite_aggregate_create',
                            aggregateType : 'header_part_item',
                            rootId : rootContext[rootNode.resultIdField ?: 'orderId'],
                            orderPartCount : orderPartCount,
                            orderItemCount : orderItemCount,
                            placed : Boolean.TRUE.equals(placeResult.placed)
                    ],
                    rootNodeContext : rootContext
            ]
        }
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
        Map execResult
        if (node.serviceName) {
            Map guardedResult = ec.service.sync().name('org.moqui.agent.AgentRuntimeServices.call#ServiceGuarded')
                    .parameters([
                            serviceName : node.serviceName,
                            parameters : nodeParameters,
                            confirmed : confirmed,
                            toolName : 'moqui_execute_agent_prompt',
                            sessionId : sessionId,
                            directToolCall : false
                    ]).call()
            Map serviceResult = (guardedResult?.serviceResult instanceof Map) ? (guardedResult.serviceResult as Map) : [:]
            execResult = [
                    success : !Boolean.TRUE.equals(guardedResult?.confirmationRequired),
                    serviceResult : serviceResult,
                    messages : serviceResult?.message ? [serviceResult.message as String] : [],
                    errors : []
            ]
        } else {
            Map executeParameters = [
                    documentId : node.documentId,
                    parameters : nodeParameters,
                    confirmed : confirmed,
                    dryRun : false,
                    // Aggregate execution already propagates context explicitly through root/parent maps.
                    // Re-reading AgentSessionContext here leaks the previous node's workEffortId into child creates.
                    useSessionContext : false,
                    sessionId : sessionId
            ]
            if (node.indexName) executeParameters.indexName = node.indexName
            execResult = ec.service.sync().name('org.moqui.agent.AgentExecutionServices.execute#AgentPrompt')
                    .parameters(executeParameters).call()
        }

        List messages = []
        if (execResult?.messages) messages.addAll(execResult.messages as List)
        List errors = []
        if (execResult?.errors) errors.addAll(execResult.errors as List)

        String resultIdField = (node.resultIdField ?: inferResultIdField((node.documentId ?: node.serviceName) as String)) as String
        String createdId = extractCreatedId(execResult, resultIdField)
        Map nodeContext = collectNonNullEntries(new LinkedHashMap(nodeParameters + [(resultIdField): createdId]))

        executionLog.add([
                nodeType : node.nodeType,
                documentId : node.documentId,
                serviceName : node.serviceName,
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
        String idField = (node.resultIdField ?: inferResultIdField((node.documentId ?: node.serviceName) as String)) as String
        if (idField && nodeContext?.get(idField) != null) serviceParameters[idField] = nodeContext[idField]
        if (node.postCreateParameters instanceof Map) serviceParameters.putAll(node.postCreateParameters as Map)
        serviceParameters = collectNonNullEntries(serviceParameters)
        if (serviceParameters) ec.service.sync().name(postCreateServiceName).parameters(serviceParameters).call()
    }

    protected static Map placeAggregateOrderIfNeeded(ExecutionContext ec, Map rootContext, Boolean confirmed, String sessionId) {
        String orderId = rootContext?.orderId as String
        if (!orderId) return [success:true, placed:false, messages:[], errors:[]]
        try {
            def orderHeader = ec.entity.find('mantle.order.OrderHeader').disableAuthz()
                    .condition('orderId', orderId)
                    .one()
            String statusId = orderHeader?.statusId as String
            if (statusId && statusId != 'OrderOpen') {
                return [success:true, placed:false, messages:[], errors:[], executionResult:[success:true, orderId:orderId, statusId:statusId]]
            }
        } catch (Throwable ignored) { }

        Map guardedResult = ec.service.sync().name('org.moqui.agent.AgentRuntimeServices.call#ServiceGuarded')
                .parameters([
                        serviceName : 'mantle.order.OrderServices.place#Order',
                        parameters : collectNonNullEntries([
                                orderId : orderId,
                                orderPartSeqId : rootContext?.orderPartSeqId,
                                requireInventory : false
                        ]),
                        confirmed : confirmed,
                        toolName : 'moqui_execute_agent_prompt',
                        sessionId : sessionId,
                        directToolCall : false
                ]).call()
        Map serviceResult = (guardedResult?.serviceResult instanceof Map) ? (guardedResult.serviceResult as Map) : [:]
        List<String> errors = (serviceResult?.errors instanceof List) ? (serviceResult.errors as List<String>) : []
        if (Boolean.TRUE.equals(guardedResult?.confirmationRequired)) {
            return [
                    success : false,
                    placed : false,
                    messages : serviceResult?.message ? [serviceResult.message as String] : [],
                    errors : errors ?: ['Order placement requires confirmation'],
                    executionResult : serviceResult
            ]
        }
        if (errors) {
            return [success:false, placed:false, messages:[], errors:errors, executionResult:serviceResult]
        }
        return [
                success : true,
                placed : true,
                messages : ["Placed order ${orderId}."],
                errors : [],
                executionResult : serviceResult
        ]
    }

    protected static Map<String, Integer> getPersistedOrderCounts(ExecutionContext ec, String orderId) {
        if (!ec || !orderId) return [orderPartCount:0, orderItemCount:0]
        try {
            int orderPartCount = ec.entity.find('mantle.order.OrderPart').disableAuthz()
                    .condition('orderId', orderId)
                    .count()
            int orderItemCount = ec.entity.find('mantle.order.OrderItem').disableAuthz()
                    .condition('orderId', orderId)
                    .count()
            return [orderPartCount:orderPartCount, orderItemCount:orderItemCount]
        } catch (Throwable ignored) {
            return [orderPartCount:0, orderItemCount:0]
        }
    }

    protected static Map<String, Integer> getPersistedBudgetCounts(ExecutionContext ec, String budgetId) {
        if (!ec || !budgetId) return [budgetItemCount:0, budgetItemDetailCount:0]
        try {
            int budgetItemCount = ec.entity.find('mantle.other.budget.BudgetItem').disableAuthz()
                    .condition('budgetId', budgetId)
                    .count()
            int budgetItemDetailCount = ec.entity.find('mantle.other.budget.BudgetItemDetail').disableAuthz()
                    .condition('budgetId', budgetId)
                    .count()
            return [budgetItemCount:budgetItemCount, budgetItemDetailCount:budgetItemDetailCount]
        } catch (Throwable ignored) {
            return [budgetItemCount:0, budgetItemDetailCount:0]
        }
    }

    protected static int countAggregateNodes(Map node) {
        if (!node) return 0
        int count = 1
        ((node.children ?: []) as List<Map>).each { Map childNode -> count += countAggregateNodes(childNode) }
        return count
    }

    protected static String inferResultIdField(String documentId) {
        String normalized = (documentId ?: '').toLowerCase()
        if (normalized.contains('createorder') || normalized.contains('orderdetail') || normalized.contains('findorder')) return 'orderId'
        if (normalized.contains('createbudget') || normalized.contains('findbudget') || normalized.contains('/editbudget/')) return 'budgetId'
        if (normalized.contains('budgetitemdetail')) return 'budgetItemDetailId'
        if (normalized.contains('budgetitem')) return 'budgetItemSeqId'
        if (normalized.contains('facility')) return 'facilityId'
        if (normalized.contains('requestitem') || normalized.contains('addrequestitem')) return 'requestItemSeqId'
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

    protected static String detectRootChildHierarchyAggregateKind(String queryText, Map document = null) {
        if (isRequestHierarchyRootDocument(document) || looksLikeRequestHierarchyPrompt(queryText)) return 'request'
        if (isBudgetHierarchyRootDocument(document) || looksLikeBudgetHierarchyPrompt(queryText)) return 'budget'
        if (isFacilityHierarchyRootDocument(document) || looksLikeFacilityHierarchyPrompt(queryText)) return 'facility'
        if (isOrderHierarchyRootDocument(document) || looksLikeOrderHierarchyPrompt(queryText)) return 'order'
        if (isProjectHierarchyRootDocument(document) || looksLikeProjectHierarchyPrompt(queryText)) return 'project'
        return null
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

    protected static Map inferRequestRootSeqPlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        String normalizedText = normalizePromptWhitespace(queryText)
        List<Map> requestItems = extractRequestItemPlan(normalizedText)
        if (!requestItems) return [:]

        Map requestParams = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (!requestParams.requestName) requestParams.requestName = extractRequestName(normalizedText)
        if (!requestParams.description) requestParams.description = extractRequestDescription(normalizedText)
        if (requestParams.priority == null) requestParams.priority = extractNumericPriority(normalizedText)
        if (!requestParams.requestTypeEnumId) requestParams.requestTypeEnumId = inferRequestTypeEnumId(normalizedText)
        if (!requestParams.responseRequiredDate) requestParams.responseRequiredDate = extractResponseRequiredDateText(normalizedText)

        String assignToName = extractAssignedPersonName(normalizedText)
        if (!requestParams.assignToPartyId && assignToName) {
            String assignToPartyId = resolvePartyIdByPersonName(ec, assignToName)
            if (assignToPartyId) requestParams.assignToPartyId = assignToPartyId
        }

        requestParams = collectNonNullEntries(requestParams)
        Map plan = [
                aggregateType : 'root_seq_child',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId ?: 'agent-prompt://request/findrequest/createrequest',
                request : requestParams,
                requestItems : requestItems,
                childCount : requestItems.size()
        ]
        plan.rootNode = buildRootChildHierarchyRootNode(plan)
        return plan
    }

    protected static Map inferBudgetRootSeqPlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        String normalizedText = normalizePromptWhitespace(queryText)
        List<Map> budgetItems = extractBudgetItemPlan(normalizedText)
        if (!budgetItems) return [:]
        budgetItems = budgetItems.collect { Map budgetItem ->
            Map normalizedBudgetItem = new LinkedHashMap(budgetItem)
            String glAccountToken = (budgetItem.glAccountId ?: budgetItem.glAccountCode ?: budgetItem.accountCode ?: null) as String
            if (glAccountToken) {
                String resolvedGlAccountId = resolveGlAccountIdByToken(ec, glAccountToken)
                if (resolvedGlAccountId) normalizedBudgetItem.glAccountId = resolvedGlAccountId
            }
            List<Map> normalizedDetails = []
            ((budgetItem.details ?: []) as List<Map>).each { Map detail ->
                Map normalizedDetail = [:]
                if (detail.amount != null) normalizedDetail.amount = detail.amount
                if (detail.quantity != null) normalizedDetail.quantity = detail.quantity
                String assetToken = (detail.assetId ?: detail.assetToken ?: null) as String
                if (assetToken) {
                    String assetId = resolveAssetIdByToken(ec, assetToken)
                    if (assetId) normalizedDetail.assetId = assetId
                }
                String facilityToken = (detail.facilityId ?: detail.facilityName ?: null) as String
                if (facilityToken) {
                    String facilityId = resolveFacilityIdByName(ec, facilityToken)
                    if (facilityId) normalizedDetail.facilityId = facilityId
                }
                String productToken = (detail.productId ?: detail.productToken ?: null) as String
                if (productToken) {
                    String productId = resolveProductIdByToken(ec, productToken)
                    if (productId) normalizedDetail.productId = productId
                }
                normalizedDetail = collectNonNullEntries(normalizedDetail)
                if (normalizedDetail) normalizedDetails.add(normalizedDetail)
            }
            if (normalizedDetails) normalizedBudgetItem.details = normalizedDetails
            else normalizedBudgetItem.remove('details')
            return collectNonNullEntries(normalizedBudgetItem)
        } as List<Map>

        Map budgetParams = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (!budgetParams.description) budgetParams.description = extractBudgetDescription(normalizedText)
        budgetParams.budgetTypeEnumId = resolveBudgetTypeEnumIdByToken(ec, budgetParams.budgetTypeEnumId ?: inferBudgetTypeEnumId(normalizedText))
        if (!budgetParams.subTimePeriodTypeId) budgetParams.subTimePeriodTypeId = inferBudgetSubTimePeriodTypeId(normalizedText)
        if (!budgetParams.organizationPartyId) {
            String organizationName = extractOrganizationName(normalizedText)
            if (organizationName) budgetParams.organizationPartyId = resolvePartyIdByDisplayName(ec, organizationName)
        }
        if (!budgetParams.organizationPartyId) {
            Object activeOrgId = ec?.context?.get('activeOrgId')
            if (activeOrgId) budgetParams.organizationPartyId = activeOrgId as String
        }
        if (!budgetParams.timePeriodId) {
            Integer fiscalYear = extractYearNumber(normalizedText)
            if (fiscalYear != null) {
                budgetParams.timePeriodId = resolveFiscalYearTimePeriodId(ec, budgetParams.organizationPartyId as String, fiscalYear)
            }
        }

        budgetParams = collectNonNullEntries(budgetParams)
        if (!budgetParams.timePeriodId) return [:]

        Map plan = [
                aggregateType : 'root_seq_child',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId ?: 'agent-prompt://accounting/findbudget/createbudget',
                budget : budgetParams,
                budgetItems : budgetItems,
                childCount : budgetItems.size(),
                detailCount : budgetItems.collect { Map it -> ((it.details ?: []) as List).size() }.sum() ?: 0
        ]
        plan.rootNode = buildRootChildHierarchyRootNode(plan)
        return plan
    }

    protected static Map translateLlmDecompositionToRequestHierarchyPlan(ExecutionContext ec, Map document, Map mergedParameters,
                                                                         Map subject, Map aggregate) {
        Map requestParams = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (!requestParams.requestName) requestParams.requestName = (subject.displayName ?: subject.name ?: subject.id ?: null) as String
        if (!requestParams.description) requestParams.description = (subject.description ?: aggregate.description ?: null) as String
        if (requestParams.priority == null) {
            Object priorityValue = subject.priority ?: aggregate.priority
            if (priorityValue != null) {
                try { requestParams.priority = Long.valueOf(priorityValue.toString()) } catch (Throwable ignored) { }
            }
        }
        if (!requestParams.requestTypeEnumId) {
            String typeDescription = (subject.requestType ?: aggregate.requestType ?: null) as String
            String enumId = inferRequestTypeEnumId(typeDescription)
            if (enumId) requestParams.requestTypeEnumId = enumId
        }

        List<Map> requestItems = []
        List rawItems = (aggregate.items instanceof List) ? (aggregate.items as List) :
                ((aggregate.requestItems instanceof List) ? (aggregate.requestItems as List) : [])
        rawItems.each { Object rawItem ->
            if (!(rawItem instanceof Map)) return
            Map rawItemMap = rawItem as Map
            String itemDescription = ((rawItemMap.description ?: rawItemMap.name ?: rawItemMap.requestItemName ?: '') as String).trim()
            if (!itemDescription) return
            Map item = [description : itemDescription]
            Object quantityValue = rawItemMap.quantity
            if (quantityValue != null) {
                try { item.quantity = new BigDecimal(quantityValue.toString().replace(',', '.')) } catch (Throwable ignored) { }
            }
            if (rawItemMap.requiredByDate) item.requiredByDate = rawItemMap.requiredByDate
            if (rawItemMap.maximumAmount) item.maximumAmount = rawItemMap.maximumAmount
            if (rawItemMap.productId) item.productId = rawItemMap.productId
            requestItems.add(collectNonNullEntries(item))
        }
        if (!requestItems) return [:]

        Map plan = [
                aggregateType : 'root_seq_child',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId ?: 'agent-prompt://request/findrequest/createrequest',
                request : collectNonNullEntries(requestParams),
                requestItems : requestItems,
                childCount : requestItems.size()
        ]
        plan.rootNode = buildRootChildHierarchyRootNode(plan)
        return plan
    }

    protected static Map translateLlmDecompositionToBudgetHierarchyPlan(ExecutionContext ec, Map document, Map mergedParameters,
                                                                        Map subject, Map aggregate) {
        Map budgetParams = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (!budgetParams.description) {
            budgetParams.description = (subject.description ?: subject.displayName ?: subject.name ?: aggregate.description ?: null) as String
        }
        budgetParams.budgetTypeEnumId = resolveBudgetTypeEnumIdByToken(ec,
                budgetParams.budgetTypeEnumId ?: (subject.budgetType ?: aggregate.budgetType ?: null) as String)
        if (!budgetParams.subTimePeriodTypeId) {
            String subTypeDescription = (subject.subTimePeriodType ?: aggregate.subTimePeriodType ?: null) as String
            String subTypeId = inferBudgetSubTimePeriodTypeId(subTypeDescription)
            if (subTypeId) budgetParams.subTimePeriodTypeId = subTypeId
        }
        String organizationName = (subject.organizationName ?: aggregate.organizationName ?: null) as String
        if (!budgetParams.organizationPartyId && organizationName) {
            String organizationPartyId = resolvePartyIdByDisplayName(ec, organizationName)
            if (organizationPartyId) budgetParams.organizationPartyId = organizationPartyId
        }
        if (!budgetParams.timePeriodId) {
            Object fiscalYearValue = subject.fiscalYear ?: aggregate.fiscalYear ?: null
            Integer fiscalYear = null
            if (fiscalYearValue != null) {
                try { fiscalYear = Integer.valueOf(fiscalYearValue.toString()) } catch (Throwable ignored) { }
            }
            if (fiscalYear != null) {
                budgetParams.timePeriodId = resolveFiscalYearTimePeriodId(ec, budgetParams.organizationPartyId as String, fiscalYear)
            }
        }

        List<Map> budgetItems = []
        List rawItems = (aggregate.items instanceof List) ? (aggregate.items as List) :
                ((aggregate.budgetItems instanceof List) ? (aggregate.budgetItems as List) : [])
        rawItems.each { Object rawItem ->
            if (!(rawItem instanceof Map)) return
            Map rawItemMap = rawItem as Map
            String purpose = ((rawItemMap.purpose ?: rawItemMap.description ?: rawItemMap.name ?: '') as String).trim()
            if (!purpose) return
            Map item = [purpose : purpose]
            Object amountValue = rawItemMap.amount
            if (amountValue != null) {
                try { item.amount = new BigDecimal(amountValue.toString().replace(',', '.')) } catch (Throwable ignored) { }
            }
            String glAccountToken = (rawItemMap.glAccountId ?: rawItemMap.glAccountCode ?: rawItemMap.accountCode ?: null) as String
            if (glAccountToken) {
                String glAccountId = resolveGlAccountIdByToken(ec, glAccountToken)
                if (glAccountId) item.glAccountId = glAccountId
            }
            String subPeriodToken = (rawItemMap.subTimePeriodId ?: rawItemMap.subPeriod ?: null) as String
            if (subPeriodToken) item.subTimePeriodId = subPeriodToken
            List<Map> detailPlans = []
            List rawDetails = (rawItemMap.details instanceof List) ? (rawItemMap.details as List) :
                    ((rawItemMap.budgetItemDetails instanceof List) ? (rawItemMap.budgetItemDetails as List) : [])
            rawDetails.each { Object rawDetail ->
                if (!(rawDetail instanceof Map)) return
                Map rawDetailMap = rawDetail as Map
                Map detail = [:]
                Object detailAmount = rawDetailMap.amount
                if (detailAmount != null) {
                    try { detail.amount = new BigDecimal(detailAmount.toString().replace(',', '.')) } catch (Throwable ignored) { }
                }
                Object detailQuantity = rawDetailMap.quantity
                if (detailQuantity != null) {
                    try { detail.quantity = new BigDecimal(detailQuantity.toString().replace(',', '.')) } catch (Throwable ignored) { }
                }
                String assetToken = (rawDetailMap.assetId ?: rawDetailMap.assetCode ?: rawDetailMap.asset ?: null) as String
                if (assetToken) {
                    String assetId = resolveAssetIdByToken(ec, assetToken)
                    if (assetId) detail.assetId = assetId
                }
                String facilityToken = (rawDetailMap.facilityId ?: rawDetailMap.facilityName ?: rawDetailMap.facility ?: null) as String
                if (facilityToken) {
                    String facilityId = resolveFacilityIdByName(ec, facilityToken)
                    if (facilityId) detail.facilityId = facilityId
                }
                String productToken = (rawDetailMap.productId ?: rawDetailMap.productPseudoId ?: rawDetailMap.product ?: null) as String
                if (productToken) {
                    String productId = resolveProductIdByToken(ec, productToken)
                    if (productId) detail.productId = productId
                }
                detail = collectNonNullEntries(detail)
                if (detail) detailPlans.add(detail)
            }
            if (detailPlans) item.details = detailPlans
            budgetItems.add(collectNonNullEntries(item))
        }
        if (!budgetItems || !budgetParams.timePeriodId) return [:]

        Map plan = [
                aggregateType : 'root_seq_child',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId ?: 'agent-prompt://accounting/findbudget/createbudget',
                budget : collectNonNullEntries(budgetParams),
                budgetItems : budgetItems,
                childCount : budgetItems.size(),
                detailCount : budgetItems.collect { Map it -> ((it.details ?: []) as List).size() }.sum() ?: 0
        ]
        plan.rootNode = buildRootChildHierarchyRootNode(plan)
        return plan
    }

    protected static Map inferFacilityParentChildPlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        String normalizedText = normalizePromptWhitespace(queryText)
        List<Map> childFacilities = extractFacilityChildPlan(normalizedText)
        if (!childFacilities) return [:]

        Map facilityParams = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (!facilityParams.facilityName) facilityParams.facilityName = extractFacilityName(normalizedText)
        if (!facilityParams.facilityTypeEnumId) facilityParams.facilityTypeEnumId = inferFacilityTypeEnumId(normalizedText)
        facilityParams = collectNonNullEntries(facilityParams)
        if (!facilityParams.facilityName) return [:]

        Map plan = [
                aggregateType : 'parent_child_tree',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId ?: 'agent-prompt://facility/findfacility/createfacility',
                facility : facilityParams,
                childFacilities : childFacilities,
                childCount : childFacilities.size()
        ]
        plan.rootNode = buildRootChildHierarchyRootNode(plan)
        return plan
    }

    protected static Map inferOrderHeaderPartItemPlan(ExecutionContext ec, String queryText, Map document = null, Map mergedParameters = null) {
        String normalizedText = normalizePromptWhitespace(queryText)
        Map orderSpec = extractOrderHierarchyPlan(normalizedText)
        if (!(orderSpec?.parts instanceof List) || !(orderSpec.parts as List)) return [:]

        Map orderParams = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (orderParams.priority == null) orderParams.priority = extractNumericPriority(normalizedText)
        if (!orderParams.estimatedDeliveryDate) orderParams.estimatedDeliveryDate = extractDateTextByCue(normalizedText, ['data consegna', 'delivery date'])

        String customerName = extractOrderCustomerName(normalizedText)
        if (!orderParams.customerPartyId && customerName) {
            String customerPartyId = resolvePartyIdByDisplayName(ec, customerName)
            if (customerPartyId) orderParams.customerPartyId = customerPartyId
        }

        String orderType = inferOrderType(normalizedText)
        if (orderType == 'sales') {
            Map salesDefaults = resolveDefaultSalesOrderContext(ec, orderParams)
            if (!orderParams.productStoreId && salesDefaults.productStoreId) orderParams.productStoreId = salesDefaults.productStoreId
            if (!orderParams.currencyUomId && salesDefaults.currencyUomId) orderParams.currencyUomId = salesDefaults.currencyUomId
            if (!orderParams.salesChannelEnumId) orderParams.salesChannelEnumId = salesDefaults.salesChannelEnumId ?: 'ScWeb'
            if (!orderParams.vendorPartyId && salesDefaults.organizationPartyId) orderParams.vendorPartyId = salesDefaults.organizationPartyId
            if (!orderParams.facilityId && salesDefaults.facilityId) orderParams.facilityId = salesDefaults.facilityId
        }

        List<Map> partPlans = []
        ((orderSpec.parts ?: []) as List<Map>).eachWithIndex { Map rawPart, int idx ->
            List<Map> partItems = []
            ((rawPart.items ?: []) as List<Map>).each { Map rawItem ->
                String resolvedProductId = resolveProductIdFromSpec(ec, rawItem)
                if (!resolvedProductId) return
                partItems.add(collectNonNullEntries([
                        productId : resolvedProductId,
                        quantity : rawItem.quantity ?: 1,
                        requiredByDate : rawItem.requiredByDate,
                        description : rawItem.description
                ]))
            }
            if (!partItems) return
            Map part = [
                    label : rawPart.label ?: "Part ${idx + 1}",
                    facilityId : rawPart.facilityId ?: orderParams.facilityId,
                    shipBeforeDate : rawPart.shipBeforeDate,
                    estimatedDeliveryDate : rawPart.estimatedDeliveryDate,
                    items : partItems
            ]
            partPlans.add(collectNonNullEntries(part))
        }
        if (!partPlans) return [:]

        Map plan = [
                aggregateType : 'header_part_item',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId ?: 'agent-prompt://order/findorder/createorder',
                order : collectNonNullEntries(orderParams),
                parts : partPlans,
                partCount : partPlans.size(),
                itemCount : partPlans.collect { Map p -> ((p.items ?: []) as List).size() }.sum() ?: 0
        ]
        plan.rootNode = buildRootChildHierarchyRootNode(plan)
        return plan
    }

    protected static Map translateLlmDecompositionToFacilityHierarchyPlan(ExecutionContext ec, Map document, Map mergedParameters,
                                                                          Map subject, Map aggregate) {
        Map facilityParams = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (!facilityParams.facilityName) facilityParams.facilityName = (subject.displayName ?: subject.name ?: subject.id ?: null) as String
        if (!facilityParams.facilityTypeEnumId) {
            String typeDescription = (subject.facilityType ?: aggregate.facilityType ?: null) as String
            String facilityTypeEnumId = inferFacilityTypeEnumId(typeDescription)
            if (facilityTypeEnumId) facilityParams.facilityTypeEnumId = facilityTypeEnumId
        }

        List<Map> childFacilities = []
        List rawChildren = (aggregate.children instanceof List) ? (aggregate.children as List) : []
        rawChildren.each { Object rawChild ->
            if (!(rawChild instanceof Map)) return
            Map rawChildMap = rawChild as Map
            String childName = ((rawChildMap.name ?: rawChildMap.facilityName ?: '') as String).trim()
            if (!childName) return
            Map child = [facilityName : childName]
            String childType = (rawChildMap.facilityType ?: rawChildMap.type ?: null) as String
            if (childType) {
                String childTypeEnumId = inferFacilityTypeEnumId(childType)
                if (childTypeEnumId) child.facilityTypeEnumId = childTypeEnumId
            }
            childFacilities.add(collectNonNullEntries(child))
        }
        if (!childFacilities || !facilityParams.facilityName) return [:]

        Map plan = [
                aggregateType : 'parent_child_tree',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId ?: 'agent-prompt://facility/findfacility/createfacility',
                facility : collectNonNullEntries(facilityParams),
                childFacilities : childFacilities,
                childCount : childFacilities.size()
        ]
        plan.rootNode = buildRootChildHierarchyRootNode(plan)
        return plan
    }

    protected static Map translateLlmDecompositionToOrderHierarchyPlan(ExecutionContext ec, Map document, Map mergedParameters,
                                                                       Map subject, Map aggregate) {
        Map orderParams = new LinkedHashMap((mergedParameters instanceof Map) ? (mergedParameters as Map) : [:])
        if (orderParams.priority == null) {
            Object priorityValue = subject.priority ?: aggregate.priority
            if (priorityValue != null) {
                try { orderParams.priority = Long.valueOf(priorityValue.toString()) } catch (Throwable ignored) { }
            }
        }
        String customerName = (subject.customerName ?: aggregate.customerName ?: null) as String
        if (!orderParams.customerPartyId && customerName) {
            String customerPartyId = resolvePartyIdByDisplayName(ec, customerName)
            if (customerPartyId) orderParams.customerPartyId = customerPartyId
        }
        Map salesDefaults = resolveDefaultSalesOrderContext(ec, orderParams)
        if (!orderParams.productStoreId && salesDefaults.productStoreId) orderParams.productStoreId = salesDefaults.productStoreId
        if (!orderParams.currencyUomId && salesDefaults.currencyUomId) orderParams.currencyUomId = salesDefaults.currencyUomId
        if (!orderParams.salesChannelEnumId) orderParams.salesChannelEnumId = salesDefaults.salesChannelEnumId ?: 'ScWeb'
        if (!orderParams.vendorPartyId && salesDefaults.organizationPartyId) orderParams.vendorPartyId = salesDefaults.organizationPartyId
        if (!orderParams.facilityId && salesDefaults.facilityId) orderParams.facilityId = salesDefaults.facilityId

        List<Map> partPlans = []
        List rawParts = (aggregate.parts instanceof List) ? (aggregate.parts as List) : []
        rawParts.eachWithIndex { Object rawPart, int idx ->
            if (!(rawPart instanceof Map)) return
            Map rawPartMap = rawPart as Map
            List<Map> itemPlans = []
            List rawItems = (rawPartMap.items instanceof List) ? (rawPartMap.items as List) : []
            rawItems.each { Object rawItem ->
                if (!(rawItem instanceof Map)) return
                Map rawItemMap = rawItem as Map
                String productToken = ((rawItemMap.productId ?: rawItemMap.pseudoId ?: rawItemMap.product ?: '') as String).trim()
                if (!productToken) return
                String productId = resolveProductIdByToken(ec, productToken)
                if (!productId) return
                Map item = [productId : productId]
                Object quantityValue = rawItemMap.quantity
                if (quantityValue != null) {
                    try { item.quantity = new BigDecimal(quantityValue.toString().replace(',', '.')) } catch (Throwable ignored) { }
                }
                if (rawItemMap.requiredByDate) item.requiredByDate = rawItemMap.requiredByDate
                itemPlans.add(collectNonNullEntries(item))
            }
            if (!itemPlans) return
            partPlans.add(collectNonNullEntries([
                    label : ((rawPartMap.name ?: rawPartMap.label ?: "Part ${idx + 1}") as String).trim(),
                    facilityId : rawPartMap.facilityId ?: orderParams.facilityId,
                    items : itemPlans
            ]))
        }
        if (!partPlans) return [:]

        Map plan = [
                aggregateType : 'header_part_item',
                aggregatePatternId : PATTERN_SILVERSTON_ROOT_CHILD_HIERARCHY,
                rootDocumentId : document?.documentId ?: 'agent-prompt://order/findorder/createorder',
                order : collectNonNullEntries(orderParams),
                parts : partPlans,
                partCount : partPlans.size(),
                itemCount : partPlans.collect { Map p -> ((p.items ?: []) as List).size() }.sum() ?: 0
        ]
        plan.rootNode = buildRootChildHierarchyRootNode(plan)
        return plan
    }

    protected static Map buildRequestRootSeqHierarchyNode(Map plan) {
        List<Map> itemNodes = ((plan.requestItems ?: []) as List<Map>).collect { Map requestItem ->
            [
                    nodeType : 'request_item',
                    documentId : 'agent-prompt://request/editrequestitems/addrequestitem',
                    resultIdField : 'requestItemSeqId',
                    contextKey : 'requestItem',
                    parameters : collectNonNullEntries([
                            description : requestItem.description,
                            quantity : requestItem.quantity,
                            requiredByDate : requestItem.requiredByDate,
                            maximumAmount : requestItem.maximumAmount,
                            productId : requestItem.productId,
                            supplierPartyId : requestItem.supplierPartyId
                    ]),
                    inheritRootFields : [requestId : 'requestId']
            ]
        }

        return [
                nodeType : 'request',
                documentId : plan.rootDocumentId,
                resultIdField : 'requestId',
                contextKey : 'request',
                parameters : plan.request,
                children : itemNodes
        ]
    }

    protected static Map buildBudgetRootSeqHierarchyNode(Map plan) {
        List<Map> itemNodes = ((plan.budgetItems ?: []) as List<Map>).collectWithIndex { Map budgetItem, int budgetItemIndex ->
            List<Map> detailNodes = ((budgetItem.details ?: []) as List<Map>).collect { Map budgetItemDetail ->
                [
                        nodeType : 'budget_item_detail',
                        documentId : 'AgentArtifactService:service://mantle.other.BudgetServices.store#BudgetItemDetail',
                        serviceName : 'store#mantle.other.budget.BudgetItemDetail',
                        indexName : 'moqui_agent_documents_v2',
                        resultIdField : 'budgetItemDetailId',
                        contextKey : 'budgetItemDetail',
                        parameters : collectNonNullEntries([
                                assetId : budgetItemDetail.assetId,
                                facilityId : budgetItemDetail.facilityId,
                                productId : budgetItemDetail.productId,
                                quantity : budgetItemDetail.quantity,
                                amount : budgetItemDetail.amount
                        ]),
                        inheritRootFields : [budgetId : 'budgetId'],
                        inheritParentFields : [budgetItemSeqId : 'budgetItemSeqId']
                ]
            }
            [
                    nodeType : 'budget_item',
                    documentId : 'agent-prompt://accounting/editbudgetitems/createbudgetitem',
                    serviceName : 'store#mantle.other.budget.BudgetItem',
                    resultIdField : 'budgetItemSeqId',
                    contextKey : 'budgetItem',
                    parameters : collectNonNullEntries([
                            budgetItemSeqId : budgetItem.budgetItemSeqId ?: String.format('%02d', budgetItemIndex + 1),
                            glAccountId : budgetItem.glAccountId,
                            amount : budgetItem.amount,
                            subTimePeriodId : budgetItem.subTimePeriodId,
                            purpose : budgetItem.purpose
                    ]),
                    inheritRootFields : [budgetId : 'budgetId'],
                    children : detailNodes
            ]
        }

        return [
                nodeType : 'budget',
                documentId : plan.rootDocumentId,
                resultIdField : 'budgetId',
                contextKey : 'budget',
                parameters : collectNonNullEntries([
                        budgetTypeEnumId : plan.budget.budgetTypeEnumId,
                        organizationPartyId : plan.budget.organizationPartyId,
                        timePeriodId : plan.budget.timePeriodId,
                        subTimePeriodTypeId : plan.budget.subTimePeriodTypeId,
                        description : plan.budget.description
                ]),
                children : itemNodes
        ]
    }

    protected static Map buildFacilityParentChildHierarchyNode(Map plan) {
        List<Map> childNodes = ((plan.childFacilities ?: []) as List<Map>).collect { Map facilityChild ->
            [
                    nodeType : 'facility_child',
                    documentId : 'agent-prompt://facility/findfacility/createfacility',
                    resultIdField : 'facilityId',
                    contextKey : 'facilityChild',
                    parameters : collectNonNullEntries([
                            pseudoId : facilityChild.pseudoId,
                            facilityName : facilityChild.facilityName,
                            facilityTypeEnumId : facilityChild.facilityTypeEnumId ?: plan.facility.facilityTypeEnumId,
                            ownerPartyId : facilityChild.ownerPartyId ?: plan.facility.ownerPartyId
                    ]),
                    inheritParentFields : [parentFacilityId : 'facilityId']
            ]
        }

        return [
                nodeType : 'facility',
                documentId : plan.rootDocumentId,
                resultIdField : 'facilityId',
                contextKey : 'facility',
                parameters : plan.facility,
                children : childNodes
        ]
    }

    protected static Map buildOrderHeaderPartItemHierarchyNode(Map plan) {
        List<Map> partSpecs = (plan.parts ?: []) as List<Map>
        Map firstPart = partSpecs ? (partSpecs.first() as Map) : [:]
        List<Map> rootPartItemNodes = ((firstPart.items ?: []) as List<Map>).collect { Map item ->
            [
                    nodeType : 'order_item',
                    documentId : 'agent-prompt://order/orderdetail/addproductitem',
                    resultIdField : 'orderItemSeqId',
                    contextKey : 'orderItem',
                    parameters : collectNonNullEntries([
                            productId : item.productId,
                            quantity : item.quantity,
                            requiredByDate : item.requiredByDate,
                            description : item.description
                    ]),
                    inheritRootFields : [orderId : 'orderId', orderPartSeqId : 'orderPartSeqId']
            ]
        }

        List<Map> additionalPartNodes = partSpecs.size() > 1 ? partSpecs.subList(1, partSpecs.size()).collect { Map part ->
            List<Map> itemNodes = ((part.items ?: []) as List<Map>).collect { Map item ->
                [
                        nodeType : 'order_item',
                        documentId : 'agent-prompt://order/orderdetail/addproductitem',
                        resultIdField : 'orderItemSeqId',
                        contextKey : 'orderItem',
                        parameters : collectNonNullEntries([
                                productId : item.productId,
                                quantity : item.quantity,
                                requiredByDate : item.requiredByDate,
                                description : item.description
                        ]),
                        inheritRootFields : [orderId : 'orderId'],
                        inheritParentFields : [orderPartSeqId : 'orderPartSeqId']
                ]
            }
            [
                    nodeType : 'order_part',
                    documentId : 'agent-prompt://order/orderdetail/createorderpart',
                    resultIdField : 'orderPartSeqId',
                    contextKey : 'orderPart',
                    parameters : collectNonNullEntries([
                            facilityId : part.facilityId,
                            shipBeforeDate : part.shipBeforeDate,
                            estimatedDeliveryDate : part.estimatedDeliveryDate,
                            customerPartyId : plan.order.customerPartyId,
                            vendorPartyId : plan.order.vendorPartyId
                    ]),
                    inheritRootFields : [orderId : 'orderId'],
                    children : itemNodes
            ]
        } : []

        return [
                nodeType : 'order_header',
                documentId : plan.rootDocumentId,
                resultIdField : 'orderId',
                contextKey : 'order',
                parameters : plan.order,
                children : rootPartItemNodes + additionalPartNodes
        ]
    }

    protected static List<String> splitAmountBearingItems(String rawText) {
        if (!rawText) return []
        String normalized = normalizePromptWhitespace(rawText)
        List<String> parts = []
        def matcher = (normalized =~ /(.+?\b[0-9]+(?:[.,][0-9]+)?)(?:(?:\s+e\s+)|$)/)
        while (matcher.find()) {
            String candidate = cleanupPromptSegment(matcher.group(1))
            if (candidate) parts.add(candidate)
        }
        if (parts.size() > 1) return parts
        return [cleanupPromptSegment(rawText)].findAll { it } as List<String>
    }

    protected static String cleanupPromptSegment(String text) {
        if (!text) return null
        String cleaned = normalizePromptWhitespace(text)
                .replaceAll(/(?i)\s+ed\s+/, ' e ')
                .replaceAll(/(?i)^e\s+/, '')
                .replaceAll(/[.,;:!?]+$/, '')
                .trim()
        return cleaned
    }

    protected static List<String> splitNamedItems(String rawText, Integer expectedCount) {
        if (!rawText) return []
        String workingText = normalizePromptWhitespace(rawText)
                .replaceFirst(/(?i)[,.;:]?\s+tutti\s+con\b.*$/, '')
                .replaceFirst(/(?i)[,.;:]?\s+assegna\b.*$/, '')
                .replaceFirst(/(?i)[,.;:]?\s+assigned\s+to\b.*$/, '')
                .trim()
        List<String> parts = workingText.split(/\s*,\s*/).collect { cleanupPromptSegment(it) }.findAll { it } as List<String>
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

    protected static Integer safeCountInteger(String text) {
        if (!text) return null
        Integer direct = safeInteger(text)
        if (direct != null) return direct
        Map<String, Integer> wordToCount = [
                'un':1, 'uno':1, 'una':1, 'one':1,
                'due':2, 'two':2,
                'tre':3, 'three':3,
                'quattro':4, 'four':4,
                'cinque':5, 'five':5,
                'sei':6, 'six':6,
                'sette':7, 'seven':7,
                'otto':8, 'eight':8,
                'nove':9, 'nine':9,
                'dieci':10, 'ten':10
        ]
        return wordToCount[text.trim().toLowerCase()]
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

    protected static String extractRequestName(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\b(?:richiesta|request)\b.*?\b(?:con\s+nome|named)\s+([^,.\n]+?)(?=(?:\s+con\b|\s+with\b|,|\.|$))/,
                /(?i)\bnome\s+([^,.\n]+?)(?=(?:\s+e\s+descrizione\b|\s+descrizione\b|\s+con\b|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        }
        return null
    }

    protected static String extractFacilityName(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\b(?:facility|magazzino|warehouse|deposito)\s+([^,.\n]+?)(?=(?:\s+con\b|\s+with\b|\s+e\s+\d+\s+(?:child|children|figli)|,|\.|$))/,
                /(?i)\bnome\s+([^,.\n]+?)(?=(?:\s+con\b|\s+with\b|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                String candidate = cleanupPromptSegment(matcher.group(1))
                candidate = candidate?.replaceAll(/(?i)^(?:nuovo|nuova|new)\s+/, '')?.trim()
                if (candidate) return candidate
            }
        }
        return null
    }

    protected static String extractBudgetDescription(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\b(?:budget)\b.*?\b(?:con\s+nome|named|descrizione|description)\s+([^,.\n]+?)(?=(?:\s+con\b|\s+with\b|,|\.|$))/,
                /(?i)\bbudget\s+([^,.\n]+?)(?=(?:\s+per\b|\s+for\b|\s+con\b|\s+with\b|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                String candidate = cleanupPromptSegment(matcher.group(1))
                candidate = candidate?.replaceAll(/(?i)^(?:nuovo|nuova|new)\s+/, '')?.trim()
                if (candidate) return candidate
            }
        }
        return null
    }

    protected static String extractOrganizationName(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\b(?:organizzazione|organization|company|azienda)\s+([^,.\n]+?)(?=(?:\s+con\b|\s+with\b|\s+anno\b|\s+year\b|,|\.|$))/,
                /(?i)\bper\s+([^,.\n]+?)(?=(?:\s+anno\b|\s+year\b|\s+con\b|\s+with\b|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        }
        return null
    }

    protected static String extractOrderCustomerName(String text) {
        if (!text) return null
        List patterns = [
                /(?i)\b(?:per|verso|for)\s+cliente\s+([^,.\n]+?)(?=(?:\s+con\b|\s+with\b|,|\.|$))/,
                /(?i)\b(?:per|for)\s+([^,.\n]+?)(?=(?:\s+con\b|\s+with\b|\s+prodott|\s+product|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        }
        return null
    }

    protected static String extractRequestDescription(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\bdescrizione\s+([^.\n]+?)(?=(?:\s+con\b|\s+with\b|,|\.|$))/,
                /(?i)\bdescription\s+([^.\n]+?)(?=(?:\s+with\b|\s+con\b|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        }
        return null
    }

    protected static String extractResponseRequiredDateText(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\bdata\s+scadenza\s+([0-9]{1,2}\/[0-9]{1,2}\/[0-9]{4})/,
                /(?i)\bdue\s+date\s+([0-9]{1,2}\/[0-9]{1,2}\/[0-9]{4})/,
                /(?i)\bdue\s+date\s+([0-9]{4}-[0-9]{2}-[0-9]{2})/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) return normalizeServiceDateText(cleanupPromptSegment(matcher.group(1)))
        }
        return null
    }

    protected static String inferRequestTypeEnumId(String text) {
        String normalized = normalizePromptWhitespace(text).toLowerCase()
        if (!normalized) return null
        if (normalized.contains('support')) return 'RqtSupport'
        if (normalized.contains('bug')) return 'RqtBugFix'
        if (normalized.contains('feature')) return 'RqtNewFeature'
        if (normalized.contains('inventory') || normalized.contains('magazzin')) return 'RqtInventory'
        if (normalized.contains('purchase') || normalized.contains('acquist')) return 'RqtPurchase'
        if (normalized.contains('quote') || normalized.contains('preventiv')) return 'RqtQuote'
        if (normalized.contains('proposal') || normalized.contains('propost')) return 'RqtProposal'
        if (normalized.contains('information') || normalized.contains('informaz')) return 'RqtInformation'
        if (normalized.contains('contact')) return 'RqtContact'
        return null
    }

    protected static String inferFacilityTypeEnumId(String text) {
        String normalized = normalizePromptWhitespace(text).toLowerCase()
        if (!normalized) return null
        if (normalized.contains('warehouse') || normalized.contains('magazzin')) return 'FcTpWarehouse'
        if (normalized.contains('retail store') || normalized.contains('negozio')) return 'FcTpRetailStore'
        if (normalized.contains('plant') || normalized.contains('impiant')) return 'FcTpPlant'
        if (normalized.contains('room') || normalized.contains('stanza')) return 'FcTpRoom'
        if (normalized.contains('line') || normalized.contains('linea')) return 'FcTpLine'
        if (normalized.contains('building') || normalized.contains('edific')) return 'FcTpBuilding'
        if (normalized.contains('office') || normalized.contains('ufficio')) return 'FcTpOffice'
        return null
    }

    protected static String inferBudgetTypeEnumId(String text) {
        String normalized = normalizePromptWhitespace(text).toLowerCase()
        if (!normalized) return null
        if (normalized.contains('capital')) return 'BudgetCapital'
        if (normalized.contains('operating') || normalized.contains('operativo') || normalized.contains('operating')) return 'BudgetOperating'
        return null
    }

    protected static String resolveBudgetTypeEnumIdByToken(ExecutionContext ec, String token) {
        if (!token) return null
        String normalized = normalizePromptWhitespace(token)
        if (!normalized) return null

        // Accept canonical IDs, common descriptions, and a few malformed aliases produced by LLMs.
        if (normalized == 'BudgetCapital' || normalized.equalsIgnoreCase('capital')) return 'BudgetCapital'
        if (normalized == 'BudgetOperating' || normalized.equalsIgnoreCase('operating') || normalized.equalsIgnoreCase('operativo')) return 'BudgetOperating'
        if (normalized.equalsIgnoreCase('BudTypeCapital')) return 'BudgetCapital'
        if (normalized.equalsIgnoreCase('BudTypeOperating')) return 'BudgetOperating'

        String inferred = inferBudgetTypeEnumId(normalized)
        if (inferred) return inferred

        try {
            def enumeration = ec?.entity?.find('moqui.basic.Enumeration')?.disableAuthz()
                    ?.condition('enumTypeId', 'BudgetType')
                    ?.condition('description', normalized)
                    ?.one()
            if (enumeration?.enumId) return enumeration.enumId as String
        } catch (Throwable ignored) { }

        return null
    }

    protected static String inferBudgetSubTimePeriodTypeId(String text) {
        String normalized = normalizePromptWhitespace(text).toLowerCase()
        if (!normalized) return null
        if (normalized.contains('quarter') || normalized.contains('quarterly') || normalized.contains('trimestre') || normalized.contains('trimestr')) return 'FiscalQuarter'
        if (normalized.contains('month') || normalized.contains('monthly') || normalized.contains('mese') || normalized.contains('mensil')) return 'FiscalMonth'
        return null
    }

    protected static String inferOrderType(String text) {
        String normalized = normalizePromptWhitespace(text).toLowerCase()
        if (normalized.contains('vendita') || normalized.contains('sales')) return 'sales'
        if (normalized.contains('acquisto') || normalized.contains('purchase')) return 'purchase'
        return null
    }

    protected static String extractDateTextByCue(String text, List<String> cues) {
        if (!text || !cues) return null
        for (String cue in cues) {
            if (!cue) continue
            def matcher = (text =~ /(?i)\b${java.util.regex.Pattern.quote(cue)}\s+([0-9]{1,2}\/[0-9]{1,2}\/[0-9]{4}|[0-9]{4}-[0-9]{2}-[0-9]{2})/)
            if (matcher.find()) return normalizeServiceDateText(cleanupPromptSegment(matcher.group(1)))
        }
        return null
    }

    protected static String normalizeServiceDateText(String value) {
        String normalized = cleanupPromptSegment(value)
        if (!normalized) return null

        def dmyMatcher = (normalized =~ /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/)
        if (dmyMatcher.matches()) {
            String day = dmyMatcher.group(1).padLeft(2, '0')
            String month = dmyMatcher.group(2).padLeft(2, '0')
            String year = dmyMatcher.group(3)
            return "${year}-${month}-${day} 00:00:00.000"
        }

        def isoMatcher = (normalized =~ /^(\d{4})-(\d{2})-(\d{2})$/)
        if (isoMatcher.matches()) return "${normalized} 00:00:00.000"

        return normalized
    }

    protected static List<Map> extractRequestItemPlan(String text) {
        if (!text) return []
        List<String> itemTexts = []
        List patterns = [
                /(?is)\b(?:con|with)\s+\d+\s+(?:items?|righe|linee)\s+(.+?)(?=(?:\bassegna\b|\bassign\b|\bcon\s+priorit|\bwith\s+priorit|$))/,
                /(?is)\b(?:items?|righe|linee)\s*:\s*(.+?)(?=(?:\bassegna\b|\bassign\b|\bcon\s+priorit|\bwith\s+priorit|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                String rawItems = cleanupPromptSegment(matcher.group(1))
                if (rawItems) {
                    itemTexts = splitNamedItems(rawItems, null)
                    if (itemTexts.size() <= 1) itemTexts = splitAmountBearingItems(rawItems)
                    break
                }
            }
        }
        if (!itemTexts) return []
        return itemTexts.collect { String itemText -> buildRequestItemEntry(itemText) }.findAll { it } as List<Map>
    }

    protected static List<Map> extractFacilityChildPlan(String text) {
        if (!text) return []
        List<String> childNames = []
        List patterns = [
                /(?is)\b(?:con|with)\s+\d+\s+(?:child(?:ren)?\s+facilit(?:y|ies)|facility\s+figli(?:e)?|magazzini\s+figli|facilities)\s+(.+?)(?=(?:\bowner\b|\bproprietari[oa]\b|\bdescription\b|$))/,
                /(?is)\b(?:children|child facilities|facility figli(?:e)?|magazzini figli|facilities)\s*:\s*(.+?)(?=(?:\bowner\b|\bproprietari[oa]\b|\bdescription\b|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                String rawChildren = cleanupPromptSegment(matcher.group(1))
                if (rawChildren) {
                    childNames = splitNamedItems(rawChildren, null)
                    break
                }
            }
        }
        if (!childNames) return []
        return childNames.collect { String name ->
            String cleaned = cleanupPromptSegment(name)
            cleaned ? [facilityName : cleaned, facilityTypeEnumId : inferFacilityTypeEnumId(cleaned)] : null
        }.findAll { it } as List<Map>
    }

    protected static List<Map> extractBudgetItemPlan(String text) {
        if (!text) return []
        List<String> itemTexts = []
        Integer expectedCount = null
        String countTokenPattern = '(?:\\d+|un|uno|una|one|due|two|tre|three|quattro|four|cinque|five|sei|six|sette|seven|otto|eight|nove|nine|dieci|ten)'
        def countMatcher = (text =~ /(?i)\b(?:con|with)\s+(${countTokenPattern})\s+(?:budget\s+)?(?:items?|item|riga|righe|linea|linee)\b/)
        if (countMatcher.find()) expectedCount = safeCountInteger(countMatcher.group(1))
        List patterns = [
                ~/(?is)\b(?:con|with)\s+(?:${countTokenPattern})\s+(?:budget\s+)?(?:items?|item|riga|righe|linea|linee)\s+(.+?)(?=(?:\bper\s+(?:organization|organizzazione|company|azienda)\b|\banno\b|\byear\b|\btype\b|\btipo\b|$))/,
                /(?is)\b(?:items?|item|riga|righe|linea|linee)\s*:\s*(.+?)(?=(?:\bper\s+(?:organization|organizzazione|company|azienda)\b|\banno\b|\byear\b|\btype\b|\btipo\b|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                String rawItems = cleanupPromptSegment(matcher.group(1))
                if (rawItems) {
                    itemTexts = splitNamedItems(rawItems, expectedCount)
                    if (itemTexts.size() <= 1) itemTexts = splitAmountBearingItems(rawItems)
                    break
                }
            }
        }
        if (!itemTexts) return []
        return itemTexts.collect { String itemText -> buildBudgetItemEntry(itemText) }.findAll { it } as List<Map>
    }

    protected static Map extractOrderHierarchyPlan(String text) {
        if (!text) return [:]
        List<Map> parts = []
        List<Map> defaultItems = extractOrderItemsFromText(text)
        if (defaultItems) parts.add([label:'Part 1', items:defaultItems])
        return [parts:parts]
    }

    protected static List<Map> extractOrderItemsFromText(String text) {
        if (!text) return []
        List<Map> items = []
        String normalized = normalizePromptWhitespace(text)
        List<String> explicitProductSegments = []
        def explicitMatcher = (normalized =~ /(?is)\b(?:prodotto|product|prodotti|products)\b\s*[:#-]?\s*[A-Z0-9_.-]+.*?(?=(?:\b(?:prodotto|product|prodotti|products)\b\s*[:#-]?\s*[A-Z0-9_.-]+)|$)/)
        while (explicitMatcher.find()) {
            String explicitSegment = cleanupPromptSegment(explicitMatcher.group(0))
            if (explicitSegment) explicitProductSegments.add(explicitSegment)
        }
        List<String> segments = explicitProductSegments ?: (normalized.split(/(?i)\s+e\s+/) as List<String>)
        segments.each { String segment ->
            Map item = buildOrderItemEntry(segment)
            if (item) items.add(item)
        }
        if (!items) {
            Map singleItem = buildOrderItemEntry(normalized)
            if (singleItem) items.add(singleItem)
        }
        return items
    }

    protected static Map buildOrderItemEntry(String text) {
        if (!text) return null
        String segment = cleanupPromptSegment(text)
        List patterns = [
                /(?i)\b(?:prodotto|product|prodotti|products)\b\s*[:#-]?\s*([A-Z0-9_.-]+)/,
                /\b([A-Z0-9]+(?:[_.-][A-Z0-9]+)+|[A-Z]{2,}[0-9][A-Z0-9_.-]*)\b/
        ]
        String productToken = null
        for (pattern in patterns) {
            def matcher = (segment =~ pattern)
            if (matcher.find()) {
                productToken = matcher.group(1)
                break
            }
        }
        if (!productToken) return null
        BigDecimal quantity = null
        def qtyMatcher = (segment =~ /(?i)\b(?:qty|quantity|quantit[àa])\s*([0-9]+(?:[.,][0-9]+)?)/)
        if (qtyMatcher.find()) {
            try { quantity = new BigDecimal(qtyMatcher.group(1).replace(',', '.')) } catch (Throwable ignored) { }
        }
        String requiredByDate = extractDateTextByCue(segment, ['data consegna', 'delivery date'])
        return collectNonNullEntries([
                productToken : productToken,
                quantity : quantity ?: 1,
                requiredByDate : requiredByDate
        ])
    }

    protected static Map buildBudgetItemEntry(String itemText) {
        if (!itemText) return null
        String workingText = cleanupPromptSegment(itemText)
        List<Map> details = extractBudgetItemDetailPlan(workingText)
        String purpose = workingText
        List detailPatterns = [
                /(?is)\b(?:con\s+dettagli?|with\s+details?)\b.+$/,
                /(?is)\b(?:dettagli?|details?)\s*:\s*.+$/
        ]
        for (pattern in detailPatterns) purpose = cleanupPromptSegment(purpose.replaceFirst(pattern, ''))
        String glAccountId = null
        def glMatcher = (purpose =~ /(?i)\b(?:gl\s*account|conto(?:\s+contabile)?|account(?:\s+code)?)\b(?:\s+(?:code|codice))?\s*[:#-]?\s*([A-Z0-9_.-]+)/)
        if (glMatcher.find()) {
            glAccountId = glMatcher.group(1)
            purpose = cleanupPromptSegment(purpose.replace(glMatcher.group(0), ''))
        }

        BigDecimal amount = null
        def amountMatcher = (purpose =~ /(?i)\b(?:amount|importo|for|da|di)\s*([0-9]+(?:[.,][0-9]+)?)\b/)
        if (amountMatcher.find()) {
            try { amount = new BigDecimal(amountMatcher.group(1).replace(',', '.')) } catch (Throwable ignored) { }
            purpose = cleanupPromptSegment(purpose.replace(amountMatcher.group(0), ''))
        }

        String subTimePeriodId = null
        def subPeriodMatcher = (itemText =~ /(?i)\b(?:periodo|period|sub-?period)\s+([A-Z0-9_.-]+)/)
        if (subPeriodMatcher.find()) subTimePeriodId = cleanupPromptSegment(subPeriodMatcher.group(1))

        if (!purpose && glAccountId) purpose = "Budget item ${glAccountId}"
        if (!purpose) return null
        return collectNonNullEntries([
                purpose : purpose,
                amount : amount,
                glAccountId : glAccountId,
                subTimePeriodId : subTimePeriodId,
                details : details
        ])
    }

    protected static List<Map> extractBudgetItemDetailPlan(String text) {
        if (!text) return []
        String rawDetails = null
        List patterns = [
                /(?is)\b(?:con\s+dettagli?|with\s+details?)\b\s+(.+)$/,
                /(?is)\b(?:dettagli?|details?)\s*:\s*(.+)$/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                rawDetails = cleanupPromptSegment(matcher.group(1))
                if (rawDetails) break
            }
        }
        if (!rawDetails) return []
        List<String> detailTexts = splitNamedItems(rawDetails, null)
        return detailTexts.collect { String detailText -> buildBudgetItemDetailEntry(detailText) }.findAll { it } as List<Map>
    }

    protected static Map buildBudgetItemDetailEntry(String detailText) {
        if (!detailText) return null
        String workingText = cleanupPromptSegment(detailText)
        Map detail = [:]

        def amountMatcher = (workingText =~ /(?i)\b(?:amount|importo|for|da|di)?\s*([0-9]+(?:[.,][0-9]+)?)\b/)
        if (amountMatcher.find()) {
            try { detail.amount = new BigDecimal(amountMatcher.group(1).replace(',', '.')) } catch (Throwable ignored) { }
            workingText = cleanupPromptSegment(workingText.replace(amountMatcher.group(0), ''))
        }

        def quantityMatcher = (workingText =~ /(?i)\b(?:qty|quantity|quantit[àa])\s*([0-9]+(?:[.,][0-9]+)?)\b/)
        if (quantityMatcher.find()) {
            try { detail.quantity = new BigDecimal(quantityMatcher.group(1).replace(',', '.')) } catch (Throwable ignored) { }
            workingText = cleanupPromptSegment(workingText.replace(quantityMatcher.group(0), ''))
        }

        List assetPatterns = [
                /(?i)\basset\s+([A-Z0-9_.-]+)/,
                /(?i)\bcespite\s+([A-Z0-9_.-]+)/
        ]
        for (pattern in assetPatterns) {
            def matcher = (workingText =~ pattern)
            if (matcher.find()) {
                detail.assetToken = cleanupPromptSegment(matcher.group(1))
                workingText = cleanupPromptSegment(workingText.replace(matcher.group(0), ''))
                break
            }
        }

        List facilityPatterns = [
                /(?i)\b(?:facility|magazzino|warehouse)\s+([^,.\n]+?)(?=(?:\s+asset\b|\s+product\b|\s+prodotto\b|$))/,
                /(?i)\b(?:in|su)\s+([^,.\n]+?)(?=(?:\s+asset\b|\s+product\b|\s+prodotto\b|$))/
        ]
        for (pattern in facilityPatterns) {
            def matcher = (workingText =~ pattern)
            if (matcher.find()) {
                detail.facilityName = cleanupPromptSegment(matcher.group(1))
                workingText = cleanupPromptSegment(workingText.replace(matcher.group(0), ''))
                break
            }
        }

        List productPatterns = [
                /(?i)\b(?:product|prodotto)\s+([A-Z0-9_.-]+)/,
                /(?i)\bsku\s+([A-Z0-9_.-]+)/
        ]
        for (pattern in productPatterns) {
            def matcher = (workingText =~ pattern)
            if (matcher.find()) {
                detail.productToken = cleanupPromptSegment(matcher.group(1))
                workingText = cleanupPromptSegment(workingText.replace(matcher.group(0), ''))
                break
            }
        }

        return collectNonNullEntries(detail)
    }

    protected static Map buildRequestItemEntry(String itemText) {
        if (!itemText) return null
        String description = cleanupPromptSegment(itemText)
        BigDecimal quantity = null
        List quantityPatterns = [
                /(?i)\b(?:qty|quantity|quantit[àa])\s*([0-9]+(?:[.,][0-9]+)?)/,
                /(?i)\bx\s*([0-9]+(?:[.,][0-9]+)?)$/
        ]
        for (pattern in quantityPatterns) {
            def matcher = (description =~ pattern)
            if (matcher.find()) {
                try { quantity = new BigDecimal(matcher.group(1).replace(',', '.')) } catch (Throwable ignored) { }
                description = cleanupPromptSegment(description.replace(matcher.group(0), ''))
                break
            }
        }
        if (!description) return null
        return collectNonNullEntries([
                description : description,
                quantity : quantity
        ])
    }

    protected static Map inferAssetMoveStatusParameters(ExecutionContext ec, String queryText, Map mergedParameters = null) {
        Map parameters = (mergedParameters instanceof Map) ? new LinkedHashMap(mergedParameters as Map) : [:]
        String normalizedText = normalizePromptWhitespace(queryText)

        if (!parameters.assetId) {
            def assetMatcher = (normalizedText =~ /(?i)\basset\s+([A-Z0-9_:-]+)\b/)
            if (assetMatcher.find()) parameters.assetId = resolveAssetIdByToken(ec, assetMatcher.group(1)) ?: assetMatcher.group(1)
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
            String lowerText = normalizedText.toLowerCase()
            String statusDescription = null
            if (lowerText.contains('on hold')) {
                statusDescription = 'On Hold'
            }
            List patterns = [
                /(?i)\bstato\s+([^,.\n]+?)(?=(?:,|\.|$))/,
                /(?i)\bstatus\s+([^,.\n]+?)(?=(?:,|\.|$))/,
                /(?i)\bon\s+hold\b/
            ]
            if (!statusDescription) {
                for (pattern in patterns) {
                    def matcher = (normalizedText =~ pattern)
                    if (matcher.find()) {
                        if (pattern.toString().toLowerCase().contains('on\\s+hold')) statusDescription = 'On Hold'
                        else statusDescription = cleanupPromptSegment(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group(0))
                        break
                    }
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
                def asset = ec.entity.find('mantle.product.asset.Asset').disableAuthz()
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
            def find = ec.entity.find('moqui.basic.Enumeration').disableAuthz()
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
        String searchedPersonId = resolveIdByLookupSpec(ec, 'person', fullName)
        if (searchedPersonId) return searchedPersonId
        try {
            def person = ec.entity.find('mantle.party.Person').disableAuthz()
                    .condition('firstName', firstName)
                    .condition('lastName', lastName)
                    .one()
            if (person?.partyId) return person.partyId as String
        } catch (Throwable ignored) { }
        return null
    }

    protected static String resolvePartyIdByDisplayName(ExecutionContext ec, String displayName) {
        if (!ec || !displayName) return null
        String normalized = normalizePromptWhitespace(displayName)
        String searchedPartyId = resolveIdByLookupSpec(ec, 'party', normalized)
        if (searchedPartyId) return searchedPartyId
        return resolvePartyIdByPersonName(ec, displayName)
    }

    protected static String resolveRoleTypeIdByDescription(ExecutionContext ec, String description) {
        if (!description) return null
        try {
            def roleType = ec?.entity?.find('mantle.party.RoleType')?.disableAuthz()
                    ?.condition('description', description)
                    ?.one()
            if (roleType?.roleTypeId) return roleType.roleTypeId as String
        } catch (Throwable ignored) { }
        if (description.equalsIgnoreCase('Project Manager')) return 'ProjectManager'
        return null
    }

    protected static String resolveFiscalYearTimePeriodId(ExecutionContext ec, String organizationPartyId, Integer yearNumber) {
        if (!ec || yearNumber == null) return null
        try {
            def find = ec.entity.find('mantle.party.time.TimePeriod').disableAuthz()
                    .condition('timePeriodTypeId', 'FiscalYear')
            if (organizationPartyId) find.condition('partyId', organizationPartyId)
            List periods = find.list()
            def exact = periods.find { ev ->
                String periodName = (ev.periodName ?: '') as String
                Integer fromYear = ev.fromDate ? ev.fromDate.toCalendar().get(Calendar.YEAR) : null
                Integer thruYear = ev.thruDate ? ev.thruDate.toCalendar().get(Calendar.YEAR) : null
                periodName.contains(yearNumber.toString()) || fromYear == yearNumber || thruYear == yearNumber
            }
            if (exact?.timePeriodId) return exact.timePeriodId as String
        } catch (Throwable ignored) { }
        if (organizationPartyId) {
            try {
                Calendar yearCal = Calendar.getInstance()
                yearCal.clear()
                yearCal.set(Calendar.YEAR, yearNumber)
                yearCal.set(Calendar.MONTH, Calendar.JANUARY)
                yearCal.set(Calendar.DAY_OF_MONTH, 1)
                Map createOut = ec.service.sync().name('mantle.party.TimeServices.create#TimePeriod').parameters([
                        partyId : organizationPartyId,
                        timePeriodTypeId : 'FiscalYear',
                        fromDate : new java.sql.Date(yearCal.getTimeInMillis())
                ]).call()
                if (createOut?.timePeriodId) return createOut.timePeriodId as String
            } catch (Throwable ignored) { }
        }
        try {
            List periods = ec.entity.find('mantle.party.time.TimePeriod').disableAuthz()
                    .condition('timePeriodTypeId', 'FiscalYear')
                    .list()
            def fallback = periods.find { ev ->
                String periodName = (ev.periodName ?: '') as String
                Integer fromYear = ev.fromDate ? ev.fromDate.toCalendar().get(Calendar.YEAR) : null
                Integer thruYear = ev.thruDate ? ev.thruDate.toCalendar().get(Calendar.YEAR) : null
                periodName.contains(yearNumber.toString()) || fromYear == yearNumber || thruYear == yearNumber
            }
            if (fallback?.timePeriodId) return fallback.timePeriodId as String
        } catch (Throwable ignored) { }
        return null
    }

    protected static Map resolveDefaultSalesOrderContext(ExecutionContext ec, Map orderParams = null) {
        Map defaults = [:]
        if (!ec) return defaults

        String preferredStoreId = null
        try { preferredStoreId = ec.user?.getPreference('OrderSalesStoreDefault') as String } catch (Throwable ignored) { }
        String activeOrgId = resolveDefaultPartyContextValue(ec, orderParams ?: [:], 'organizationPartyId')
        String customerPartyId = (orderParams?.customerPartyId ?: null) as String
        String customerOwnerPartyId = null
        if (customerPartyId) {
            try {
                def customerParty = ec.entity.find('mantle.party.Party').disableAuthz()
                        .condition('partyId', customerPartyId)
                        .one()
                customerOwnerPartyId = customerParty?.ownerPartyId as String
            } catch (Throwable ignored) { }
        }

        try {
            List storeList = ec.entity.find('mantle.product.store.ProductStore').disableAuthz().list()
            def matchedStore = null
            if (preferredStoreId) {
                matchedStore = storeList.find { ev -> (ev.productStoreId as String)?.equalsIgnoreCase(preferredStoreId) }
            }
            if (!matchedStore && customerOwnerPartyId) {
                matchedStore = storeList.find { ev -> (ev.organizationPartyId as String) == customerOwnerPartyId }
            }
            if (!matchedStore && activeOrgId) {
                matchedStore = storeList.find { ev -> (ev.organizationPartyId as String) == activeOrgId }
            }
            if (!matchedStore && storeList.size() == 1) matchedStore = storeList[0]
            if (!matchedStore && customerPartyId) {
                matchedStore = storeList.find { ev -> (ev.organizationPartyId as String) == customerPartyId }
            }
            if (matchedStore) {
                defaults.productStoreId = matchedStore.productStoreId
                defaults.currencyUomId = matchedStore.defaultCurrencyUomId
                defaults.salesChannelEnumId = matchedStore.defaultSalesChannelEnumId ?: 'ScWeb'
                defaults.organizationPartyId = matchedStore.organizationPartyId
                defaults.facilityId = matchedStore.inventoryFacilityId
            }
        } catch (Throwable ignored) { }

        return collectNonNullEntries(defaults)
    }

    protected static String canonicalLookupToken(String text) {
        if (!text) return null
        String normalized = normalizePromptWhitespace(text).toLowerCase()
        String canonical = normalized.replaceAll(/[^a-z0-9]+/, '')
        return canonical ?: null
    }

    protected static boolean isLikelyStructuredIdToken(String token) {
        if (!token) return false
        String normalized = normalizePromptWhitespace(token)
        if (!normalized) return false
        if (normalized ==~ /[A-Z0-9]+(?:[_.-][A-Z0-9]+)+/) return true
        if (normalized ==~ /[A-Z]{2,}[0-9][A-Z0-9_.-]*/) return true
        return false
    }

    protected static String resolveGlAccountIdByToken(ExecutionContext ec, String token) {
        if (!ec || !token) return null
        String normalized = token.trim()
        String searchedGlAccountId = resolveIdByLookupSpec(ec, 'glAccount', normalized)
        if (searchedGlAccountId) return searchedGlAccountId
        return null
    }

    protected static String resolveBudgetIdByDescription(ExecutionContext ec, String budgetDescription) {
        if (!ec || !budgetDescription) return null
        String normalized = normalizePromptWhitespace(budgetDescription)
        return resolveIdByLookupSpec(ec, 'budget', normalized)
    }

    protected static Map resolveBudgetContextByDescription(ExecutionContext ec, String budgetDescription) {
        if (!ec || !budgetDescription) return [:]
        Map budgetContext = [:]
        try {
            String budgetId = resolveBudgetIdByDescription(ec, budgetDescription)
            if (budgetId) budgetContext.budgetId = budgetId
            def budget = null
            if (budgetId) {
                budget = ec.entity.find('mantle.other.budget.BudgetAndTimePeriod').disableAuthz()
                        .condition('budgetId', budgetId)
                        .one()
            }
            if (!budget) {
                String normalized = normalizePromptWhitespace(budgetDescription)
                budget = ec.entity.find('mantle.other.budget.BudgetAndTimePeriod').disableAuthz()
                        .condition('description', normalized)
                        .one()
            }
            if (budget?.budgetId) budgetContext.budgetId = budget.budgetId as String
            if (budget?.partyId) budgetContext.organizationPartyId = budget.partyId as String
            if (budget?.timePeriodId) budgetContext.timePeriodId = budget.timePeriodId as String
            if (budget?.budgetItemSeqId) budgetContext.budgetItemSeqId = budget.budgetItemSeqId as String
            if (budget?.description) budgetContext.budgetDescription = budget.description as String
        } catch (Throwable ignored) { }
        return collectNonNullEntries(budgetContext)
    }

    protected static String resolveFacilityIdByName(ExecutionContext ec, String facilityName) {
        if (!ec || !facilityName) return null
        String normalized = normalizePromptWhitespace(facilityName)
        return resolveIdByLookupSpec(ec, 'facility', normalized)
    }

    protected static String resolveAssetIdByToken(ExecutionContext ec, String token) {
        if (!ec || !token) return null
        String normalized = token.trim()
        return resolveIdByLookupSpec(ec, 'asset', normalized)
    }

    protected static String resolveProductIdByToken(ExecutionContext ec, String token) {
        if (!ec || !token) return null
        String normalized = token.trim()
        return resolveIdByLookupSpec(ec, 'product', normalized)
    }

    protected static String resolveEmplPositionClassIdByToken(ExecutionContext ec, String token) {
        if (!ec || !token) return null
        String normalized = normalizePromptWhitespace(token)
        return resolveIdByLookupSpec(ec, 'emplPositionClass', normalized)
    }

    protected static String resolveEmplPositionIdByToken(ExecutionContext ec, String token) {
        if (!ec || !token) return null
        String normalized = normalizePromptWhitespace(token)
        return resolveIdByLookupSpec(ec, 'emplPosition', normalized)
    }

    protected static boolean looksLikeEmploymentPositionPrompt(String queryText) {
        if (!queryText) return false
        String normalizedText = normalizePromptWhitespace(queryText).toLowerCase()
        boolean hasPosition = normalizedText.contains('position') || normalizedText.contains('posizione') ||
                normalizedText.contains('ruol') || normalizedText.contains('job') ||
                normalizedText.contains('impiegat') || normalizedText.contains('dipendent') ||
                normalizedText.contains('emplposition')
        boolean hasBudget = normalizedText.contains('budget')
        boolean hasEmployment = normalizedText.contains('employee') || normalizedText.contains('impiegato') ||
                normalizedText.contains('dipendente') || normalizedText.contains('collaborator') ||
                normalizedText.contains('assegn')
        return hasPosition && (hasBudget || hasEmployment)
    }

    protected static String extractPositionDescription(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\bdescrizione\s+([^,.\n]+?)(?=(?:\s+posizione\b|\s+stato\b|\s+status\b|\s+budget\b|\s+connessa\b|\s+collegata\b|,|\.|$))/,
                /(?i)\bposition\s+([^,.\n]+?)(?=(?:\s+stato\b|\s+status\b|\s+budget\b|\s+connessa\b|,|\.|$))/,
                /(?i)\bposizione\s+([^,.\n]+?)(?=(?:\s+stato\b|\s+status\b|\s+budget\b|\s+connessa\b|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        }
        return null
    }

    protected static String extractFallbackPositionLabel(String text) {
        if (!text) return null
        def matcher = (text =~ /(?i)\b(?:posizione|position)\s+([^,.\n]+?)(?=(?:\s+stato\b|\s+status\b|\s+budget\b|\s+connessa\b|,|\.|$))/)
        if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        return null
    }

    protected static String extractPositionClassDescription(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\b(?:posizione|position)\s+([^,.\n]+?)(?=(?:\s+stato\b|\s+status\b|\s+budget\b|\s+connessa\b|,|\.|$))/,
                /(?i)\b(?:position\s+class|classe\s+posizione)\s+([^,.\n]+?)(?=(?:\s+stato\b|\s+status\b|\s+budget\b|\s+connessa\b|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) return cleanupPromptSegment(matcher.group(1))
        }
        return null
    }

    protected static String extractEmployeePersonName(String text) {
        if (!text) return null
        List<String> patterns = [
                /(?i)\b(?:assegna(?:rla|rlo|rli|rle)?\s+al|assign(?:\s+it)?\s+to|assegna\s+a|al\s+nuovo\s+(?:impiegato|dipendente|employee|collaboratore|persona)|a\s+nuovo\s+(?:impiegato|dipendente|employee|collaboratore|persona)|new\s+employee|nuovo\s+(?:impiegato|dipendente|employee|collaboratore|persona))\s+([^,.\n]+?)(?=(?:\s+con\b|\s+with\b|,|\.|$))/,
                /(?i)\b(?:impiegato|dipendente|employee|persona|person)\s+([^,.\n]+?)(?=(?:\s+con\b|\s+with\b|,|\.|$))/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                String candidate = cleanupPromptSegment(matcher.group(1))
                candidate = candidate?.replaceAll(/(?i)^(?:nuovo|new)\s+/, '')?.trim()
                candidate = candidate?.replaceAll(/(?i)^(?:impiegato|dipendente|employee|persona|person|collaboratore)\s+/, '')?.trim()
                if (candidate) return candidate
            }
        }
        return null
    }

    protected static String inferEmplPositionStatusId(String text) {
        String normalized = normalizePromptWhitespace(text).toLowerCase()
        if (!normalized) return null
        if (normalized.contains('open') || normalized.contains('aperto') || normalized.contains('active')) return 'EmpsActive'
        if (normalized.contains('planned') || normalized.contains('pianificat')) return 'EmpsPlanned'
        if (normalized.contains('closed') || normalized.contains('chiuso') || normalized.contains('inactive')) return 'EmpsInactive'
        return null
    }

    protected static Integer extractStandardHoursPerWeek(String text) {
        if (!text) return null
        List patterns = [
                /(?i)\b(?:ore|ora|hours?)\s+settimanali\s+standard\b(?:[^0-9]{0,12})?([0-9]+)\b/,
                /(?i)\b(?:standard\s+hours?\s+per\s+week|hours?\s+per\s+week)\s*([0-9]+)\b/,
                /(?i)\b([0-9]+)\s*(?:ore|ora|hours?)\s+settimanali\s+standard\b/,
                /(?i)\b([0-9]+)\s*(?:standard\s+hours?\s+per\s+week|hours?\s+per\s+week)\b/
        ]
        for (pattern in patterns) {
            def matcher = (text =~ pattern)
            if (matcher.find()) {
                try {
                    return Integer.valueOf(matcher.group(1))
                } catch (Throwable ignored) { }
            }
        }
        return null
    }

    protected static String resolveProductIdFromSpec(ExecutionContext ec, Map itemSpec) {
        if (!(itemSpec instanceof Map)) return null
        String productToken = (itemSpec.productId ?: itemSpec.pseudoId ?: itemSpec.productToken ?: null) as String
        if (!productToken) return null
        String resolvedProductId = resolveProductIdByToken(ec, productToken)
        if (resolvedProductId) return resolvedProductId
        String cleanedToken = cleanupPromptSegment(productToken)
        return isLikelyStructuredIdToken(cleanedToken) ? cleanedToken : null
    }

    protected static String resolveIdByLookupSpec(ExecutionContext ec, String lookupKey, String token) {
        if (!ec || !lookupKey || !token) return null
        try {
            Map<String, Object> spec = (DATA_DOCUMENT_LOOKUP_SPECS[lookupKey] ?: [:]) as Map<String, Object>
            if (!spec) return null
            List<Map> documentList = searchDataDocumentLookup(ec, spec, token)
            if (documentList) {
                Map bestDocument = selectBestDataDocumentLookupHit(documentList, spec, token)
                String idField = spec.idField as String
                Object idValue = bestDocument?.get(idField)
                if (idValue != null) return idValue as String
            }
            return resolveIdByEntityExactFallback(ec, spec, token)
        } catch (Throwable t) {
            ec.logger.warn("Lookup failed for ${lookupKey} [${abbreviate(token, 120)}]: ${t.message}")
            return null
        } finally {
            try { ec.message.clearErrors() } catch (Throwable ignored) { }
        }
    }

    protected static String resolveIdByEntityExactFallback(ExecutionContext ec, Map<String, Object> spec, String token) {
        if (!ec || !spec || !token) return null
        String entityName = spec.fallbackEntityName as String
        String idField = spec.idField as String
        List<String> fallbackFields = ((spec.fallbackExactFields ?: []) as List<String>).findAll { it } as List<String>
        if (!entityName || !idField || !fallbackFields) return null

        List<String> lookupValues = buildExactLookupVariants(token)
        for (String fieldName in fallbackFields) {
            for (String lookupValue in lookupValues) {
                try {
                    def exact = ec.entity.find(entityName).disableAuthz()
                            .condition(fieldName, lookupValue)
                            .one()
                    Object idValue = exact?.get(idField)
                    if (idValue != null) return idValue as String
                } catch (Throwable ignored) { }
            }
        }
        return null
    }

    protected static List<String> buildExactLookupVariants(String token) {
        String normalized = normalizePromptWhitespace(token)
        String canonical = canonicalLookupToken(normalized)
        List<String> variants = [normalized]
        if (canonical && canonical != normalized) variants.add(canonical)
        return variants.findAll { it }?.unique() ?: []
    }

    protected static List<Map> searchDataDocumentLookup(ExecutionContext ec, Map<String, Object> spec, String token) {
        if (!ec || !spec || !token) return []
        String indexName = resolveLookupIndexName(ec, spec)
        String documentType = spec.documentType as String
        if (!indexName || !documentType) return []

        String queryString = buildDataDocumentLookupQuery(spec, token)
        if (!queryString) return []
        try {
            return (ec.transaction.runRequireNew(null, 'Agent DataDocument lookup failed', {
                Map searchResult = ec.service.sync()
                        .name('org.moqui.search.SearchServices.search#DataDocuments')
                        .ignoreTransaction(true)
                        .ignorePreviousError(true)
                        .parameters([
                                indexName : indexName,
                                documentType : documentType,
                                queryString : queryString,
                                pageIndex : 0,
                                pageSize : 10,
                                flattenDocument : true
                        ]).call()
                return (searchResult?.documentList ?: []) as List<Map>
            }) ?: []) as List<Map>
        } catch (Throwable ignored) {
            ec.message.clearErrors()
            return []
        }
    }

    protected static String resolveLookupIndexName(ExecutionContext ec, Map<String, Object> spec) {
        if (!ec || !spec) return null
        def elasticClient
        try {
            elasticClient = ec.factory?.elastic?.getClient('default')
        } catch (Throwable ignored) {
            return null
        }
        if (elasticClient == null) return null

        List<String> candidateNames = []
        String configuredIndexName = spec.indexName as String
        String documentType = spec.documentType as String
        if (configuredIndexName) candidateNames.add(configuredIndexName)
        if (documentType) {
            String derivedIndexName = deriveIndexNameFromDocumentType(documentType)
            if (derivedIndexName) candidateNames.add(derivedIndexName)
        }

        for (String candidateName in candidateNames.findAll { it }.unique()) {
            try {
                if (elasticClient.indexExists(candidateName)) return candidateName
            } catch (Throwable ignored) { }
        }
        return null
    }

    protected static String deriveIndexNameFromDocumentType(String documentType) {
        if (!documentType) return null
        String normalized = documentType
                .replaceAll(/([a-z0-9])([A-Z])/, '$1_$2')
                .replaceAll(/([A-Z]+)([A-Z][a-z])/, '$1_$2')
                .toLowerCase()
        return normalized ?: null
    }

    protected static String buildDataDocumentLookupQuery(Map<String, Object> spec, String token) {
        String normalized = normalizePromptWhitespace(token)
        String canonical = canonicalLookupToken(normalized)
        List<String> tokens = tokenizeSearchText(normalized).findAll { it } as List<String>
        if (!normalized) return null

        List<String> clauses = []
        ((spec.exactFields ?: []) as List<String>).each { String fieldName ->
            if (fieldName) clauses.add("${fieldName}:${toLucenePhrase(normalized)}")
        }
        if (canonical && canonical != normalized) {
            ((spec.canonicalFields ?: []) as List<String>).each { String fieldName ->
                if (fieldName) clauses.add("${fieldName}:${toLucenePhrase(canonical)}")
            }
        }
        clauses.add(toLucenePhrase(normalized))
        if (canonical && canonical != normalized) clauses.add(toLucenePhrase(canonical))
        if (tokens.size() > 1) {
            String andClause = tokens.collect { toLucenePhrase(it) }.findAll { it }.join(' AND ')
            if (andClause) clauses.add("(${andClause})")
        }
        return clauses.findAll { it }.unique().join(' OR ')
    }

    protected static Map selectBestDataDocumentLookupHit(List<Map> documentList, Map<String, Object> spec, String token) {
        if (!(documentList instanceof List) || documentList.isEmpty()) return [:]
        String normalized = normalizePromptWhitespace(token)
        String canonical = canonicalLookupToken(normalized)
        return (documentList as List<Map>).max { Map document ->
            scoreDataDocumentLookupHit(document, spec, normalized, canonical)
        } ?: [:]
    }

    protected static Integer scoreDataDocumentLookupHit(Map document, Map<String, Object> spec, String normalized, String canonical) {
        if (!(document instanceof Map)) return 0
        int score = 0
        ((spec.exactFields ?: []) as List<String>).each { String fieldName ->
            String fieldValue = document[fieldName] != null ? document[fieldName].toString() : null
            if (fieldValue && fieldValue.equalsIgnoreCase(normalized)) score = Math.max(score, 100)
        }
        ((spec.canonicalFields ?: []) as List<String>).each { String fieldName ->
            String fieldValue = document[fieldName] != null ? document[fieldName].toString() : null
            if (fieldValue && canonicalLookupToken(fieldValue) == canonical) score = Math.max(score, 90)
        }
        ((spec.textFields ?: []) as List<String>).each { String fieldName ->
            String fieldValue = document[fieldName] != null ? document[fieldName].toString() : null
            if (!fieldValue) return
            String lowered = fieldValue.toLowerCase()
            String normalizedLower = normalized?.toLowerCase()
            if (normalizedLower && lowered.contains(normalizedLower)) score = Math.max(score, 70)
            String fieldCanonical = canonicalLookupToken(fieldValue)
            if (canonical && fieldCanonical && fieldCanonical.contains(canonical)) score = Math.max(score, 75)
        }
        if (score == 0) score = 10
        return score
    }

    protected static String toLucenePhrase(String text) {
        String escaped = escapeLuceneQueryValue(text)
        return escaped ? '"' + escaped + '"' : null
    }

    protected static String escapeLuceneQueryValue(String text) {
        if (!text) return null
        StringBuilder sb = new StringBuilder()
        for (char ch : text.toCharArray()) {
            if (ch in ['\\' as char, '+' as char, '-' as char, '!' as char, '(' as char, ')' as char,
                       '{' as char, '}' as char, '[' as char, ']' as char, '^' as char, '"' as char,
                       '~' as char, '*' as char, '?' as char, ':' as char, '/' as char, '&' as char,
                       '|' as char]) sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    protected static String resolveStatusIdByDescription(ExecutionContext ec, String description, String statusPrefix = null) {
        if (!ec || !description) return null
        String normalized = normalizePromptWhitespace(description)
        try {
            List statusList = ec.entity.find('moqui.basic.StatusItem').disableAuthz().list()
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

    static String canonicalizeEntityName(ExecutionContext ec, String entityName) {
        if (!ec || !entityName) return null
        String normalized = entityName.trim()
        if (!normalized) return null

        Map<String, String> explicitAliases = [
                'mantle.account.budget.Budget' : 'mantle.other.budget.Budget',
                'mantle.account.budget.BudgetItem' : 'mantle.other.budget.BudgetItem',
                'mantle.account.budget.BudgetItemDetail' : 'mantle.other.budget.BudgetItemDetail'
        ]
        if (explicitAliases.containsKey(normalized)) normalized = explicitAliases[normalized]

        if (ec.entity.isEntityDefined(normalized)) return normalized

        String simpleName = normalized.contains('.') ? normalized.tokenize('.').last() : normalized
        if (!simpleName) return null

        List<String> exactCandidates = []
        List<String> suffixCandidates = []
        for (String candidateName in (ec.entity.getAllEntityNames() ?: [])) {
            if (!candidateName) continue
            String candidateSimpleName = candidateName.contains('.') ? candidateName.tokenize('.').last() : candidateName
            if (candidateSimpleName.equalsIgnoreCase(simpleName)) exactCandidates.add(candidateName)
            else if (candidateName.toLowerCase().endsWith('.' + simpleName.toLowerCase())) suffixCandidates.add(candidateName)
        }

        List<String> candidates = exactCandidates ?: suffixCandidates
        if (candidates.size() == 1) return candidates.first()
        if (!candidates) return null

        String normalizedLower = normalized.toLowerCase()
        String preferred = candidates.find { String candidateName ->
            candidateName.toLowerCase().contains(normalizedLower) || normalizedLower.contains(candidateName.toLowerCase())
        }
        return preferred ?: candidates.first()
    }

    static Map canonicalizeEntityQuery(ExecutionContext ec, String entityName, Map conditions = null, List fieldList = null) {
        String canonicalEntityName = canonicalizeEntityName(ec, entityName) ?: entityName
        Map normalizedConditions = new LinkedHashMap((conditions ?: [:]) as Map)
        List normalizedFieldList = fieldList ? new ArrayList(fieldList) : null
        Set<String> queryFieldNames = ([] as Set<String>)
        queryFieldNames.addAll((normalizedConditions.keySet() ?: []).collect { it?.toString() }.findAll { it })
        if (normalizedFieldList) queryFieldNames.addAll(normalizedFieldList.collect { it?.toString() }.findAll { it })

        if (canonicalEntityName in ['mantle.party.Party', 'mantle.party.Person']) {
            boolean hasUserAccountFields = queryFieldNames.any { String fieldName -> fieldName in ['userId', 'username', 'userFullName', 'userDisabled', 'userTerminateDate', 'disabledDateTime', 'terminateDate'] }
            boolean hasPersonFields = queryFieldNames.any { String fieldName -> fieldName in ['firstName', 'middleName', 'lastName', 'birthDate', 'combinedName'] }
            boolean hasOrgFields = queryFieldNames.any { String fieldName -> fieldName in ['organizationName', 'organizationPartyId'] }
            boolean hasRoleFields = queryFieldNames.any { String fieldName -> fieldName in ['roleTypeId', 'role'] }

            if (hasUserAccountFields) {
                canonicalEntityName = 'mantle.party.PersonWithUserAccount'
            } else if (hasRoleFields) {
                canonicalEntityName = 'mantle.party.PartyDetailAndRole'
            } else if (hasPersonFields || hasOrgFields) {
                canonicalEntityName = 'mantle.party.PartyDetail'
            } else {
                canonicalEntityName = 'mantle.party.Party'
            }
        }

        Set<String> budgetLookupKeys = ['partyId', 'organizationPartyId', 'budgetName', 'periodName', 'fromDate', 'thruDate', 'timePeriodTypeId'] as Set<String>
        boolean needsBudgetTimeView =
                canonicalEntityName in ['mantle.other.budget.Budget', 'mantle.account.budget.Budget'] &&
                        ((!normalizedConditions.isEmpty() && normalizedConditions.keySet().any { String key -> budgetLookupKeys.contains(key) }) ||
                                ((normalizedFieldList ?: []).any { Object fieldName -> budgetLookupKeys.contains((fieldName ?: '').toString()) }))

        if (canonicalEntityName == 'mantle.other.budget.BudgetAndTimePeriod' || needsBudgetTimeView) {
            canonicalEntityName = 'mantle.other.budget.BudgetAndTimePeriod'
            normalizedConditions = canonicalizeBudgetLookupConditions(normalizedConditions)
            normalizedFieldList = canonicalizeBudgetLookupFieldList(normalizedFieldList)
        }

        return [entityName : canonicalEntityName, conditions : normalizedConditions, fieldList : normalizedFieldList]
    }

    protected static Map canonicalizeBudgetLookupConditions(Map conditions) {
        Map normalized = new LinkedHashMap((conditions ?: [:]) as Map)
        if (normalized.containsKey('budgetName') && !normalized.containsKey('description')) {
            normalized.description = normalized.remove('budgetName')
        }
        if (normalized.containsKey('organizationPartyId') && !normalized.containsKey('partyId')) {
            normalized.partyId = normalized.remove('organizationPartyId')
        }
        return normalized
    }

    protected static List canonicalizeBudgetLookupFieldList(List fieldList) {
        if (!fieldList) return fieldList
        return fieldList.collect { Object fieldNameObj ->
            String fieldName = (fieldNameObj ?: '').toString()
            if (fieldName == 'budgetName') return 'description'
            if (fieldName == 'organizationPartyId') return 'partyId'
            return fieldNameObj
        }
    }

    static Map resolveScreenActionArtifact(Map document) {
        List transitionNames = (document?.transitionNames instanceof List) ? (List) document.transitionNames : []
        String transitionName = transitionNames ? (transitionNames.first() as String) : null
        String sourceWidgetPath = (document?.sourceWidgetPath ?: '') as String
        String sourceScreenPath = (document?.sourceScreenPath ?: '') as String
        boolean looksLikeTransition = sourceWidgetPath.contains("/transition[") || transitionName
        if (!looksLikeTransition) return null

        String screenArtifactName = resolveScreenArtifactName(document)
        if (!screenArtifactName && !sourceScreenPath) return null

        return [
            executionChannel : 'screen_transition',
            artifactTypeEnumId : transitionName ? 'AT_XML_SCREEN_TRANS' : 'AT_XML_SCREEN',
            artifactName : transitionName && screenArtifactName ? "${screenArtifactName}/${transitionName}" : (screenArtifactName ?: sourceScreenPath),
            parentArtifactName : screenArtifactName ?: sourceScreenPath,
            alternateArtifactName : transitionName && sourceScreenPath ? "${sourceScreenPath}/${transitionName}" : null,
            alternateParentArtifactName : sourceScreenPath ?: null,
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
        if (parentArtifactName && checkArtifactAccess(ec, 'AT_XML_SCREEN', 'AUTHZA_VIEW', parentArtifactName)) return true

        String alternateArtifactName = (executableArtifact.alternateArtifactName ?: '') as String
        if (artifactTypeEnumId && alternateArtifactName &&
                checkArtifactAccess(ec, artifactTypeEnumId, executableArtifact.authzActionEnumId as String ?: 'AUTHZA_VIEW', alternateArtifactName)) {
            return true
        }

        String alternateParentArtifactName = (executableArtifact.alternateParentArtifactName ?: '') as String
        return alternateParentArtifactName ? checkArtifactAccess(ec, 'AT_XML_SCREEN', 'AUTHZA_VIEW', alternateParentArtifactName) : false
    }

    static Map resolveExecutableArtifact(Map document) {
        Map screenActionArtifact = resolveScreenActionArtifact(document)
        if (screenActionArtifact) return screenActionArtifact

        String serviceName = (document?.preferredService ?: document?.serviceName ?: '') as String
        if (serviceName) {
            return [
                executionChannel : document.executionChannel ?: 'service',
                artifactTypeEnumId : 'AT_SERVICE',
                artifactName : serviceName,
                authzActionEnumId : document.authzActionEnumId ?: getServiceAuthzActionEnumId(serviceName)
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
