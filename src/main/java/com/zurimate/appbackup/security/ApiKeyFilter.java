package com.zurimate.appbackup.security;

import com.zurimate.appbackup.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protects the API with a shared key sent in the {@code X-API-Key} header.
 * The health endpoint stays open for container probes.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-API-Key";

    private final byte[] expectedKey;

    public ApiKeyFilter(SecurityProperties properties) {
        String key = properties.apiKey();
        this.expectedKey = key == null || key.isBlank() ? null : key.strip().getBytes(StandardCharsets.UTF_8);
        if (expectedKey == null) {
            log.warn("BACKUP_APP_API_KEY is not set; every API call will be rejected");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedKey == null) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "API disabled: BACKUP_APP_API_KEY is not configured");
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expectedKey, provided.strip().getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected {} {} from {}: missing or invalid API key", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\",\"data\":null}");
    }
}
