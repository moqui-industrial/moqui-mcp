import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import spock.lang.Shared
import spock.lang.Specification

class AgentXmlActionsPrototypeTests extends Specification {
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

    private void cleanupProjectAggregate(String projectId, List milestoneIds, List taskIds) {
        if (!projectId) return
        def cf = ec.entity.conditionFactory
        List<String> ids = ([projectId] + (milestoneIds ?: []) + (taskIds ?: [])).findAll { it } as List<String>
        if (!ids) return
        List<String> childIds = ((milestoneIds ?: []) + (taskIds ?: [])).findAll { it } as List<String>
        boolean began = ec.transaction.begin(null)
        try {
            def inCond = cf.makeCondition('workEffortId', EntityCondition.IN, ids)
            def toInCond = cf.makeCondition('toWorkEffortId', EntityCondition.IN, ids)
            ec.entity.find('mantle.work.effort.WorkEffortAssoc')
                    .condition(cf.makeCondition([inCond, toInCond], EntityCondition.OR))
                    .deleteAll()
            ec.entity.find('mantle.work.effort.WorkEffortParty').condition('workEffortId', EntityCondition.IN, ids).deleteAll()
            if (childIds) {
                ec.entity.find('mantle.work.effort.WorkEffort').condition('workEffortId', EntityCondition.IN, childIds).deleteAll()
            }
            ec.entity.find('mantle.work.effort.WorkEffort').condition('workEffortId', projectId).deleteAll()
            ec.transaction.commit(began)
        } catch (Throwable t) {
            ec.transaction.rollback(began, "Unable to cleanup test project aggregate", t)
            throw t
        }
    }

    private void cleanupRequest(String requestId) {
        if (!requestId) return
        boolean began = ec.transaction.begin(null)
        try {
            ec.entity.find('mantle.request.RequestCommEvent').condition('requestId', requestId).deleteAll()
            ec.entity.find('mantle.request.RequestParty').condition('requestId', requestId).deleteAll()
            ec.entity.find('mantle.request.Request').condition('requestId', requestId).deleteAll()
            ec.transaction.commit(began)
        } catch (Throwable t) {
            ec.transaction.rollback(began, "Unable to cleanup test request", t)
            throw t
        }
    }

    private void cleanupGeneratedMorphism(String morphismId, String generatedServiceFilePath) {
        if (generatedServiceFilePath) {
            File file = new File(generatedServiceFilePath)
            if (file.exists()) file.delete()
        }
        if (!morphismId) return
        boolean began = ec.transaction.begin(null)
        try {
            ec.entity.find('moqui.math.Parameter').condition('morphismId', morphismId).deleteAll()
            ec.entity.find('moqui.math.ct.Morphism').condition('morphismId', morphismId).deleteAll()
            ec.transaction.commit(began)
        } catch (Throwable t) {
            ec.transaction.rollback(began, "Unable to cleanup generated morphism", t)
            throw t
        }
    }

    def "prototype compiles project aggregate prompt into xml actions"() {
        given:
        String prompt = "Potresti creare un nuovo progetto Commessa Hormel Food Corporation 2027, con priorità 3, purpose Product Development, con 2 milestone Installazione Meccanica e Installazione Automazione. Inoltre potresti creare sotto al primo milestone 2 tasks con nome Installazione gruppo freddo ed Installazione AHU , e sotto al secondo milestone i 3 tasks Installazione inverter e remote IO module e Installazione software , Test software e ricette. Tutti con priorità 3. Assegna tutti i taks a John Doe con ruolo Project Manager."
        String generatedMorphismId = null
        String generatedServiceFilePath = null

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.execute#ProjectAggregateXmlActionsPrototype")
                .parameters([queryText: prompt, dryRun: true])
                .call()
        generatedMorphismId = result.generatedMorphismId as String
        generatedServiceFilePath = result.generatedServiceFilePath as String

        then:
        result.success == true
        result.generatedXmlActions.contains('mantle.work.ProjectServices.create#Project')
        result.generatedXmlActions.contains('mantle.work.ProjectServices.create#Milestone')
        result.generatedXmlActions.contains('mantle.work.TaskServices.create#Task')
        result.generatedGroovy.contains('projectOut')
        result.generatedGroovy.contains('taskOut1')
        result.generatedServiceLocation?.contains('component://moqui-mcp/service/org/moqui/agent/generated/')
        result.generatedServiceFilePath
        new File(result.generatedServiceFilePath as String).exists()
        ec.entity.find('moqui.math.ct.Morphism').condition('morphismId', result.generatedMorphismId).one()?.serviceName == result.generatedServiceName
        ec.serviceFacade.getServiceDefinition(result.generatedServiceName as String) != null
        (result.projectPlan.milestones as List).size() == 2

        cleanup:
        cleanupGeneratedMorphism(generatedMorphismId, generatedServiceFilePath)
    }

    def "prototype executes project aggregate prompt through native xml actions context propagation"() {
        given:
        String prompt = "Potresti creare un nuovo progetto Commessa Hormel Food Corporation 2027, con priorità 3, purpose Product Development, con 2 milestone Installazione Meccanica e Installazione Automazione. Inoltre potresti creare sotto al primo milestone 2 tasks con nome Installazione gruppo freddo ed Installazione AHU , e sotto al secondo milestone i 3 tasks Installazione inverter e remote IO module e Installazione software , Test software e ricette. Tutti con priorità 3. Assegna tutti i taks a John Doe con ruolo Project Manager."
        String projectId = null
        List milestoneIds = []
        List taskIds = []
        String generatedMorphismId = null
        String generatedServiceFilePath = null

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.execute#ProjectAggregateXmlActionsPrototype")
                .parameters([queryText: prompt, dryRun: false])
                .call()
        projectId = result.projectId as String
        milestoneIds = (result.milestoneIds ?: []) as List
        taskIds = (result.taskIds ?: []) as List
        generatedMorphismId = result.generatedMorphismId as String
        generatedServiceFilePath = result.generatedServiceFilePath as String

        then:
        result.success == true
        projectId
        milestoneIds.size() == 2
        taskIds.size() == 5
        new File(result.generatedServiceFilePath as String).exists()

        and:
        ec.entity.find('mantle.work.effort.WorkEffort').condition('workEffortId', projectId).one()?.workEffortName == 'Commessa Hormel Food Corporation 2027'
        ec.entity.find('mantle.work.effort.WorkEffort').condition('rootWorkEffortId', projectId).condition('workEffortTypeEnumId', 'WetMilestone').count() == 2
        ec.entity.find('mantle.work.effort.WorkEffort').condition('rootWorkEffortId', projectId).condition('workEffortTypeEnumId', 'WetTask').count() == 5

        cleanup:
        cleanupProjectAggregate(projectId, milestoneIds, taskIds)
        cleanupGeneratedMorphism(generatedMorphismId, generatedServiceFilePath)
    }

    def "runtime resolve and execute uses xml actions prototype for project aggregate prompt"() {
        given:
        String prompt = "Potresti creare un nuovo progetto Commessa Hormel Food Corporation 2027, con priorità 3, purpose Product Development, con 2 milestone Installazione Meccanica e Installazione Automazione. Inoltre potresti creare sotto al primo milestone 2 tasks con nome Installazione gruppo freddo ed Installazione AHU , e sotto al secondo milestone i 3 tasks Installazione inverter e remote IO module e Installazione software , Test software e ricette. Tutti con priorità 3. Assegna tutti i taks a John Doe con ruolo Project Manager."
        String projectId = null
        List milestoneIds = []
        List taskIds = []

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentRuntimeServices.resolve#AndExecuteAgentPrompt")
                .parameters([queryText: prompt, dryRun: false, confirmed: true])
                .call()
        Map executionResult = (result.executionResult ?: [:]) as Map
        projectId = executionResult.projectId as String
        milestoneIds = (executionResult.milestoneIds ?: []) as List
        taskIds = (executionResult.taskIds ?: []) as List

        then:
        result.success == true
        result.resolvedDocumentId == 'agent-xmlactions://project-aggregate-prototype'
        projectId
        milestoneIds.size() == 2
        taskIds.size() == 5
        ec.entity.find('mantle.work.effort.WorkEffort').condition('workEffortId', projectId).one()?.workEffortName == 'Commessa Hormel Food Corporation 2027'

        cleanup:
        cleanupProjectAggregate(projectId, milestoneIds, taskIds)
    }

    def "prototype executes support request prompt through native xml actions context propagation"() {
        given:
        String prompt = "Potresti creare una richiesta di support con priorità 1, assegnata a John Doe, con nome Cambio compressore e descrizione Cambio urgente compressore per rottura motore con data scadenza 19/04/2027."
        String requestId = null
        String generatedMorphismId = null
        String generatedServiceFilePath = null

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentAlgebraicServices.execute#SupportRequestXmlActionsPrototype")
                .parameters([queryText: prompt, dryRun: false])
                .call()
        requestId = result.requestId as String
        generatedMorphismId = result.generatedMorphismId as String
        generatedServiceFilePath = result.generatedServiceFilePath as String

        then:
        result.success == true
        requestId
        new File(result.generatedServiceFilePath as String).exists()
        def request = ec.entity.find('mantle.request.Request').condition('requestId', requestId).one()
        request?.requestName == 'Cambio compressore'
        request?.priority == 1
        request?.requestTypeEnumId == 'RqtSupport'
        ec.entity.find('mantle.request.RequestParty').condition('requestId', requestId).count() >= 2

        cleanup:
        cleanupRequest(requestId)
        cleanupGeneratedMorphism(generatedMorphismId, generatedServiceFilePath)
    }

    def "runtime resolve and execute uses xml actions prototype for support request prompt"() {
        given:
        String prompt = "Potresti creare una richiesta di support con priorità 1, assegnata a John Doe, con nome Cambio compressore e descrizione Cambio urgente compressore per rottura motore con data scadenza 19/04/2027."
        String requestId = null

        when:
        Map result = ec.service.sync()
                .name("org.moqui.agent.AgentRuntimeServices.resolve#AndExecuteAgentPrompt")
                .parameters([queryText: prompt, dryRun: false, confirmed: true])
                .call()
        Map executionResult = (result.executionResult ?: [:]) as Map
        requestId = executionResult.requestId as String

        then:
        result.success == true
        result.resolvedDocumentId == 'agent-xmlactions://support-request-prototype'
        requestId
        def request = ec.entity.find('mantle.request.Request').condition('requestId', requestId).one()
        request?.requestName == 'Cambio compressore'
        request?.priority == 1
        request?.requestTypeEnumId == 'RqtSupport'

        cleanup:
        cleanupRequest(requestId)
    }
}
