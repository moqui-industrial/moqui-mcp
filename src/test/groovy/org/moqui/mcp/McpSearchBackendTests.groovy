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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl
import spock.lang.Shared
import spock.lang.Specification

class McpSearchBackendTests extends Specification {
    static final String USER_GROUP_ID = 'MCP_SEARCH_TEST'
    static final String ACTIVE_USER_GROUP_ID = 'MCP_SEARCH_ACTIVE'
    static final String ARTIFACT_GROUP_ID = 'MCP_SEARCH_SERVICE'
    static final String ARTIFACT_AUTHZ_ID = 'MCP_SEARCH_AUTHZ'
    static final String ACTIVE_ARTIFACT_AUTHZ_ID = 'MCP_SEARCH_ACTIVE_AUTHZ'
    static final String FILTER_SET_ID = 'MANTLE_USER_ORG'
    static final String ACTIVE_FILTER_SET_ID = 'MANTLE_ACTIVE_ORG'
    static final String FILTER_ID = 'MCP_SEARCH_PRODUCT_FILTER'
    static final String ACTIVE_FILTER_ID = 'MCP_SEARCH_ACTIVE_PRODUCT'
    static final String USER_A = 'mcp-search-a'
    static final String USER_B = 'mcp-search-b'
    static final String USER_ACTIVE = 'mcp-search-active'
    static final String USER_DENIED = 'mcp-search-denied'
    static final String PARTY_A = 'MCP_USER_A'
    static final String PARTY_B = 'MCP_USER_B'
    static final String PARTY_ACTIVE = 'MCP_USER_ACTIVE'
    static final String ORG_A = 'MCP_ORG_A'
    static final String ORG_B = 'MCP_ORG_B'
    static final String SEARCH_SERVICE = 'org.moqui.mcp.McpServices.search#AuthorizedDataDocuments'

    @Shared ExecutionContext ec
    @Shared HttpClient httpClient
    @Shared URI searchEndpoint
    @Shared McpClient client
    @Shared List<Map> sharedRecordsCreated = []
    @Shared boolean filterSetCreated
    @Shared boolean activeFilterSetCreated

    def setupSpec() {
        String endpoint = System.getProperty('moqui.mcp.search.url')?.trim()
        assert endpoint: 'Set -PmcpSearchUrl to a real OpenSearch/Elasticsearch endpoint'
        searchEndpoint = URI.create(endpoint.endsWith('/') ? endpoint : endpoint + '/')
        httpClient = HttpClient.newHttpClient()
        assert request('GET', '', null).statusCode() == 200

        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        assert ec.service.getServiceDefinition('mantle.GeneralServices.search#MantleFiltered') != null
        cleanupUniqueFixtures()
        createSecurityFixtures()
        createOrganizationFixtures()
        indexDocuments()
        ec.cache.clearAllCaches()
        client = new McpClient(ec)
    }

    def cleanupSpec() {
        ec?.user?.logoutUser()
        ec?.artifactExecution?.disableAuthz()
        cleanupUniqueFixtures()
        sharedRecordsCreated.reverseEach { Map record ->
            ec.entity.find(record.entityName as String).condition(record.pkMap as Map).deleteAll()
        }
        if (filterSetCreated) {
            ec.entity.find('moqui.security.EntityFilterSet').condition('entityFilterSetId', FILTER_SET_ID).deleteAll()
        }
        if (activeFilterSetCreated) {
            ec.entity.find('moqui.security.EntityFilterSet').condition('entityFilterSetId', ACTIVE_FILTER_SET_ID).deleteAll()
        }
        deleteIndex('mantle_product')
        deleteIndex('mantle_party')
        deleteIndex('mantle')
        ec?.destroy()
    }

    def 'two users only receive documents from their authorized organizations'() {
        expect:
        searchAs(USER_A, 'MantleProduct', 'needle').collect { it.ownerPartyId } == [ORG_A]
        searchAs(USER_B, 'MantleProduct', 'needle').collect { it.ownerPartyId } == [ORG_B]
        searchAs(USER_A, 'MantleParty', 'needle').collect { it.ownerPartyId } == [ORG_A]
    }

    def 'adversarial query syntax cannot escape the organization conjunction'() {
        when:
        List<Map> results = searchAs(USER_A, 'MantleProduct', query)

        then:
        results.every { it.ownerPartyId == ORG_A }
        !results.any { it.ownerPartyId == ORG_B }

        where:
        query << [
                'needle OR ownerPartyId:MCP_ORG_B',
                '* OR ownerPartyId:MCP_ORG_B',
                '(needle) OR (_exists_:ownerPartyId)',
                'ownerPartyId:*'
        ]
    }

    def 'any-type and pagination counts remain inside the same authorized scope'() {
        when:
        Map result = searchResultAs(USER_A, '_NA_', 'needle', 0, 1)

        then:
        result.documentListCount == 1
        result.documentList.size() == 1
        result.documentList[0].ownerPartyId == ORG_A
        result.pageIndex == 0
        result.pageSize == 1
    }

    def 'active organization scope follows the authenticated user preference'() {
        expect:
        searchAs(USER_ACTIVE, 'MantleProduct', 'needle').collect { it.ownerPartyId } == [ORG_A]

        when:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue('moqui.security.UserPreference').setAll([
                userId: USER_ACTIVE, preferenceKey: 'ACTIVE_ORGANIZATION', preferenceValue: ORG_B
        ]).update()
        ec.cache.clearAllCaches()

        then:
        searchAs(USER_ACTIVE, 'MantleProduct', 'needle').collect { it.ownerPartyId } == [ORG_B]
    }

    def 'user without search service permission cannot invoke the builtin'() {
        given:
        login(USER_DENIED)

        when:
        client.callTool('moqui_search_data_documents', [queryString: 'needle'])

        then:
        thrown(IllegalArgumentException)
    }

    def 'unsupported scopes and types return tool execution errors'() {
        given:
        login(USER_A)

        when:
        Map result = client.callTool('moqui_search_data_documents', arguments)

        then:
        result.isError
        result.content[0].text.startsWith('Unsupported')
        !result.content[0].text.contains('\tat ')

        where:
        arguments << [
                [scope: 'other', queryString: 'needle'],
                [documentType: 'CustomDocument', queryString: 'needle']
        ]
    }

    def 'client-side security overrides are rejected before service execution'() {
        given:
        login(USER_A)

        when:
        client.callTool('moqui_search_data_documents', arguments)

        then:
        thrown(IllegalArgumentException)

        where:
        arguments << [
                [queryString: 'needle', activeOrgId: ORG_B],
                [queryString: 'needle', nestedQueryMap: [ownerPartyId: ORG_B]],
                [queryString: 'needle', clusterName: 'other']
        ]
    }

    private List<Map> searchAs(String userId, String documentType, String query) {
        return (List<Map>) searchResultAs(userId, documentType, query, 0, 20).documentList
    }

    private Map searchResultAs(String userId, String documentType, String query, int pageIndex, int pageSize) {
        login(userId)
        Map result = client.callTool('moqui_search_data_documents', [
                scope: 'mantle', documentType: documentType, queryString: query,
                pageIndex: pageIndex, pageSize: pageSize
        ])
        assert !result.isError: result.content
        return (Map) result.structuredContent
    }

    private void login(String userId) {
        ec.user.logoutUser()
        ec.artifactExecution.disableAuthz()
        assert ((ExecutionContextImpl) ec).userFacade.internalLoginUser(userId, false)
        ec.artifactExecution.enableAuthz()
    }

    private void createSecurityFixtures() {
        ensureShared('moqui.basic.EnumerationType', [enumTypeId: 'ArtifactType', description: 'Artifact Type'])
        ensureShared('moqui.basic.EnumerationType', [enumTypeId: 'AuthzType', description: 'Authorization Type'])
        ensureShared('moqui.basic.EnumerationType', [enumTypeId: 'AuthzAction', description: 'Authorization Action'])
        ensureShared('moqui.basic.Enumeration', [enumId: 'AT_SERVICE', enumTypeId: 'ArtifactType', description: 'Service'])
        ensureShared('moqui.basic.Enumeration', [enumId: 'AUTHZT_ALLOW', enumTypeId: 'AuthzType', description: 'Allow'])
        ensureShared('moqui.basic.Enumeration', [enumId: 'AUTHZA_VIEW', enumTypeId: 'AuthzAction', description: 'View'])
        ensure('moqui.security.UserGroup', [userGroupId: USER_GROUP_ID, description: 'MCP search test users'])
        ensure('moqui.security.UserGroup', [userGroupId: ACTIVE_USER_GROUP_ID, description: 'MCP active organization search user'])
        ensure('moqui.security.ArtifactGroup', [artifactGroupId: ARTIFACT_GROUP_ID, description: 'MCP search facade'])
        ensure('moqui.security.ArtifactGroupMember', [artifactGroupId: ARTIFACT_GROUP_ID,
                artifactName: SEARCH_SERVICE, artifactTypeEnumId: 'AT_SERVICE', inheritAuthz: 'Y'])
        ensure('moqui.security.ArtifactAuthz', [artifactAuthzId: ARTIFACT_AUTHZ_ID,
                userGroupId: USER_GROUP_ID, artifactGroupId: ARTIFACT_GROUP_ID,
                authzTypeEnumId: 'AUTHZT_ALLOW', authzActionEnumId: 'AUTHZA_VIEW'])
        ensure('moqui.security.ArtifactAuthz', [artifactAuthzId: ACTIVE_ARTIFACT_AUTHZ_ID,
                userGroupId: ACTIVE_USER_GROUP_ID, artifactGroupId: ARTIFACT_GROUP_ID,
                authzTypeEnumId: 'AUTHZT_ALLOW', authzActionEnumId: 'AUTHZA_VIEW'])
        if (!ec.entity.find('moqui.security.EntityFilterSet').condition('entityFilterSetId', FILTER_SET_ID).one()) {
            ensure('moqui.security.EntityFilterSet', [entityFilterSetId: FILTER_SET_ID,
                    description: 'MCP test user organization filter'])
            filterSetCreated = true
        }
        ensure('moqui.security.EntityFilter', [entityFilterId: FILTER_ID, entityFilterSetId: FILTER_SET_ID,
                entityName: 'mantle.product.Product', filterMap: '[ownerPartyId:filterOrgIds]'])
        ensure('moqui.security.EntityFilter', [entityFilterId: FILTER_ID + '_P', entityFilterSetId: FILTER_SET_ID,
                entityName: 'mantle.party.Party', filterMap: "[ownerPartyId:(['_NA_']+filterOrgIds)]", joinOr: 'Y'])
        ensure('moqui.security.ArtifactAuthzFilter', [artifactAuthzId: ARTIFACT_AUTHZ_ID,
                entityFilterSetId: FILTER_SET_ID])
        if (!ec.entity.find('moqui.security.EntityFilterSet').condition('entityFilterSetId', ACTIVE_FILTER_SET_ID).one()) {
            ensure('moqui.security.EntityFilterSet', [entityFilterSetId: ACTIVE_FILTER_SET_ID,
                    description: 'MCP test active organization filter', applyCond: 'activeOrgId'])
            activeFilterSetCreated = true
        }
        ensure('moqui.security.EntityFilter', [entityFilterId: ACTIVE_FILTER_ID, entityFilterSetId: ACTIVE_FILTER_SET_ID,
                entityName: 'mantle.product.Product', filterMap: '[ownerPartyId:activeOrgId]'])
        ensure('moqui.security.EntityFilter', [entityFilterId: ACTIVE_FILTER_ID + '_P', entityFilterSetId: ACTIVE_FILTER_SET_ID,
                entityName: 'mantle.party.Party', filterMap: "[ownerPartyId:activeOrgId]"])
        ensure('moqui.security.ArtifactAuthzFilter', [artifactAuthzId: ACTIVE_ARTIFACT_AUTHZ_ID,
                entityFilterSetId: ACTIVE_FILTER_SET_ID])
    }

    private void createOrganizationFixtures() {
        ensureShared('moqui.basic.EnumerationType', [enumTypeId: 'PartyRelationshipType', description: 'Party Relationship'])
        ensureShared('moqui.basic.Enumeration', [enumId: 'PrtEmployee', enumTypeId: 'PartyRelationshipType', description: 'Employee'])
        ensureShared('mantle.party.RoleType', [roleTypeId: 'OrgInternal', description: 'Internal Organization'])
        [[USER_A, PARTY_A, ORG_A], [USER_B, PARTY_B, ORG_B]].each { row ->
            String userId = row[0]
            String partyId = row[1]
            String orgId = row[2]
            ensure('mantle.party.Party', [partyId: orgId, pseudoId: orgId, ownerPartyId: orgId])
            ensure('mantle.party.Party', [partyId: partyId, pseudoId: partyId, ownerPartyId: orgId])
            ensure('mantle.party.PartyRole', [partyId: orgId, roleTypeId: 'OrgInternal'])
            ensure('mantle.party.PartyRelationship', [partyRelationshipId: "${partyId}_${orgId}",
                    relationshipTypeEnumId: 'PrtEmployee', fromPartyId: partyId,
                    toPartyId: orgId, toRoleTypeId: 'OrgInternal', fromDate: ec.user.nowTimestamp])
            ensure('moqui.security.UserAccount', [userId: userId, username: userId, partyId: partyId, disabled: 'N'])
            ensure('moqui.security.UserGroupMember', [userGroupId: USER_GROUP_ID, userId: userId,
                    fromDate: new java.sql.Timestamp(0)])
        }
        ensure('mantle.party.Party', [partyId: PARTY_ACTIVE, pseudoId: PARTY_ACTIVE, ownerPartyId: ORG_A])
        [ORG_A, ORG_B].each { String orgId ->
            ensure('mantle.party.PartyRelationship', [partyRelationshipId: "${PARTY_ACTIVE}_${orgId}",
                    relationshipTypeEnumId: 'PrtEmployee', fromPartyId: PARTY_ACTIVE,
                    toPartyId: orgId, toRoleTypeId: 'OrgInternal', fromDate: ec.user.nowTimestamp])
        }
        ensure('moqui.security.UserAccount', [userId: USER_ACTIVE, username: USER_ACTIVE,
                partyId: PARTY_ACTIVE, disabled: 'N'])
        ensure('moqui.security.UserGroupMember', [userGroupId: ACTIVE_USER_GROUP_ID, userId: USER_ACTIVE,
                fromDate: new java.sql.Timestamp(0)])
        ensure('moqui.security.UserPreference', [userId: USER_ACTIVE,
                preferenceKey: 'ACTIVE_ORGANIZATION', preferenceValue: ORG_A])
        ensure('moqui.security.UserAccount', [userId: USER_DENIED, username: USER_DENIED, disabled: 'N'])
    }

    private void ensure(String entityName, Map values) {
        def ed = ec.entity.getEntityDefinition(entityName)
        Map pkMap = [:]
        ed.pkFieldNames.each { String fieldName -> pkMap[fieldName] = values[fieldName] }
        if (!ec.entity.find(entityName).condition(pkMap).one()) ec.entity.makeValue(entityName).setAll(values).create()
    }

    private void ensureShared(String entityName, Map values) {
        def ed = ec.entity.getEntityDefinition(entityName)
        Map pkMap = [:]
        ed.pkFieldNames.each { String fieldName -> pkMap[fieldName] = values[fieldName] }
        if (!ec.entity.find(entityName).condition(pkMap).one()) {
            ec.entity.makeValue(entityName).setAll(values).create()
            sharedRecordsCreated.add([entityName: entityName, pkMap: pkMap])
        }
    }

    private void indexDocuments() {
        ['mantle_product', 'mantle_party', 'mantle'].each { String index ->
            deleteIndex(index)
            HttpResponse<String> createResponse = request('PUT', index, JsonOutput.toJson([mappings: [properties: [
                    name: [type: 'text'], ownerPartyId: [type: 'keyword']
            ]]]))
            assert createResponse.statusCode() == 200: createResponse.body()
            putDocument(index, 'a', [name: 'needle shared', ownerPartyId: ORG_A])
            putDocument(index, 'b', [name: 'needle shared', ownerPartyId: ORG_B])
            request('POST', "${index}/_refresh", null)
        }
    }

    private void putDocument(String index, String id, Map document) {
        HttpResponse<String> response = request('PUT', "${index}/_doc/${id}", JsonOutput.toJson(document))
        assert response.statusCode() in [200, 201]: response.body()
    }

    private void deleteIndex(String index) {
        if (searchEndpoint == null) return
        request('DELETE', index, null)
    }

    private HttpResponse<String> request(String method, String path, String body) {
        URI uri = searchEndpoint.resolve(path)
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header('Accept', 'application/json')
        if (body != null) builder.header('Content-Type', 'application/json')
        switch (method) {
            case 'GET': builder.GET(); break
            case 'PUT': builder.PUT(HttpRequest.BodyPublishers.ofString(body ?: '')); break
            case 'POST': builder.POST(HttpRequest.BodyPublishers.ofString(body ?: '')); break
            case 'DELETE': builder.DELETE(); break
            default: throw new IllegalArgumentException("Unsupported HTTP method ${method}")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private void cleanupUniqueFixtures() {
        if (ec == null) return
        ec.artifactExecution.disableAuthz()
        [
                ['moqui.security.UserGroupMember', [userGroupId: USER_GROUP_ID]],
                ['moqui.security.UserGroupMember', [userGroupId: ACTIVE_USER_GROUP_ID]],
                ['moqui.security.ArtifactAuthzFilter', [artifactAuthzId: ARTIFACT_AUTHZ_ID]],
                ['moqui.security.ArtifactAuthzFilter', [artifactAuthzId: ACTIVE_ARTIFACT_AUTHZ_ID]],
                ['moqui.security.EntityFilter', [entityFilterId: FILTER_ID]],
                ['moqui.security.EntityFilter', [entityFilterId: FILTER_ID + '_P']],
                ['moqui.security.EntityFilter', [entityFilterId: ACTIVE_FILTER_ID]],
                ['moqui.security.EntityFilter', [entityFilterId: ACTIVE_FILTER_ID + '_P']],
                ['moqui.security.ArtifactAuthz', [artifactAuthzId: ARTIFACT_AUTHZ_ID]],
                ['moqui.security.ArtifactAuthz', [artifactAuthzId: ACTIVE_ARTIFACT_AUTHZ_ID]],
                ['moqui.security.ArtifactGroupMember', [artifactGroupId: ARTIFACT_GROUP_ID]],
                ['moqui.security.ArtifactGroup', [artifactGroupId: ARTIFACT_GROUP_ID]],
                ['moqui.security.UserGroup', [userGroupId: USER_GROUP_ID]],
                ['moqui.security.UserGroup', [userGroupId: ACTIVE_USER_GROUP_ID]],
                ['mantle.party.PartyRelationship', [partyRelationshipId: PARTY_A + '_' + ORG_A]],
                ['mantle.party.PartyRelationship', [partyRelationshipId: PARTY_B + '_' + ORG_B]],
                ['mantle.party.PartyRelationship', [partyRelationshipId: PARTY_ACTIVE + '_' + ORG_A]],
                ['mantle.party.PartyRelationship', [partyRelationshipId: PARTY_ACTIVE + '_' + ORG_B]],
                ['mantle.party.PartyRole', [partyId: ORG_A]],
                ['mantle.party.PartyRole', [partyId: ORG_B]],
                ['moqui.security.UserAccount', [userId: USER_A]],
                ['moqui.security.UserAccount', [userId: USER_B]],
                ['moqui.security.UserPreference', [userId: USER_ACTIVE]],
                ['moqui.security.UserAccount', [userId: USER_ACTIVE]],
                ['moqui.security.UserAccount', [userId: USER_DENIED]],
                ['mantle.party.Party', [partyId: PARTY_A]],
                ['mantle.party.Party', [partyId: PARTY_B]],
                ['mantle.party.Party', [partyId: PARTY_ACTIVE]],
                ['mantle.party.Party', [partyId: ORG_A]],
                ['mantle.party.Party', [partyId: ORG_B]]
        ].each { row -> ec.entity.find(row[0] as String).condition(row[1] as Map).deleteAll() }
    }
}
