/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */

import groovy.json.JsonOutput
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class AgentSinglePromptExecutionTest extends Specification {
    @Shared
    ExecutionContext ec
    @Shared
    File projectRoot

    protected static void applyAgentTestOverrides() {
        Map<String, String> overrides = [
                'moqui.agent.chat.provider' : (System.getProperty('agent.chat.provider') ?: System.getenv('AGENT_CHAT_PROVIDER')),
                'moqui.agent.chat.model' : (System.getProperty('agent.chat.model') ?: System.getenv('AGENT_CHAT_MODEL')),
                'moqui.agent.chat.plannerMode' : (System.getProperty('agent.chat.plannerMode') ?: System.getenv('AGENT_CHAT_PLANNER_MODE')),
                'moqui.agent.chat.compat.baseUrl' : (System.getProperty('agent.chat.compat.baseUrl') ?: System.getenv('AGENT_CHAT_COMPAT_BASE_URL')),
                'moqui.agent.chat.compat.apiKey' : (System.getProperty('agent.chat.compat.apiKey') ?: System.getenv('AGENT_CHAT_COMPAT_API_KEY')),
                'moqui.agent.chat.apiKey' : (System.getProperty('agent.chat.apiKey') ?: System.getenv('AGENT_CHAT_API_KEY'))
        ]
        overrides.each { String k, String v ->
            if (v != null && !v.toString().trim().isEmpty()) System.setProperty(k, v.toString())
        }
    }

    def setupSpec() {
        File searchDir = new File(".").canonicalFile
        while (searchDir != null && !new File(searchDir, "MoquiInit.properties").exists()) {
            searchDir = searchDir.parentFile
        }
        assert searchDir != null: "Unable to locate moqui-framework root from ${new File('.').canonicalPath}"
        projectRoot = searchDir
        System.setProperty("moqui.init.static", "true")
        System.setProperty("moqui.runtime", new File(projectRoot, "runtime").absolutePath)
        System.setProperty("moqui.conf", new File(projectRoot, "runtime/conf/MoquiDevConf.xml").absolutePath)
        applyAgentTestOverrides()
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
        ec.service.sync().name('org.moqui.agent.AgentDocumentServices.index#StandardLookupDataDocuments').call()
        ec.service.sync().name('org.moqui.agent.AgentDocumentServices.index#ArtifactGraphDataDocuments')
                .parameters([graphId: 'AgentArtifactGraph', includeEmbedding: false]).call()
        ec.service.sync().name('org.moqui.agent.AgentDocumentServices.index#RuntimeLookupDataDocuments').call()
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

    protected void resetAssetPromptBaseline() {
        def asset = ec.entity.find('mantle.product.asset.Asset').condition('assetId', 'DEMO_1_1A').disableAuthz().one()
        if (asset == null) return
        String facilityId = asset.facilityId as String
        if ((asset.locationSeqId as String) != '01010101') {
            ec.service.sync().name('mantle.product.AssetServices.move#Asset')
                    .parameters([assetId: 'DEMO_1_1A', facilityId: facilityId, locationSeqId: '01010101']).call()
            ec.message.clearErrors()
        }
        if ((asset.statusId as String) != 'AstAvailable') {
            ec.service.sync().name('update#mantle.product.asset.Asset')
                    .parameters([assetId: 'DEMO_1_1A', statusId: 'AstAvailable']).call()
            ec.message.clearErrors()
        }
    }

    def "execute single prompt from system property and persist result"() {
        given:
        String promptId = System.getProperty('agent.prompt.id') ?: System.getenv('AGENT_PROMPT_ID') ?: 'prompt'
        String promptText = System.getProperty('agent.prompt.text') ?: System.getenv('AGENT_PROMPT_TEXT')
        boolean debugPlan = (System.getProperty('agent.debug.plan') ?: System.getenv('AGENT_DEBUG_PLAN') ?: 'false').toBoolean()
        Map plannerDebug = [:]
        assert promptText: 'Missing -Dagent.prompt.text system property'
        if (promptText.contains('asset DEMO_1_1A')) resetAssetPromptBaseline()

        if (debugPlan) {
            Map decompose = ec.service.sync().name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                    .parameters([queryText: promptText]).call()
            Map classify = ec.service.sync().name("org.moqui.agent.AgentAlgebraicServices.classify#AtomicCommandPattern")
                    .parameters([
                            atomicCommands: decompose.atomicCommands,
                            commandGraph: decompose.commandGraph,
                            workflowIntent: decompose.workflowIntent
                    ]).call()
            Map mapped = ec.service.sync().name("org.moqui.agent.AgentAlgebraicServices.map#AtomicCommandsToMorphisms")
                    .parameters([
                            atomicCommands: decompose.atomicCommands,
                            planningMode: classify.patternClassification?.planningMode,
                            limitPerCommand: 5
                    ]).call()
            List<Map> searches = []
            if (mapped.commandMorphismMap instanceof List) {
                ((List<Map>) mapped.commandMorphismMap).each { Map entry ->
                    String searchQueryText = (entry.searchQueryText ?: '') as String
                    if (!searchQueryText) return
                    Map searchResult = ec.service.sync().name("org.moqui.agent.AgentAlgebraicServices.search#Morphisms")
                            .parameters([
                                    queryText: searchQueryText,
                                    mutatingOnly: false,
                                    readOnlyOnly: true,
                                    limit: 10
                            ]).call()
                    if (ec.message.hasError()) ec.message.clearErrors()
                    searches << [
                            queryText: searchQueryText,
                            morphismList: (searchResult.morphismList instanceof List) ? (List) searchResult.morphismList : []
                    ]
                }
            }
            Map planned = ec.service.sync().name("org.moqui.agent.AgentAlgebraicServices.plan#PromptAsMorphism")
                    .parameters([queryText: promptText, dryRunOnly: true, limit: 5]).call()
            File debugDir = new File(projectRoot, "runtime/component/moqui-mcp/tmp-runtime-validation/single-prompt-debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            File debugFile = new File(debugDir, "${promptId}.planner.json")
            plannerDebug = [
                    decompose: decompose,
                    classify : classify,
                    map      : mapped,
                    searches : searches,
                    plan     : planned
            ]
            debugFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(plannerDebug))
            println "Wrote planner debug to ${debugFile.absolutePath}"
            ec.message.clearErrors()
        }

        when:
        long startedAt = System.currentTimeMillis()
        Map serviceResult = ec.service.sync()
                .name("org.moqui.agent.AgentRuntimeServices.resolve#AndExecuteAgentPrompt")
                .parameters([
                        queryText : promptText,
                        confirmed : true,
                        dryRun    : false
                ]).call()
        long elapsedMs = System.currentTimeMillis() - startedAt
        Map executionResult = serviceResult.executionResult instanceof Map ? (Map) serviceResult.executionResult : [:]
        List<String> serviceErrors = []
        if (executionResult.errors instanceof List) serviceErrors.addAll(((List) executionResult.errors).collect { it?.toString() })
        if (serviceResult.errors instanceof List) serviceErrors.addAll(((List) serviceResult.errors).collect { it?.toString() })
        if (ec.message.errors) serviceErrors.addAll((ec.message.errors as List).collect { it?.toString() })

        Map row = [
                id                 : promptId,
                prompt             : promptText,
                elapsedMs          : elapsedMs,
                success            : serviceResult.success == true,
                resolvedDocumentId : serviceResult.resolvedDocumentId,
                resolvedServiceName: executionResult.serviceName ?: executionResult.selectedService ?: serviceResult.serviceName,
                executionState     : executionResult.state ?: executionResult.operation ?: executionResult.result,
                resultMessage      : serviceResult.resultText ?: executionResult.message ?: executionResult.resultMessage,
                errors             : serviceErrors.findAll { it }.unique(),
                executionResult    : executionResult,
                plannerDebug       : plannerDebug
        ]

        File reportDir = new File(projectRoot, "runtime/component/moqui-mcp/tmp-runtime-validation/single-prompt-results")
        if (!reportDir.exists()) reportDir.mkdirs()
        File outFile = new File(reportDir, "${promptId}.json")
        outFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(row))
        println "Wrote single prompt result to ${outFile.absolutePath}"
        println JsonOutput.toJson([
                id: row.id, success: row.success, elapsedMs: row.elapsedMs,
                resolvedDocumentId: row.resolvedDocumentId, resolvedServiceName: row.resolvedServiceName,
                executionState: row.executionState, errors: row.errors, resultMessage: row.resultMessage
        ])

        then:
        outFile.exists()
    }
}
