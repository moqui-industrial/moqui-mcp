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

import groovy.json.JsonBuilder
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.service.ServiceDefinition

class MoquiResourceProvider {
    protected final ExecutionContext ec

    MoquiResourceProvider(ExecutionContext ec) {
        this.ec = ec
    }

    Map listResources(Map params) {
        List<Map> resources = []

        ((Collection<String>) ec.entity.getAllEntityNames()).toList().sort().each { String entityName ->
            if (!isEntityVisible(entityName)) return
            resources.add([
                    uri : "moqui://entity-def/${entityName}",
                    name : entityName,
                    description : "Moqui entity or view-entity definition for ${entityName}",
                    mimeType : 'application/json'
            ])
        }

        ec.entity.find('moqui.entity.document.DataDocument').useCache(true).list()?.each { dd ->
            if (!isDataDocumentVisible(dd as Map)) return
            resources.add([
                    uri : "moqui://data-document/${dd.dataDocumentId}",
                    name : dd.documentName ?: dd.dataDocumentId,
                    description : dd.documentName ?: dd.documentTitle ?: "DataDocument ${dd.dataDocumentId}",
                    mimeType : 'application/json'
            ])
        }

        Map page = McpPaginationSupport.paginate(resources.sort { a, b -> (a.uri ?: '') <=> (b.uri ?: '') }, params, 'resources')
        Map result = [
                resultType : 'complete',
                resources : page.resources,
                ttlMs : McpClient.CACHE_TTL_MS,
                cacheScope : 'private'
        ]
        if (page.nextCursor) result.nextCursor = page.nextCursor
        return result
    }

    Map listResourceTemplates(Map params) {
        List<Map> templates = [
                [
                        name : 'moqui-entity-definition',
                        title : 'Moqui Entity Definition',
                        description : 'Read Moqui entity or view-entity definition metadata by full entity name.',
                        uriTemplate : 'moqui://entity-def/{entityName}',
                        mimeType : 'application/json'
                ],
                [
                        name : 'moqui-entity-record',
                        title : 'Moqui Entity Record',
                        description : 'Read a deterministic Moqui entity record by full entity name and complete primary key token.',
                        uriTemplate : 'moqui://entity/{entityName}/{primaryKeyToken}',
                        mimeType : 'application/json'
                ],
                [
                        name : 'moqui-data-document',
                        title : 'Moqui Data Document',
                        description : 'Read DataDocument definition metadata by dataDocumentId.',
                        uriTemplate : 'moqui://data-document/{dataDocumentId}',
                        mimeType : 'application/json'
                ]
        ]
        Map page = McpPaginationSupport.paginate(templates, params, 'resourceTemplates')
        Map result = [
                resultType : 'complete',
                resourceTemplates : page.resourceTemplates,
                ttlMs : McpClient.CACHE_TTL_MS,
                cacheScope : 'private'
        ]
        if (page.nextCursor) result.nextCursor = page.nextCursor
        return result
    }

    Map readResource(String uri, Map params) {
        if (!uri) throw new IllegalArgumentException('Resource URI is required')

        if (uri.startsWith('moqui://entity-def/')) {
            String entityName = uri.substring('moqui://entity-def/'.length())
            return readEntityDefinition(uri, entityName)
        }

        if (uri.startsWith('moqui://entity/')) {
            String suffix = uri.substring('moqui://entity/'.length())
            int slashIndex = suffix.indexOf('/')
            if (slashIndex < 1) throw new IllegalArgumentException("Invalid entity record URI ${uri}")
            String entityName = suffix.substring(0, slashIndex)
            String pkToken = suffix.substring(slashIndex + 1)
            return readEntityRecord(uri, entityName, pkToken)
        }

        if (uri.startsWith('moqui://data-document/')) {
            String dataDocumentId = uri.substring('moqui://data-document/'.length())
            return readDataDocument(uri, dataDocumentId)
        }

        if (uri.startsWith('entity://')) {
            return readEntityDefinition(uri, uri.substring('entity://'.length()))
        }

        if (uri.startsWith('datadocument://')) {
            return readDataDocument(uri, uri.substring('datadocument://'.length()))
        }

        if (uri.startsWith('record://')) {
            throw new IllegalArgumentException("Legacy record:// URIs are no longer supported. Use moqui://entity/{entityName}/{primaryKeyToken}.")
        }

        throw new IllegalArgumentException("Unsupported resource URI: ${uri}")
    }

    protected Map readEntityDefinition(String uri, String entityName) {
        if (!ec.entity.isEntityDefined(entityName)) throw new IllegalArgumentException("Unknown entity resource ${entityName}")
        if (!isEntityVisible(entityName)) throw new IllegalArgumentException("Entity resource is not available for ${entityName}")
        def ed = ec.entityFacade.getEntityDefinition(entityName)
        List<String> pkFieldNames = ed.getPkFieldNames()
        def entityNode = ed.getEntityNode()
        List<Map> fieldList = ed.getAllFieldNames().collect { String fieldName ->
            def fieldNode = ed.getFieldNode(fieldName)
            [
                    name : fieldName,
                    type : fieldNode?.attribute('type'),
                    isPk : pkFieldNames.contains(fieldName),
                    notNull : fieldNode?.attribute('not-null') == 'true',
                    encrypted : fieldNode?.attribute('encrypt') == 'true'
            ]
        }
        List<Map> relList = ed.getRelationshipsInfo(false).collect { relInfo ->
            [
                    type : relInfo.type,
                    title : relInfo.title,
                    relatedEntity : relInfo.relatedEntityName,
                    shortAlias : relInfo.shortAlias,
                    keyMap : new LinkedHashMap(relInfo.keyMap ?: [:]),
                    keyValueMap : new LinkedHashMap(relInfo.keyValueMap ?: [:])
            ]
        }
        List<Map> memberEntityList = []
        List<Map> aliasList = []
        List<Map> aliasAllList = []
        if (ed.isViewEntity) {
            memberEntityList = entityNode.children('member-entity').collect { memberNode ->
                [
                        entityAlias : memberNode.attribute('entity-alias'),
                        entityName : memberNode.attribute('entity-name'),
                        joinFromAlias : memberNode.attribute('join-from-alias'),
                        joinOptional : memberNode.attribute('join-optional'),
                        subSelect : memberNode.attribute('sub-select'),
                        keyMapList : memberNode.children('key-map').collect { km ->
                            [fieldName : km.attribute('field-name'), related: km.attribute('related') ?: km.attribute('related-field-name')]
                        }
                ]
            }
            aliasList = entityNode.children('alias').collect { aliasNode ->
                [
                        name : aliasNode.attribute('name'),
                        entityAlias : aliasNode.attribute('entity-alias'),
                        field : aliasNode.attribute('field'),
                        function : aliasNode.attribute('function'),
                        groupBy : aliasNode.attribute('group-by')
                ].findAll { key, value -> value != null && value != '' }
            }
            aliasAllList = entityNode.children('alias-all').collect { aliasAllNode ->
                [
                        entityAlias : aliasAllNode.attribute('entity-alias'),
                        prefix : aliasAllNode.attribute('prefix'),
                        excludeList : aliasAllNode.children('exclude').collect { excludeNode -> excludeNode.attribute('field') }
                ].findAll { key, value -> value != null && value != '' && value != [] }
            }
        }
        return [
                resultType : 'complete',
                ttlMs : McpClient.CACHE_TTL_MS,
                cacheScope : 'private',
                contents : [[
                    uri : uri,
                    mimeType : 'application/json',
                    text : new JsonBuilder([
                    entityName : entityName,
                    packageName : entityNode.attribute('package'),
                    isViewEntity : ed.isViewEntity,
                    primaryKeyFields : pkFieldNames,
                    fieldList : fieldList,
                    relationshipList : relList,
                    memberEntityList : memberEntityList,
                    aliasList : aliasList,
                    aliasAllList : aliasAllList,
                    hasEntityCondition : !entityNode.children('entity-condition').isEmpty(),
                    hasHavingCondition : !entityNode.children('having-econditions').isEmpty()
                ]).toString()
        ]]
        ]
    }

    protected Map readEntityRecord(String uri, String entityName, String pkToken) {
        if (!ec.entity.isEntityDefined(entityName)) throw new IllegalArgumentException("Unknown entity resource ${entityName}")
        if (!isEntityVisible(entityName)) throw new IllegalArgumentException("Entity resource is not available for ${entityName}")
        def ed = ec.entityFacade.getEntityDefinition(entityName)
        List<String> pkFieldNames = ed.getPkFieldNames()
        if (!pkFieldNames) throw new IllegalArgumentException("Entity ${entityName} has no deterministic primary key")
        Map<String, String> pkMap = decodePrimaryKeyToken(pkToken, pkFieldNames)
        def find = ec.entity.find(entityName)
        pkMap.each { String fieldName, String value -> find.condition(fieldName, value) }
        def record = find.one()
        if (!record) throw new IllegalArgumentException("Record resource not found for ${uri}")
        Map recordMap = new LinkedHashMap(record)
        ed.getAllFieldNames().each { String fieldName ->
            if (ed.getFieldNode(fieldName)?.attribute('encrypt') == 'true') recordMap.remove(fieldName)
        }
        return [
                resultType : 'complete',
                ttlMs : McpClient.CACHE_TTL_MS,
                cacheScope : 'private',
                contents : [[
                               uri : uri,
                               mimeType : 'application/json',
                               text : new JsonBuilder(recordMap).toString()
                           ]]
        ]
    }

    protected Map readDataDocument(String uri, String dataDocumentId) {
        def dd = ec.entity.find('moqui.entity.document.DataDocument').condition('dataDocumentId', dataDocumentId).one()
        if (!dd) throw new IllegalArgumentException("Unknown data document resource ${dataDocumentId}")
        if (!isDataDocumentVisible(dd as Map)) throw new IllegalArgumentException("Data document resource is not available for ${dataDocumentId}")
        def fields = ec.entity.find('moqui.entity.document.DataDocumentField')
                .condition('dataDocumentId', dataDocumentId).orderBy('sequenceNum').list()
        return [
                resultType: 'complete',
                ttlMs : McpClient.CACHE_TTL_MS,
                cacheScope : 'private',
                contents : [[
                               uri : uri,
                               mimeType : 'application/json',
                               text : new JsonBuilder([
                                             dataDocument : dd,
                                             summary : [
                                             dataDocumentId : dd?.dataDocumentId,
                                             documentName : dd?.documentName,
                                             documentTitle : dd?.documentTitle,
                                             indexName : dd?.indexName,
                                             primaryEntityName : dd?.primaryEntityName,
                                             manualDataService : dd?.manualDataServiceName,
                                             manualMappingService : dd?.manualMappingServiceName
                                             ],
                                             fieldList : fields
                                ]).toString()
                          ]]
        ]
    }

    protected boolean isEntityVisible(String entityName) {
        if (!entityName) return false
        try {
            return ArtifactExecutionFacadeImpl.isPermitted("AT_ENTITY:AUTHZA_VIEW:${entityName}", ec)
        } catch (Throwable ignored) {
            return false
        }
    }

    protected boolean isDataDocumentVisible(Map dd) {
        if (!dd) return false
        String primaryEntityName = dd.primaryEntityName as String
        if (primaryEntityName) return isEntityVisible(primaryEntityName)
        String manualDataServiceName = dd.manualDataServiceName as String
        if (manualDataServiceName) {
            try {
                ServiceDefinition sd = ec.serviceFacade.getServiceDefinition(manualDataServiceName)
                if (sd == null) return false
                return ArtifactExecutionFacadeImpl.isPermitted("AT_SERVICE:${sd.authzAction.name()}:${sd.serviceName}", ec)
            } catch (Throwable ignored) {
                return false
            }
        }
        return false
    }

    protected static Map<String, String> decodePrimaryKeyToken(String pkToken, List<String> pkFieldNames) {
        if (!pkToken) throw new IllegalArgumentException('primaryKeyToken is required')
        if (pkFieldNames.size() == 1) {
            return [(pkFieldNames[0]): strictUrlDecode(pkToken)]
        }
        if (!pkFieldNames) throw new IllegalArgumentException('Resource has no primary key fields')

        Map<String, String> decoded = [:]
        pkToken.split(';').each { String pair ->
            List<String> kv = pair.split('=', 2) as List<String>
            if (kv.size() != 2) throw new IllegalArgumentException("Invalid composite primaryKeyToken segment ${pair}")
            if (decoded.containsKey(kv[0])) throw new IllegalArgumentException("Duplicate primary key field ${kv[0]}")
            decoded[kv[0]] = strictUrlDecode(kv[1])
        }
        if (decoded.keySet() != pkFieldNames as Set) {
            throw new IllegalArgumentException("Composite primaryKeyToken must include exactly these fields: ${pkFieldNames}")
        }
        return decoded
    }

    protected static String strictUrlDecode(String encoded) {
        if (encoded == null) return null
        for (int i = 0; i < encoded.length(); i++) {
            if (encoded.charAt(i) == '%') {
                if (i + 2 >= encoded.length() || !isHex(encoded.charAt(i + 1)) || !isHex(encoded.charAt(i + 2))) {
                    throw new IllegalArgumentException("Malformed percent encoding in primaryKeyToken")
                }
            }
        }
        java.net.URLDecoder.decode(encoded.replace('+', '%2B'), 'UTF-8')
    }

    protected static boolean isHex(char c) {
        (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')
    }
}
