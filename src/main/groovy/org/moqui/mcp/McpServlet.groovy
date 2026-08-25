package org.moqui.mcp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

class McpServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(McpServlet.class)

    static final String PROTOCOL_VERSION = '2026-07-28'
    static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = ['2026-07-28', '2025-11-25'] as Set
    static final Map SERVER_INFO = [name: 'moqui-mcp', version: '4.0.0']

    private final JsonSlurper jsonSlurper = new JsonSlurper()

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        logger.info("Initialized McpServlet on /mcp for MCP {}", PROTOCOL_VERSION)
    }

    @Override
    void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (handleCors(request, response)) return

        if (!"POST".equalsIgnoreCase(request.method)) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
            return
        }

        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        String webappName = getServletContext().getInitParameter("moqui-name") ?: "webroot"
        if (ecfi == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "System is initializing, try again soon.")
            return
        }

        String requestBody = request.reader.text
        ExecutionContextImpl ec = ecfi.getEci()
        try {
            ec.initWebFacade(webappName, request, response)
            ensureAuthenticated(ec, request)

            Object payload = parseRequestBody(requestBody)
            if (!(payload instanceof Map)) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, null, -32600, "MCP requests must be a single JSON-RPC object")
                return
            }

            Map rpcRequest = (Map) payload
            validateHttpHeaders(request, rpcRequest)
            validateRequestMeta(rpcRequest)

            Map rpcResponse = handleJsonRpcRequest(ec, rpcRequest, request)
            if (rpcResponse != null) {
                writeJson(response, rpcResponse)
            } else {
                response.status = HttpServletResponse.SC_ACCEPTED
                response.setHeader("Cache-Control", "no-store")
            }
        } catch (McpProtocolException mpe) {
            writeError(response, mpe.httpStatus, mpe.id, mpe.code, mpe.message, mpe.data)
        } catch (IllegalArgumentException iae) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, null, -32602, iae.message ?: "Invalid params")
        } catch (Throwable t) {
            logger.error("Error handling MCP request", t)
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null, -32603, t.message ?: "Internal error")
        } finally {
            ec.destroy()
        }
    }

    private Map handleJsonRpcRequest(ExecutionContextImpl ec, Map rpcRequest, HttpServletRequest request) {
        String method = rpcRequest.method as String
        boolean hasId = rpcRequest.containsKey('id')
        Object id = rpcRequest.id
        if (!hasId || id == null) {
            if (method?.startsWith('notifications/')) return null
            throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32600, "Request id is required", null, null)
        }

        Map params = rpcRequest.params instanceof Map ? (Map) rpcRequest.params : [:]
        Map result = new McpClient(ec, [
                clientProfile: resolveClientProfile(request)
        ]).handle(method, params)
        Map resultMeta = (result._meta instanceof Map) ? new LinkedHashMap((Map) result._meta) : [:]
        resultMeta['io.modelcontextprotocol/serverInfo'] = SERVER_INFO
        result._meta = resultMeta

        return [jsonrpc: "2.0", id: id, result: result]
    }

    private void validateRequestMeta(Map rpcRequest) {
        String method = rpcRequest.method as String
        Map params = rpcRequest.params instanceof Map ? (Map) rpcRequest.params : [:]
        Map meta = params._meta instanceof Map ? (Map) params._meta : null
        if (!meta) {
            if (method == 'initialize') {
                String initVersion = normalizeProtocolVersionValue(params.protocolVersion as String)
                if (initVersion && !SUPPORTED_PROTOCOL_VERSIONS.contains(initVersion)) {
                    throw new McpProtocolException(
                            HttpServletResponse.SC_BAD_REQUEST,
                            -32022,
                            "Unsupported protocol version",
                            rpcRequest.id,
                            [supported: SUPPORTED_PROTOCOL_VERSIONS as List, requested: initVersion]
                    )
                }
                if (params.capabilities != null && !(params.capabilities instanceof Map)) {
                    throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32602, "initialize params.capabilities must be an object", rpcRequest.id, null)
                }
                return
            }
            return
        }

        String bodyVersion = normalizeProtocolVersionValue(meta['io.modelcontextprotocol/protocolVersion'] as String)
        if (!bodyVersion) throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32602, "Missing _meta.io.modelcontextprotocol/protocolVersion", rpcRequest.id, null)
        if (!SUPPORTED_PROTOCOL_VERSIONS.contains(bodyVersion)) {
            throw new McpProtocolException(
                    HttpServletResponse.SC_BAD_REQUEST,
                    -32022,
                    "Unsupported protocol version",
                    rpcRequest.id,
                    [supported: SUPPORTED_PROTOCOL_VERSIONS as List, requested: bodyVersion]
            )
        }

        if (!(meta['io.modelcontextprotocol/clientCapabilities'] instanceof Map)) {
            throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32602, "Missing _meta.io.modelcontextprotocol/clientCapabilities", rpcRequest.id, null)
        }
    }

    private void validateHttpHeaders(HttpServletRequest request, Map rpcRequest) {
        Object id = rpcRequest.id
        String bodyMethod = rpcRequest.method as String
        String headerVersion = normalizeProtocolVersionValue(request.getHeader('MCP-Protocol-Version'))
        if (!headerVersion) throw headerMismatch(id, "Missing required header MCP-Protocol-Version")
        if (!SUPPORTED_PROTOCOL_VERSIONS.contains(headerVersion)) {
            throw new McpProtocolException(
                    HttpServletResponse.SC_BAD_REQUEST,
                    -32022,
                    "Unsupported protocol version",
                    id,
                    [supported: SUPPORTED_PROTOCOL_VERSIONS as List, requested: headerVersion]
            )
        }

        Map params = rpcRequest.params instanceof Map ? (Map) rpcRequest.params : [:]
        Map meta = params._meta instanceof Map ? (Map) params._meta : [:]
        String bodyVersion = normalizeProtocolVersionValue((meta['io.modelcontextprotocol/protocolVersion'] ?: params.protocolVersion) as String)
        if (bodyVersion && bodyVersion != headerVersion) {
            throw headerMismatch(id, "Header mismatch: MCP-Protocol-Version header value '${headerVersion}' does not match body value '${bodyVersion}'")
        }

        String headerMethod = request.getHeader('Mcp-Method')
        if (headerMethod && headerMethod != bodyMethod) {
            throw headerMismatch(id, "Header mismatch: Mcp-Method header value '${headerMethod}' does not match body value '${bodyMethod}'")
        }

        String expectedName = getExpectedName(bodyMethod, params)
        String headerName = request.getHeader('Mcp-Name')
        if (expectedName != null) {
            if (headerName) {
                String decodedHeaderName = decodeHeaderValue(headerName)
                if (decodedHeaderName != expectedName) {
                    throw headerMismatch(id, "Header mismatch: Mcp-Name header value '${decodedHeaderName}' does not match body value '${expectedName}'")
                }
            }
        }
    }

    private static String getExpectedName(String method, Map params) {
        if (method == 'tools/call') return params.name as String
        if (method == 'resources/read') return params.uri as String
        if (method == 'prompts/get') return params.name as String
        return null
    }

    private static String decodeHeaderValue(String value) {
        if (!value) return value
        if (value.startsWith('=?base64?') && value.endsWith('?=')) {
            String base64 = value.substring('=?base64?'.length(), value.length() - 2)
            return new String(base64.decodeBase64(), 'UTF-8')
        }
        return value
    }

    private static String normalizeProtocolVersionValue(String rawValue) {
        if (!rawValue) return rawValue
        List<String> parts = rawValue.split(',').collect { it?.trim() }.findAll { it }
        if (parts.isEmpty()) return null
        return parts[0]
    }

    private void ensureAuthenticated(ExecutionContextImpl ec, HttpServletRequest request) {
        if (ec.user?.userId) return
        boolean serviceEnabled = (System.getProperty("moqui.mcp.serviceAccountEnabled", "true") ?: "true").toBoolean()
        String serviceUserId = System.getProperty("moqui.mcp.serviceAccountUserId", "john.doe")?.trim()
        if (serviceEnabled && serviceUserId && isLocalRequest(request)) {
            UserFacadeImpl ufi = ec.userFacade
            if (!ufi.internalLoginUser(serviceUserId, false)) {
                throw new IllegalStateException("Could not log in MCP service account ${serviceUserId}: ${ec.message.errorsString}")
            }
            return
        }
        throw new IllegalStateException("MCP request is not authenticated. Use standard Moqui authentication (Basic Auth or api_key/login_key) or configure moqui.mcp.serviceAccountUserId for trusted local calls.")
    }

    private static boolean isLocalRequest(HttpServletRequest request) {
        String remoteAddr = request?.getRemoteAddr() ?: ""
        return remoteAddr == "127.0.0.1" || remoteAddr == "0:0:0:0:0:0:0:1" || remoteAddr == "::1" || remoteAddr == "localhost"
    }

    private static String resolveClientProfile(HttpServletRequest request) {
        String explicitProfile = request?.getHeader('X-Moqui-Mcp-Profile')?.trim()
        if (explicitProfile) return explicitProfile

        String userAgent = request?.getHeader('User-Agent') ?: ''
        if (userAgent.toLowerCase().contains('librechat')) return 'librechat'
        return 'default'
    }

    private Object parseRequestBody(String bodyText) {
        if (!bodyText?.trim()) throw new IllegalArgumentException("Empty MCP request body")
        return jsonSlurper.parseText(bodyText)
    }

    private boolean handleCors(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, api_key, login_key, MCP-Protocol-Version, Mcp-Method, Mcp-Name, X-Moqui-Mcp-Profile")
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS")
        if ("OPTIONS".equalsIgnoreCase(request.method)) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            return true
        }
        return false
    }

    private void writeJson(HttpServletResponse response, Object payload) {
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write(JsonOutput.toJson(payload))
    }

    private void writeError(HttpServletResponse response, int httpStatus, Object id, int code, String message, Map data = null) {
        Map error = [code: code, message: message]
        if (data) error.data = data
        response.status = httpStatus
        writeJson(response, [jsonrpc: "2.0", id: id, error: error])
    }

    private static McpProtocolException headerMismatch(Object id, String message) {
        return new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32020, message, id, null)
    }

    static class McpProtocolException extends RuntimeException {
        final int httpStatus
        final int code
        final Object id
        final Map data

        McpProtocolException(int httpStatus, int code, String message, Object id, Map data) {
            super(message)
            this.httpStatus = httpStatus
            this.code = code
            this.id = id
            this.data = data
        }
    }
}
