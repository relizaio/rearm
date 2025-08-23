/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.reliza.service.AuthorizationService;
import io.reliza.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Global rate limiting filter using Bucket4j.
 * Keying strategy:
 *  - Authenticated users: JWT subject
 *  - Programmatic Basic auth: username (API key id)
 *  - Otherwise: client IP (X-Forwarded-For -> RemoteAddr)
 * Policy: capacity 50, refill 50 tokens every 30 seconds.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    @Autowired
    UserService userService;

    @Autowired
    AuthorizationService authorizationService;

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String key = resolveKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k ->
                Bucket.builder()
                        .addLimit(limit -> limit
                                .capacity(20)
                                .refillGreedy(20, Duration.ofSeconds(20))
                        )
                        .build()
        );

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            // Optionally expose remaining tokens
            response.setHeader("X-Rate-Limit-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
            return;
        }

        long nanosToWait = probe.getNanosToWaitForRefill();
        long seconds = (long) Math.ceil(nanosToWait / 1_000_000_000.0);
        response.setHeader("Retry-After", String.valueOf(Math.max(1, seconds)));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); // 429
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
        log.debug("Rate limit exceeded for key={} path={} wait={}s", key, request.getRequestURI(), seconds);
    }

    private String resolveKey(HttpServletRequest request) {
        // 1) If authenticated user -> user uuid
        JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var oud = userService.getUserDataByAuth(auth);
        if (oud.isPresent()) {
            return "user:" + oud.get().getUuid();
        }

        // 2) If programmatic auth present -> apiKeyId (username part)
        HttpHeaders headers = new HttpHeaders();
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        ServletWebRequest servletWebRequest = new ServletWebRequest(request);
        if (StringUtils.hasText(authHeader)) {
            headers.add(HttpHeaders.AUTHORIZATION, authHeader);
            var ahp = authorizationService.authenticateProgrammatic(headers, servletWebRequest);
            if (ahp != null && StringUtils.hasText(ahp.getApiKeyId())) {
                return "api:" + ahp.getApiKeyId();
            }
        }

        // 3) Fallback to client IP
        return "ip:" + servletWebRequest.getRequest().getRemoteAddr();
    }

}
