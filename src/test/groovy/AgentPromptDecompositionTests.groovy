/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class AgentPromptDecompositionTests extends Specification {
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

    def "decompose budget command into create and set style atomic commands"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Create a budget for ZICORP for 2027; then add a budget item for account 612300000 amount 100000 USD"
                ]).call()

        then:
        result.success == true
        result.workflowIntent.language == "en"
        result.workflowIntent.complexity == "compound"
        (result.atomicCommands as List).size() == 2
        (result.atomicCommands as List)*.verbLemma == ["create", "create"]
        (result.atomicCommands as List)*.targetObjectText == ["budget", "budget item"]
        (result.commandGraph as List).size() == 0
        ((result.atomicCommands as List)[0].complements.references.organizationCode) == "ZICORP"
        ((result.atomicCommands as List)[0].complements.values.fiscalYear) == "2027"
        ((result.atomicCommands as List)[1].complements.references.accountCode) == "612300000"
    }

    def "decompose italian budget command with multiple accounting lines into budget item intent"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Potresti creare un budget operativo per l'anno 2027, per l'azienda ZICORP, con descrizione Budget per apparecchiature di logistica e produzione con 4 righe associate ai conti contabili 612300000 Vehicle Rent per un importo di 100000 USD, 612400000 Other Equipment Rent per un importo di 400000 USD."
                ]).call()

        then:
        result.success == true
        result.workflowIntent.language == "it"
        (result.atomicCommands as List).size() == 1
        (result.atomicCommands as List)[0].verbLemma == "create"
        (result.atomicCommands as List)[0].targetObjectText == "budget item"
        (((result.atomicCommands as List)[0].complements.values.budgetLines) as List).size() == 2
        ((result.atomicCommands as List)[0].complements.references.organizationCode) == "ZICORP"
        ((result.atomicCommands as List)[0].complements.values.fiscalYear) == "2027"
    }

    def "decompose employment prompt with reference commands and dependency edges"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Potresti creare una nuova posizione da impiegato; poi collegala al budget HR Plan; mettila in stato aperto e full time con ore settimanali standard 40; poi assegnala al nuovo impiegato Mario Rossi"
                ]).call()

        then:
        result.success == true
        result.workflowIntent.language == "it"
        result.workflowIntent.complexity == "compound"
        (result.atomicCommands as List).size() == 4
        (result.atomicCommands as List)*.verbLemma == ["create", "link", "set", "assign"]
        (result.atomicCommands as List)*.targetObjectText == [
                "employment position",
                "employment position",
                "employment position",
                "employment position"
        ]
        (result.commandGraph as List)*.from.unique() == ["c1"]
        (result.commandGraph as List)*.to == ["c2", "c3", "c4"]
        ((result.atomicCommands as List)[1].complements.references.budgetName) == "HR Plan"
        ((result.atomicCommands as List)[2].complements.fields.status) == "open"
        ((result.atomicCommands as List)[2].complements.fields.fullTime) == true
        ((result.atomicCommands as List)[2].complements.fields.estimatedStandardHours) == "40"
        ((result.atomicCommands as List)[3].complements.references.personName) == "Mario Rossi"
    }

    def "decompose read only prompt into read command"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Show the open full time employment positions"
                ]).call()

        then:
        result.success == true
        result.workflowIntent.mutability == "read-only"
        (result.atomicCommands as List).size() == 1
        (result.atomicCommands as List)[0].verbLemma == "read"
        (result.atomicCommands as List)[0].targetObjectText == "employment position"
    }

    def "decompose support ticket open assign deadline prompt into rooted request composition"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Potresti aprire un ticket urgente per il cambio compressore, assegnarlo a John Doe e impostare la scadenza al 19/04/2026?"
                ]).call()

        then:
        result.success == true
        result.workflowIntent.language == "it"
        result.workflowIntent.complexity == "compound"
        (result.atomicCommands as List)*.verbLemma == ["create", "assign", "set"]
        (result.atomicCommands as List)*.targetObjectText == ["request", "request", "request"]
        ((result.atomicCommands as List)[1].complements.references.assignedPartyName) == "John Doe"
        ((result.atomicCommands as List)[2].complements.values.responseRequiredDate) == "19/04/2026"
    }

    def "decompose assigned task query as read over task object"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Quali task sono assegnati a John Doe con ruolo Project Manager?"
                ]).call()

        then:
        result.success == true
        result.workflowIntent.mutability == "read-only"
        (result.atomicCommands as List).size() == 1
        (result.atomicCommands as List)[0].verbLemma == "read"
        (result.atomicCommands as List)[0].targetObjectText == "task"
        ((result.atomicCommands as List)[0].complements.references.assignedPartyName) == "John Doe"
    }

    def "decompose project verification query as read projection on project root"() {
        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Puoi mostrarmi le milestone e i task del progetto Commessa Hormel Food Corporation 2027 appena creato?"
                ]).call()

        then:
        result.success == true
        result.workflowIntent.mutability == "read-only"
        (result.atomicCommands as List).size() == 1
        (result.atomicCommands as List)[0].verbLemma == "read"
        (result.atomicCommands as List)[0].targetObjectText == "project"
        ((result.atomicCommands as List)[0].complements.references.projectName) == "Commessa Hormel Food Corporation 2027 appena creato"
    }

    def "classify root child aggregate composition from decomposed budget prompt"() {
        given:
        Map decomposition = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Create a budget for ZICORP for 2027; then add a budget item for account 612300000 amount 100000 USD"
                ]).call()

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.classify#AtomicCommandPattern")
                .parameters([
                        atomicCommands: decomposition.atomicCommands,
                        commandGraph: decomposition.commandGraph,
                        workflowIntent: decomposition.workflowIntent
                ]).call()

        then:
        result.planningMode == "aggregate_composition"
        result.aggregateRootObject == "budget"
        result.patternClassification.patternId == "root_child_aggregate"
        result.patternClassification.leafObjectSet == ["budget item"]
    }

    def "classify italian budget prompt with accounting lines as aggregate composition"() {
        given:
        Map decomposition = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Potresti creare un budget operativo per l'anno 2027, per l'azienda ZICORP, con descrizione Budget per apparecchiature di logistica e produzione con 4 righe associate ai conti contabili 612300000 Vehicle Rent per un importo di 100000 USD, 612400000 Other Equipment Rent per un importo di 400000 USD."
                ]).call()

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.classify#AtomicCommandPattern")
                .parameters([
                        atomicCommands: decomposition.atomicCommands,
                        commandGraph: decomposition.commandGraph,
                        workflowIntent: decomposition.workflowIntent
                ]).call()

        then:
        result.planningMode in ["aggregate_composition", "single_morphism"]
        result.patternClassification.aggregateRootObject in ["budget", "budget item", null]
    }

    def "classify cross aggregate workflow for employment position assigned to new person"() {
        given:
        Map decomposition = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Create a new employment position; then assign it to a new employee Mario Rossi"
                ]).call()

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.classify#AtomicCommandPattern")
                .parameters([
                        atomicCommands: decomposition.atomicCommands,
                        commandGraph: decomposition.commandGraph,
                        workflowIntent: decomposition.workflowIntent
                ]).call()

        then:
        result.planningMode == "cross_aggregate_workflow"
        result.patternClassification.patternId == "cross_aggregate_workflow"
        (result.blockedReasons as List)[0].reason == "cross_aggregate_workflow"
    }

    def "classify support ticket open assign deadline prompt as single object composition"() {
        given:
        Map decomposition = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Potresti aprire un ticket urgente per il cambio compressore, assegnarlo a John Doe e impostare la scadenza al 19/04/2026?"
                ]).call()

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.classify#AtomicCommandPattern")
                .parameters([
                        atomicCommands: decomposition.atomicCommands,
                        commandGraph: decomposition.commandGraph,
                        workflowIntent: decomposition.workflowIntent
                ]).call()

        then:
        result.planningMode == "single_object_composition"
        result.aggregateRootObject == "request"
        result.patternClassification.patternId == "single_object_mutation"
    }

    def "map atomic commands to morphism candidates for budget aggregate prompt"() {
        given:
        Map decomposition = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.decompose#PromptIntoAtomicCommands")
                .parameters([
                        queryText: "Create a budget for ZICORP for 2027; then add a budget item for account 612300000 amount 100000 USD"
                ]).call()
        Map classifyResult = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.classify#AtomicCommandPattern")
                .parameters([
                        atomicCommands: decomposition.atomicCommands,
                        commandGraph: decomposition.commandGraph,
                        workflowIntent: decomposition.workflowIntent
                ]).call()

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.map#AtomicCommandsToMorphisms")
                .parameters([
                        atomicCommands: decomposition.atomicCommands,
                        planningMode: classifyResult.planningMode,
                ]).call()

        then:
        result.primaryCommandId == "c2"
        (result.commandMorphismMap as List).size() == 2
        ((result.commandMorphismMap as List)[0].candidates as List)[0].serviceName == "create#mantle.other.budget.Budget"
        ((result.commandMorphismMap as List)[1].candidates as List)[0].serviceName == "create#mantle.other.budget.BudgetItem"
        (result.primaryMorphismCandidates as List)[0].serviceName == "create#mantle.other.budget.BudgetItem"
    }
}
