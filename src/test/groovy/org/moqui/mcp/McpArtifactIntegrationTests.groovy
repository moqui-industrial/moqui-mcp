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

import groovy.json.JsonSlurper
import java.net.URLEncoder
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.service.ServiceDefinition
import spock.lang.Shared
import spock.lang.Specification

class McpArtifactIntegrationTests extends Specification {
    static final String ENUM_TYPE_ID = 'McpTestType'
    static final String ENUM_ID = 'McpTestValue'
    static final String TEST_ENTITY_ID = 'mcp+a%/=&é'
    static final String REMOTE_ID = 'MCP_TEST_REMOTE'
    static final String DATA_DOCUMENT_ID = 'McpTestDocument'
    static final String UNBOUND_DATA_DOCUMENT_ID = 'McpTestUnbound'
    static final String TEST_USER_ID = 'mcp-artifact-test'

    @Shared ExecutionContext ec
    @Shared TestableMcpClient client
    @Shared TestableResourceProvider resources

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        cleanupFixtures()
        ec.entity.makeValue('moqui.security.UserAccount')
                .setAll([userId: TEST_USER_ID, username: TEST_USER_ID, disabled: 'N']).create()
        assert ((ExecutionContextImpl) ec).userFacade.internalLoginUser(TEST_USER_ID, false)
        ec.entity.makeValue('moqui.basic.EnumerationType')
                .setAll([enumTypeId: ENUM_TYPE_ID, description: 'MCP Test Type']).create()
        ec.entity.makeValue('moqui.basic.Enumeration')
                .setAll([enumId: ENUM_ID, enumTypeId: ENUM_TYPE_ID, description: 'MCP Test Value']).create()
        ec.entity.makeValue('moqui.test.TestEntity')
                .setAll([testId: TEST_ENTITY_ID, testMedium: 'read-only fixture']).create()
        ec.entity.makeValue('moqui.service.message.SystemMessageRemote')
                .setAll([systemMessageRemoteId: REMOTE_ID, description: 'secret fixture',
                         username: 'remote-user', password: 'secret-value', privateKey: 'private-value']).create()
        ec.entity.makeValue('moqui.entity.document.DataDocument')
                .setAll([dataDocumentId: DATA_DOCUMENT_ID, documentName: 'MCP Test Document',
                         primaryEntityName: 'moqui.basic.Enumeration']).create()
        ec.entity.makeValue('moqui.entity.document.DataDocument')
                .setAll([dataDocumentId: UNBOUND_DATA_DOCUMENT_ID, documentName: 'MCP Unbound Document']).create()
        client = new TestableMcpClient(ec)
        resources = new TestableResourceProvider(ec)
    }

    def cleanupSpec() {
        ec?.user?.logoutUser()
        cleanupFixtures()
        ec?.destroy()
    }

    private void cleanupFixtures() {
        ec.entity.find('moqui.basic.Enumeration').condition('enumId', ENUM_ID).deleteAll()
        ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', ENUM_TYPE_ID).deleteAll()
        ec.entity.find('moqui.test.TestEntity').condition('testId', TEST_ENTITY_ID).deleteAll()
        ec.entity.find('moqui.service.message.SystemMessageRemote')
                .condition('systemMessageRemoteId', REMOTE_ID).deleteAll()
        ec.entity.find('moqui.entity.document.DataDocument')
                .condition('dataDocumentId', 'in', [DATA_DOCUMENT_ID, UNBOUND_DATA_DOCUMENT_ID]).deleteAll()
        ec.entity.find('moqui.security.UserAccount').condition('userId', TEST_USER_ID).deleteAll()
    }

    def 'tool catalog exposes concrete authorized services with stable safe aliases'() {
        given:
        String remoteService = 'org.moqui.impl.BasicServices.find#Enumeration'
        String nonRemoteService = 'org.moqui.impl.WikiServices.get#PublishedWikiPageText'

        when:
        List<Map> tools = client.serviceTools()
        Map remoteTool = tools.find { it._meta?.get('org.moqui/originalName') == remoteService }

        then:
        remoteTool
        remoteTool.name == TestableMcpClient.alias(remoteService)
        remoteTool.name ==~ /[A-Za-z0-9_-]{1,96}/
        remoteTool.inputSchema instanceof Map
        remoteTool.outputSchema instanceof Map
        tools.any { it._meta?.get('org.moqui/originalName') == nonRemoteService }
        !tools.any { (it._meta?.get('org.moqui/originalName') as String)?.startsWith('org.moqui.mcp.McpServices.') }
    }

    def 'direct and helper tool calls traverse the real Moqui service engine'() {
        given:
        String serviceName = 'org.moqui.impl.BasicServices.find#Enumeration'
        String alias = TestableMcpClient.alias(serviceName)

        when:
        Map direct = client.callTool(alias, [enumTypeId: ENUM_TYPE_ID])
        Map helper = client.callTool('moqui_call_service',
                [serviceName: serviceName, parameters: [enumTypeId: ENUM_TYPE_ID]])

        then:
        !direct.isError
        direct.resultType == 'complete'
        ((List) direct.structuredContent.enumerationList)*.enumId.contains(ENUM_ID)
        !helper.isError
        ((List) helper.structuredContent.enumerationList)*.enumId.contains(ENUM_ID)
    }

    def 'catalog and invocation recalculate Moqui authorization on every request'() {
        given:
        String serviceName = 'org.moqui.impl.BasicServices.find#Enumeration'
        String alias = TestableMcpClient.alias(serviceName)
        assert client.serviceTools().any { it.name == alias }

        when:
        ec.user.logoutUser()
        ec.artifactExecution.enableAuthz()

        then:
        !client.serviceTools().any { it.name == alias }

        when:
        client.callTool(alias, [enumTypeId: ENUM_TYPE_ID])

        then:
        thrown(IllegalArgumentException)

        cleanup:
        ec.artifactExecution.disableAuthz()
        assert ((ExecutionContextImpl) ec).userFacade.internalLoginUser(TEST_USER_ID, false)
    }

    def 'non remote metadata is available and service validation becomes a tool error'() {
        when:
        Map metadata = client.callTool('moqui_get_service_metadata',
                [serviceName: 'org.moqui.impl.WikiServices.get#PublishedWikiPageText'])

        then:
        !metadata.isError
        metadata.structuredContent.serviceName == 'org.moqui.impl.WikiServices.get#PublishedWikiPageText'

        when:
        Map result = client.callTool(TestableMcpClient.alias('org.moqui.impl.BasicServices.find#Enumeration'), [:])

        then:
        result.isError
        result.resultType == 'complete'
        !result.containsKey('structuredContent')
        !ec.message.hasError()
    }

    def 'tool invocation preserves messages already present on the execution context'() {
        given:
        ec.message.addError('caller error')

        when:
        Map result = client.callTool(TestableMcpClient.alias('org.moqui.impl.BasicServices.find#Enumeration'),
                [enumTypeId: ENUM_TYPE_ID])

        then:
        !result.isError
        ec.message.errors == ['caller error']

        cleanup:
        ec.message.clearAll()
    }

    def 'entity and view metadata preserve relationships members aliases and functions'() {
        when:
        Map entityResult = resources.readResource('moqui://entity-def/moqui.basic.Enumeration', [:])
        Map viewResult = resources.readResource('moqui://entity-def/moqui.test.FooBar', [:])
        Map entityMetadata = parseContent(entityResult)
        Map viewMetadata = parseContent(viewResult)

        then:
        entityMetadata.packageName == 'moqui.basic'
        entityMetadata.primaryKeyFields == ['enumId']
        entityMetadata.relationshipList.any { it.relatedEntity == 'moqui.basic.EnumerationType' && it.keyMap }
        viewMetadata.isViewEntity
        viewMetadata.memberEntityList*.entityAlias.containsAll(['T1', 'T2'])
        viewMetadata.aliasList.any { it.name == 'score' && it.function == 'sum' }
        viewMetadata.aliasAllList.any { it.entityAlias == 'T1' }
    }

    def 'record URI codec is deterministic and encrypted fields are omitted'() {
        given:
        String token = encodePathSegment(TEST_ENTITY_ID)

        when:
        Map record = parseContent(resources.readResource("moqui://entity/moqui.test.TestEntity/${token}", [:]))
        Map secretRecord = parseContent(resources.readResource(
                "moqui://entity/moqui.service.message.SystemMessageRemote/${REMOTE_ID}", [:]))

        then:
        record.testId == TEST_ENTITY_ID
        record.testMedium == 'read-only fixture'
        secretRecord.username == 'remote-user'
        !secretRecord.containsKey('password')
        !secretRecord.containsKey('privateKey')
    }

    def 'composite key decoder rejects incomplete duplicate and malformed tokens'() {
        expect:
        TestableResourceProvider.decode('left=a%2Bb;right=%25%2F%3D%26%C3%A9', ['left', 'right']) ==
                [left: 'a+b', right: '%/=&é']

        when:
        TestableResourceProvider.decode(token, ['left', 'right'])

        then:
        thrown(IllegalArgumentException)

        where:
        token << ['left=a', 'left=a;left=b;right=c', 'left=%ZZ;right=c', 'left=a;right=b;extra=c']
    }

    def 'view without a deterministic primary key cannot be read as a record'() {
        when:
        resources.readResource('moqui://entity/moqui.test.FooBar/anything', [:])

        then:
        thrown(IllegalArgumentException)
    }

    def 'data document resources require an authorizable entity or service source'() {
        when:
        Map listed = resources.listResources([pageSize: 1000])
        Map document = parseContent(resources.readResource(
                "moqui://data-document/${DATA_DOCUMENT_ID}", [:]))

        then:
        listed.resources*.uri.contains("moqui://data-document/${DATA_DOCUMENT_ID}")
        !listed.resources*.uri.contains("moqui://data-document/${UNBOUND_DATA_DOCUMENT_ID}")
        document.summary.primaryEntityName == 'moqui.basic.Enumeration'

        when:
        resources.readResource("moqui://data-document/${UNBOUND_DATA_DOCUMENT_ID}", [:])

        then:
        thrown(IllegalArgumentException)
    }

    def 'direct resource reads recalculate authorization and cannot bypass it'() {
        given:
        assert resources.listResources([pageSize: 1000]).resources
                .any { it.uri == 'moqui://entity-def/moqui.basic.Enumeration' }

        and:
        ec.user.logoutUser()
        ec.artifactExecution.enableAuthz()

        when:
        resources.readResource('moqui://entity-def/moqui.basic.Enumeration', [:])

        then:
        thrown(IllegalArgumentException)

        cleanup:
        ec.message.clearAll()
        ec.artifactExecution.disableAuthz()
        assert ((ExecutionContextImpl) ec).userFacade.internalLoginUser(TEST_USER_ID, false)
    }

    private static Map parseContent(Map result) {
        assert result.resultType == 'complete'
        return new JsonSlurper().parseText(result.contents[0].text as String) as Map
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, 'UTF-8').replace('+', '%20')
    }

    static class TestableMcpClient extends McpClient {
        TestableMcpClient(ExecutionContext ec) { super(ec) }
        List<Map> serviceTools() { getServiceToolList() }
        static String alias(String serviceName) { serviceToolName(serviceName) }
        ServiceDefinition definition(String serviceName) { ec.serviceFacade.getServiceDefinition(serviceName) }
    }

    static class TestableResourceProvider extends MoquiResourceProvider {
        TestableResourceProvider(ExecutionContext ec) { super(ec) }
        static Map<String, String> decode(String token, List<String> fields) {
            decodePrimaryKeyToken(token, fields)
        }
    }
}
