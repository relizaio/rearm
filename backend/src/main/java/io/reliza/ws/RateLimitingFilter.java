/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
import io.github.bucket4j.TimeMeter;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.LogSafe;
import io.reliza.common.OAuthQueryGuard;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.ApiTokenService.TokenClaims;
import io.reliza.service.AuthorizationService;
import io.reliza.service.UserService;

/**
 * Global rate limiting filter using Bucket4j. It sits on both security chains: on the programmatic
 * chain in front of {@link ProgrammaticAuthenticationFilter}, so every request there is counted before
 * any credential is verified, and on the browser chain after the bearer-token filter (task TPF-26).
 * The servlet-level registration of the same bean is a no-op inside either chain (OncePerRequestFilter).
 * Keying strategy ({@link #resolveKey}), in order:
 *  - a browser user (IdP JWT in the security context): user:&lt;user uuid&gt;
 *  - a ReARM bearer that verifies: api:&lt;key id text of the verified key row&gt; (the text the key's
 *    Basic exchange presents, so a command's exchange and call share one bucket)
 *  - HTTP Basic in the header: api:&lt;canonical parsed identity&gt; ({@link #bucketOf}), so every text
 *    that reaches one key row's secret check is charged to that row's bucket
 *  - the token endpoint's client_id form field when the Authorization header is empty: folded and
 *    charged like the header form, read with the same {@link OAuthQueryGuard#single} as the controller
 *  - otherwise: client IP (X-Forwarded-For -> RemoteAddr)
 * Policy: capacity REQUESTS_PER_WINDOW (100), refilled every WINDOW_SECONDS (30). Agents are told this
 * in orientation.md section 1.1, counted in CLI commands too: a command with an API key exchanges it
 * for a token and then calls, two requests from the same bucket. OrientationContractTest and
 * CoordinatorTemplatesContractTest keep the numbers the same.
 * Buckets are evicted after 1 hour of inactivity; map is capped at 50,000 keys.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    @Autowired
    UserService userService;

    @Autowired
    AuthorizationService authorizationService;

    @Autowired
    ApiTokenService apiTokenService;

    @Autowired
    ApiKeyService apiKeyService;

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);
    static final int DEVICE_CODE_STARTS_PER_MINUTE = 10;
    /** Requests each caller may make per window; orientation section 1.1 states it to agents. */
    public static final int REQUESTS_PER_WINDOW = 100;
    public static final int WINDOW_SECONDS = 30;

    /** POST on the token endpoint: the only request whose form parameters the limiter reads. */
    private static final RequestMatcher TOKEN_REQUEST = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, ProgrammaticTokenController.PATH);

    /**
     * The CLI login start, matched on the parsed and decoded path as the chain matches it, so every spelling the chain
     * claims is counted in the device-code bucket; the method is tested beside it, case-insensitively, as before.
     */
    private static final RequestMatcher DEVICE_CODE_START = PathPatternRequestMatcher.withDefaults()
            .matcher(DeviceAuthorizationController.DEVICE_CODE_PATH);

    /** The time source of every bucket: the wall clock in milliseconds (the Bucket4j default); tests pause it. */
    final BucketClock clock = new BucketClock();

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String key = resolveKey(request);
        if (DEVICE_CODE_START.matches(request) && "POST".equalsIgnoreCase(request.getMethod())) {
            // an unauthenticated write that stores a row: a person starts a login a few times an hour, so a much smaller bucket than the general one
            Bucket starts = buckets.get("device-code|" + key, k -> Bucket.builder().withCustomTimePrecision(clock)
                    .addLimit(limit -> limit.capacity(DEVICE_CODE_STARTS_PER_MINUTE).refillGreedy(DEVICE_CODE_STARTS_PER_MINUTE, Duration.ofMinutes(1))).build());
            ConsumptionProbe p = starts.tryConsumeAndReturnRemaining(1);
            if (!p.isConsumed()) {
                long seconds = (long) Math.ceil(p.getNanosToWaitForRefill() / 1_000_000_000.0);
                response.setHeader("Retry-After", String.valueOf(Math.max(1, seconds)));
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
                log.warn("SECURITY: CLI login starts rate-limited for {} (more than {} a minute)", key, DEVICE_CODE_STARTS_PER_MINUTE);
                return;
            }
        }
        Bucket bucket = buckets.get(key, k ->
                Bucket.builder()
                        .withCustomTimePrecision(clock)
                        .addLimit(limit -> limit
                                .capacity(REQUESTS_PER_WINDOW)
                                .refillGreedy(REQUESTS_PER_WINDOW, Duration.ofSeconds(WINDOW_SECONDS))
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
        log.debug("Rate limit exceeded for key={} path={} wait={}s", key, LogSafe.text(request.getRequestURI()), seconds);
    }

    String resolveKey(HttpServletRequest request) {
        // 1) If authenticated user -> user uuid
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            var oud = userService.getUserDataByAuth(jwtAuth);
            if (oud.isPresent()) {
                return "user:" + oud.get().getUuid();
            }
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 2) A ReARM bearer -> the key id text of the verified key row; only verified claims name a bucket
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            Optional<TokenClaims> claims = apiTokenService.verify(authHeader.substring(7).trim(), ApiTokenService.AUD_PROGRAMMATIC);
            Optional<String> keyId = claims.flatMap(c -> apiKeyService.getApiKey(c.keyUuid())).map(ApiKeyService::keyIdOf);
            if (keyId.isPresent()) {
                return "api:" + keyId.get();
            }
        }

        // 3) HTTP Basic in the header -> the canonical parsed identity (no secret check, no lookup)
        if (StringUtils.isNotBlank(authHeader)) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, authHeader);
            var ahp = authorizationService.authenticateProgrammatic(headers, new ServletWebRequest(request));
            String bucket = bucketOf(ahp);
            if (bucket != null) {
                return bucket;
            }
        }

        // 4) The token endpoint's form carrier, read only when the header is empty, as the controller reads it
        if (StringUtils.isEmpty(authHeader) && TOKEN_REQUEST.matches(request)) {
            String clientId = OAuthQueryGuard.single(request.getParameterMap(), "client_id");
            if (StringUtils.isNotEmpty(clientId)) {
                HttpHeaders folded = new HttpHeaders();
                folded.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString((clientId + ":").getBytes(StandardCharsets.UTF_8)));
                String bucket = bucketOf(AuthHeaderParse.parseAuthHeader(folded, request.getRemoteAddr()));
                if (bucket != null) {
                    return bucket;
                }
            }
        }

        // 5) Fallback to client IP, respecting X-Forwarded-For from reverse proxies
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(forwarded)) {
            // X-Forwarded-For may be a comma-separated list; first entry is the client IP
            // rendered so the bucket key, which the two log lines above write, is never client free text
            return "ip:" + LogSafe.text(forwarded.split(",")[0].trim());
        }
        return "ip:" + LogSafe.text(request.getRemoteAddr());
    }

    /**
     * The bucket of a parsed Basic credential: {@code api:} plus the parse's canonical identity
     * ({@link AuthHeaderParse#canonicalId()}: type, object uuid in canonical spelling, and the key order when
     * non-empty), so every text that reaches one key row's secret check lands in that row's bucket (the org
     * segment and ignored segments are not part of it), and the key the limiter logs is never client free text:
     * a presented order that is not a plain token (no stored order is) lands in {@code __ord__?}, which names no
     * row. Null when the parse names no key.
     */
    static String bucketOf(AuthHeaderParse ahp) {
        String id = ahp == null ? "" : ahp.canonicalId();
        return id.isEmpty() ? null : "api:" + id;
    }

    /**
     * Wall-clock milliseconds, as Bucket4j measures by default, with a pause for tests: while paused it
     * answers the instant it was paused at, so a burst is not refilled while it runs. Resuming only moves
     * the time forward.
     */
    static final class BucketClock implements TimeMeter {
        private volatile Long pausedAt;

        @Override
        public long currentTimeNanos() {
            Long p = pausedAt;
            return p != null ? p : TimeMeter.SYSTEM_MILLISECONDS.currentTimeNanos();
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }

        void pause() {
            pausedAt = TimeMeter.SYSTEM_MILLISECONDS.currentTimeNanos();
        }

        void resume() {
            pausedAt = null;
        }
    }

}
