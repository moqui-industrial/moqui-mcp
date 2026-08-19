package org.moqui.mcp

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.mcp.adapter.McpSession
import org.moqui.mcp.adapter.McpSessionAdapter
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

class McpServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(McpServlet.class)

    private final JsonSlurper jsonSlurper = new JsonSlurper()
    private final McpSessionAdapter sessionAdapter = new McpSessionAdapter()

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        logger.info("Initialized McpServlet on /mcp")
    }

    @Override
    void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (handleCors(request, response)) return

        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        String webappName = getServletContext().getInitParameter("moqui-name") ?: "webroot"
        if (ecfi == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "System is initializing, try again soon.")
            return
        }
        String requestBody = "POST".equalsIgnoreCase(request.method) ? request.reader.text : null

        ExecutionContextImpl ec = ecfi.getEci()
        try {
            ec.initWebFacade(webappName, request, response)
            ensureAuthenticated(ec)
            String responseSessionId = request.getHeader("Mcp-Session-Id") ?: request.getSession(true).id

            if ("GET".equalsIgnoreCase(request.method)) {
                writeJson(response, responseSessionId, [name: "moqui-mcp", protocolVersion: "2026-07-28", transport: "streamable-http"])
                return
            }

            if (!"POST".equalsIgnoreCase(request.method)) {
                response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
                return
            }

            Object payload = parseRequestBody(requestBody)
            if (payload instanceof List) {
                List responses = ((List) payload).collect { Object req -> handleJsonRpcRequest(ec, request, response, req as Map) }
                writeJson(response, responseSessionId, responses)
            } else {
                Map rpcResponse = handleJsonRpcRequest(ec, request, response, payload as Map)
                writeJson(response, responseSessionId, rpcResponse)
            }
        } catch (Throwable t) {
            logger.error("Error handling MCP request", t)
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            String responseSessionId = request.getHeader("Mcp-Session-Id") ?: request.getSession(true).id
            writeJson(response, responseSessionId, [
                    jsonrpc: "2.0",
                    error  : [code: -32603, message: t.message ?: "Internal error"],
                    id     : null
            ])
        } finally {
            ec.destroy()
        }
    }

    private Map handleJsonRpcRequest(ExecutionContextImpl ec, HttpServletRequest request, HttpServletResponse response, Map rpcRequest) {
        String method = rpcRequest.method as String
        Object id = rpcRequest.id
        Map params = rpcRequest.params instanceof Map ? (Map) rpcRequest.params : [:]
        String sessionId = request.getHeader("Mcp-Session-Id") ?: request.getSession(true).id
        McpSession session = sessionAdapter.getOrCreateSession(sessionId, ec.user.userId)
        session.touch()
        response.setHeader("Mcp-Session-Id", sessionId)

        Map result
        if ("initialize" == method) {
            session.initialized = true
            result = [
                    protocolVersion: "2026-07-28",
                    capabilities   : [
                            tools    : [listChanged: true],
                            resources: [listChanged: true],
                            prompts  : [:]
                    ],
                    serverInfo     : [name: "moqui-mcp", version: "3.0.0"]
            ]
        } else if ("ping" == method) {
            result = [:]
        } else {
            result = new McpClient(ec).handle(method, params)
        }

        return [
                jsonrpc: "2.0",
                id     : id,
                result : result,
                _meta  : [sessionId: sessionId]
        ]
    }

    private void ensureAuthenticated(ExecutionContextImpl ec) {
        if (ec.user?.userId) return
        String serviceUserId = System.getProperty("moqui.mcp.serviceAccountUserId", "john.doe")?.trim()
        if (!serviceUserId) throw new IllegalStateException("Missing moqui.mcp.serviceAccountUserId")
        UserFacadeImpl ufi = ec.userFacade
        if (!ufi.internalLoginUser(serviceUserId, false)) {
            throw new IllegalStateException("Could not log in MCP service account ${serviceUserId}: ${ec.message.errorsString}")
        }
    }

    private Object parseRequestBody(String bodyText) {
        if (!bodyText?.trim()) throw new IllegalArgumentException("Empty MCP request body")
        return jsonSlurper.parseText(bodyText)
    }

    private boolean handleCors(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Access-Control-Allow-Origin", "*")
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id")
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        if ("OPTIONS".equalsIgnoreCase(request.method)) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            return true
        }
        return false
    }

    private void writeJson(HttpServletResponse response, String sessionId, Object payload) {
        if (sessionId) response.setHeader("Mcp-Session-Id", sessionId)
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write(JsonOutput.toJson(payload))
    }
}
