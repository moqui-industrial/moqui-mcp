package org.moqui.mcp.adapter

import java.util.concurrent.ConcurrentHashMap

class McpSessionAdapter {
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>()

    McpSession getOrCreateSession(String sessionId, String userId) {
        return sessions.computeIfAbsent(sessionId) {
            new McpSession(sessionId: sessionId, userId: userId)
        }
    }

    McpSession getSession(String sessionId) {
        return sessions.get(sessionId)
    }

    void closeSession(String sessionId) {
        sessions.remove(sessionId)
    }
}

class McpSession {
    String sessionId
    String userId
    boolean initialized = false
    long createdAt = System.currentTimeMillis()
    long lastActivity = System.currentTimeMillis()

    void touch() {
        lastActivity = System.currentTimeMillis()
    }
}
