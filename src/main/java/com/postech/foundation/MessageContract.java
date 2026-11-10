package com.postech.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Validates the published, versioned JSON contract before a consumer mutates state. */
final class MessageContract {

	private static final JsonNode SCHEMA = load();

	private MessageContract() {
	}

	private static JsonNode load() {
		try (var input = MessageContract.class.getResourceAsStream("/contracts/mensagens.schema.json")) {
			return new ObjectMapper().readTree(input);
		}
		catch (java.io.IOException error) {
			throw new IllegalStateException(error);
		}
	}

	static void payload(Envelope e, String kind) {
		Set<String> allowed = switch (kind) {
			case "execution" -> Set.of("execucao.solicitar-diagnostico.v1", "execucao.enfileirar-reparo.v1",
					"execucao.cancelar-trabalho.v1");
			case "billing" -> Set.of("billing.criar-cobranca.v1", "billing.cancelar-cobranca.v1",
					"billing.estornar-pagamento.v1", "billing.gerar-orcamento.v1", "billing.cancelar-orcamento.v1");
			case "os" -> Set.of("billing.pagamento-confirmado.v1", "billing.pagamento-recusado.v1",
					"billing.pagamento-expirado.v1", "billing.cobranca-cancelada.v1", "billing.cobranca-nao-criada.v1",
					"billing.pagamento-estornado.v1", "billing.compensacao-financeira-pendente.v1",
					"execucao.diagnostico-admitido.v1", "execucao.diagnostico-iniciado.v1",
					"execucao.diagnostico-concluido.v1", "billing.orcamento-gerado.v1", "billing.orcamento-aprovado.v1",
					"billing.orcamento-rejeitado.v1", "billing.orcamento-cancelado.v1",
					"execucao.reparo-enfileirado.v1", "execucao.reparo-iniciado.v1", "execucao.reparo-concluido.v1",
					"execucao.trabalho-cancelado.v1", "execucao.cancelamento-recusado.v1",
					"execucao.enfileiramento-recusado.v1");
			default -> Set.of();
		};
		if (!allowed.contains(e.messageType()))
			throw new IllegalArgumentException("UNSUPPORTED_MESSAGE");
		for (JsonNode branch : SCHEMA.path("oneOf")) {
			var props = branch.path("properties");
			if (props.path("messageType").path("const").asText().equals(e.messageType())) {
				if (!props.path("producer").path("const").asText().equals(e.producer()))
					throw new IllegalArgumentException("INVALID_PRODUCER");
				check(props.path("payload"), e.payload());
				return;
			}
		}
		throw new IllegalArgumentException("UNSUPPORTED_MESSAGE");
	}

	static void check(JsonNode rule, JsonNode value) {
		boolean valid = switch (rule.path("type").asText()) {
			case "object" -> value.isObject();
			case "array" -> value.isArray();
			case "string" -> value.isTextual();
			case "integer" -> value.isIntegralNumber();
			case "boolean" -> value.isBoolean();
			default -> true;
		};
		if (!valid || (rule.has("const") && !rule.get("const").equals(value)))
			fail();
		if (rule.has("enum")) {
			boolean found = false;
			for (JsonNode option : rule.get("enum"))
				found |= option.equals(value);
			if (!found)
				fail();
		}
		if (value.isObject()) {
			for (JsonNode required : rule.path("required"))
				if (!value.has(required.asText()))
					fail();
			var keys = value.fieldNames();
			while (keys.hasNext()) {
				String key = keys.next();
				if (!rule.path("properties").has(key))
					fail();
				check(rule.path("properties").get(key), value.get(key));
			}
		}
		else if (value.isArray()) {
			if (value.size() < rule.path("minItems").asInt(0) || value.size() > 200)
				fail();
			value.forEach(item -> check(rule.path("items"), item));
		}
		else if (value.isTextual()) {
			String text = value.asText();
			if (rule.path("minLength").asInt(0) > 0 && text.isBlank())
				fail();
			if (text.length() < rule.path("minLength").asInt(0) || text.length() > rule.path("maxLength").asInt(4096))
				fail();
			if (rule.has("pattern") && !text.matches(rule.get("pattern").asText()))
				fail();
			if (rule.path("format").asText().equals("uuid")) {
				if (!UUID.fromString(text).toString().equalsIgnoreCase(text))
					fail();
			}
			if (rule.path("format").asText().equals("date-time") && !isInstant(text))
				fail();
		}
		else if (value.isNumber()) {
			if (rule.has("minimum") && value.decimalValue().compareTo(rule.get("minimum").decimalValue()) < 0)
				fail();
			if (rule.has("maximum") && value.decimalValue().compareTo(rule.get("maximum").decimalValue()) > 0)
				fail();
		}
	}

	private static boolean isInstant(String text) {
		try {
			return Instant.parse(text) != null;
		}
		catch (java.time.format.DateTimeParseException invalid) {
			return false; // permanent contract violation, not a transient failure to retry
		}
	}

	private static void fail() {
		throw new IllegalArgumentException("INVALID_PAYLOAD");
	}

}
