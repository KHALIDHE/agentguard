package dev.agentguard.financetools.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Only the gateway may call the tools. It proves it with a shared service
 * token in the Authorization header. Agents never see this token, so they
 * cannot skip the gateway and call the tools directly.
 *
 * Fails closed: if no token is configured, every MCP call is refused.
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);

    private final byte[] expected;

    public ServiceTokenFilter(@Value("${finance.service-token:}") String token) {
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        if (token.isBlank()) {
            log.warn("finance.service-token is empty: every MCP call will be refused");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        boolean configured = expected.length > "Bearer ".length();
        // MessageDigest.isEqual compares in constant time, so timing does not leak the token.
        boolean valid = configured && header != null
            && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), expected);
        if (!valid) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
