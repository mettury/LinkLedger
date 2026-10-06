package dev.linkledger.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.linkledger.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Single shared-key prototype access control, not tenant identity or production SSO. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiAccessFilter extends OncePerRequestFilter {
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private long window = -1;
    private int requests;

    public ApiAccessFilter(AppProperties properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        response.setHeader("Cache-Control", "no-store");
        String path = request.getServletPath();
        boolean protectedPath = path.equals("/api") || path.startsWith("/api/")
                || ((path.equals("/actuator") || path.startsWith("/actuator/")) && !path.equals("/actuator/health"));
        if (protectedPath && !authorized(request.getHeader("X-API-Key"))) {
            response.setHeader("WWW-Authenticate", "ApiKey realm=\"LinkLedger\"");
            error(response, 401, "Unauthorized", "A valid X-API-Key header is required", requestId);
            return;
        }
        if (request.getMethod().equals("POST") && path.equals("/api/v1/urls")) {
            if (request.getContentLengthLong() > 8192) {
                error(response, 413, "Content Too Large", "Request body must not exceed 8192 bytes", requestId);
                return;
            }
            byte[] body = request.getInputStream().readNBytes(8193);
            if (body.length > 8192) {
                error(response, 413, "Content Too Large", "Request body must not exceed 8192 bytes", requestId);
                return;
            }
            request = new BufferedBodyRequest(request, body);
            if (!allowCreate()) {
                response.setHeader("Retry-After", Long.toString(60 - Math.floorMod(clock.instant().getEpochSecond(), 60)));
                error(response, 429, "Too Many Requests", "Create limit reached; retry after this window", requestId);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean authorized(String supplied) {
        return supplied != null && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                properties.apiKey().getBytes(StandardCharsets.UTF_8));
    }

    private synchronized boolean allowCreate() {
        long currentWindow = Math.floorDiv(clock.instant().getEpochSecond(), 60);
        if (currentWindow != window) {
            window = currentWindow;
            requests = 0;
        }
        return ++requests <= properties.createLimitPerMinute();
    }

    private void error(HttpServletResponse response, int status, String title, String detail, String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", title,
                "status", status, "detail", detail, "requestId", requestId));
    }
}
