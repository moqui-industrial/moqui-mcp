/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */

import groovy.json.JsonOutput
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import java.sql.Timestamp
import spock.lang.Shared
import spock.lang.Specification

class AgentPromptExamplesExecutionTests extends Specification {
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
        ec.message.clearErrors()
    }

    def cleanup() {
        ec.message.clearErrors()
    }

    protected Map getJohnDoeParty() {
        return ec.entity.find('mantle.party.Person').condition('firstName', 'John').condition('lastName', 'Doe').disableAuthz().one()?.getMap()
    }

    protected void resetAssetPromptBaseline() {
        def asset = ec.entity.find('mantle.product.asset.Asset').condition('assetId', 'DEMO_1_1A').disableAuthz().one()
        assert asset != null: 'Expected DEMO_1_1A asset to exist for prompt smoke test'
        String facilityId = asset.facilityId as String
        if ((asset.locationSeqId as String) != '01010101' || (asset.facilityId as String) != facilityId) {
            ec.message.clearErrors()
            Map moveResult = ec.service.sync()
                    .name('mantle.product.AssetServices.move#Asset')
                    .parameters([assetId: 'DEMO_1_1A', facilityId: facilityId, locationSeqId: '01010101'])
                    .call()
            assert !ec.message.hasError(): "Unable to reset asset location baseline: ${ec.message.errorsString}"
            assert moveResult != null
        }
        if ((asset.statusId as String) != 'AstAvailable') {
            ec.message.clearErrors()
            Map updateResult = ec.service.sync()
                    .name('update#mantle.product.asset.Asset')
                    .parameters([assetId: 'DEMO_1_1A', statusId: 'AstAvailable'])
                    .call()
            assert !ec.message.hasError(): "Unable to reset asset status baseline: ${ec.message.errorsString}"
            assert updateResult != null
        }
        ec.entity.find('mantle.product.asset.Asset').condition('assetId', 'DEMO_1_1A').disableAuthz().one()?.refresh()
    }

    def "lookup customer party by business name resolves uniquely"() {
        when:
        ec.message.clearErrors()
        Map lookupResult = ec.service.sync()
                .name("org.moqui.agent.AgentExecutionServices.find#LookupRecord")
                .parameters([
                        lookupKey: 'party',
                        lookupText: "Joe & Joe's Distributing",
                        roleTypeId: 'Customer'
                ]).call()

        then:
        !ec.message.hasError()
        lookupResult.uniqueMatch == true
        lookupResult.recordId == 'JoeDist'
    }

    def "execute prompt examples smoke suite and persist diagnostic report"() {
        given:
        resetAssetPromptBaseline()
        Map johnDoeParty = getJohnDoeParty()
        assert johnDoeParty?.partyId
        List<Map> promptSuite = [
                [
                        id: "support_request",
                        prompt: "Potresti creare una richiesta di support con priorita` 1, assegnata a John Doe, con nome Cambio compressore e descrizione Cambio urgente compressore per rottura motore con data scadenza 19/04/2026."
                ],
                [
                        id: "asset_move",
                        prompt: "Potresti spostare dal magazzino Ziziwork Retail Warehouse, l'asset DEMO_1_1A dalla locazione 01010101 attuale alla locazione 01010102 e mettere l'asset in stato On Hold."
                ],
                [
                        id: "budget_create",
                        prompt: "Potresti creare un budget operativo per l'anno 2027, per l'azienda ZICORP, con descrizione Budget per apparecchiature di logistica e produzione con 4 righe associate ai conti contabili 612300000 Vehicle Rent per un importo di 100000 USD, 612400000 Other Equipment Rent per un importo di 400000 USD, 613400000 Repairs and Maintenance - Vehicle per un importo di 15000 USD, 613900000 Repairs and Maintenance - Other per un importo di 60000 USD."
                ],
                [
                        id: "sales_order",
                        prompt: "Potresti creare un ordine di vendita verso cliente Joe & Joe's Distributing con prodotti DEMO_1_1, quantita` 2 e data consegna 28/06/2026 e DEMO_1_1, quantita` 1 e data consegna 07/07/2026."
                ]
        ]

        List<Map> results = []

        when:
        promptSuite.each { Map promptInfo ->
            ec.message.clearErrors()
            long startedAt = System.currentTimeMillis()
            Map serviceResult = ec.service.sync()
                    .name("org.moqui.agent.AgentRuntimeServices.resolve#AndExecuteAgentPrompt")
                    .parameters([
                            queryText : promptInfo.prompt,
                            confirmed : true,
                            dryRun : false
                    ]).call()
            long elapsedMs = System.currentTimeMillis() - startedAt

            Map executionResult = serviceResult.executionResult instanceof Map ? (Map) serviceResult.executionResult : [:]
            List serviceErrors = []
            if (executionResult.errors instanceof List) serviceErrors.addAll((List) executionResult.errors)
            if (serviceResult.errors instanceof List) serviceErrors.addAll((List) serviceResult.errors)
            if (ec.message.errors) serviceErrors.addAll(ec.message.errors as List)

            results.add([
                    id                  : promptInfo.id,
                    prompt              : promptInfo.prompt,
                    elapsedMs           : elapsedMs,
                    success             : serviceResult.success,
                    resolvedDocumentId  : serviceResult.resolvedDocumentId,
                    resolvedServiceName : executionResult.serviceName ?: executionResult.selectedService ?: serviceResult.serviceName,
                    executionState      : executionResult.state ?: executionResult.operation ?: executionResult.result ?: null,
                    resultMessage       : serviceResult.resultText ?: executionResult.message ?: executionResult.resultMessage,
                    errors              : serviceErrors.findAll { it != null }.collect { it.toString() }.unique(),
                    executionResult     : executionResult
            ])
        }

        File reportDir = new File("tmp-runtime-validation")
        if (!reportDir.exists()) reportDir.mkdirs()
        File reportFile = new File(reportDir, "prompt-execution-report.json")
        reportFile.text = JsonOutput.prettyPrint(JsonOutput.toJson([
                generatedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssZ"),
                promptCount : results.size(),
                results     : results
        ]))

        println "\nPrompt execution report written to ${reportFile.absolutePath}\n"
        results.each { Map row ->
            println "[${row.id}] success=${row.success} elapsedMs=${row.elapsedMs} resolvedDocumentId=${row.resolvedDocumentId} service=${row.resolvedServiceName}"
            if (row.errors) println "  errors=${row.errors}"
            if (row.resultMessage) println "  message=${row.resultMessage}"
            if (row.id == 'sales_order') {
                println "  salesOrderRow full map: ${row}"
            }
        }

        then:
        results.size() == promptSuite.size()

        Map supportRow = results.find { it.id == 'support_request' }
        supportRow != null
        supportRow.success == false
        (supportRow.resolvedDocumentId as String) in [
                'agent-morphism://mantle.request.RequestServices.create#Request',
                'agent-morphism://create#mantle.request.Request'
        ]
        ((supportRow.errors ?: []) as List).any { String err -> (err ?: '').contains('assignee_binding') || (err ?: '').contains('Semantic binding unsupported') }

        Map budgetRow = results.find { it.id == 'budget_create' }
        budgetRow != null
        budgetRow.success == true
        budgetRow.resolvedDocumentId == 'agent-morphism://create#mantle.other.budget.BudgetItem'
        String budgetId = budgetRow.executionResult?.resolvedParameters?.budgetId ?: budgetRow.executionResult?.finalResult?.budgetId
        budgetId != null
        long budgetItemCount = ec.entity.find('mantle.other.budget.BudgetItem')
                .condition('budgetId', budgetId)
                .disableAuthz()
                .count()
        budgetItemCount == 4L

        Map assetRow = results.find { it.id == 'asset_move' }
        assetRow != null
        assetRow.success == true
        (assetRow.resolvedDocumentId as String) in ['agent-prompt://asset/assetdetail/updateasset', 'agent-morphism://mantle.product.AssetServices.move#Asset']
        def movedAsset = ec.entity.find('mantle.product.asset.Asset').condition('assetId', 'DEMO_1_1A').disableAuthz().one()
        movedAsset != null
        (movedAsset.locationSeqId as String) == '01010102'
        (movedAsset.statusId as String) == 'AstOnHold'

        Map salesOrderRow = results.find { it.id == 'sales_order' }
        salesOrderRow != null
        salesOrderRow.success == true
        salesOrderRow.resolvedDocumentId == 'agent-morphism://create#mantle.order.OrderItem'
        List<Map> orderGraph = (salesOrderRow.executionPlanGraph ?: salesOrderRow.executionResult?.executionPlanGraph) as List<Map>
        orderGraph != null
        List<String> orderServices = orderGraph.collect { it.serviceName as String }
        orderServices.any { it == 'create#mantle.order.OrderHeader' || it == 'mantle.order.OrderServices.create#Order' }
        orderServices.contains('create#mantle.order.OrderItem')
        Map itemNode = orderGraph.find { (it.serviceName as String) == 'create#mantle.order.OrderItem' }
        itemNode != null
        (itemNode.parameterBatchList as List).size() == 2
        String orderId = salesOrderRow.executionResult?.resolvedParameters?.orderId ?: salesOrderRow.executionResult?.finalResult?.orderId
        orderId != null
        long orderItemCount = ec.entity.find('mantle.order.OrderItem')
                .condition('orderId', orderId)
                .disableAuthz()
                .count()
        orderItemCount == 2L
    }
}
