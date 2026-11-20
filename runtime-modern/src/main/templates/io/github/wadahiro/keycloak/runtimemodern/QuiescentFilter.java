package io.github.wadahiro.keycloak.runtimemodern;

import java.io.IOException;
import ${servlet.namespace}.servlet.*;

/** Includes the downstream request filter's transaction completion in the barrier. */
public final class QuiescentFilter implements Filter {
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            RequestGate.read(() -> {
                try { chain.doFilter(request, response); }
                catch (IOException | ServletException e) { throw new FilterFailure(e); }
            });
        } catch (FilterFailure e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            throw (ServletException) e.getCause();
        }
    }
    @Override public void init(FilterConfig config) { }
    @Override public void destroy() { }
    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Exception cause) { super(cause); }
    }
}
