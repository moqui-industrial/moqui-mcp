/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class AgentAlgebraicExecutionTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        File searchDir = new File(".").canonicalFile
        while (searchDir != null && !new File(searchDir, "MoquiInit.properties").exists()) {
            searchDir = searchDir.parentFile
        }
        assert searchDir != null: "Unable to locate moqui-framework root from ${new File('.').canonicalPath}"
        String projectRoot = searchDir.absolutePath
        System.setProperty("moqui.init.static", "true")
        System.setProperty("moqui.runtime", new File(projectRoot, "runtime").absolutePath)
        System.setProperty("moqui.conf", new File(projectRoot, "runtime/conf/MoquiDevConf.xml").absolutePath)
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
    }

    def cleanupSpec() {
        if (ec != null) ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.message.clearErrors()
    }

    def cleanup() {
        ec.message.clearErrors()
        ec.artifactExecution.enableAuthz()
    }

    def "algebraic planner builds prerequisite budget chain for budget item prompt"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "create budget item for ZICORP account 612300000 amount 100000 for 2027"])
                .call()

        then:
        result.state == "ready"
        result.selectedService == "create#mantle.other.budget.BudgetItem"
        (result.executionPlanGraph as List)*.serviceName == [
                "create#mantle.other.budget.Budget",
                "create#mantle.other.budget.BudgetItem"
        ]
    }

    def "resolve and execute supports algebraic dry run for budget item prompt"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentRuntimeServices.resolve#AndExecuteAgentPrompt")
                .parameters([
                        queryText : "create budget item for ZICORP account 612300000 amount 100000 for 2027",
                        dryRun : true,
                        confirmed : true
                ]).call()

        then:
        result.success == true
        result.resolvedDocumentId == "agent-morphism://create#mantle.other.budget.BudgetItem"
        result.executionResult instanceof Map
        result.executionResult.dryRun == true
        (result.executionResult.executionPlanGraph as List)*.serviceName == [
                "create#mantle.other.budget.Budget",
                "create#mantle.other.budget.BudgetItem"
        ]
    }

    def "execute morphism chain smoke test runs through dedicated algebraic executor"() {
        given:
        Map planResult = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "create budget item for ZICORP account 612300000 amount 100000 for 2027"])
                .call()

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.execute#MorphismChain")
                .parameters([
                        selectedService: planResult.selectedService,
                        executionPlanGraph: planResult.executionPlanGraph,
                        parameters: [
                                organizationName: "ZICORP",
                                glAccountCode: "612300000",
                                amount: "100000",
                                fiscalYear: "2027"
                        ],
                        dryRun: true,
                        confirmed: true,
                        internalDisableAuthzForSmokeTest: true
                ]).call()

        then:
        planResult.state == "ready"
        result.success == true
        result.dryRun == true
        result.operation == "algebraic_morphism_chain"
        (result.executionPlanGraph as List)*.serviceName == [
                "create#mantle.other.budget.Budget",
                "create#mantle.other.budget.BudgetItem"
        ]
    }

    def "planner blocks cross aggregate workflow instead of forcing a flat morphism match"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "Create a new employment position and then assign it to a new employee Mario Rossi"])
                .call()

        then:
        result.state == "workflowBoundary"
        result.authorizationResult.reason == "cross_aggregate_workflow"
        result.patternClassification.patternId == "cross_aggregate_workflow"
        (result.executionPlanGraph as List)[0].nodeType == "workflow_boundary"
    }

    def "planner exposes command to morphism mapping for budget aggregate prompt"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "Create a budget for ZICORP for 2027; then add a budget item for account 612300000 amount 100000 USD"])
                .call()

        then:
        result.state == "ready"
        (result.commandMorphismMap as List).size() == 2
        ((result.commandMorphismMap as List)[0].candidates as List)[0].serviceName == "create#mantle.other.budget.Budget"
        ((result.commandMorphismMap as List)[1].candidates as List)[0].serviceName == "create#mantle.other.budget.BudgetItem"
        result.selectedService == "create#mantle.other.budget.BudgetItem"
    }

    def "algebraic planner builds order aggregate chain for sales order prompt"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "create sales order for Joe & Joe's Distributing with product DEMO_1_1 quantity 2"])
                .call()

        then:
        result.state == "ready"
        result.selectedService == "create#mantle.order.OrderItem"
        List<String> orderGraphServices = ((result.executionPlanGraph as List)*.serviceName) as List<String>
        orderGraphServices.any { it == "create#mantle.order.OrderHeader" || it == "mantle.order.OrderServices.create#Order" }
        orderGraphServices.contains("create#mantle.order.OrderItem")
    }

    def "algebraic planner builds shipment aggregate chain for shipment item prompt"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "create shipment item for product DEMO_1_1 quantity 2"])
                .call()

        then:
        result.state == "ready"
        result.selectedService == "create#mantle.shipment.ShipmentItem"
        (result.executionPlanGraph as List)*.serviceName == [
                "create#mantle.shipment.Shipment",
                "create#mantle.shipment.ShipmentItem"
        ]
    }

    def "algebraic planner builds ledger aggregate chain for accounting transaction entry prompt"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "create accounting transaction entry amount 100 debit account 111600000 credit account 611100000"])
                .call()

        then:
        result.state == "ready"
        result.selectedService == "create#mantle.ledger.transaction.AcctgTransEntry"
        (result.executionPlanGraph as List)*.serviceName == [
                "create#mantle.ledger.transaction.AcctgTrans",
                "create#mantle.ledger.transaction.AcctgTransEntry"
        ]
    }
}
