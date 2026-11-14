package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvelopeTest {

	private Envelope event(String type, String producer, int version, Map<String, Object> body) {
		UUID id = UUID.randomUUID();
		return new Envelope(id, type, version, Instant.now(), producer, id, id, id, id, null, id, 1,
				new ObjectMapper().valueToTree(body));
	}

	@Test
	void validCommandAndReply() {
		UUID id = UUID.randomUUID();
		event("execucao.solicitar-diagnostico.v1", "os-service", 1, Map.of("trabalhoId", id, "solicitacao", "revisao"))
			.validate("execution");
		event("execucao.diagnostico-admitido.v1", "execucao-service", 1, Map.of("trabalhoId", id)).validate("os");
	}

	@Test
	void wrongOriginTypeVersionAndPayloadRejected() {
		UUID id = UUID.randomUUID();
		Map<String, Object> body = Map.of("trabalhoId", id, "solicitacao", "revisao");
		assertThrows(IllegalArgumentException.class,
				() -> event("execucao.solicitar-diagnostico.v1", "billing-service", 1, body).validate("execution"));
		assertThrows(IllegalArgumentException.class,
				() -> event("unknown", "os-service", 1, body).validate("execution"));
		assertThrows(IllegalArgumentException.class,
				() -> event("execucao.solicitar-diagnostico.v1", "os-service", 2, body).validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> event("execucao.solicitar-diagnostico.v1", "os-service", 1,
				Map.of("trabalhoId", "bad", "solicitacao", "x"))
			.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> event("execucao.solicitar-diagnostico.v1", "os-service", 1,
				Map.of("trabalhoId", id, "solicitacao", " "))
			.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> event("execucao.solicitar-diagnostico.v1", "os-service", 1,
				Map.of("trabalhoId", id, "solicitacao", "x", "jwt", "bad"))
			.validate("execution"));
	}

}
