/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class AgentAlgebraicNoDomainHardcodingTests extends Specification {
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
    }

    def cleanupSpec() {
        if (ec != null) ec.destroy()
    }

    def "verify runtime planner does not rely on domain specific hardcoding strings"() {
        given:
        File searchDir = new File(".").canonicalFile
        while (searchDir != null && !new File(searchDir, "MoquiInit.properties").exists()) {
            searchDir = searchDir.parentFile
        }
        File servicesFile = new File(searchDir, "runtime/component/moqui-mcp/service/org/moqui/agent/AgentAlgebraicServices.xml")
        assert servicesFile.exists()

        String text = servicesFile.text

        // Scan for domain specific strings in runtime planner logic
        List<String> forbiddenPhrases = [
                "childDetailPenalty",
                "refs.accountCode + budgetitem",
                "refs.productCode + orderitem",
                "text.contains('accounting transaction entry')",
                "text.contains('budget item')",
                "text.contains('order item')",
                "text.contains('shipment item')",
                "current in ['order'",
                "current in ['budget'"
        ]

        List<String> foundViolations = []
        forbiddenPhrases.each { String phrase ->
            if (text.contains(phrase)) {
                foundViolations.add(phrase)
            }
        }

        when:
        if (foundViolations) {
            println "WARNING: AgentAlgebraicServices.xml still contains hardcoded domain rules: ${foundViolations}"
        } else {
            println "SUCCESS: AgentAlgebraicServices.xml has no forbidden hardcoded domain rules."
        }

        then:
        foundViolations.isEmpty()
    }
}
