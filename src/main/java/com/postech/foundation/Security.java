package com.postech.foundation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class Security {

	@Bean
	JwtDecoder decoder(@Value("${foundation.jwt-secret}") String secret) {
		if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
			throw new IllegalArgumentException("JWT secret too short");
		NimbusJwtDecoder decoder = NimbusJwtDecoder
			.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
		OAuth2TokenValidator<Jwt> audience = jwt -> {
			try {
				if (jwt.getSubject() != null) {
					UUID.fromString(jwt.getSubject());
					if (jwt.getExpiresAt() != null && jwt.getAudience() != null
							&& jwt.getAudience().contains("workshop-service"))
						return OAuth2TokenValidatorResult.success();
				}
			}
			catch (IllegalArgumentException ignored) {
			}
			return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
		};
		decoder.setJwtValidator(
				new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("workshop-auth"), audience));
		return decoder;
	}

	@Bean
	SecurityFilterChain chain(HttpSecurity http, Identity identity) throws Exception {
		return http.csrf(c -> c.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(a -> a
				.requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**",
						"/internal/v1/identidade/introspectar", "/api/v1/webhooks/mercado-pago")
				.permitAll()
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer(o -> {
				o.jwt(j -> j.jwtAuthenticationConverter(jwt -> new JwtAuthenticationToken(jwt,
						identity.roles(jwt).stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList())));
				o.authenticationEntryPoint((request, response, error) -> {
					boolean unavailable = error instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException failure
							&& "temporarily_unavailable".equals(failure.getError().getErrorCode());
					response.setStatus(unavailable ? 503 : 401);
				});
			})
			.build();
	}

}
