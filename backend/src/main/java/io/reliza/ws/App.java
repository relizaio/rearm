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

}