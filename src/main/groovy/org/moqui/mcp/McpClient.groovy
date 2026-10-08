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
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.util.RestSchemaUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class McpClient {
    protected static final Logger logger = LoggerFactory.getLogger(McpClient)
    protected final ExecutionContext ec
    static final String PROTOCOL_VERSION = '2026-07-28'
    static final long CACHE_TTL_MS = 300000L

    protected final MoquiResourceProvider resourceProvider
    protected final CompositePromptProvider promptProvider

    McpClient(ExecutionContext ec) {
        this.ec = ec
        this.resourceProvider = new MoquiResourceProvider(ec)
        this.promptProvider = new CompositePromptProvider(ec)
    }

    Map handle(String method, Map params) {
        Map requestParams = params ?: [:]
        Map<String, Closure<Map>> handlers = [
                'server/discover' : { Map p -> serverDiscover() },
                'tools/list' : { Map p -> listTools(p) },
                'tools/call' : { Map p ->
                    if (p.containsKey('arguments') && p.arguments != null && !(p.arguments instanceof Map)) {
                        throw new IllegalArgumentException('tools/call arguments must be an object when present')
                    }
                    callTool((String) p.name, p.arguments instanceof Map ? (Map) p.arguments : [:])
                },
                'resources/list' : { Map p -> resourceProvider.listResources(p) },
                'resources/templates/list': { Map p -> resourceProvider.listResourceTemplates(p) },
                'resources/read' : { Map p -> resourceProvider.readResource((String) p.uri, p) },
                'prompts/list' : { Map p -> promptProvider.listPrompts(p) },
                'prompts/get' : { Map p -> promptProvider.getPrompt((String) p.name, p) }
        ]
        Closure<Map> handler = handlers[method]
        if (handler == null) throw new IllegalArgumentException("Unsupported MCP method: ${method}")
        return handler.call(requestParams)
    }

    Map serverDiscover() {
        return [
                resultType : 'complete',
                supportedVersions: [PROTOCOL_VERSION],
                capabilities : [
                        tools : [listChanged: false],
                        resources: [listChanged: false],
                        prompts : [listChanged: false]
                ],
                instructions : 'Minimal Moqui MCP surface: services as tools, entities/DataDocuments as resources, prompts from Wiki pages.',
                _meta : [
                        'io.modelcontextprotocol/serverInfo': [
                                name : 'Moqui MCP Server',
                                version : '4.0.0'
                        ]
                ],
                ttlMs : 3600000,
                cacheScope : 'public'
        ]
    }

    Map listTools(Map params = [:]) {
        List<Map> toolList = getBuiltinToolList()
        toolList.addAll(getServiceToolList().sort { Map a, Map b -> (a.name ?: '') <=> (b.name ?: '') })
        Map page = McpPaginationSupport.paginate(toolList, params, 'tools')
        Map result = [
                resultType: 'complete',
                tools : page.tools,
                ttlMs : CACHE_TTL_MS,
                cacheScope : 'private'
        ]
        if (page.nextCursor) result.nextCursor = page.nextCursor
        return result
    }

    protected List<Map> getBuiltinToolList() {
        List<Map> builtinTools = []
        ServiceDefinition searchDefinition = ec.serviceFacade.getServiceDefinition('org.moqui.mcp.McpServices.search#AuthorizedDataDocuments')
        if (searchDefinition != null && searchDefinition.allowRemote && isServiceVisible(searchDefinition) &&
                ec.serviceFacade.getServiceDefinition('mantle.GeneralServices.search#MantleFiltered') != null) {
            builtinTools.add([
                        name : 'moqui_search_data_documents',
                        title : 'Search Data Documents',
                        description : 'Run text search against Moqui DataDocument indexes in OpenSearch.',
                        inputSchema : [
                                type : 'object',
                                properties : [
                                        scope : [type: 'string', enum: ['mantle'], description: 'Configured search scope. Currently supported: mantle.'],
                                        documentType : [type: 'string', description: 'Optional authorized DataDocument type, such as MantleParty or MantleProduct.'],
                                        queryString : [type: 'string', description: 'Plain text search query. Server-side organization filters are always applied.'],
                                        pageIndex : [type: 'integer', minimum: 0],
                                        pageSize : [type: 'integer', minimum: 1, maximum: 100]
                                ],
                                required : ['queryString'],
                                additionalProperties : false
                        ]
                ])
        }
        builtinTools.addAll([
                [
                        name : 'moqui_get_service_metadata',
                        title : 'Get Service Metadata',
                        description : 'Return service definition metadata, including input and output JSON Schema derived from the Moqui service definition.',
                        inputSchema : [
                                type : 'object',
                                properties : [
                                        serviceName: [type: 'string', description: 'Full Moqui service name']
                                ],
                                required : ['serviceName'],
                                additionalProperties: false
                        ]
                ],
                [
                        name : 'moqui_call_service',
                        title : 'Call Moqui Service',
                        description : 'Invoke an existing Moqui service by full service name with explicit parameters.',
                        inputSchema : [
                                type : 'object',
                                properties : [
                                        serviceName: [type: 'string', description: 'Full Moqui service name'],
                                        parameters : [type: 'object', description: 'Input parameter map']
                                ],
                                required : ['serviceName'],
                                additionalProperties: false
                        ]
                ]
        ])
        return builtinTools
    }

    Map callTool(String name, Map arguments) {
        if (!name) throw new IllegalArgumentException('Tool name is required')
        Map args = arguments ?: [:]

        if (name == 'moqui_search_data_documents') {
            assertAllowedKeys(args, ['scope', 'documentType', 'queryString', 'pageIndex', 'pageSize'] as Set, name)
            ServiceDefinition sd = ec.serviceFacade.getServiceDefinition('org.moqui.mcp.McpServices.search#AuthorizedDataDocuments')
            assertServiceCallable(sd)
            return invokeService(sd, args) { Map svcRes ->
                [
                        documentList : svcRes.documentList ?: [],
                        documentListCount : svcRes.documentListCount ?: 0,
                        pageIndex : svcRes.documentListPageIndex,
                        pageSize : svcRes.documentListPageSize
                ]
            }
        }

        if (name == 'moqui_get_service_metadata') {
            assertAllowedKeys(args, ['serviceName'] as Set, name)
            String serviceName = args.serviceName as String
            if (!serviceName) throw new IllegalArgumentException('serviceName is required')
            ServiceDefinition sd = ec.serviceFacade.getServiceDefinition(serviceName)
            if (sd == null) throw new IllegalArgumentException("Unknown service ${serviceName}")
            assertServiceCallable(sd)
            Map payload = [
                    serviceName : sd.serviceName,
                    verb : sd.verb,
                    noun : sd.noun,
                    path : sd.path,
                    authenticate : sd.authenticate,
                    allowRemote : sd.allowRemote,
                    inSchema : RestSchemaUtil.getJsonSchemaMapIn(sd),
                    outSchema : RestSchemaUtil.getJsonSchemaMapOut(sd)
            ]
            String description = sd.serviceNode.first('description')?.text
            if (description) payload.description = description
            return wrapToolResult(payload)
        }

        if (name == 'moqui_call_service') {
            assertAllowedKeys(args, ['serviceName', 'parameters'] as Set, name)
            String serviceName = args.serviceName as String
            if (args.containsKey('parameters') && args.parameters != null && !(args.parameters instanceof Map)) {
                throw new IllegalArgumentException('moqui_call_service parameters must be an object when present')
            }
            Map serviceParameters = (args.parameters instanceof Map) ? (Map) args.parameters : [:]
            if (!serviceName) throw new IllegalArgumentException('serviceName is required')
            ServiceDefinition sd = ec.serviceFacade.getServiceDefinition(serviceName)
            if (sd == null) throw new IllegalArgumentException("Unknown service ${serviceName}")
            assertServiceCallable(sd)
            assertServiceArguments(sd, serviceParameters)
            return invokeService(sd, serviceParameters)
        }

        ServiceDefinition serviceDefinition = getServiceDefinitionForToolName(name)
        if (serviceDefinition != null) {
            assertServiceCallable(serviceDefinition)
            assertServiceArguments(serviceDefinition, args)
            return invokeService(serviceDefinition, args)
        }

        throw new IllegalArgumentException("Unknown MCP tool ${name}")
    }

    protected static void assertAllowedKeys(Map args, Set<String> allowedKeys, String toolName) {
        Set<String> extraKeys = args.keySet().findAll { !(it in allowedKeys) } as Set<String>
        if (extraKeys) throw new IllegalArgumentException("Unsupported parameter(s) for ${toolName}: ${extraKeys.sort().join(', ')}")
    }

    protected List<Map> getServiceToolList() {
        ServiceFacadeImpl sfi = (ServiceFacadeImpl) ec.serviceFacade
        Set<String> seenNames = new LinkedHashSet<>()
        List<Map> serviceTools = []
        for (String serviceName in sfi.getKnownServiceNames()) {
            ServiceDefinition sd = ec.serviceFacade.getServiceDefinition(serviceName)
            if (sd == null) continue
            if (sd.serviceType == 'interface') continue
            if (!sd.allowRemote) continue
            if (isMcpTransportService(sd.serviceName)) continue
            if (!isServiceVisible(sd)) continue
            if (seenNames.add(sd.serviceName)) serviceTools.add(makeServiceToolDescriptor(sd))
        }
        return serviceTools
    }

    protected Map makeServiceToolDescriptor(ServiceDefinition sd) {
        Map inputSchema = RestSchemaUtil.getJsonSchemaMapIn(sd) ?: [type: 'object']
        Map outputSchema = RestSchemaUtil.getJsonSchemaMapOut(sd) ?: [type: 'object']

        String title = sd.serviceNode.attribute('displayName') ?: sd.serviceName
        String description = sd.serviceNode.first('description')?.text ?: buildServiceDescription(sd)

        return [
                name       : serviceToolName(sd.serviceName),
                title      : title,
                description: description,
                inputSchema: inputSchema,
                outputSchema: outputSchema,
                _meta      : [
                        'org.moqui/originalName': sd.serviceName,
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

    protected boolean isServiceVisible(ServiceDefinition sd) {
        if (sd == null) return false
        try {
            return ArtifactExecutionFacadeImpl.isPermitted("AT_SERVICE:${sd.authzAction.name()}:${sd.serviceName}", ec)
        } catch (Throwable ignored) {
            return false
        }
    }

    protected ServiceDefinition getServiceDefinitionForToolName(String toolName) {
        ServiceFacadeImpl sfi = (ServiceFacadeImpl) ec.serviceFacade
        for (String serviceName in sfi.getKnownServiceNames()) {
            if (serviceToolName(serviceName) == toolName) return ec.serviceFacade.getServiceDefinition(serviceName)
        }
        return null
    }

    protected static String serviceToolName(String serviceName) {
        String safe = serviceName.replaceAll(/[^A-Za-z0-9_-]+/, '_')
        if (safe == serviceName && safe.length() <= 96) return safe
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(serviceName.getBytes('UTF-8'))
        String hash = digest.encodeHex().toString().substring(0, 12)
        String prefix = safe.length() > 70 ? safe.substring(0, 70) : safe
        return "moqui_${prefix}_${hash}"
    }

    protected static boolean isMcpTransportService(String serviceName) {
        return serviceName?.startsWith('org.moqui.mcp.McpServices.')
    }

    protected void assertServiceCallable(ServiceDefinition sd) {
        if (sd == null) throw new IllegalArgumentException('Service definition is required')
        if (!sd.allowRemote) {
            throw new IllegalArgumentException("Service ${sd.serviceName} is not available for remote invocation")
        }
        if (!isServiceVisible(sd)) {
            throw new IllegalArgumentException("Service ${sd.serviceName} is not visible for the current user")
        }
    }

    protected static void assertServiceArguments(ServiceDefinition sd, Map arguments) {
        Map inputSchema = RestSchemaUtil.getJsonSchemaMapIn(sd) ?: [:]
        if (inputSchema.additionalProperties != false || !(inputSchema.properties instanceof Map)) return
        assertAllowedKeys(arguments ?: [:], ((Map) inputSchema.properties).keySet() as Set<String>, serviceToolName(sd.serviceName))
    }

    protected Map invokeService(ServiceDefinition sd, Map parameters, Closure transform = null) {
        Map callerMessages = captureMessages()
        ec.message.clearAll()
        try {
            Map serviceResult = ec.service.sync().name(sd.serviceName).parameters(parameters ?: [:]).call()
            if (ec.message.hasError()) {
                String errorText = ec.message.errorsString?.trim() ?: 'Service execution failed'
                return makeToolError(errorText)
            }
            return wrapToolResult(transform != null ? transform.call(serviceResult) : serviceResult)
        } catch (Throwable t) {
            logger.warn("MCP tool service execution failed for [${sd.serviceName}]", t)
            return makeToolError('Service execution failed')
        } finally {
            ec.message.clearAll()
            restoreMessages(callerMessages)
        }
    }

    protected static Map makeToolError(String message) {
        return [
                resultType : 'complete',
                content : [[type: 'text', text: message]],
                isError : true
        ]
    }

    protected Map captureMessages() {
        return [
                messages : new ArrayList(ec.message.messageInfos),
                publicMessages : new ArrayList(ec.message.publicMessageInfos),
                errors : new ArrayList(ec.message.errors),
                validationErrors : new ArrayList(ec.message.validationErrors)
        ]
    }

    protected void restoreMessages(Map snapshot) {
        List publicMessages = snapshot.publicMessages ?: []
        publicMessages.each { info -> ec.message.addPublic(info.message, info.typeString) }

        List remainingMessages = new ArrayList(snapshot.messages ?: [])
        publicMessages.each { publicInfo ->
            int index = remainingMessages.findIndexOf { info ->
                info.message == publicInfo.message && info.typeString == publicInfo.typeString
            }
            if (index >= 0) remainingMessages.remove(index)
        }
        remainingMessages.each { info -> ec.message.addMessage(info.message, info.typeString) }
        (snapshot.errors ?: []).each { String error -> ec.message.addError(error) }
        (snapshot.validationErrors ?: []).each { validationError -> ec.message.addError(validationError) }
    }

    protected Map wrapToolResult(Object payload) {
        if (ec.message.hasError()) {
            String errText = ec.message.errorsString?.trim() ?: 'Service execution failed'
            return [
                    resultType : 'complete',
                    content : [[type: 'text', text: errText]],
                    isError : true
            ]
        }
        Object jsonPayload = normalizeJsonValue(payload)
        return [
                resultType : 'complete',
                content : [[type: 'text', text: new JsonBuilder(jsonPayload).toString()]],
                structuredContent : jsonPayload,
                isError : false
        ]
    }

    protected static Object normalizeJsonValue(Object value) {
        return new JsonSlurper().parseText(JsonOutput.toJson(value))
    }
}
