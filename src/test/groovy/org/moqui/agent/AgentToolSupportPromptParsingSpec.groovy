package org.moqui.agent

import spock.lang.Specification
import spock.lang.Unroll

class AgentToolSupportPromptParsingSpec extends Specification {
    def "detect employment position prompt"() {
        expect:
        AgentToolSupport.looksLikeEmploymentPositionPrompt('Potresti inserire una nuova posizione da impiegato con descrizione Solution Architect per reingegnerizzazione dei processi, posizione project manager, stato aperto, connessa al budget HR development, full time e possibilità di straordinario, ora settimanali standard 40?')
    }

    def "infer employment position plan from real prompt"() {
        when:
        def plan = AgentToolSupport.inferEmploymentPositionPlan(null, 'Potresti inserire una nuova posizione da impiegato con descrizione Solution Architect per reingegnerizzazione dei processi, posizione project manager, stato aperto, connessa al budget HR development, full time e possibilità di straordinario, ora settimanali standard 40?')

        then:
        plan.planType == 'same_subject_multi_action'
        plan.compositeType == 'employment_position_assignment'
        plan.actions*.actionName == ['createPerson', 'createPosition', 'createEmployment']
        plan.actions[0].serviceName == 'mantle.party.PartyServices.create#Person'
        plan.actions[1].parameters.description == 'Solution Architect per reingegnerizzazione dei processi'
        plan.actions[1].parameters.statusId == 'EmpsActive'
        plan.actions[1].parameters.fullTimeFlag == 'Y'
        plan.actions[1].parameters.overtimeFlag == 'Y'
        plan.actions[1].parameters.standardHoursPerWeek == 40
    }

    def "extract budget description and person name"() {
        when:
        String budget = AgentToolSupport.extractBudgetDescription('... connessa al budget HR development, full time ...')
        String person = AgentToolSupport.extractEmployeePersonName('Puoi assegnarla al nuovo impiegato mario Rossi?')

        then:
        budget == 'HR development'
        person == 'mario Rossi'
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
}
