package com.postech.foundation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract-derived adversarial cases: required data, origin, type and value boundaries.
 */
class MessageContractMatrixTest {

	final ObjectMapper json = new ObjectMapper();

	JsonNode sample(JsonNode rule) {
		if (rule.has("const"))
			return rule.get("const").deepCopy();
		if (rule.has("enum"))
			return rule.get("enum").get(0).deepCopy();
		return switch (rule.path("type").asText()) {
			case "object" -> {
				var object = json.createObjectNode();
				rule.path("properties").fields().forEachRemaining(f -> object.set(f.getKey(), sample(f.getValue())));
				yield object;
			}
			case "array" -> json.createArrayNode().add(sample(rule.path("items")));
			case "integer" -> LongNode.valueOf(Math.max(1, rule.path("minimum").asLong()));
			case "boolean" -> BooleanNode.FALSE;
			default -> TextNode.valueOf(rule.path("format").asText().equals("uuid") ? UUID.randomUUID().toString()
					: rule.has("pattern") ? "1" : "example");
		};
	}

	Envelope event(String type, String producer, JsonNode payload) {
		var id = UUID.randomUUID();
		return new Envelope(id, type, 1, Instant.now(), producer, id, id, id, id, null, id, 1, payload);
	}

	@TestFactory
	Stream<DynamicTest> everyImplementedContractRejectsMissingAndUnexpectedFields() throws Exception {
		JsonNode schema = json.readTree(getClass().getResourceAsStream("/contracts/mensagens.schema.json"));
		List<DynamicTest> cases = new ArrayList<>();
		for (JsonNode branch : schema.path("oneOf")) {
			var props = branch.path("properties");
			String type = props.path("messageType").path("const").asText(),
					producer = props.path("producer").path("const").asText();
			if (type.equals("billing.geracao-orcamento-falhou.v1"))
				continue;
			String consumer = producer.equals("os-service") ? type.startsWith("billing.") ? "billing" : "execution"
					: "os";
			cases.add(DynamicTest.dynamicTest(type, () -> {
				ObjectNode payload = (ObjectNode) sample(props.path("payload"));
				event(type, producer, payload).validate(consumer);
				for (JsonNode required : props.path("payload").path("required")) {
					var missing = payload.deepCopy();
					missing.remove(required.asText());
					assertThrows(IllegalArgumentException.class,
							() -> event(type, producer, missing).validate(consumer));
				}
				var extra = payload.deepCopy();
				extra.put("untrustedExtra", "forbidden");
				assertThrows(IllegalArgumentException.class, () -> event(type, producer, extra).validate(consumer));
				assertThrows(IllegalArgumentException.class,
						() -> event(type, "unknown-service", payload).validate(consumer));
				props.path("payload").path("properties").fields().forEachRemaining(field -> {
					var rule = field.getValue();
					var bad = payload.deepCopy();
					String name = field.getKey();
					if (rule.path("type").asText().equals("string"))
						bad.set(name, LongNode.valueOf(1));
					else
						bad.set(name, TextNode.valueOf("invalid"));
					assertThrows(IllegalArgumentException.class, () -> event(type, producer, bad).validate(consumer));
					if (rule.has("minimum")) {
						var low = payload.deepCopy();
						low.put(name, rule.get("minimum").asLong() - 1);
						assertThrows(IllegalArgumentException.class,
								() -> event(type, producer, low).validate(consumer));
					}
					if (rule.has("maximum")) {
						var high = payload.deepCopy();
						high.put(name, rule.get("maximum").asLong() + 1);
						assertThrows(IllegalArgumentException.class,
								() -> event(type, producer, high).validate(consumer));
					}
					if (rule.path("format").asText().equals("uuid")) {
						var malformed = payload.deepCopy();
						malformed.put(name, "not-a-uuid");
						assertThrows(IllegalArgumentException.class,
								() -> event(type, producer, malformed).validate(consumer));
					}
					if (rule.has("enum")) {
						var other = payload.deepCopy();
						other.put(name, "unknown");
						assertThrows(IllegalArgumentException.class,
								() -> event(type, producer, other).validate(consumer));
					}
				});
			}));
		}
		return cases.stream();
	}

	@Test
	void incompleteEnvelopesAndWrongDestinationsAreRejected() {
		var id = UUID.randomUUID();
		var payload = json.createObjectNode().put("trabalhoId", id.toString()).put("solicitacao", "Revisao");
		var valid = event("execucao.solicitar-diagnostico.v1", "os-service", payload);
		assertThrows(IllegalArgumentException.class, () -> valid.validate("unknown"));
		assertThrows(IllegalArgumentException.class, () -> new Envelope(null, valid.messageType(), 1, Instant.now(),
				"os-service", id, id, id, id, null, id, 1, payload)
			.validate("execution"));
		assertThrows(IllegalArgumentException.class,
				() -> new Envelope(id, valid.messageType(), 1, null, "os-service", id, id, id, id, null, id, 1, payload)
					.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> new Envelope(id, valid.messageType(), 1, Instant.now(),
				"os-service", null, id, id, id, null, id, 1, payload)
			.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> new Envelope(id, valid.messageType(), 1, Instant.now(),
				"os-service", id, null, id, id, null, id, 1, payload)
			.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> new Envelope(id, valid.messageType(), 1, Instant.now(),
				"os-service", id, id, null, id, null, id, 1, payload)
			.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> new Envelope(id, valid.messageType(), 1, Instant.now(),
				"os-service", id, id, id, null, null, id, 1, payload)
			.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> new Envelope(id, valid.messageType(), 1, Instant.now(),
				"os-service", id, id, id, id, null, null, 1, payload)
			.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> new Envelope(id, valid.messageType(), 1, Instant.now(),
				"os-service", id, id, id, id, null, id, 0, payload)
			.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> new Envelope(id, valid.messageType(), 1, Instant.now(),
				"os-service", id, id, id, id, null, id, 1, null)
			.validate("execution"));
		assertThrows(IllegalArgumentException.class, () -> new Envelope(id, valid.messageType(), 1, Instant.now(),
				"os-service", id, id, id, id, null, id, 1, json.createArrayNode())
			.validate("execution"));
	}

}
