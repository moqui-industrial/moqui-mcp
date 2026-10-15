/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.mcp

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class McpLlmToolIntegrationTests extends Specification {
    static final String ENUM_TYPE_ID = 'McpTestType'
    static final String ENUM_ID = 'McpTestValue'
    static final String TEST_USER_ID = 'mcp-tool-test'
    static final String PLAIN_USER_ID = 'mcp-tool-plain'
    static final String TEST_GROUP_ID = 'MCP_TOOL_TEST'
    static final String SERVICE_NAME = 'org.moqui.impl.BasicServices.find#Enumeration'

    @Shared ExecutionContext ec
    @Shared McpClient client

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        cleanupFixtures()
        ec.transaction.runUseOrBegin(null, 'MCP test fixtures') {
            ec.entity.makeValue('moqui.basic.EnumerationType')
                    .setAll([enumTypeId: ENUM_TYPE_ID, description: 'MCP Test Type']).create()
            ec.entity.makeValue('moqui.basic.Enumeration')
                    .setAll([enumId: ENUM_ID, enumTypeId: ENUM_TYPE_ID, description: 'MCP Test Value']).create()
            // run_service keeps artifact authz on: the test group may call the one service the tests use
            ec.entity.makeValue('moqui.security.UserGroup').setAll([userGroupId: TEST_GROUP_ID, description: 'MCP tool test']).create()
            ec.entity.makeValue('moqui.security.ArtifactGroup').setAll([artifactGroupId: TEST_GROUP_ID, description: 'MCP tool test']).create()
            ec.entity.makeValue('moqui.security.ArtifactGroupMember').setAll([artifactGroupId: TEST_GROUP_ID,
                    artifactName: SERVICE_NAME, artifactTypeEnumId: 'AT_SERVICE', inheritAuthz: 'Y']).create()
            ec.entity.makeValue('moqui.security.ArtifactAuthz').setAll([artifactAuthzId: TEST_GROUP_ID, userGroupId: TEST_GROUP_ID,
                    artifactGroupId: TEST_GROUP_ID, authzTypeEnumId: 'AUTHZT_ALWAYS', authzActionEnumId: 'AUTHZA_ALL']).create()
        }
        McpTestUsers.create(ec, TEST_USER_ID)
        ec.transaction.runUseOrBegin(null, 'MCP test fixtures') {
            ec.entity.makeValue('moqui.security.UserGroupMember').setAll([userGroupId: TEST_GROUP_ID, userId: TEST_USER_ID,
                    fromDate: new java.sql.Timestamp(0)]).create()
        }
        McpTestUsers.create(ec, PLAIN_USER_ID, null)
        McpTestUsers.login(ec, TEST_USER_ID)
        ec.artifactExecution.enableAuthz()
        client = new McpClient(ec)
    }

    def cleanupSpec() {
        ec?.user?.logoutUser()
        ec?.artifactExecution?.disableAuthz()
        cleanupFixtures()
        ec?.destroy()
    }

    private void cleanupFixtures() {
        ec.transaction.runUseOrBegin(null, 'MCP test fixtures') {
            ec.entity.find('moqui.basic.Enumeration').condition('enumId', ENUM_ID).deleteAll()
            ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', ENUM_TYPE_ID).deleteAll()
        }
        McpTestUsers.delete(ec, TEST_USER_ID)
        McpTestUsers.delete(ec, PLAIN_USER_ID)
        ec.transaction.runUseOrBegin(null, 'MCP test fixtures') {
            ec.entity.find('moqui.security.ArtifactAuthz').condition('artifactAuthzId', TEST_GROUP_ID).deleteAll()
            ec.entity.find('moqui.security.ArtifactGroupMember').condition('artifactGroupId', TEST_GROUP_ID).deleteAll()
            ec.entity.find('moqui.security.ArtifactGroup').condition('artifactGroupId', TEST_GROUP_ID).deleteAll()
            ec.entity.find('moqui.security.UserGroup').condition('userGroupId', TEST_GROUP_ID).deleteAll()
        }
    }

    def 'tools are the server-side llm-client tools of the assist profile under their own names, without the skills'() {
        when:
        List<Map> tools = client.listTools([pageSize: 100]).tools as List<Map>

        then:
        tools*.name.containsAll(['request', 'browse', 'run_service', 'find_basic', 'screen_use'])
        !tools*.name.any { it in ['write_ui', 'pin', 'find_skill', 'enter_sim'] }
        tools.find { it.name == 'screen_use' }.inputSchema.get('properties').action.get('enum') == ['navigate']
        !tools*.name.any { it.startsWith('moqui_') }
        tools.every { it.inputSchema?.type == 'object' && it.description }
    }

    def 'run_service calls a service as the user through the real service engine'() {
        when:
        Map result = client.callTool('run_service',
                [serviceName: SERVICE_NAME, parameters: [enumTypeId: ENUM_TYPE_ID]])

        then:
        !result.isError
        result.structuredContent.ok
        ((List) result.structuredContent.result.enumerationList)*.enumId.contains(ENUM_ID)
    }

    def 'request reads through the service REST api, not the entity REST api'() {
        when:
        Map services = client.callTool('request', [method: 'GET', path: '/rest/s1/moqui/dataDocuments'])
        Map entities = client.callTool('request', [method: 'GET', path: '/rest/e1/moqui.basic.Enumeration'])

        then:
        !services.isError
        services.structuredContent.status == 200
        // llm-client renders the request with a WebFacadeStub, which does not handle /rest/e1
        entities.isError
        (entities.structuredContent.text as String).contains('handleEntityRestCall not supported')
    }

    def 'browse finds screens and every screen hit carries a link'() {
        when:
        Map result = client.callTool('browse', [q: 'enumeration'])
        List hits = result.structuredContent.hits as List

        then:
        !result.isError
        hits.every { Map hit -> !(hit.path as String)?.startsWith('/qapps/') || (hit.url as String)?.endsWith(hit.path as String) }
    }

    def 'screen_use navigate checks the screen with llm-client and returns the link it sent to the Moqui page'() {
        when:
        Map result = client.callTool('screen_use', [action: 'navigate', path: '/qapps/tools/Entity/DataEdit/EntityList',
                parameters: [filterRegexp: 'Enumeration']])

        then:
        !result.isError
        result.structuredContent.sent
        (result.structuredContent.url as String).endsWith('/qapps/tools/Entity/DataEdit/EntityList?filterRegexp=Enumeration')
    }

    def 'screen_use refuses what llm-client refuses and every action but navigate'() {
        when:
        Map rest = client.callTool('screen_use', [action: 'navigate', path: '/rest/s1/moqui/dataDocuments'])
        Map missing = client.callTool('screen_use', [action: 'navigate', path: '/qapps/no/such/Screen'])
        Map snapshot = client.callTool('screen_use', [action: 'snapshot', path: '/qapps/tools'])

        then:
        rest.isError
        missing.isError
        snapshot.isError
    }

    def 'llm-client refusals become tool errors'() {
        when:
        Map result = client.callTool('run_service', [serviceName: 'org.moqui.impl.NoSuchServices.do#Nothing'])

        then:
        result.isError
        result.resultType == 'complete'
    }

    def 'the tool call keeps messages already present on the execution context'() {
        given:
        ec.message.addError('caller error')

        when:
        Map result = client.callTool('run_service',
                [serviceName: SERVICE_NAME, parameters: [enumTypeId: ENUM_TYPE_ID]])

        then:
        !result.isError
        ec.message.errors == ['caller error']

        cleanup:
        ec.message.clearAll()
    }

    def 'unknown tools and the old moqui_ names are rejected'() {
        when:
        client.callTool(name, [:])

        then:
        thrown(IllegalArgumentException)

        where:
        name << ['moqui_call_service', 'write_ui', 'pin', 'find_skill', 'enter_sim', 'no_such_tool']
    }

    def 'without the LlmGateway permission no llm-client tool is listed or callable'() {
        given:
        ec.artifactExecution.disableAuthz()
        McpTestUsers.login(ec, PLAIN_USER_ID)
        ec.artifactExecution.enableAuthz()

        when:
        List<Map> tools = client.listTools([pageSize: 100]).tools as List<Map>

        then:
        !tools*.name.contains('run_service')

        when:
        client.callTool('run_service', [serviceName: 'org.moqui.impl.BasicServices.find#Enumeration'])

        then:
        thrown(IllegalArgumentException)

        cleanup:
        ec.artifactExecution.disableAuthz()
        McpTestUsers.login(ec, TEST_USER_ID)
        ec.artifactExecution.enableAuthz()
    }
}
