package com.postech.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Orders API, QR dynamic. Provider calls are outside the local database transaction. */
@Component
@ConditionalOnProperty(name = "payments.enabled", havingValue = "true")
public class MercadoPagoGateway implements PagamentoGateway {

	private final RestClient http;

	private final String pos, owner;

	public MercadoPagoGateway(@Value("${payments.base-url:https://api.mercadopago.com}") String url,
			@Value("${MP_ACCESS_TOKEN}") String token, @Value("${MP_POS_ID}") String pos,
			@Value("${MP_SELLER_ID}") String owner) {
		var uri = java.net.URI.create(url);
		if (!"https".equals(uri.getScheme()) && !Set.of("localhost", "127.0.0.1").contains(uri.getHost()))
			throw new IllegalArgumentException("HTTPS_REQUIRED");
		if (token.isBlank() || pos.isBlank() || owner.isBlank())
			throw new IllegalArgumentException("MP_CONFIGURATION_REQUIRED");
		this.pos = pos;
		this.owner = owner;
		var factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
		factory.setReadTimeout(Duration.ofSeconds(5));
		http = RestClient.builder()
			.baseUrl(url)
			.requestFactory(factory)
			.defaultHeader("Authorization", "Bearer " + token)
			.build();
	}

	public Result create(Charge charge) {
		String amount = BigDecimal.valueOf(charge.totalCentavos(), 2).toPlainString();
		var payload = Map.of("type", "qr", "total_amount", amount, "external_reference", charge.externalReference(),
				"description", "Ordem de servico da oficina", "expiration_time", "PT15M", "config",
				Map.of("qr", Map.of("external_pos_id", pos, "mode", "dynamic")), "transactions",
				Map.of("payments", List.of(Map.of("amount", amount))));
		JsonNode response = http.post()
			.uri("/v1/orders")
			.header("X-Idempotency-Key", charge.operationId().toString())
			.body(payload)
			.retrieve()
			.body(JsonNode.class);
		if (response == null || response.path("id").asText().isBlank())
			throw new IllegalStateException("INVALID_PROVIDER_RESPONSE");
		return consult(response.path("id").asText());
	}

	public Result consult(String id) {
		return parse(http.get().uri("/v1/orders/{id}", safeId(id)).retrieve().body(JsonNode.class));
	}

	public Result cancel(UUID operationId, String id) {
		http.post()
			.uri("/v1/orders/{id}/cancel", safeId(id))
			.header("X-Idempotency-Key", operationId.toString())
			.retrieve()
			.toBodilessEntity();
		return consult(id);
	}

	public Result refund(UUID operationId, String id, long amount) {
		var prior = consult(id);
		if (prior.totalCentavos() != amount)
			throw new IllegalArgumentException("REFUND_AMOUNT_MISMATCH");
		http.post()
			.uri("/v1/orders/{id}/refunds", safeId(id))
			.header("X-Idempotency-Key", operationId.toString())
			.retrieve()
			.toBodilessEntity();
		return consult(id);
	}

	Result parse(JsonNode resource) {
		if (resource == null || !owner.equals(resource.path("user_id").asText())
				|| !"qr".equals(resource.path("type").asText()) || !"BRL".equals(resource.path("currency").asText()))
			throw new IllegalArgumentException("PROVIDER_OWNER_OR_CURRENCY_MISMATCH");
		long amount = new BigDecimal(resource.path("total_amount").asText()).movePointRight(2).longValueExact();
		String status = resource.path("status").asText();
		String translated = switch (status) {
			case "created", "processing", "action_required" -> "PENDENTE";
			case "processed" ->
				"accredited".equals(resource.path("status_detail").asText()) ? "CONFIRMADO" : "PENDENTE";
			case "refunded" -> "ESTORNADO";
			case "canceled", "cancelled" -> "CANCELADO";
			case "expired" -> "EXPIRADO";
			case "failed", "rejected" -> "RECUSADO";
			default -> throw new IllegalArgumentException("UNKNOWN_PROVIDER_STATUS");
		};
		if (amount <= 0)
			throw new IllegalArgumentException("INVALID_PROVIDER_AMOUNT");
		return new Result(safeId(resource.path("id").asText()), translated, amount, "BRL",
				resource.path("external_reference").asText(), owner,
				resource.path("type_response").path("qr_data").asText(null));
	}

	private static String safeId(String value) {
		if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
			throw new IllegalArgumentException("INVALID_RESOURCE_ID");
		return value;
	}

}
