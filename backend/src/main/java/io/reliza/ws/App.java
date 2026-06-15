/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableAutoConfiguration
@EnableConfigurationProperties(RelizaConfigProps.class)
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableWebSecurity
@EnableMethodSecurity
@ComponentScan({"io.reliza.model", "io.reliza.ws", "io.reliza.repositories", "io.reliza.service", "io.reliza.common", "io.reliza.ws.tea"})
@EnableJpaRepositories("io.reliza.repositories")
@EnableTransactionManagement
@EntityScan("io.reliza.model") 
public class App {
	
	private static final Logger log = LoggerFactory.getLogger(App.class);

	private RelizaConfigProps relizaConfigProps;

	@Autowired
    public void setProps(RelizaConfigProps relizaConfigProps) {
        this.relizaConfigProps = relizaConfigProps;
    }
	
	public static void main(String[] args) {
		SpringApplication.run(App.class, args);
	}

	@Bean
	public RestTemplate restTemplate(RestTemplateBuilder builder) {
	   // Do any additional configuration here
	   return builder.build();
	}

	/**
	 * Default WebClient bean. Spring Boot 4 stopped auto-configuring
	 * both WebClient and WebClient.Builder when the primary web stack
	 * is Spring MVC (we keep WebClient around for outbound calls only).
	 * Construct directly via WebClient.builder() so consumers that
	 * @Autowire a bare WebClient (DTrackService etc.) keep working.
	 */
	@Bean
	public org.springframework.web.reactive.function.client.WebClient defaultWebClient() {
		return org.springframework.web.reactive.function.client.WebClient.builder().build();
	}

	/**
	 * Loosen Jackson 3's default CREATOR visibility from PUBLIC_ONLY to
	 * NON_PRIVATE. Lombok's @Builder generates a package-private
	 * @AllArgsConstructor on every @Data @Builder DTO; under the
	 * Jackson 2 defaults Spring Boot 3 shipped, those were findable via
	 * parameter-name binding, but Jackson 3 tightened the default and
	 * the same DTOs now fail to deserialize at the HTTP/GraphQL boundary
	 * with "no Creators ... exist". Annotating every DTO with
	 * @NoArgsConstructor would touch ~30 files; one global mapper tweak
	 * is the smaller surface change.
	 */
	@Bean
	public org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer relaxCreatorVisibilityCustomizer() {
		return builder -> builder.changeDefaultVisibility(vc -> vc.withCreatorVisibility(
				com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NON_PRIVATE));
	}

	/**
	 * JWT decoder for the resource server. Beyond Spring's defaults (signature via
	 * jwk-set-uri, issuer + exp/nbf), this pins the token's authorized party
	 * ({@code azp}) to an allowlist of trusted Keycloak clients (default
	 * {@code login-app}). Without it, the resource server accepts ANY token the
	 * realm signed — i.e. a token minted for a different client in the same realm
	 * would be honoured (cross-client token replay). The allowlist is configurable
	 * via {@code relizaprops.jwtAllowedAzp} (CSV) so additional first-party clients
	 * can be added without a code change. Keys are loaded from jwk-set-uri (matching
	 * the prior auto-config behaviour) rather than doing OIDC discovery against the
	 * public issuer, which isn't reachable internally.
	 */
	@Bean
	public JwtDecoder jwtDecoder(
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
			@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
			@Value("${relizaprops.jwtAllowedAzp:login-app}") String allowedAzpCsv,
			@Value("${relizaprops.jwtValidateAudience:false}") boolean validateAudience,
			@Value("${relizaprops.jwtAudience:rearm-backend}") String audience) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
		java.util.Set<String> allowedAzp = parseCsvSet(allowedAzpCsv);
		java.util.List<OAuth2TokenValidator<Jwt>> validators = new java.util.ArrayList<>();
		validators.add(JwtValidators.createDefaultWithIssuer(issuerUri));
		validators.add(buildAzpValidator(allowedAzp));
		// Audience validation is OFF by default and must only be enabled once Keycloak
		// is issuing tokens with this audience (the oidc-audience-mapper on login-app) —
		// enabling it before the mapper is live would reject every token (lockout).
		if (validateAudience) {
			validators.add(buildAudienceValidator(audience));
		}
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
		log.info("JWT resource-server decoder configured (issuer={}, azp allowlist={}, validateAudience={}, audience={})",
				issuerUri, allowedAzp, validateAudience, validateAudience ? audience : "n/a");
		return decoder;
	}

	/** Parse a comma-separated value into a trimmed, non-empty set. */
	static java.util.Set<String> parseCsvSet(String csv) {
		java.util.Set<String> out = new java.util.HashSet<>();
		if (csv != null) {
			for (String s : csv.split(",")) {
				String t = s.trim();
				if (!t.isEmpty()) out.add(t);
			}
		}
		return out;
	}

	/**
	 * Validator that requires the token's {@code azp} (authorized party — the client
	 * the token was issued to) to be in {@code allowedAzp}. A missing/blank azp or a
	 * value outside the allowlist fails validation.
	 */
	static OAuth2TokenValidator<Jwt> buildAzpValidator(java.util.Set<String> allowedAzp) {
		return new JwtClaimValidator<String>("azp", azp -> azp != null && allowedAzp.contains(azp));
	}

	/**
	 * Validator that requires the token's {@code aud} (audience) to contain
	 * {@code expectedAudience} — i.e. the token was issued *for this API*. Requires
	 * Keycloak to add the audience (the login-app oidc-audience-mapper); gated off by
	 * default via {@code relizaprops.jwtValidateAudience} so it is only switched on
	 * once the mapper is live.
	 */
	static OAuth2TokenValidator<Jwt> buildAudienceValidator(String expectedAudience) {
		return new JwtClaimValidator<java.util.List<String>>("aud",
				aud -> aud != null && aud.contains(expectedAudience));
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, RateLimitingFilter rateLimitingFilter) throws Exception {
	  http
	  .csrf(c -> c
			.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
			.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
			// Webhook intake is signature-authenticated (HMAC-SHA256 via
			// X-Hub-Signature-256) — CSRF protection doesn't apply, and
			// GitHub can't carry a CSRF cookie anyway. The path is opt-in
			// and the controller verifies the signature before any state
			// mutation.
			.ignoringRequestMatchers("/api/programmatic/v1/webhook/**")
	  )
	  .oauth2ResourceServer(
		    oauth2 -> oauth2.jwt(Customizer.withDefaults())
		)
	  	.logout(l -> l
	  		.permitAll().logoutUrl("/v1/logoutMain").logoutSuccessHandler(new LogoutSuccessHandler() {
				@Override
				public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
						Authentication authentication) throws IOException, ServletException {
					log.info("***onLogoutSuccess***");
					
				}
	        })
	  	)
	  	.authorizeHttpRequests(authz -> {
	  		if ("true".equalsIgnoreCase(relizaConfigProps.getEnableBetaTea())) {
	  			authz
		  				// .anyRequest().permitAll()
		  				.requestMatchers("/graphql").permitAll() // has granular per-query auth
		  				.requestMatchers("/tea/**").permitAll() // TODO adjust with auth coming to TEA
		  				.requestMatchers("/.well-known/tea/**").permitAll() // TODO adjust with auth coming to TEA
		  				.requestMatchers("/api/manual/v1/fetchCsrf").permitAll()
						.requestMatchers("/api/healthCheck").permitAll()
						.requestMatchers("/api/programmatic/v1/**").permitAll()
						.requestMatchers("/api/agents/**").permitAll() // public agent-orientation discovery
		  				.anyRequest().authenticated();
	  		} else {
	  			authz
		  				// .anyRequest().permitAll()
		  				.requestMatchers("/graphql").permitAll() // has granular per-query auth
		  				.requestMatchers("/tea/**").denyAll() // TODO adjust with auth coming to TEA
		  				.requestMatchers("/.well-known/tea/**").denyAll() // TODO adjust with auth coming to TEA
		  				.requestMatchers("/api/manual/v1/fetchCsrf").permitAll()
						.requestMatchers("/api/healthCheck").permitAll()
						.requestMatchers("/api/programmatic/v1/**").permitAll()
						.requestMatchers("/api/agents/**").permitAll() // public agent-orientation discovery
		  				.anyRequest().authenticated();
	  		}
	  	});
    	// Place rate limiting after JWT auth so SecurityContext is populated
    	http.addFilterAfter(rateLimitingFilter, BearerTokenAuthenticationFilter.class);
    	return http.build();
	}

	@Bean
	public ScheduledExecutorService scheduledExecutorService() {
		return Executors.newSingleThreadScheduledExecutor();
	}

	/**
	 * Dedicated, BOUNDED executor for after-commit product auto-integration.
	 * Caps background-integration concurrency so it can never exhaust the
	 * Hikari pool the way the old in-request, in-transaction path did
	 * (each integration briefly needs up to 2 connections — its own tx plus
	 * the REQUIRES_NEW version assignment). maxPoolSize small on purpose.
	 * Queue overflow just discards the immediate attempt; the durable
	 * flow_control marker + scheduler drain still guarantees the work runs.
	 */
	@Bean(name = "autoIntegrateExecutor")
	public org.springframework.core.task.TaskExecutor autoIntegrateExecutor() {
		org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor ex =
				new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
		ex.setCorePoolSize(2);
		ex.setMaxPoolSize(3);
		ex.setQueueCapacity(2000);
		ex.setThreadNamePrefix("auto-integrate-");
		ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
		ex.initialize();
		return ex;
	}

}