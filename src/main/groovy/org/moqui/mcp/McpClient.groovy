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
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.LlmGateway
import org.moqui.impl.llm.tools.FindBasicTool
import org.moqui.impl.service.ServiceDefinition
import org.moqui.llm.LlmTool
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * MCP methods over the llm-client tools. The tools, their names, schemas and rules are the ones that llm-client attaches
 * for the configured profile (assist by default), so an MCP client gets what the Assist panel gets on the server side.
 * Tools that run in the browser of a Moqui user (screen_use, write_ui) and pin, which needs a Moqui conversation, are
 * not offered. find_document adds the OpenSearch DataDocument search, which llm-client does not have.
 */
class McpClient {
    protected static final Logger logger = LoggerFactory.getLogger(McpClient)
    static final String PROTOCOL_VERSION = '2026-07-28'
    static final long CACHE_TTL_MS = 300000L

    /** llm-client profile whose tools are offered; set with the moqui_mcp_llm_profile property */
    static final String PROFILE_PROPERTY = 'moqui_mcp_llm_profile'
    static final String DEFAULT_PROFILE = 'assist'
    /** Address of Moqui for the user's browser, used for screen links; set with the moqui_mcp_public_url property */
    static final String PUBLIC_URL_PROPERTY = 'moqui_mcp_public_url'

    static final String FIND_DOCUMENT = 'find_document'
    static final String SEARCH_SERVICE = 'org.moqui.mcp.McpServices.search#AuthorizedDataDocuments'
    /** pin keeps ids on a Moqui conversation, which an MCP client does not have */
    static final Set<String> CONVERSATION_TOOLS = ['pin'] as Set

    static final String INSTRUCTIONS = 'Moqui tools from llm-client. browse finds screens (q), services and entities; ' +
            'a screen hit carries url, a link the user can open: give that link instead of describing the data. ' +
            'request reads and writes through /rest/s1 (services) and /rest/e1 (entities and view-entities). ' +
            'run_service calls a service as the user. find_skill looks up a playbook. find_basic lists options of ' +
            'configuration entities. find_document is a text search over the DataDocuments in OpenSearch. ' +
            'screen_use and write_ui are not available here.'

    protected final ExecutionContext ec

    McpClient(ExecutionContext ec) {
        this.ec = ec
    }

    Map handle(String method, Map params) {
        Map requestParams = params ?: [:]
        Map<String, Closure<Map>> handlers = [
                'server/discover': { Map p -> serverDiscover() },
                'tools/list'     : { Map p -> listTools(p) },
                'tools/call'     : { Map p ->
                    if (p.containsKey('arguments') && p.arguments != null && !(p.arguments instanceof Map)) {
                        throw new IllegalArgumentException('tools/call arguments must be an object when present')
                    }
                    callTool((String) p.name, p.arguments instanceof Map ? (Map) p.arguments : [:])
                }
        ]
        Closure<Map> handler = handlers[method]
        if (handler == null) throw new IllegalArgumentException("Unsupported MCP method: ${method}")
        return handler.call(requestParams)
    }

    Map serverDiscover() {
        return [
                resultType      : 'complete',
                supportedVersions: [PROTOCOL_VERSION],
                capabilities    : [tools: [listChanged: false]],
                instructions    : INSTRUCTIONS,
                _meta           : ['io.modelcontextprotocol/serverInfo': [name: 'Moqui MCP Server', version: '4.0.0']],
                ttlMs           : 3600000,
                cacheScope      : 'public'
        ]
    }

    Map listTools(Map params = [:]) {
        List<Map> toolList = hasGateway() ? llmTools().collect { LlmTool tool -> toolDescriptor(tool) } : []
        if (searchAvailable()) toolList.add(findDocumentDescriptor())
        Map page = McpPaginationSupport.paginate(toolList, params, 'tools')
        Map result = [
                resultType: 'complete',
                tools     : page.tools,
                ttlMs     : CACHE_TTL_MS,
                cacheScope: 'private'
        ]
        if (page.nextCursor) result.nextCursor = page.nextCursor
        return result
    }

    Map callTool(String name, Map arguments) {
        if (!name) throw new IllegalArgumentException('Tool name is required')
        Map args = arguments ?: [:]
        if (name == FIND_DOCUMENT) return findDocument(args)

        if (!hasGateway()) throw new IllegalArgumentException('The current user may not use the llm-client tools (LlmGateway permission)')
        LlmTool tool = llmTools().find { LlmTool t -> t.name == name }
        if (tool == null) throw new IllegalArgumentException("Unknown MCP tool ${name}")

        Map callerMessages = captureMessages()
        ec.message.clearAll()
        try {
            Object result = tool.execute(args as Map<String, Object>, ec)
            if (name == 'browse') addScreenLinks(result)
            return toolResult(result)
        } catch (Throwable t) {
            logger.warn("MCP tool ${name} failed", t)
            return makeToolError("${name} failed: ${t.message ?: t.class.simpleName}")
        } finally {
            ec.message.clearAll()
            restoreMessages(callerMessages)
        }
    }

    /** The server-side tools that LlmGateway.attachServletTools would attach for the profile, built the same way. */
    protected List<LlmTool> llmTools() {
        LlmFacadeImpl.ProfileState profile = profileState()
        if (profile == null) throw new IllegalStateException("llm-client profile ${profileName()} is not configured")
        List<LlmTool> tools = []
        LlmTool request = LlmGateway.requestToolForServlet(profile.allowedPaths, profile.allowUnprefixedRequest)
        if (request != null) tools.add(request)
        if (profile.allowBrowse) tools.add(LlmTool.browse())
        if (profile.allowRunService) tools.add(LlmTool.runService())
        tools.add(LlmTool.findSkill())
        if (profile.allowEnterSim) tools.add(LlmTool.enterSim())
        if (profile.allowedBasicEntities) tools.add(new FindBasicTool(profile.allowedBasicEntities))
        return tools.findAll { LlmTool t -> t.execution == LlmTool.Execution.SERVER && !(t.name in CONVERSATION_TOOLS) }
    }

    protected LlmFacadeImpl.ProfileState profileState() {
        Object facade = ec.llm
        // getProfileState is package-private in llm-client; the Groovy call reaches it until llm-client makes it public
        return facade instanceof LlmFacadeImpl ? (LlmFacadeImpl.ProfileState) ((LlmFacadeImpl) facade).getProfileState(profileName()) : null
    }

    protected static String profileName() {
        return (System.getProperty(PROFILE_PROPERTY) ?: System.getenv(PROFILE_PROPERTY))?.trim() ?: DEFAULT_PROFILE
    }

    protected boolean hasGateway() {
        return ec.user?.userId && ec.user.hasPermission('LlmGateway')
    }

    protected static Map toolDescriptor(LlmTool tool) {
        Map schema = tool.parametersSchema ? new LinkedHashMap(tool.parametersSchema) : [:]
        schema.type = 'object'
        return [name: tool.name, description: tool.description, inputSchema: normalizeJsonValue(schema)]
    }

    /** A screen hit or row of browse gets url, the address the user's browser opens. */
    protected void addScreenLinks(Object result) {
        if (!(result instanceof Map)) return
        String base = screenBaseUrl()
        ['hits', 'rows', 'children'].each { String key ->
            Object list = ((Map) result).get(key)
            if (!(list instanceof List)) return
            ((List) list).each { Object row ->
                if (!(row instanceof Map)) return
                String path = ((Map) row).path as String
                if (path && (path.startsWith('/qapps/') || path.startsWith('/apps/') || path.startsWith('/vapps/')))
                    ((Map) row).url = base + path
            }
        }
    }

    protected String screenBaseUrl() {
        String configured = (System.getProperty(PUBLIC_URL_PROPERTY) ?: System.getenv(PUBLIC_URL_PROPERTY))?.trim()
        if (!configured) configured = ec.web?.getWebappRootUrl(true, null) ?: ''
        return configured.endsWith('/') ? configured.substring(0, configured.length() - 1) : configured
    }

    protected boolean searchAvailable() {
        ServiceDefinition sd = ec.serviceFacade.getServiceDefinition(SEARCH_SERVICE)
        return sd != null && isServiceVisible(sd) &&
                ec.serviceFacade.getServiceDefinition('mantle.GeneralServices.search#MantleFiltered') != null
    }

    protected static Map findDocumentDescriptor() {
        return [
                name       : FIND_DOCUMENT,
                description: 'Text search over the Moqui DataDocuments indexed in OpenSearch (MantleParty, MantleProduct), ' +
                        'inside the organizations the user may see. Omit query to page through all of them.',
                inputSchema: [
                        type                : 'object',
                        properties          : [
                                query       : [type: 'string', description: 'Plain text, for example a name or a code. Not a query language.'],
                                documentType: [type: 'string', description: 'Optional DataDocument type, such as MantleParty or MantleProduct.'],
                                pageIndex   : [type: 'integer', minimum: 0],
                                pageSize    : [type: 'integer', minimum: 1, maximum: 100]
                        ],
                        additionalProperties: false
                ]
        ]
    }

    protected Map findDocument(Map args) {
        assertAllowedKeys(args, ['query', 'documentType', 'pageIndex', 'pageSize'] as Set, FIND_DOCUMENT)
        ServiceDefinition sd = ec.serviceFacade.getServiceDefinition(SEARCH_SERVICE)
        if (sd == null || !isServiceVisible(sd)) throw new IllegalArgumentException("${FIND_DOCUMENT} is not available for the current user")
        Map parameters = [queryString: args.query, documentType: args.documentType, pageIndex: args.pageIndex, pageSize: args.pageSize]
                .findAll { it.value != null }
        Map callerMessages = captureMessages()
        ec.message.clearAll()
        try {
            Map svcRes = ec.service.sync().name(SEARCH_SERVICE).parameters(parameters).call()
            if (ec.message.hasError()) return makeToolError(ec.message.errorsString?.trim() ?: 'Search failed')
            return toolResult([
                    documentList     : svcRes.documentList ?: [],
                    documentListCount: svcRes.documentListCount ?: 0,
                    pageIndex        : svcRes.documentListPageIndex,
                    pageSize         : svcRes.documentListPageSize
            ])
        } catch (Throwable t) {
            logger.warn("MCP ${FIND_DOCUMENT} failed", t)
            return makeToolError('Search failed')
        } finally {
            ec.message.clearAll()
            restoreMessages(callerMessages)
        }
    }

    protected boolean isServiceVisible(ServiceDefinition sd) {
        if (sd == null) return false
        try {
            return ArtifactExecutionFacadeImpl.isPermitted("AT_SERVICE:${sd.authzAction.name()}:${sd.serviceName}", ec)
        } catch (Throwable ignored) {
            return false
        }
    }

    protected static void assertAllowedKeys(Map args, Set<String> allowedKeys, String toolName) {
        Set<String> extraKeys = args.keySet().findAll { !(it in allowedKeys) } as Set<String>
        if (extraKeys) throw new IllegalArgumentException("Unsupported parameter(s) for ${toolName}: ${extraKeys.sort().join(', ')}")
    }

    /** A tool result is an error when llm-client marks it so: an error text or an HTTP status of 400 or more. */
    protected static Map toolResult(Object payload) {
        Object jsonPayload = normalizeJsonValue(payload instanceof Map ? payload : [result: payload])
        Map map = (Map) jsonPayload
        Object status = map.status
        boolean isError = map.error != null || (status instanceof Number && ((Number) status).intValue() >= 400)
        return [
                resultType       : 'complete',
                content          : [[type: 'text', text: new JsonBuilder(jsonPayload).toString()]],
                structuredContent: jsonPayload,
                isError          : isError
        ]
    }

    protected static Map makeToolError(String message) {
        return [
                resultType: 'complete',
                content   : [[type: 'text', text: message]],
                isError   : true
        ]
    }

    protected Map captureMessages() {
        return [
                messages        : new ArrayList(ec.message.messageInfos),
                publicMessages  : new ArrayList(ec.message.publicMessageInfos),
                errors          : new ArrayList(ec.message.errors),
                validationErrors: new ArrayList(ec.message.validationErrors)
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

    protected static Object normalizeJsonValue(Object value) {
        return new JsonSlurper().parseText(JsonOutput.toJson(value))
    }
}
