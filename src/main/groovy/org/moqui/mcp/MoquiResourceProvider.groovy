package org.moqui.mcp

import groovy.json.JsonBuilder
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ArtifactExecutionFacadeImpl

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
                    uri        : "moqui://entity-def/${entityName}",
                    name       : entityName,
                    description: "Moqui entity or view-entity definition for ${entityName}",
                    mimeType   : 'application/json'
            ])
        }

        ec.entity.find('moqui.entity.document.DataDocument').useCache(true).list()?.each { dd ->
            if (!isDataDocumentVisible(dd as Map)) return
            resources.add([
                    uri        : "moqui://data-document/${dd.dataDocumentId}",
                    name       : dd.documentName ?: dd.dataDocumentId,
                    description: dd.documentName ?: dd.documentTitle ?: "DataDocument ${dd.dataDocumentId}",
                    mimeType   : 'application/json'
            ])
        }

        Map page = McpPaginationSupport.paginate(resources.sort { a, b -> (a.uri ?: '') <=> (b.uri ?: '') }, params, 'resources')
        Map result = [
                resultType: 'complete',
                resources : page.resources,
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private'
        ]
        if (page.nextCursor) result.nextCursor = page.nextCursor
        return result
    }

    Map listResourceTemplates(Map params) {
        List<Map> templates = [
                [
                        name       : 'moqui-entity-definition',
                        title      : 'Moqui Entity Definition',
                        description: 'Read Moqui entity or view-entity definition metadata by full entity name.',
                        uriTemplate: 'moqui://entity-def/{entityName}',
                        mimeType   : 'application/json'
                ],
                [
                        name       : 'moqui-entity-record',
                        title      : 'Moqui Entity Record',
                        description: 'Read a deterministic Moqui entity record by full entity name and complete primary key token.',
                        uriTemplate: 'moqui://entity/{entityName}/{primaryKeyToken}',
                        mimeType   : 'application/json'
                ],
                [
                        name       : 'moqui-data-document',
                        title      : 'Moqui Data Document',
                        description: 'Read DataDocument definition metadata by dataDocumentId.',
                        uriTemplate: 'moqui://data-document/{dataDocumentId}',
                        mimeType   : 'application/json'
                ]
        ]
        Map page = McpPaginationSupport.paginate(templates, params, 'resourceTemplates')
        Map result = [
                resultType       : 'complete',
                resourceTemplates: page.resourceTemplates,
                ttlMs            : McpClient.CACHE_TTL_MS,
                cacheScope       : 'private'
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
        List<Map> fieldList = ed.getAllFieldNames().collect { String fieldName ->
            def fieldNode = ed.getFieldNode(fieldName)
            [
                    name     : fieldName,
                    type     : fieldNode?.attribute('type'),
                    isPk     : pkFieldNames.contains(fieldName),
                    notNull  : fieldNode?.attribute('not-null') == 'true',
                    encrypted: fieldNode?.attribute('encrypt') == 'true'
            ]
        }
        List<Map> relList = ed.getRelationshipsInfo(false).collect { relInfo ->
            [
                    type         : relInfo.type,
                    title        : relInfo.title,
                    relatedEntity: relInfo.relatedEntityName,
                    shortAlias   : relInfo.shortAlias
            ]
        }
        return [
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private',
                contents  : [[
                                     uri     : uri,
                                     mimeType: 'application/json',
                                     text    : new JsonBuilder([
                                             entityName      : entityName,
                                             packageName     : ed.getFullEntityName(),
                                             primaryKeyFields: pkFieldNames,
                                             fieldList       : fieldList,
                                             relationshipList: relList
                                     ]).toString()
                             ]]
        ]
    }

    protected Map readEntityRecord(String uri, String entityName, String pkToken) {
        if (!ec.entity.isEntityDefined(entityName)) throw new IllegalArgumentException("Unknown entity resource ${entityName}")
        if (!isEntityVisible(entityName)) throw new IllegalArgumentException("Entity resource is not available for ${entityName}")
        def ed = ec.entityFacade.getEntityDefinition(entityName)
        List<String> pkFieldNames = ed.getPkFieldNames()
        Map<String, String> pkMap = decodePrimaryKeyToken(pkToken, pkFieldNames)
        def find = ec.entity.find(entityName)
        pkMap.each { String fieldName, String value -> find.condition(fieldName, value) }
        def record = find.one()
        if (!record) throw new IllegalArgumentException("Record resource not found for ${uri}")
        return [
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private',
                contents  : [[
                                     uri     : uri,
                                     mimeType: 'application/json',
                                     text    : new JsonBuilder(record ?: [:]).toString()
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
                ttlMs     : McpClient.CACHE_TTL_MS,
                cacheScope: 'private',
                contents  : [[
                                     uri     : uri,
                                     mimeType: 'application/json',
                                     text    : new JsonBuilder([
                                             dataDocument: dd,
                                             summary     : [
                                                     dataDocumentId      : dd?.dataDocumentId,
                                                     documentName        : dd?.documentName,
                                                     documentTitle       : dd?.documentTitle,
                                                     indexName           : dd?.indexName,
                                                     primaryEntityName   : dd?.primaryEntityName,
                                                     manualDataService   : dd?.manualDataServiceName,
                                                     manualMappingService: dd?.manualMappingServiceName
                                             ],
                                             fieldList   : fields
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
                return ArtifactExecutionFacadeImpl.isPermitted("AT_SERVICE:AUTHZA_VIEW:${manualDataServiceName}", ec)
            } catch (Throwable ignored) {
                return false
            }
        }
        return true
    }

    protected static Map<String, String> decodePrimaryKeyToken(String pkToken, List<String> pkFieldNames) {
        if (!pkToken) throw new IllegalArgumentException('primaryKeyToken is required')
        if (pkFieldNames.size() == 1) {
            return [(pkFieldNames[0]): java.net.URLDecoder.decode(pkToken, 'UTF-8')]
        }

        Map<String, String> decoded = [:]
        pkToken.split(';').each { String pair ->
            List<String> kv = pair.split('=', 2) as List<String>
            if (kv.size() != 2) throw new IllegalArgumentException("Invalid composite primaryKeyToken segment ${pair}")
            decoded[kv[0]] = java.net.URLDecoder.decode(kv[1], 'UTF-8')
        }
        if (decoded.keySet() != pkFieldNames as Set) {
            throw new IllegalArgumentException("Composite primaryKeyToken must include exactly these fields: ${pkFieldNames}")
        }
        return decoded
    }
}
