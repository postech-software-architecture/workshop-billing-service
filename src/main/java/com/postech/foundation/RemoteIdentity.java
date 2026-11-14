package com.postech.foundation;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RemoteIdentity implements Identity {

	private final RestClient client;

	private final String credential;

	public RemoteIdentity(@Value("${foundation.identity-url}") String url,
			@Value("${foundation.service-token}") String credential) {
		var factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
		factory.setReadTimeout(Duration.ofSeconds(2));
		this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
		this.credential = credential;
	}

	public record Result(boolean active, String usuarioId, String clienteId, List<String> roles) {
	}

	public List<String> roles(Jwt jwt) {
		return details(jwt).roles();
	}

	public Result details(Jwt jwt) {
		try {
			Result result = client.post()
				.uri("/internal/v1/identidade/introspectar")
				.header("X-Service-Token", credential)
				.body(Map.of("accessToken", jwt.getTokenValue()))
				.retrieve()
				.body(Result.class);
			if (result == null || !result.active() || !jwt.getSubject().equals(result.usuarioId())
					|| result.roles() == null)
				throw new OAuth2AuthenticationException("invalid_token");
			return result;
		}
		catch (RestClientException failure) {
			throw new OAuth2AuthenticationException(new OAuth2Error("temporarily_unavailable"));
		}
	}

}
