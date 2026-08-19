package org.moqui.mcp

import groovy.json.JsonBuilder
import org.moqui.context.ExecutionContext

class McpClient {
    protected final ExecutionContext ec
    static final String PROTOCOL_VERSION = '2026-07-28'
    static final long CACHE_TTL_MS = 300000L

    McpClient(ExecutionContext ec) {
        this.ec = ec
    }

    Map handle(String method, Map params) {
        Map<String, Closure<Map>> handlers = [
                'server/discover'      : { Map p -> serverDiscover() },
                'tools/list'           : { Map p -> listTools() },
                'tools/call'           : { Map p -> callTool((String) p.name, p.arguments instanceof Map ? (Map) p.arguments : [:]) },
                'resources/list'       : { Map p -> listResources() },
                'resources/read'       : { Map p -> readResource((String) p.uri) },
                'prompts/list'         : { Map p -> listPrompts() },
                'prompts/get'          : { Map p -> getPrompt((String) p.name, p.arguments instanceof Map ? (Map) p.arguments : [:]) }
        ]
        Closure<Map> handler = handlers[method]
        if (handler == null) throw new IllegalStateException("Unsupported MCP method: ${method}")
        return handler.call(params ?: [:])
    }

    Map serverDiscover() {
        return [
                resultType       : 'complete',
                supportedVersions: [PROTOCOL_VERSION],
                capabilities     : [
                        tools    : [listChanged: false],
                        resources: [listChanged: false],
                        prompts  : [listChanged: false]
                ],
                instructions     : 'Use tools for mutations, resources for schema and document inspection, and prompts for wiki-backed Moqui guidance. Every request is stateless and must include protocol metadata.',
                _meta            : [
                        'io.modelcontextprotocol/serverInfo': [
                                name   : 'Moqui MCP Server',
                                version: '3.0.0'
                        ]
                ],
                ttlMs            : 3600000,
                cacheScope       : 'public'
        ]
    }

    Map listTools() {
        List<Map> toolList = [
                [
                        name       : 'moqui_search_data_documents',
                        title      : 'Search Data Documents',
                        description: 'Run text search against Moqui DataDocument indexes in OpenSearch. Use this for all business lookups before calling mutating services.',
                        inputSchema: [
                                type      : 'object',
                                properties: [
                                        indexName   : [type: 'string', description: 'OpenSearch index name'],
                                        documentType: [type: 'string', description: 'Optional DataDocument identifier'],
                                        queryString : [type: 'string', description: 'Lucene/Elastic query string'],
                                        pageIndex   : [type: 'integer'],
                                        pageSize    : [type: 'integer']
                                ],
                                required  : ['indexName', 'queryString'],
                                additionalProperties: false
                        ]
                ],
                [
                        name       : 'moqui_call_service',
                        title      : 'Call Moqui Service',
                        description: 'Invoke an existing Moqui service by full service name with explicit parameters. Use only after required identifiers and enumerations have been resolved.',
                        inputSchema: [
                                type      : 'object',
                                properties: [
                                        serviceName: [type: 'string', description: 'Full Moqui service name'],
                                        parameters : [type: 'object', description: 'Input parameter map']
                                ],
                                required  : ['serviceName'],
                                additionalProperties: false
                        ]
                ],
                [
                        name       : 'moqui_make_notification',
                        title      : 'Create Notification Message',
                        description: 'Create a Moqui notification message using the standard NotificationMessage mechanism.',
                        inputSchema: [
                                type      : 'object',
                                properties: [
                                        topic      : [type: 'string'],
                                        subTopic   : [type: 'string'],
                                        userId     : [type: 'string'],
                                        userGroupId: [type: 'string'],
                                        type       : [type: 'string'],
                                        title      : [type: 'string'],
                                        message    : [type: 'object']
                                ],
                                required  : ['topic'],
                                additionalProperties: false
                        ]
                ]
        ]

        return [
                resultType: 'complete',
                tools     : toolList,
                ttlMs     : CACHE_TTL_MS,
                cacheScope: 'private'
        ]
    }

    Map callTool(String name, Map arguments) {
        Map args = arguments ?: [:]
        ec.message.clearAll()

        if (name == 'moqui_search_data_documents') {
            Map svcRes = ec.service.sync().name('mantle.GeneralServices.search#General').parameters(args).call()
            return wrapToolResult(svcRes, [
                    documentList     : svcRes.documentList ?: [],
                    documentListCount: svcRes.documentListCount ?: 0,
                    pageIndex        : svcRes.documentListPageIndex,
                    pageSize         : svcRes.documentListPageSize
            ])
        }

        if (name == 'moqui_call_service') {
            String serviceName = args.serviceName as String
            Map serviceParameters = (args.parameters instanceof Map) ? (Map) args.parameters : [:]
            if (!serviceName) throw new IllegalArgumentException('serviceName is required')
            Map svcRes = ec.service.sync().name(serviceName).parameters(serviceParameters).call()
            return wrapToolResult(svcRes, svcRes)
        }

        if (name == 'moqui_make_notification') {
            def nm = ec.makeNotificationMessage().topic(args.topic as String)
            if (args.subTopic) nm.subTopic(args.subTopic as String)
            if (args.userId) nm.userId(args.userId as String)
            if (args.userGroupId) nm.userGroupId(args.userGroupId as String)
            if (args.type) nm.type(args.type as String)
            if (args.title) nm.title(args.title as String)
            if (args.message instanceof Map) nm.message((Map) args.message)
            nm.send()
            return [
                    resultType       : 'complete',
                    content          : [[type: 'text', text: new JsonBuilder([status: 'sent', topic: args.topic]).toString()]],
                    structuredContent: [status: 'sent', topic: args.topic],
                    isError          : false
            ]
        }
        throw new IllegalArgumentException("Unknown MCP tool ${name}")
    }

    Map listResources() {
        List<Map> resources = []

        ((Collection<String>) ec.entity.getAllEntityNames()).toList().sort().each { String entityName ->
            resources.add([
                    uri        : "entity://${entityName}",
                    name       : entityName,
                            description: "Moqui entity schema for ${entityName}",
                            mimeType   : 'application/json'
            ])
        }

        ec.entity.find('moqui.entity.document.DataDocument').useCache(true).list()?.each { dd ->
            resources.add([
                    uri        : "datadocument://${dd.dataDocumentId}",
                    name       : dd.dataDocumentId,
                    description: dd.documentName ?: dd.documentTitle ?: "DataDocument ${dd.dataDocumentId}",
                            mimeType   : 'application/json'
            ])
        }

        return [
                resultType: 'complete',
                resources : resources,
                ttlMs     : CACHE_TTL_MS,
                cacheScope: 'private'
        ]
    }

    Map readResource(String uri) {
        if (!uri) throw new IllegalArgumentException('Resource URI is required')

        if (uri.startsWith('entity://')) {
            String entityName = uri.substring('entity://'.length())
            if (!ec.entity.isEntityDefined(entityName)) throw new IllegalArgumentException("Unknown entity resource ${entityName}")
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
                        type        : relInfo.type,
                        title       : relInfo.title,
                        relatedEntity: relInfo.relatedEntityName,
                        shortAlias  : relInfo.shortAlias
                ]
            }
            return [
                    resultType: 'complete',
                    ttlMs     : CACHE_TTL_MS,
                    cacheScope: 'private',
                    contents: [[
                            uri     : uri,
                            mimeType: 'application/json',
                            text    : new JsonBuilder([
                                    entityName   : entityName,
                                    packageName  : ed.getFullEntityName(),
                                    fieldList    : fieldList,
                                    relationshipList: relList
                            ]).toString()
                    ]]
            ]
        }

        if (uri.startsWith('record://')) {
            String withoutScheme = uri.substring('record://'.length())
            List<String> parts = withoutScheme.split('\\?', 2) as List<String>
            String entityName = parts[0]
            Map<String, Object> conditions = [:]
            if (parts.size() > 1 && parts[1]) {
                parts[1].split('&').each { String pair ->
                    List<String> kv = pair.split('=', 2) as List<String>
                    conditions[kv[0]] = kv.size() > 1 ? java.net.URLDecoder.decode(kv[1], 'UTF-8') : ''
                }
            }
            def find = ec.entity.find(entityName)
            conditions.each { String key, Object value -> find.condition(key, value) }
            def record = find.one()
            if (!record) throw new IllegalArgumentException("Record resource not found for ${uri}")
            return [
                    resultType: 'complete',
                    ttlMs     : CACHE_TTL_MS,
                    cacheScope: 'private',
                    contents: [[
                            uri     : uri,
                            mimeType: 'application/json',
                            text    : new JsonBuilder(record ?: [:]).toString()
                    ]]
            ]
        }

        if (uri.startsWith('datadocument://')) {
            String dataDocumentId = uri.substring('datadocument://'.length())
            def dd = ec.entity.find('moqui.entity.document.DataDocument').condition('dataDocumentId', dataDocumentId).one()
            if (!dd) throw new IllegalArgumentException("Unknown data document resource ${dataDocumentId}")
            def fields = ec.entity.find('moqui.entity.document.DataDocumentField')
                    .condition('dataDocumentId', dataDocumentId).orderBy('sequenceNum').list()
            return [
                    resultType: 'complete',
                    ttlMs     : CACHE_TTL_MS,
                    cacheScope: 'private',
                    contents: [[
                            uri     : uri,
                            mimeType: 'application/json',
                            text    : new JsonBuilder([
                                    dataDocument: dd,
                                    summary     : [
                                            dataDocumentId    : dd?.dataDocumentId,
                                            documentName      : dd?.documentName,
                                            documentTitle     : dd?.documentTitle,
                                            indexName         : dd?.indexName,
                                            primaryEntityName : dd?.primaryEntityName,
                                            manualDataService : dd?.manualDataServiceName,
                                            manualMappingService: dd?.manualMappingServiceName
                                    ],
                                    fieldList: fields
                            ]).toString()
                    ]]
            ]
        }

        throw new IllegalArgumentException("Unsupported resource URI: ${uri}")
    }

    Map listPrompts() {
        List promptList = ec.entity.find('moqui.resource.wiki.WikiPage')
                .condition('wikiSpaceId', 'MCP_PROMPTS')
                .orderBy('pagePath')
                .list()
                .collect { wp ->
                    [
                            name       : wp.pagePath,
                            title      : wp.pageName ?: wp.pagePath,
                            description: wp.pageName ?: wp.pagePath,
                            arguments  : []
                    ]
                }
        return [
                resultType: 'complete',
                prompts   : promptList,
                ttlMs     : CACHE_TTL_MS,
                cacheScope: 'private'
        ]
    }

    Map getPrompt(String name, Map arguments) {
        if (!name) throw new IllegalArgumentException('Prompt name is required')
        Map res = ec.service.sync().name('org.moqui.impl.WikiServices.get#PublishedWikiPageText')
                .parameters([wikiSpaceId: 'MCP_PROMPTS', pagePath: name]).call()
        if (!res?.pageText) throw new IllegalArgumentException("Unknown prompt ${name}")
        return [
                resultType : 'complete',
                description: name,
                messages   : [[
                        role   : 'user',
                        content: [type: 'text', text: (res.pageText ?: '') as String]
                ]]
        ]
    }

    protected Map wrapToolResult(Map serviceResult, Object payload) {
        if (ec.message.hasError()) {
            String errText = ec.message.errorsString
            ec.message.clearErrors()
            return [
                    resultType       : 'complete',
                    content          : [[type: 'text', text: "Error: ${errText}"]],
                    structuredContent: [error: errText],
                    isError          : true
            ]
        }
        return [
                resultType       : 'complete',
                content          : [[type: 'text', text: new JsonBuilder(payload).toString()]],
                structuredContent: payload,
                isError          : false
        ]
    }
}
