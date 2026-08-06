package org.moqui.agent

import spock.lang.Specification
import spock.lang.Unroll

class AgentToolSupportPromptParsingSpec extends Specification {
    def "aggregate skill policy enables skill-driven aggregate path"() {
        given:
        Map selectedSkill = [
                skillId: 'moqui.aggregate.patterns',
                skillTypeEnumId: 'AGSKL_PATTERN',
                frontmatter: [
                        patterns: ['aggregate', 'root-child', 'workflow-boundary'],
                        fallbackMode: 'prompt_search'
                ]
        ]

        expect:
        AgentSkillSupport.shouldUseSkillDrivenAggregatePath(selectedSkill, 'Potresti creare un budget operativo per l\'anno 2027 per ZICORP con 2 righe associate ai conti contabili')
        AgentSkillSupport.resolveAggregateRootDocumentId('Potresti creare un budget operativo per l\'anno 2027 per ZICORP con 2 righe associate ai conti contabili') == 'agent-prompt://accounting/findbudget/createbudget'
    }

    def "business budget skill overrides generic aggregate guide"() {
        given:
        List<Map> ranked = AgentSkillSupport.rerankSkillCandidates([
                [
                        skillId: 'moqui.aggregate.patterns',
                        skillTypeEnumId: 'AGSKL_PATTERN',
                        title: 'Moqui Aggregate Pattern Guide',
                        description: 'Parametric guidance for recognizing root-child aggregates',
                        searchText: 'aggregate root-child workflow budget order project'
                ],
                [
                        skillId: 'mantle.budget-planning',
                        skillTypeEnumId: 'AGSKL_BUSINESS_PROCESS',
                        title: 'Budget Planning',
                        description: 'Create a budget root and its ordered budget items as a single rooted aggregate in Moqui.',
                        searchText: 'budget planning accounting budget budget item ZICORP gl account amount'
                ]
        ], 'Potresti creare un budget operativo per l\'anno 2027 per ZICORP con 4 righe associate ai conti contabili')

        expect:
        ranked[0].skillId == 'mantle.budget-planning'
    }

    def "preferred skill id is deterministic for common business prompts"() {
        expect:
        AgentSkillSupport.preferredSkillIdForPrompt('Potresti creare un budget operativo per il 2027 con 4 righe') == 'mantle.budget-planning'
        AgentSkillSupport.preferredSkillIdForPrompt('Potresti creare un ordine di vendita con due prodotti') == 'mantle.order-entry'
        AgentSkillSupport.preferredSkillIdForPrompt('Apri una richiesta di supporto urgente') == 'mantle.support-request'
    }

    def "skill root document id takes precedence when present"() {
        given:
        Map selectedSkill = [
                frontmatter: [
                        rootDocumentId: 'agent-prompt://accounting/findbudget/createbudget'
                ]
        ]

        expect:
        AgentSkillSupport.resolveAggregateRootDocumentId('qualunque prompt budget', selectedSkill) == 'agent-prompt://accounting/findbudget/createbudget'
    }

    def "non aggregate skill policy does not hijack prompt execution"() {
        given:
        Map selectedSkill = [
                skillId: 'moqui-agent.skill.registry',
                skillTypeEnumId: 'AGSKL_SYSTEM',
                frontmatter: [
                        patterns: ['registry', 'discovery']
                ]
        ]

        expect:
        !AgentSkillSupport.shouldUseSkillDrivenAggregatePath(selectedSkill, 'Potresti creare un budget operativo per l\'anno 2027 per ZICORP con 2 righe associate ai conti contabili')
    }

    def "detect employment position prompt"() {
        expect:
        AgentToolSupport.looksLikeEmploymentPositionPrompt('Potresti inserire una nuova posizione da impiegato con descrizione Solution Architect per reingegnerizzazione dei processi, posizione project manager, stato aperto, connessa al budget HR development, full time e possibilità di straordinario, ora settimanali standard 40?')
    }

    def "infer employment position plan for single-root HR request"() {
        when:
        def plan = AgentToolSupport.inferEmploymentPositionPlan(null, 'Potresti inserire una nuova posizione da impiegato, descrizione Solution Architect per reingegnerizzazione dei processi, posizione project manager, stato aperto, full time e possibilità di straordinario, ora settimanali standard 40?')

        then:
        plan.planType == 'same_subject_multi_action'
        plan.compositeType == 'employment_position_assignment'
        plan.actions*.actionName == ['createPosition', 'createEmployment']
        plan.actions[0].parameters.description == 'Solution Architect per reingegnerizzazione dei processi'
        plan.actions[0].parameters.statusId == 'EmpsActive'
        plan.actions[0].parameters.fullTimeFlag == 'Y'
        plan.actions[0].parameters.overtimeFlag == 'Y'
        plan.actions[0].parameters.standardHoursPerWeek == 40
    }

    def "extract budget description and person name"() {
        when:
        String budget = AgentToolSupport.extractBudgetDescription('... connessa al budget HR development, full time ...')
        String person = AgentToolSupport.extractEmployeePersonName('Puoi assegnarla al nuovo impiegato mario Rossi?')

        then:
        budget == 'HR development'
        person == 'mario Rossi'
    }

    def "sanitize person name candidate removes trailing technical marker"() {
        expect:
        AgentToolSupport.sanitizePersonNameCandidate('Mario Rossi CODX0712T') == 'Mario Rossi'
        AgentToolSupport.extractEmployeePersonName('Poi assegnala al nuovo impiegato Mario Rossi CODX0712T') == 'Mario Rossi'
    }

    @Unroll
    def "extract #label from prompt"() {
        expect:
        AgentToolSupport."$method"(input) == expected

        where:
        label         | method                         | input                                                                                                                        | expected
        'description' | 'extractPositionDescription'    | 'Potresti inserire una nuova posizione da impiegato con descrizione Solution Architect per reingegnerizzazione dei processi, posizione project manager, stato aperto.' | 'Solution Architect per reingegnerizzazione dei processi'
        'label'       | 'extractFallbackPositionLabel'  | 'Posizione project manager, stato aperto'                                                                                    | 'project manager'
        'status'      | 'inferEmplPositionStatusId'     | 'Posizione project manager, stato aperto'                                                                                    | 'EmpsActive'
        'hours'       | 'extractStandardHoursPerWeek'   | 'ore settimanali standard 40'                                                                                                | 40
    }

    def "detect localized employment prompt variants"() {
        expect:
        AgentToolSupport.looksLikeEmploymentPositionPrompt('Inserire una nuova posizione da impiegato connessa al budget HR Plan, ruolo project manager e stato aperto')
    }

    def "extract budget item plan from real accounting prompt"() {
        when:
        def items = AgentToolSupport.extractBudgetItemPlan("""Potresti creare un budget operativo per l'anno 2027, per l'azienda ZICORP, con descrizione Budget per apparecchiature di logistica e produzione con 4 righe associate ai conti contabili 612300000 Vehicle Rent per un importo di 100000 USD, 612400000 Other Equipment Rent per un importo di 400000 USD, 613400000 Repairs and Maintenance - Vehicle per un importo di 15000 USD, 613900000 Repairs and Maintenance - Other per un importo di 60000 USD.""")

        then:
        items*.glAccountId == ['612300000', '612400000', '613400000', '613900000']
        items*.amount == [new BigDecimal('100000'), new BigDecimal('400000'), new BigDecimal('15000'), new BigDecimal('60000')]
        items*.purpose == ['Vehicle Rent', 'Other Equipment Rent', 'Repairs and Maintenance - Vehicle', 'Repairs and Maintenance - Other']
    }

    def "extract order item plan from multi line order prompt"() {
        when:
        def items = AgentToolSupport.extractOrderItemsFromText("""Potresti creare un ordine di vendita verso cliente Joe & Joe's Distributing con prodotti DEMO_1_1, quantita' 2 e data consegna 28/06/2026 e DEMO_1_2, quantita' 3 e data consegna 07/07/2026.""")

        then:
        items*.productToken == ['DEMO_1_1', 'DEMO_1_2']
        items*.quantity == [new BigDecimal('2'), new BigDecimal('3')]
        items*.requiredByDate == ['2026-06-28 00:00:00.000', '2026-07-07 00:00:00.000']
    }

    def "employment workflow prompt is deferred to workflow boundary detection"() {
        when:
        def plan = AgentToolSupport.inferEmploymentPositionPlan(null, 'Potresti inserire una nuova posizione da impiegato con descrizione Solution Architect, connessa al budget HR Plan, full time. Poi assegnala al nuovo impiegato Mario Rossi')
        def boundary = AgentToolSupport.detectUnsupportedMultiDomainWorkflow('Potresti inserire una nuova posizione da impiegato con descrizione Solution Architect, connessa al budget HR Plan, full time. Poi assegnala al nuovo impiegato Mario Rossi')

        then:
        !plan.planType
        boundary.blocked
        boundary.message.contains('workflow multi-dominio')
    }

    def "build supported aggregate pattern hints includes canonical families"() {
        when:
        def hints = AgentToolSupport.buildSupportedAggregatePatternHints(null)

        then:
        hints.any { it.planType == 'same_subject_multi_action' && it.subjectEntity == 'Asset' }
        hints.any { it.planType == 'aggregate_create_tree' && it.subjectEntity == 'Project' && it.aggregateType == 'root_parent_tree' && it.children == ['Milestone', 'Task'] }
        hints.any { it.planType == 'aggregate_create_tree' && it.subjectEntity == 'OrderHeader' && it.aggregateType == 'header_part_item' && it.children == ['OrderPart', 'OrderItem'] }
    }

    def "detect unsupported multi domain workflow prompts"() {
        when:
        def result = AgentToolSupport.detectUnsupportedMultiDomainWorkflow('Crea un ordine di vendita e poi un budget operativo per ZICORP con Mario Rossi')

        then:
        result.blocked
        result.message.contains('workflow multi-dominio')
    }

    def "build business result summary keeps created identifiers and actions"() {
        when:
        Map summary = AgentToolSupport.buildBusinessResultSummary([
                success : true,
                executionResult : [
                        operation : 'employment_position_assignment',
                        planType : 'same_subject_multi_action',
                        currentBusinessObjects : [
                                budgetId : '100919',
                                organizationPartyId : 'ORG_ZIZI_CORP',
                                personPartyId : '100051',
                                emplPositionId : '100357',
                                partyRelationshipId : '100102'
                        ],
                        actions : [
                                [
                                        actionName : 'createPosition',
                                        serviceName : 'create#mantle.humanres.position.EmplPosition',
                                        serviceResult : [emplPositionId : '100357']
                                ],
                                [
                                        actionName : 'updateEmployment',
                                        serviceName : 'mantle.humanres.EmploymentServices.update#Employment',
                                        parameters : [partyRelationshipId : '100102']
                                ]
                        ]
                ],
                messages : ['ok']
        ])

        then:
        summary.success
        summary.operation == 'employment_position_assignment'
        summary.planType == 'same_subject_multi_action'
        summary.businessObjects.emplPositionId == '100357'
        summary.businessObjects.partyRelationshipId == '100102'
        summary.actions*.actionName == ['createPosition', 'updateEmployment']
        summary.actions[0].identifiers.emplPositionId == '100357'
        summary.actions[1].identifiers.partyRelationshipId == '100102'
    }
}
