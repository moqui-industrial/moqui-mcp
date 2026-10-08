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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.eclipse.jetty.ee11.servlet.ServletContextHandler
import org.eclipse.jetty.ee11.servlet.ServletHolder
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl
import spock.lang.Shared
import spock.lang.Specification

class McpServletIntegrationTests extends Specification {
    static final String TEST_USER_ID = 'mcp-http-test'
    static final String TEST_GROUP_ID = 'MCP_HTTP_TEST'
    static final String TEST_ARTIFACT_GROUP_ID = 'MCP_HTTP_ENTITY'
    static final String TEST_AUTHZ_ID = 'MCP_HTTP_ENTITY_VIEW'

    @Shared ExecutionContext ec
    @Shared Server server
    @Shared URI endpoint
    @Shared HttpClient httpClient
    @Shared List<Map> sharedRecordsCreated = []

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        cleanupFixtures()
        ensureShared('moqui.basic.EnumerationType', [enumTypeId: 'ArtifactType', description: 'Artifact Type'])
        ensureShared('moqui.basic.EnumerationType', [enumTypeId: 'AuthzType', description: 'Authorization Type'])
        ensureShared('moqui.basic.EnumerationType', [enumTypeId: 'AuthzAction', description: 'Authorization Action'])
        ensureShared('moqui.basic.Enumeration', [enumId: 'AT_ENTITY', enumTypeId: 'ArtifactType', description: 'Entity'])
        ensureShared('moqui.basic.Enumeration', [enumId: 'AUTHZT_ALLOW', enumTypeId: 'AuthzType', description: 'Allow'])
        ensureShared('moqui.basic.Enumeration', [enumId: 'AUTHZA_VIEW', enumTypeId: 'AuthzAction', description: 'View'])
        if (!ec.entity.find('moqui.security.UserAccount').condition('userId', TEST_USER_ID).one()) {
            ec.entity.makeValue('moqui.security.UserAccount')
                    .setAll([userId: TEST_USER_ID, username: TEST_USER_ID, disabled: 'N']).create()
        }
        ec.entity.makeValue('moqui.security.UserGroup')
                .setAll([userGroupId: TEST_GROUP_ID, description: 'MCP HTTP test user']).create()
        ec.entity.makeValue('moqui.security.UserGroupMember')
                .setAll([userGroupId: TEST_GROUP_ID, userId: TEST_USER_ID,
                         fromDate: new java.sql.Timestamp(0)]).create()
        ec.entity.makeValue('moqui.security.ArtifactGroup')
                .setAll([artifactGroupId: TEST_ARTIFACT_GROUP_ID, description: 'MCP HTTP entity test']).create()
        ec.entity.makeValue('moqui.security.ArtifactGroupMember')
                .setAll([artifactGroupId: TEST_ARTIFACT_GROUP_ID, artifactName: 'moqui.basic.Enumeration',
                         artifactTypeEnumId: 'AT_ENTITY', inheritAuthz: 'Y']).create()
        ec.entity.makeValue('moqui.security.ArtifactAuthz')
                .setAll([artifactAuthzId: TEST_AUTHZ_ID, userGroupId: TEST_GROUP_ID,
                         artifactGroupId: TEST_ARTIFACT_GROUP_ID, authzTypeEnumId: 'AUTHZT_ALLOW',
                         authzActionEnumId: 'AUTHZA_VIEW']).create()
        ec.cache.clearAllCaches()

        System.setProperty('moqui.mcp.serviceAccountEnabled', 'true')
        System.setProperty('moqui.mcp.serviceAccountUserId', TEST_USER_ID)

        server = new Server(0)
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS)
        context.contextPath = '/'
        context.setAttribute('executionContextFactory', ((ExecutionContextImpl) ec).ecfi)
        context.setInitParameter('moqui-name', 'webroot')
        context.addServlet(new ServletHolder(new McpServlet()), '/mcp')
        server.handler = context
        server.start()
        int port = ((ServerConnector) server.connectors[0]).localPort
        endpoint = URI.create("http://127.0.0.1:${port}/mcp")
        httpClient = HttpClient.newHttpClient()
    }

    def cleanupSpec() {
        server?.stop()
        System.clearProperty('moqui.mcp.serviceAccountEnabled')
        System.clearProperty('moqui.mcp.serviceAccountUserId')
        ec?.artifactExecution?.disableAuthz()
        cleanupFixtures()
        sharedRecordsCreated.reverseEach { Map record ->
            ec.entity.find(record.entityName as String).condition(record.pkMap as Map).deleteAll()
        }
        ec?.destroy()
    }

    private void ensureShared(String entityName, Map values) {
        def entityDefinition = ec.entity.getEntityDefinition(entityName)
        Map pkMap = [:]
        entityDefinition.pkFieldNames.each { String fieldName -> pkMap[fieldName] = values[fieldName] }
        if (!ec.entity.find(entityName).condition(pkMap).one()) {
            ec.entity.makeValue(entityName).setAll(values).create()
            sharedRecordsCreated.add([entityName: entityName, pkMap: pkMap])
        }
    }

    private void cleanupFixtures() {
        if (ec == null) return
        ec.artifactExecution.disableAuthz()
        ec.entity.find('moqui.security.ArtifactAuthz').condition('artifactAuthzId', TEST_AUTHZ_ID).deleteAll()
        ec.entity.find('moqui.security.ArtifactGroupMember')
                .condition('artifactGroupId', TEST_ARTIFACT_GROUP_ID).deleteAll()
        ec.entity.find('moqui.security.ArtifactGroup')
                .condition('artifactGroupId', TEST_ARTIFACT_GROUP_ID).deleteAll()
        ec.entity.find('moqui.security.UserGroupMember').condition('userGroupId', TEST_GROUP_ID).deleteAll()
        ec.entity.find('moqui.security.UserGroup').condition('userGroupId', TEST_GROUP_ID).deleteAll()
        ec.entity.find('moqui.security.UserAccount').condition('userId', TEST_USER_ID).deleteAll()
    }

    def 'real HTTP discover request returns a schema-shaped complete result'() {
        when:
        HttpResponse<String> response = post('server/discover', [:], 41)
        Map body = new JsonSlurper().parseText(response.body()) as Map

        then:
        response.statusCode() == 200
        body.jsonrpc == '2.0'
        body.id == 41
        body.result.resultType == 'complete'
        body.result.supportedVersions == ['2026-07-28']
    }

    def 'real HTTP boundary rejects malformed transport and envelope input'() {
        expect:
        rawPost('{', standardHeaders('server/discover')).statusCode() == 400
        rawPost(JsonOutput.toJson(requestMap('server/discover', [:], 1)),
                headersWithout('server/discover', 'MCP-Protocol-Version')).statusCode() == 400
        rawPost(JsonOutput.toJson(requestMap('server/discover', [:], 1)),
                standardHeaders('server/discover') + ['Accept': 'application/json']).statusCode() == 406
        rawPost(JsonOutput.toJson(requestMap('server/discover', [:], 1)),
                standardHeaders('server/discover') + ['Mcp-Method': 'tools/list']).statusCode() == 400
        rawPost(JsonOutput.toJson(requestMap('resources/read', [uri: 'moqui://entity-def/moqui.basic.Enumeration'], 1)),
                standardHeaders('resources/read') + ['Mcp-Name': '=?base64?not canonical!?=']).statusCode() == 400
    }

    def 'real HTTP boundary rejects unknown method origin and oversized body'() {
        when:
        HttpResponse<String> unknown = post('unknown/method', [:], 'unknown')
        Map unknownBody = new JsonSlurper().parseText(unknown.body()) as Map

        then:
        unknown.statusCode() == 404
        unknownBody.id == 'unknown'
        unknownBody.error.code == -32601

        and:
        rawPost(JsonOutput.toJson(requestMap('server/discover', [:], 2)),
                standardHeaders('server/discover') + ['Origin': 'https://attacker.invalid']).statusCode() == 403

        and:
        rawPost(' ' * (McpServlet.MAX_BODY_CHARS + 1), standardHeaders('server/discover')).statusCode() == 413
    }

    def 'non POST requests are not exposed as an MCP stream'() {
        when:
        HttpRequest request = HttpRequest.newBuilder(endpoint).GET().build()

        then:
        httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 405
    }

    def 'name-bearing request accepts canonical base64 and OPTIONS is transport-only'() {
        given:
        String uri = 'moqui://entity-def/moqui.basic.Enumeration'
        String encodedName = '=?base64?' + Base64.encoder.encodeToString(uri.getBytes('UTF-8')) + '?='

        when:
        HttpResponse<String> readResponse = rawPost(JsonOutput.toJson(requestMap('resources/read', [uri: uri], 51)),
                standardHeaders('resources/read') + ['Mcp-Name': encodedName])
        HttpResponse<String> optionsResponse = httpClient.send(HttpRequest.newBuilder(endpoint)
                .method('OPTIONS', HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString())

        then:
        readResponse.statusCode() == 200
        new JsonSlurper().parseText(readResponse.body()).result.resultType == 'complete'
        optionsResponse.statusCode() == 204
    }

    def 'request without Moqui identity is rejected'() {
        given:
        System.clearProperty('moqui.mcp.serviceAccountEnabled')

        when:
        HttpResponse<String> response = rawPost(JsonOutput.toJson(requestMap('server/discover', [:], 61)),
                standardHeaders('server/discover'))

        then:
        response.statusCode() == 401

        cleanup:
        System.setProperty('moqui.mcp.serviceAccountEnabled', 'true')
    }

    private HttpResponse<String> post(String method, Map params, Object id) {
        return rawPost(JsonOutput.toJson(requestMap(method, params, id)), standardHeaders(method))
    }

    private static Map requestMap(String method, Map params, Object id) {
        Map requestParams = new LinkedHashMap(params ?: [:])
        requestParams._meta = [
                'io.modelcontextprotocol/protocolVersion'  : '2026-07-28',
                'io.modelcontextprotocol/clientCapabilities': [:]
        ]
        return [jsonrpc: '2.0', id: id, method: method, params: requestParams]
    }

    private HttpResponse<String> rawPost(String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.each { String name, String value -> builder.header(name, value) }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private static Map<String, String> standardHeaders(String method) {
        return [
                'Content-Type'        : 'application/json',
                'Accept'              : 'application/json, text/event-stream',
                'MCP-Protocol-Version': '2026-07-28',
                'Mcp-Method'          : method
        ]
    }

    private static Map<String, String> headersWithout(String method, String headerName) {
        Map<String, String> headers = new LinkedHashMap<>(standardHeaders(method))
        headers.remove(headerName)
        return headers
    }
}
