/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */

import groovy.json.JsonOutput
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class AgentPromptExamplesFileExecutionTests extends Specification {
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
            ec.message.clearErrors()
            ec.service.sync()
                    .name('mantle.product.AssetServices.move#Asset')
                    .parameters([assetId: 'DEMO_1_1A', facilityId: facilityId, locationSeqId: '01010101'])
                    .call()
            ec.message.clearErrors()
        }
        if ((asset.statusId as String) != 'AstAvailable') {
            ec.message.clearErrors()
            ec.service.sync()
                    .name('update#mantle.product.asset.Asset')
                    .parameters([assetId: 'DEMO_1_1A', statusId: 'AstAvailable'])
                    .call()
            ec.message.clearErrors()
        }
    }

    protected List<Map> extractPromptExamples(File promptFile) {
        List<Map> prompts = []
        int counter = 0
        promptFile.eachLine('UTF-8') { String rawLine ->
            String line = rawLine?.trim()
            if (!line) return
            if (line.startsWith('- ')) return
            if (line in ['Prompt examples', 'Prompt examples:', 'Test aggiuntivi consigliati:', 'Suite consigliata per test reali immediati',
                         'Dry run / conferma', 'Ambiguità controllata', 'Denial / guarded execution',
                         'Query di sola ricerca', 'Varianti linguistiche / sinonimi', 'Casi edge sui dati',
                         'Post check / verifica', 'Prompt da evitare nei test smoke iniziali', 'Note di validazione sui demo data attuali']) return
            if (!(line ==~ /^(Potresti|Quali|Dove|Puoi)\b.*/)) return
            counter++
            prompts << [id: String.format('prompt_%02d', counter), prompt: line]
        }
        prompts
    }

    def "execute every prompt from Prompt examples markdown and persist full report"() {
        given:
        File promptFile = new File(projectRoot.parentFile, "Prompt examples.md")
        assert promptFile.exists(): "Prompt examples file not found at ${promptFile.absolutePath}"
        List<Map> promptSuite = extractPromptExamples(promptFile)
        assert !promptSuite.isEmpty(): "No prompts extracted from ${promptFile.absolutePath}"

        List<Map> results = []

        when:
        promptSuite.each { Map promptInfo ->
            if ((promptInfo.prompt as String).contains('asset DEMO_1_1A')) resetAssetPromptBaseline()
            ec.message.clearErrors()
            long startedAt = System.currentTimeMillis()
            Map serviceResult = ec.service.sync()
                    .name("org.moqui.agent.AgentRuntimeServices.resolve#AndExecuteAgentPrompt")
                    .parameters([
                            queryText : promptInfo.prompt,
                            confirmed : true,
                            dryRun    : false
                    ]).call()
            long elapsedMs = System.currentTimeMillis() - startedAt

            Map executionResult = serviceResult.executionResult instanceof Map ? (Map) serviceResult.executionResult : [:]
            List<String> serviceErrors = []
            if (executionResult.errors instanceof List) serviceErrors.addAll(((List) executionResult.errors).collect { it?.toString() })
            if (serviceResult.errors instanceof List) serviceErrors.addAll(((List) serviceResult.errors).collect { it?.toString() })
            if (ec.message.errors) serviceErrors.addAll((ec.message.errors as List).collect { it?.toString() })

            results << [
                    id                 : promptInfo.id,
                    prompt             : promptInfo.prompt,
                    elapsedMs          : elapsedMs,
                    success            : serviceResult.success == true,
                    resolvedDocumentId : serviceResult.resolvedDocumentId,
                    resolvedServiceName: executionResult.serviceName ?: executionResult.selectedService ?: serviceResult.serviceName,
                    executionState     : executionResult.state ?: executionResult.operation ?: executionResult.result,
                    resultMessage      : serviceResult.resultText ?: executionResult.message ?: executionResult.resultMessage,
                    errors             : serviceErrors.findAll { it }.unique(),
                    executionResult    : executionResult
            ]
        }

        File reportDir = new File(projectRoot, "runtime/component/moqui-mcp/tmp-runtime-validation")
        if (!reportDir.exists()) reportDir.mkdirs()
        File jsonFile = new File(reportDir, "prompt-examples-full-report.json")
        File markdownFile = new File(reportDir, "prompt-examples-full-report.md")

        jsonFile.text = JsonOutput.prettyPrint(JsonOutput.toJson([
                generatedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ssZ"),
                promptCount : results.size(),
                results     : results
        ]))

        StringBuilder md = new StringBuilder()
        md << "# Prompt Examples Full Report\n\n"
        md << "- generatedAt: ${new Date().format("yyyy-MM-dd'T'HH:mm:ssZ")}\n"
        md << "- promptCount: ${results.size()}\n\n"
        results.each { Map row ->
            md << "## ${row.id}\n\n"
            md << "- success: ${row.success}\n"
            md << "- elapsedMs: ${row.elapsedMs}\n"
            md << "- resolvedDocumentId: ${row.resolvedDocumentId ?: ''}\n"
            md << "- resolvedServiceName: ${row.resolvedServiceName ?: ''}\n"
            md << "- executionState: ${row.executionState ?: ''}\n"
            md << "- prompt: ${row.prompt}\n"
            if (row.resultMessage) md << "- resultMessage: ${row.resultMessage}\n"
            if (row.errors) {
                md << "- errors:\n"
                (row.errors as List).each { String err -> md << "  - ${err}\n" }
            }
            md << "\n"
        }
        markdownFile.text = md.toString()

        println "\nPrompt examples full report written to ${jsonFile.absolutePath}"
        println "Prompt examples markdown summary written to ${markdownFile.absolutePath}\n"
        results.each { Map row ->
            println "[${row.id}] success=${row.success} elapsedMs=${row.elapsedMs} doc=${row.resolvedDocumentId} service=${row.resolvedServiceName}"
            if (row.errors) println "  errors=${row.errors}"
            if (row.resultMessage) println "  message=${row.resultMessage}"
        }

        then:
        results.size() == promptSuite.size()
    }
}
