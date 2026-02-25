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
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
	
	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, RateLimitingFilter rateLimitingFilter) throws Exception {
	  http
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
		  				.anyRequest().authenticated(); 
	  		} else {
	  			authz
		  				// .anyRequest().permitAll()
		  				.requestMatchers("/graphql").permitAll() // has granular per-query auth
		  				.requestMatchers("/tea/**").denyAll() // TODO adjust with auth coming to TEA
		  				.requestMatchers("/.well-known/tea/**").denyAll() // TODO adjust with auth coming to TEA
		  				.requestMatchers("/api/manual/v1/fetchCsrf").permitAll()
						.requestMatchers("/api/healthCheck").permitAll()
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