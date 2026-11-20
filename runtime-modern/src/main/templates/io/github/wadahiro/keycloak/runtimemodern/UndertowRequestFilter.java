package io.github.wadahiro.keycloak.runtimemodern;

import java.io.IOException;
import ${servlet.namespace}.servlet.*;
import ${servlet.namespace}.servlet.http.HttpServletRequest;
import org.jboss.resteasy.core.ResteasyContext;
import org.keycloak.common.ClientConnection;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resources.KeycloakApplication;

public final class UndertowRequestFilter implements Filter {
    @Override public void init(FilterConfig config) { }
    @Override public void destroy() { }
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        request.setCharacterEncoding("UTF-8");
        HttpServletRequest http=(HttpServletRequest)request;
        KeycloakSession session=KeycloakApplication.getSessionFactory().create();
        var tx=session.getTransactionManager();
        KeycloakSession previous=SessionContext.set(session);
        try {
            tx.begin();
            ResteasyContext.pushContext(KeycloakSession.class, session);
            ResteasyContext.pushContext(HttpServletRequest.class, http);
            ClientConnection connection = new ClientConnection() {
                public String getRemoteAddr() { return http.getRemoteAddr(); }
                public String getRemoteHost() { return http.getRemoteHost(); }
                public int getRemotePort() { return http.getRemotePort(); }
                public String getLocalAddr() { return http.getLocalAddr(); }
                public int getLocalPort() { return http.getLocalPort(); }
            };
            ResteasyContext.pushContext(ClientConnection.class, connection);
            try { session.getContext().getClass().getMethod("setConnection",ClientConnection.class).invoke(session.getContext(),connection); }
            catch (NoSuchMethodException ignored) { }
            catch (ReflectiveOperationException failure) { throw new ServletException(failure); }
            chain.doFilter(request,response);
            if (tx.isActive()) { if (tx.getRollbackOnly()) tx.rollback(); else tx.commit(); }
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            if (tx.isActive()) tx.rollback(); throw failure;
        } finally { SessionContext.set(previous); session.close(); ResteasyContext.clearContextData(); }
    }
}
