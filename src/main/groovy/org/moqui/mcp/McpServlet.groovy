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
import java.util.Locale
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
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
    static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = ['2026-07-28'] as Set
    static final String LIBRECHAT_LEGACY_PROTOCOL_VERSION = '2025-11-25'
    static final String LIBRECHAT_PROFILE_HEADER = 'X-Moqui-Mcp-Profile'
    static final String LIBRECHAT_PROFILE = 'librechat'
    static final Map SERVER_INFO = [name: 'moqui-mcp', version: '4.0.0']
    static final int MAX_BODY_CHARS = 1024 * 1024
    static final String ALLOWED_ORIGINS_PROPERTY = 'moqui.mcp.allowedOrigins'

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

        ExecutionContextImpl ec = ecfi.getEci()
        Object errorId = null
        try {
            validateContentHeaders(request)
            String requestBody = readRequestBody(request)
            ec.initWebFacade(webappName, request, response)
            ensureAuthenticated(ec, request)

            Object payload = parseRequestBody(requestBody)
            if (!(payload instanceof Map)) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, null, -32600, "MCP requests must be a single JSON-RPC object")
                return
            }

            Map rpcRequest = (Map) payload
            errorId = rpcRequest.id
            validateJsonRpcEnvelope(rpcRequest)
            boolean legacyLibreChat = isLegacyLibreChatRequest(request)
            validateHttpHeaders(request, rpcRequest, legacyLibreChat)
            validateRequestMeta(rpcRequest, legacyLibreChat)

            Map rpcResponse = handleJsonRpcRequest(ec, rpcRequest, legacyLibreChat)
            if (rpcResponse != null) {
                writeJson(response, rpcResponse)
            } else {
                response.status = HttpServletResponse.SC_ACCEPTED
                response.setHeader("Cache-Control", "no-store")
            }
        } catch (McpProtocolException mpe) {
            writeError(response, mpe.httpStatus, mpe.id, mpe.code, mpe.message, mpe.data)
        } catch (IllegalArgumentException iae) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, errorId, -32602, iae.message ?: "Invalid params")
        } catch (Throwable t) {
            logger.error("Error handling MCP request", t)
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorId, -32603, "Internal error")
        } finally {
            ec.destroy()
        }
    }

    private Map handleJsonRpcRequest(ExecutionContextImpl ec, Map rpcRequest, boolean legacyLibreChat) {
        String method = rpcRequest.method as String
        boolean hasId = rpcRequest.containsKey('id')
        Object id = rpcRequest.id
        // LibreChat 0.8.x completes the 2025-11-25 handshake with this notification.
        if (legacyLibreChat && method == 'notifications/initialized' && (!hasId || id == null)) return null
        if (!hasId || id == null) {
            throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32600, "Request id is required", null, null)
        }

        if (rpcRequest.containsKey('params') && rpcRequest.params != null && !(rpcRequest.params instanceof Map)) {
            throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32602, "params must be an object when present", id, null)
        }
        Map params = rpcRequest.params instanceof Map ? (Map) rpcRequest.params : [:]
        Map result
        if (legacyLibreChat && method == 'initialize') {
            // Keep the old initialize result isolated from the native 2026 server/discover contract.
            result = makeLibreChatInitializeResult()
        } else if (legacyLibreChat && method == 'ping') {
            // LibreChat probes legacy streamable HTTP servers after its initialize handshake.
            result = [:]
        } else {
            try {
                result = new McpClient(ec).handle(method, params)
            } catch (IllegalArgumentException e) {
                if ((e.message ?: '').startsWith('Unsupported MCP method:')) {
                    throw new McpProtocolException(HttpServletResponse.SC_NOT_FOUND, -32601, e.message, id, null)
                }
                throw e
            }
        }
        Map resultMeta = (result._meta instanceof Map) ? new LinkedHashMap((Map) result._meta) : [:]
        resultMeta['io.modelcontextprotocol/serverInfo'] = SERVER_INFO
        result._meta = resultMeta

        return [jsonrpc: "2.0", id: id, result: result]
    }

    private void validateJsonRpcEnvelope(Map rpcRequest) {
        Object id = rpcRequest.id
        if (rpcRequest.jsonrpc != '2.0') {
            throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32600, "jsonrpc must be '2.0'", id, null)
        }
        if (!(rpcRequest.method instanceof String) || !rpcRequest.method) {
            throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32600, "method is required", id, null)
        }
        if (rpcRequest.containsKey('id') && !(rpcRequest.id == null || rpcRequest.id instanceof String || rpcRequest.id instanceof Number)) {
            throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32600, "id must be a string or number", null, null)
        }
    }

    private void validateRequestMeta(Map rpcRequest, boolean legacyLibreChat) {
        // The 2025-11-25 LibreChat client does not send the 2026 request metadata envelope.
        if (legacyLibreChat) return
        Map params = rpcRequest.params instanceof Map ? (Map) rpcRequest.params : [:]
        Map meta = params._meta instanceof Map ? (Map) params._meta : null
        if (!meta) throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32602,
                "Missing params._meta", rpcRequest.id, null)

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

    private void validateHttpHeaders(HttpServletRequest request, Map rpcRequest, boolean legacyLibreChat) {
        Object id = rpcRequest.id
        String bodyMethod = rpcRequest.method as String
        String headerVersion = normalizeProtocolVersionValue(request.getHeader('MCP-Protocol-Version'))
        if (legacyLibreChat) {
            // LibreChat 0.8.7 omits this header during its legacy streamable HTTP handshake.
            if (headerVersion && headerVersion != LIBRECHAT_LEGACY_PROTOCOL_VERSION) {
                throw headerMismatch(id, "Legacy LibreChat requires MCP-Protocol-Version ${LIBRECHAT_LEGACY_PROTOCOL_VERSION}")
            }
            return
        }
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
        String bodyVersion = normalizeProtocolVersionValue(meta['io.modelcontextprotocol/protocolVersion'] as String)
        if (bodyVersion && bodyVersion != headerVersion) {
            throw headerMismatch(id, "Header mismatch: MCP-Protocol-Version header value '${headerVersion}' does not match body value '${bodyVersion}'")
        }

        String headerMethod = request.getHeader('Mcp-Method')
        if (!headerMethod) throw headerMismatch(id, "Missing required header Mcp-Method")
        if (headerMethod != bodyMethod) {
            throw headerMismatch(id, "Header mismatch: Mcp-Method header value '${headerMethod}' does not match body value '${bodyMethod}'")
        }

        String expectedName = getExpectedName(bodyMethod, params)
        String headerName = request.getHeader('Mcp-Name')
        if (expectedName != null) {
            if (!headerName) throw headerMismatch(id, "Missing required header Mcp-Name")
            String decodedHeaderName
            try {
                decodedHeaderName = decodeHeaderValue(headerName)
            } catch (IllegalArgumentException ignored) {
                throw headerMismatch(id, 'Mcp-Name contains invalid base64 encoding')
            }
            if (decodedHeaderName != expectedName) {
                throw headerMismatch(id, "Header mismatch: Mcp-Name header value '${decodedHeaderName}' does not match body value '${expectedName}'")
            }
        }
    }

    private static boolean isLegacyLibreChatRequest(HttpServletRequest request) {
        if (request.getHeader(LIBRECHAT_PROFILE_HEADER)?.trim() != LIBRECHAT_PROFILE) return false
        String headerVersion = normalizeProtocolVersionValue(request.getHeader('MCP-Protocol-Version'))
        if (!headerVersion || headerVersion == LIBRECHAT_LEGACY_PROTOCOL_VERSION) return true
        return false
    }

    private static Map makeLibreChatInitializeResult() {
        return [
                protocolVersion: LIBRECHAT_LEGACY_PROTOCOL_VERSION,
                capabilities   : [
                        tools    : [listChanged: false],
                        resources: [subscribe: false, listChanged: false],
                        prompts  : [listChanged: false]
                ],
                serverInfo     : new LinkedHashMap(SERVER_INFO),
                instructions   : 'Moqui MCP compatibility endpoint for LibreChat.'
        ]
    }

    private static void validateContentHeaders(HttpServletRequest request) {
        String contentType = request.getContentType()
        if (!contentType || !contentType.toLowerCase(Locale.ROOT).contains('application/json')) {
            throw new McpProtocolException(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, -32011,
                    "Content-Type must be application/json", null, null)
        }
        String accept = request.getHeader('Accept')
        String normalizedAccept = accept?.toLowerCase(Locale.ROOT)
        if (!normalizedAccept || !normalizedAccept.contains('application/json') || !normalizedAccept.contains('text/event-stream')) {
            throw new McpProtocolException(HttpServletResponse.SC_NOT_ACCEPTABLE, -32012,
                    "Accept must allow application/json and text/event-stream", null, null)
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
            if (!base64 || !(base64 ==~ /[A-Za-z0-9+\/=]+/)) throw new IllegalArgumentException('Invalid base64 value')
            byte[] decoded = Base64.decoder.decode(base64)
            if (Base64.encoder.encodeToString(decoded) != base64) throw new IllegalArgumentException('Non-canonical base64 value')
            return new String(decoded, 'UTF-8')
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
        boolean serviceEnabled = (System.getProperty("moqui.mcp.serviceAccountEnabled", "false") ?: "false").toBoolean()
        String serviceUserId = System.getProperty("moqui.mcp.serviceAccountUserId", "")?.trim()
        if (serviceEnabled && serviceUserId && isLocalRequest(request)) {
            if (!ec.userFacade.internalLoginUser(serviceUserId, false)) {
                throw new IllegalStateException("Could not log in MCP service account ${serviceUserId}: ${ec.message.errorsString}")
            }
            return
        }
        throw new McpProtocolException(HttpServletResponse.SC_UNAUTHORIZED, -32001,
                "MCP request is not authenticated. Use standard Moqui authentication.", null, null)
    }

    private static boolean isLocalRequest(HttpServletRequest request) {
        String remoteAddr = request?.getRemoteAddr() ?: ""
        return remoteAddr == "127.0.0.1" || remoteAddr == "0:0:0:0:0:0:0:1" || remoteAddr == "::1" || remoteAddr == "localhost"
    }

    private Object parseRequestBody(String bodyText) {
        if (!bodyText?.trim()) throw new IllegalArgumentException("Empty MCP request body")
        try {
            return jsonSlurper.parseText(bodyText)
        } catch (Throwable t) {
            throw new McpProtocolException(HttpServletResponse.SC_BAD_REQUEST, -32700, "Parse error", null, null)
        }
    }

    private static String readRequestBody(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder()
        char[] buffer = new char[8192]
        int read
        while ((read = request.reader.read(buffer)) != -1) {
            sb.append(buffer, 0, read)
            if (sb.length() > MAX_BODY_CHARS) throw new McpProtocolException(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    -32010, "MCP request body is too large", null, null)
        }
        return sb.toString()
    }

    private boolean handleCors(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader('Origin')?.trim()
        if (origin) {
            Set<String> allowedOrigins = (System.getProperty(ALLOWED_ORIGINS_PROPERTY, '') ?: '')
                    .split(',').collect { it.trim() }.findAll { it } as Set<String>
            if (origin != getRequestOrigin(request) && !allowedOrigins.contains(origin)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, 'Origin is not allowed')
                return true
            }
            response.setHeader('Access-Control-Allow-Origin', origin)
            response.addHeader('Vary', 'Origin')
            response.setHeader('Access-Control-Allow-Headers',
                    'Content-Type, Authorization, api_key, login_key, MCP-Protocol-Version, Mcp-Method, Mcp-Name, X-Moqui-Mcp-Profile')
            response.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS')
        }
        if ("OPTIONS".equalsIgnoreCase(request.method)) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            return true
        }
        return false
    }

    private static String getRequestOrigin(HttpServletRequest request) {
        String host = request.getHeader('Host')
        if (host) return "${request.scheme}://${host}"
        int port = request.serverPort
        boolean defaultPort = (request.scheme == 'http' && port == 80) || (request.scheme == 'https' && port == 443)
        return "${request.scheme}://${request.serverName}${defaultPort ? '' : ':' + port}"
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
