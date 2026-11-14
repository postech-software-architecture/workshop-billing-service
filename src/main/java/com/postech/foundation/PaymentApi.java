package com.postech.foundation;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.jdbc.core.JdbcTemplate;

@RestController
public class PaymentApi {

	private final Finance finance;

	private final Billing billing;

	private final RemoteIdentity identity;

	private final PaymentWebhook webhook;

	private final JdbcTemplate db;

	public PaymentApi(Finance finance, Billing billing, RemoteIdentity identity, PaymentWebhook webhook,
			JdbcTemplate db) {
		this.finance = finance;
		this.billing = billing;
		this.identity = identity;
		this.webhook = webhook;
		this.db = db;
	}

	@GetMapping("/api/v1/pagamentos/{id}")
	@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE','CLIENTE')")
	public Map<String, Object> find(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		Payment p = finance.find(id, false);
		Billing.authorize(billing.find(p.budgetId(), false), identity.details(jwt));
		var data = new LinkedHashMap<String, Object>();
		data.put("pagamento", p);
		data.put("qrData",
				db.queryForObject("SELECT qr_data FROM payment_context WHERE payment_id=?", String.class, id));
		data.put("operacoes", db.queryForList(
				"SELECT id,kind,state,attempts,next_at,last_error FROM financial_operations WHERE payment_id=?", id));
		return data;
	}

	@PostMapping("/api/v1/operacoes-financeiras/{id}/retomar")
	@PreAuthorize("hasRole('ADMINISTRADOR')")
	public void resume(@PathVariable UUID id) {
		finance.resume(id);
	}

	@PostMapping("/api/v1/webhooks/mercado-pago")
	public void webhook(@RequestHeader(value = "X-Signature", required = false) String signature,
			@RequestHeader(value = "X-Request-Id", required = false) String request,
			@RequestParam(value = "data.id", required = false) String resource,
			@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
		webhook.receive(signature, request, resource, body);
	}

}
