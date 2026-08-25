package org.moqui.mcp

import groovy.json.JsonBuilder
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.util.RestSchemaUtil

class McpClient {
    protected final ExecutionContext ec
    static final String PROTOCOL_VERSION = '2026-07-28'
    static final long CACHE_TTL_MS = 300000L

    protected final MoquiResourceProvider resourceProvider
    protected final CompositePromptProvider promptProvider

    McpClient(ExecutionContext ec, Map options = [:]) {
        this.ec = ec
        this.resourceProvider = new MoquiResourceProvider(ec)
        this.promptProvider = new CompositePromptProvider(ec)
    }

    Map handle(String method, Map params) {
        Map requestParams = params ?: [:]
        Map<String, Closure<Map>> handlers = [
                'initialize'             : { Map p -> initialize(p) },
                'ping'                   : { Map p -> [:] },
                'server/discover'        : { Map p -> serverDiscover() },
                'tools/list'             : { Map p -> listTools(p) },
                'tools/call'             : { Map p -> callTool((String) p.name, p.arguments instanceof Map ? (Map) p.arguments : [:]) },
                'resources/list'         : { Map p -> resourceProvider.listResources(p) },
                'resources/templates/list': { Map p -> resourceProvider.listResourceTemplates(p) },
                'resources/read'         : { Map p -> resourceProvider.readResource((String) p.uri, p) },
                'resources/subscribe'    : { Map p -> subscribeResource((String) p.uri) },
                'resources/unsubscribe'  : { Map p -> unsubscribeResource((String) p.uri) },
                'prompts/list'           : { Map p -> promptProvider.listPrompts(p) },
                'prompts/get'            : { Map p -> promptProvider.getPrompt((String) p.name, p) }
        ]
        Closure<Map> handler = handlers[method]
        if (handler == null) throw new IllegalArgumentException("Unsupported MCP method: ${method}")
        return handler.call(requestParams)
    }

    Map initialize(Map params = [:]) {
        String negotiatedVersion = (params.protocolVersion as String) ?: PROTOCOL_VERSION
        return [
                resultType     : 'complete',
                protocolVersion: negotiatedVersion,
                capabilities   : [
                        tools    : [listChanged: false],
                        resources: [listChanged: false, subscribe: true],
                        prompts  : [listChanged: false]
                ],
                serverInfo     : [
                        name   : 'moqui-mcp',
                        version: '4.0.0'
                ],
                instructions   : 'Use tools for Moqui service execution, resources for Moqui entity and DataDocument inspection, and prompts for Wiki-backed reusable guidance.'
        ]
    }

    Map serverDiscover() {
        return [
                resultType       : 'complete',
                supportedVersions: [PROTOCOL_VERSION],
                capabilities     : [
                        tools    : [listChanged: false],
                        resources: [listChanged: false, subscribe: true],
                        prompts  : [listChanged: false]
                ],
                instructions     : 'Minimal Moqui MCP surface: services as tools, entities/DataDocuments as resources, prompts from Wiki pages.',
                _meta            : [
                        'io.modelcontextprotocol/serverInfo': [
                                name   : 'Moqui MCP Server',
                                version: '4.0.0'
                        ]
                ],
                ttlMs            : 3600000,
                cacheScope       : 'public'
        ]
    }

    Map listTools(Map params = [:]) {
        List<Map> toolList = getBuiltinToolList()
        toolList.addAll(getServiceToolList().sort { Map a, Map b -> (a.name ?: '') <=> (b.name ?: '') })
        Map page = (!params?.cursor && !params?.pageSize) ? [tools: toolList] : McpPaginationSupport.paginate(toolList, params, 'tools')
        Map result = [
                resultType: 'complete',
                tools     : page.tools,
                ttlMs     : CACHE_TTL_MS,
                cacheScope: 'private'
        ]
        if (page.nextCursor) result.nextCursor = page.nextCursor
        return result
    }

    protected List<Map> getBuiltinToolList() {
        return [
                [
                        name       : 'moqui_search_data_documents',
                        title      : 'Search Data Documents',
                        description: 'Run text search against Moqui DataDocument indexes in OpenSearch.',
                        inputSchema: [
                                type      : 'object',
                                properties: [
                                        indexName   : [type: 'string', description: 'OpenSearch index name'],
                                        documentType: [type: 'string', description: 'Optional DataDocument identifier'],
                                        queryString : [type: 'string', description: 'Lucene or OpenSearch query string'],
                                        pageIndex   : [type: 'integer'],
                                        pageSize    : [type: 'integer']
                                ],
                                required  : ['indexName', 'queryString'],
                                additionalProperties: false
                        ]
                ],
                [
                        name       : 'moqui_get_service_metadata',
                        title      : 'Get Service Metadata',
                        description: 'Return service definition metadata, including input and output JSON Schema derived from the Moqui service definition.',
                        inputSchema: [
                                type      : 'object',
                                properties: [
                                        serviceName: [type: 'string', description: 'Full Moqui service name']
                                ],
                                required  : ['serviceName'],
                                additionalProperties: false
                        ]
                ],
                [
                        name       : 'moqui_call_service',
                        title      : 'Call Moqui Service',
                        description: 'Invoke an existing Moqui service by full service name with explicit parameters.',
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
    }

    Map callTool(String name, Map arguments) {
        Map args = arguments ?: [:]
        ec.message.clearAll()

        if (name == 'moqui_search_data_documents') {
            Map svcRes = ec.service.sync().name('org.moqui.search.SearchServices.search#DataDocuments').parameters(args).call()
            return wrapToolResult(svcRes, [
                    documentList     : svcRes.documentList ?: [],
                    documentListCount: svcRes.documentListCount ?: 0,
                    pageIndex        : svcRes.documentListPageIndex,
                    pageSize         : svcRes.documentListPageSize
            ])
        }

        if (name == 'moqui_get_service_metadata') {
            String serviceName = args.serviceName as String
            if (!serviceName) throw new IllegalArgumentException('serviceName is required')
            ServiceDefinition sd = ec.serviceFacade.getServiceDefinition(serviceName)
            if (sd == null) throw new IllegalArgumentException("Unknown service ${serviceName}")
            Map payload = [
                    serviceName : sd.serviceName,
                    verb        : sd.verb,
                    noun        : sd.noun,
                    path        : sd.path,
                    authenticate: sd.authenticate,
                    allowRemote : sd.allowRemote,
                    inSchema    : RestSchemaUtil.getJsonSchemaMapIn(sd),
                    outSchema   : RestSchemaUtil.getJsonSchemaMapOut(sd)
            ]
            String description = sd.serviceNode.first('description')?.text
            if (description) payload.description = description
            return wrapToolResult([:], payload)
        }

        if (name == 'moqui_call_service') {
            String serviceName = args.serviceName as String
            Map serviceParameters = (args.parameters instanceof Map) ? (Map) args.parameters : [:]
            if (!serviceName) throw new IllegalArgumentException('serviceName is required')
            Map svcRes = ec.service.sync().name(serviceName).parameters(serviceParameters).call()
            return wrapToolResult(svcRes, svcRes)
        }

        ServiceDefinition serviceDefinition = ec.serviceFacade.getServiceDefinition(name)
        if (serviceDefinition != null) {
            Map svcRes = ec.service.sync().name(name).parameters(args).call()
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
                    content          : [[type: 'text', text: new JsonBuilder([status: 'sent', topic: args.topic]).toString()]],
                    structuredContent: [status: 'sent', topic: args.topic],
                    isError          : false
            ]
        }

        throw new IllegalArgumentException("Unknown MCP tool ${name}")
    }

    protected List<Map> getServiceToolList() {
        ServiceFacadeImpl sfi = (ServiceFacadeImpl) ec.serviceFacade
        Set<String> seenNames = new LinkedHashSet<>()
        List<Map> serviceTools = []
        for (String serviceName in sfi.getKnownServiceNames()) {
            ServiceDefinition sd = ec.serviceFacade.getServiceDefinition(serviceName)
            if (sd == null) continue
            if (!sd.allowRemote) continue
            if (!isServiceVisible(sd.serviceName)) continue
            if (seenNames.add(sd.serviceName)) serviceTools.add(makeServiceToolDescriptor(sd))
        }
        return serviceTools
    }

    protected Map makeServiceToolDescriptor(ServiceDefinition sd) {
        Map inputSchema = RestSchemaUtil.getJsonSchemaMapIn(sd) ?: [type: 'object']
        if (inputSchema.additionalProperties == null) inputSchema.additionalProperties = false

        String title = sd.serviceNode.attribute('displayName') ?: sd.serviceName
        String description = sd.serviceNode.first('description')?.text ?: buildServiceDescription(sd)

        return [
                name       : sd.serviceName,
                title      : title,
                description: description,
                inputSchema: inputSchema,
                _meta      : [
                        'org.moqui/serviceName' : sd.serviceName,
                        'org.moqui/servicePath' : sd.path,
                        'org.moqui/serviceVerb' : sd.verb,
                        'org.moqui/serviceNoun' : sd.noun,
                        'org.moqui/allowRemote' : sd.allowRemote,
                        'org.moqui/authenticate': sd.authenticate
                ]
        ]
    }

    protected static String buildServiceDescription(ServiceDefinition sd) {
        StringBuilder sb = new StringBuilder()
        sb.append("Moqui service ").append(sd.serviceName).append('.')
        if (sd.noun) sb.append(' ').append(sd.verb?.capitalize() ?: 'Run').append(' ').append(sd.noun).append('.')
        if (sd.authenticate) sb.append(' Authentication is required.')
        if (sd.allowRemote) sb.append(' Remote invocation is allowed.')
        return sb.toString().trim()
    }

    protected boolean isServiceVisible(String serviceName) {
        if (!serviceName) return false
        try {
            return ArtifactExecutionFacadeImpl.isPermitted("AT_SERVICE:AUTHZA_VIEW:${serviceName}", ec)
        } catch (Throwable ignored) {
            return false
        }
    }

    Map subscribeResource(String uri) {
        Map payload = McpSubscriptionRegistry.subscribeResource(currentPrincipalId(), uri)
        return [
                structuredContent: payload,
                content          : [[type: 'text', text: new JsonBuilder(payload).toString()]],
                isError          : false
        ]
    }

    Map unsubscribeResource(String uri) {
        Map payload = McpSubscriptionRegistry.unsubscribeResource(currentPrincipalId(), uri)
        return [
                structuredContent: payload,
                content          : [[type: 'text', text: new JsonBuilder(payload).toString()]],
                isError          : false
        ]
    }

    protected String currentPrincipalId() {
        return ec.user?.userId ?: ec.user?.visitId ?: 'anonymous'
    }

    protected Map wrapToolResult(Map serviceResult, Object payload) {
        if (ec.message.hasError()) {
            String errText = ec.message.errorsString
            ec.message.clearErrors()
            return [
                    content          : [[type: 'text', text: "Error: ${errText}"]],
                    structuredContent: [error: errText],
                    isError          : true
            ]
        }
        return [
                content          : [[type: 'text', text: new JsonBuilder(payload).toString()]],
                structuredContent: payload,
                isError          : false
        ]
    }
}
