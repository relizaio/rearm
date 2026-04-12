/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.filter.OncePerRequestFilter;

import org.apache.commons.lang3.StringUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.reliza.service.AuthorizationService;
import io.reliza.service.UserService;

/**
 * Global rate limiting filter using Bucket4j.
 * Keying strategy:
 *  - Authenticated users: JWT subject
 *  - Programmatic Basic auth: username (API key id)
 *  - Otherwise: client IP (X-Forwarded-For -> RemoteAddr)
 * Policy: capacity 50, refill 50 tokens every 30 seconds.
 * Buckets are evicted after 1 hour of inactivity; map is capped at 50,000 keys.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    @Autowired
    UserService userService;

    @Autowired
    AuthorizationService authorizationService;

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String key = resolveKey(request);
        Bucket bucket = buckets.get(key, k ->
                Bucket.builder()
                        .addLimit(limit -> limit
                                .capacity(50)
                                .refillGreedy(50, Duration.ofSeconds(30))
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
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            var oud = userService.getUserDataByAuth(jwtAuth);
            if (oud.isPresent()) {
                return "user:" + oud.get().getUuid();
            }
        }

        // 2) If programmatic auth present -> apiKeyId (username part)
        HttpHeaders headers = new HttpHeaders();
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        ServletWebRequest servletWebRequest = new ServletWebRequest(request);
        if (StringUtils.isNotBlank(authHeader)) {
            headers.add(HttpHeaders.AUTHORIZATION, authHeader);
            var ahp = authorizationService.authenticateProgrammatic(headers, servletWebRequest);
            if (ahp != null && StringUtils.isNotBlank(ahp.getApiKeyId())) {
                return "api:" + ahp.getApiKeyId();
            }
        }

        // 3) Fallback to client IP, respecting X-Forwarded-For from reverse proxies
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(forwarded)) {
            // X-Forwarded-For may be a comma-separated list; first entry is the client IP
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

}
