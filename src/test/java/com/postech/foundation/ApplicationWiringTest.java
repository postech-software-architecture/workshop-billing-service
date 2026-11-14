package com.postech.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.ApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "spring.rabbitmq.host=localhost", "spring.rabbitmq.username=test",
		"spring.rabbitmq.password=test", "spring.rabbitmq.listener.simple.auto-startup=false",
		"foundation.jwt-secret=01234567890123456789012345678901", "foundation.service-token=test",
		"management.tracing.enabled=false", "management.otlp.metrics.export.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:billingwiring;MODE=PostgreSQL", "spring.datasource.username=sa",
		"spring.datasource.password=", "spring.flyway.enabled=false" })
@AutoConfigureMockMvc
class ApplicationWiringTest {

	@MockitoBean
	Transport transport;

	@Autowired
	ApplicationContext context;

	@Autowired
	MockMvc http;

	@Test
	void singleConsumerAndSecurityChainLoad() {
		assertEquals(1, context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class).size());
		assertEquals(1, context.getBeansOfType(Handler.class).size());
	}

	@Test
	void generatedApiDescribesCanonicalRoutes() throws Exception {
		String api = http.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		var paths = new com.fasterxml.jackson.databind.ObjectMapper().readTree(api).path("paths");
		assertTrue(paths.has("/api/v1/orcamentos/{id}"));
		assertFalse(paths.has("/api/v1/fundacao"));
		java.nio.file.Files.writeString(java.nio.file.Path.of("target/openapi-phase3.json"), api);
		var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		var generated = mapper.readTree(api);
		var published = mapper
			.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("contracts/openapi.json")));
		var alias = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("openapi.json")));
		assertEquals(published, alias, "Root OpenAPI must match canonical contracts/openapi.json");
		assertEquals(generated.path("paths"), published.path("paths"), "Published routes diverged; regenerate OpenAPI");
		assertEquals(generated.path("components"), published.path("components"),
				"Published schemas diverged; regenerate OpenAPI");

	}

	@Autowired
	org.springframework.jdbc.core.JdbcTemplate localDb;

	@Autowired
	org.springframework.transaction.PlatformTransactionManager transactions;

	@Autowired
	SqlMessages messages;

	@Test
	void businessAndOutboxShareLocalCommitAndRollback() {
		localDb.execute("CREATE TABLE IF NOT EXISTS transaction_probe(id UUID PRIMARY KEY)");
		localDb.execute(
				"CREATE TABLE IF NOT EXISTS outbox(id UUID PRIMARY KEY,body TEXT,exchange_name TEXT,routing_key TEXT,traceparent TEXT)");
		var id = java.util.UUID.randomUUID();
		var event = new Envelope(id, "execucao.diagnostico-admitido.v1", 1, java.time.Instant.now(), "execucao-service",
				id, id, id, id, null, id, 1,
				new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(java.util.Map.of("trabalhoId", id)));
		var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
		assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
			localDb.update("INSERT INTO transaction_probe(id) VALUES(?)", id);
			messages.emit(event, false);
			throw new IllegalStateException("fail before commit");
		}));
		assertEquals(0, localDb.queryForObject("SELECT count(*) FROM transaction_probe WHERE id=?", Integer.class, id));
		assertEquals(0, localDb.queryForObject("SELECT count(*) FROM outbox WHERE id=?", Integer.class, id));
		transaction.executeWithoutResult(status -> {
			localDb.update("INSERT INTO transaction_probe(id) VALUES(?)", id);
			messages.emit(event, false);
		});
		assertEquals(1, localDb.queryForObject("SELECT count(*) FROM transaction_probe WHERE id=?", Integer.class, id));
		assertEquals(1, localDb.queryForObject("SELECT count(*) FROM outbox WHERE id=?", Integer.class, id));
	}

}
