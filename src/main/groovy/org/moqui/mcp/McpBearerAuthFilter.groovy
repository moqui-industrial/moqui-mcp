package org.moqui.mcp

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.webapp.MoquiAuthFilter
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

/**
 * The authentication of the MCP endpoint. A request with HTTP Basic, an api_key or a login_key goes on as before. A request
 * with "Authorization: Bearer <access token>" (LibreChat sends the token of the person who is chatting) is checked by
 * org.moqui.sso.BearerTokenValidator of the moqui-sso component, and goes on as the Moqui user of the same username: the
 * request gets a short-lived login key of that user, which the framework then takes as it takes any login_key. A token that
 * is not accepted, or a person without a Moqui account, is refused; nothing falls back to another user.
 */
class McpBearerAuthFilter extends MoquiAuthFilter {
    private static final Logger logger = LoggerFactory.getLogger(McpBearerAuthFilter.class)
    private static final float KEY_HOURS = 0.1f
    private static final long REUSE_MILLIS = 2 * 60 * 1000L
    private static final Map<String, List> keysByUsername = new ConcurrentHashMap<>()

    @Override
    void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest) || !(resp instanceof HttpServletResponse)) { super.doFilter(req, resp, chain); return }
        HttpServletRequest request = (HttpServletRequest) req
        String authorization = request.getHeader("Authorization")
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) { super.doFilter(req, resp, chain); return }

        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) request.getServletContext().getAttribute("executionContextFactory")
        if (ecfi == null) { ((HttpServletResponse) resp).sendError(500, "System is initializing, try again soon."); return }
        String loginKey
        try {
            loginKey = loginKeyFor(ecfi, authorization.substring(7).trim())
        } catch (SecurityException e) {
            logger.info("MCP Bearer token refused: ${e.message}")
            ((HttpServletResponse) resp).sendError(401, e.message)
            return
        } catch (Exception e) {
            logger.warn("MCP Bearer token could not be checked: ${e.toString()}")
            ((HttpServletResponse) resp).sendError(401, "The Bearer token could not be checked")
            return
        }
        super.doFilter(new LoginKeyRequest(request, loginKey), resp, chain)
    }

    private static String loginKeyFor(ExecutionContextFactoryImpl ecfi, String token) {
        Class validator
        try { validator = Class.forName("org.moqui.sso.BearerTokenValidator") }
        catch (ClassNotFoundException e) { throw new SecurityException("Bearer tokens need the moqui-sso component") }

        ExecutionContextImpl ec = ecfi.getEci()
        try {
            String username = (String) validator.getMethod("usernameFor", org.moqui.context.ExecutionContext, String).invoke(null, ec, token)
            logger.info("MCP Bearer token accepted for ${username}")
            String cacheKey = username.toLowerCase()
            List cached = keysByUsername.get(cacheKey)
            if (cached != null && System.currentTimeMillis() < (long) cached[1]) return (String) cached[0]

            UserFacadeImpl ufi = ec.userFacade
            if (!ufi.internalLoginUser(username, false))
                throw new SecurityException("There is no Moqui account for ${username}")
            String key = ufi.getLoginKey(KEY_HOURS)
            keysByUsername.put(cacheKey, [key, System.currentTimeMillis() + REUSE_MILLIS])
            return key
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause()
            if (cause instanceof SecurityException) throw (SecurityException) cause
            throw e
        } finally {
            ec.destroy()
        }
    }

    /** The request as it came, with the login key as a header and without the Bearer token. */
    private static class LoginKeyRequest extends HttpServletRequestWrapper {
        private final String loginKey
        LoginKeyRequest(HttpServletRequest request, String loginKey) { super(request); this.loginKey = loginKey }

        @Override String getHeader(String name) {
            if ("login_key".equalsIgnoreCase(name)) return loginKey
            if ("Authorization".equalsIgnoreCase(name)) return null
            return super.getHeader(name)
        }
        @Override Enumeration<String> getHeaders(String name) {
            if ("login_key".equalsIgnoreCase(name)) return Collections.enumeration([loginKey])
            if ("Authorization".equalsIgnoreCase(name)) return Collections.emptyEnumeration()
            return super.getHeaders(name)
        }
    }
}
