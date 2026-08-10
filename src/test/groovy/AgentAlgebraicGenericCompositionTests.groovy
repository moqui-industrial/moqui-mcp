/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class AgentAlgebraicGenericCompositionTests extends Specification {
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
        ec.entity.makeDataLoader()
                .location('component://moqui-mcp/data/AppSeedData.xml')
                .location('component://moqui-mcp/data/McpSecuritySeedData.xml')
                .location('component://moqui-mcp/data/AgentDocumentSeedData.xml')
                .location('component://moqui-mcp/data/AgentSkillDocumentSeedData.xml')
                .location('component://moqui-mcp/data/AgentSkillSeedData.xml')
                .location('component://moqui-mcp/data/AgentAggregatePatternSeedData.xml')
                .location('component://moqui-mcp/data/AgentUniversalPatternSeedData.xml')
                .location('component://moqui-mcp/data/AgentGraphMetadataSeedData.xml')
                .location('component://moqui-mcp/data/AgentMathModelSeedData.xml')
                .location('component://moqui-mcp/data/generated/XmlActionMorphismSeed.xml')
                .location('component://moqui-mcp/data/generated/ServiceMorphismSeed.xml')
                .location('component://moqui-mcp/data/generated/EntityMorphismSeed.xml')
                .location('component://moqui-mcp/data/AgentRuntimeSeedData.xml')
                .load()
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

    def "Test 1 - Multi line budget planning populates parameterBatchList for budget item"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([
                        queryText: "create budget for ZICORP 2027 with lines: 612300000 Vehicle Rent amount 100000 USD, 612400000 Other Equipment Rent amount 400000 USD"
                ]).call()
        println "TEST 1 RESULT: state=${result.state}, selectedService=${result.selectedService}, missingOperands=${result.missingOperands}, graph=${result.executionPlanGraph*.serviceName}"

        then:
        result.state == "ready"
        result.selectedService == "create#mantle.other.budget.BudgetItem"
        List<Map> graph = (result.executionPlanGraph as List)
        graph*.serviceName == [
                "create#mantle.other.budget.Budget",
                "create#mantle.other.budget.BudgetItem"
        ]
        Map primaryNode = graph.find { it.serviceName == "create#mantle.other.budget.BudgetItem" }
        (primaryNode.parameterBatchList as List).size() >= 2
    }

    def "Test 2 - Sales order multi line planning builds OrderItem batch x2"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([
                        queryText: "create sales order for Joe & Joe's Distributing with product DEMO_1_1 quantity 2 and product DEMO_1_2 quantity 3"
                ]).call()

        then:
        result.state == "ready"
        result.selectedService == "create#mantle.order.OrderItem"
        List<Map> graph = (result.executionPlanGraph as List)
        graph*.serviceName.any { it == "create#mantle.order.OrderHeader" || it == "mantle.order.OrderServices.create#Order" }
        graph*.serviceName.contains("create#mantle.order.OrderItem")
        Map itemNode = graph.find { it.serviceName == "create#mantle.order.OrderItem" }
        (itemNode.parameterBatchList as List).size() == 2
    }

    def "Test 3 - Shipment item prompt builds Shipment aggregate chain"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "create shipment item for product DEMO_1_1 quantity 2"])
                .call()

        then:
        result.state == "ready"
        result.selectedService == "create#mantle.shipment.ShipmentItem"
        List<Map> graph = (result.executionPlanGraph as List)
        graph*.serviceName == [
                "create#mantle.shipment.Shipment",
                "create#mantle.shipment.ShipmentItem"
        ]
    }

    def "Test 4 - Ledger entry prompt builds AcctgTrans aggregate chain"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "create accounting transaction entry amount 100 debit account 111600000 credit account 611100000"])
                .call()

        then:
        result.state == "ready"
        result.selectedService == "create#mantle.ledger.transaction.AcctgTransEntry"
        List<Map> graph = (result.executionPlanGraph as List)
        graph*.serviceName == [
                "create#mantle.ledger.transaction.AcctgTrans",
                "create#mantle.ledger.transaction.AcctgTransEntry"
        ]
    }

    def "Test 5 - Support request prompt does not trigger false workflow boundary"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "creare una richiesta di support con priorità 1, assegnata a John Doe, nome Cambio compressore, descrizione Cambio del compressore guasto"])
                .call()

        then:
        result.state in ["ready", "needsMoreContext"]
        result.state != "workflowBoundary"
    }

    def "Test 6 - Asset move prompt plans update#mantle.product.asset.Asset morphism"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                .parameters([queryText: "Potresti spostare dal magazzino Ziziwork Retail Warehouse, l'asset DEMO_1_1A dalla locazione 01010101 attuale alla locazione 01010102 e mettere l'asset in stato On Hold."])
                .call()

        println "ASSET MOVE PLAN RESULT: state=${result.state}, selectedService=${result.selectedService}, atomicCommands=${result.atomicCommands}"
        then:
        result.state == "ready"
        result.selectedService != "create#mantle.product.asset.Asset"
        List<Map> graph = (result.executionPlanGraph as List)
        !graph*.serviceName.contains("create#mantle.product.asset.Asset")
    }
}
