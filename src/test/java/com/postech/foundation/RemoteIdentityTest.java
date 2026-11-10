package com.postech.foundation;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.*;

class RemoteIdentityTest {

	private static final String ID = "11111111-1111-4111-8111-111111111111";

	Jwt token() {
		return Jwt.withTokenValue("demo-token").header("alg", "HS256").subject(ID).build();
	}

	void runCase(int status, String body, java.util.function.Consumer<RemoteIdentity> check) throws Exception {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/v1/identidade/introspectar", exchange -> {
			assertEquals("technical-test-key", exchange.getRequestHeaders().getFirst("X-Service-Token"));
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		try {
			check.accept(new RemoteIdentity("http://127.0.0.1:" + server.getAddress().getPort(), "technical-test-key"));
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void obtainsCurrentRoles() throws Exception {
		runCase(200, "{\"active\":true,\"usuarioId\":\"" + ID + "\",\"roles\":[\"MECANICO\"]}",
				identity -> assertEquals(List.of("MECANICO"), identity.roles(token())));
	}

	@Test
	void blockedUserIsRejected() throws Exception {
		runCase(200, "{\"active\":false}",
				identity -> assertThrows(OAuth2AuthenticationException.class, () -> identity.roles(token())));
	}

	@Test
	void unavailableIsDistinctFromBadToken() throws Exception {
		runCase(503, "{}", identity -> {
			var error = assertThrows(OAuth2AuthenticationException.class, () -> identity.roles(token()));
			assertEquals("temporarily_unavailable", error.getError().getErrorCode());
		});
	}

}
