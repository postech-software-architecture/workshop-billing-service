package com.postech.foundation;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orcamentos")
@PreAuthorize("hasAnyRole('ADMINISTRADOR','ATENDENTE','CLIENTE')")
public class BudgetApi {

	private final Billing billing;

	private final RemoteIdentity identity;

	private final org.springframework.jdbc.core.JdbcTemplate db;

	public BudgetApi(Billing billing, RemoteIdentity identity, org.springframework.jdbc.core.JdbcTemplate db) {
		this.billing = billing;
		this.identity = identity;
		this.db = db;
	}

	@GetMapping("/{id}/notificacao")
	public java.util.Map<String, Object> notification(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		Billing.authorize(billing.find(id, false), identity.details(jwt));
		return db.queryForMap("SELECT state,attempts,last_error FROM budget_notifications WHERE budget_id=?", id);
	}

	@GetMapping("/{id}")
	public Budget find(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		Budget budget = billing.find(id, false);
		Billing.authorize(budget, identity.details(jwt));
		return budget;
	}

	@PostMapping("/{id}/aprovar")
	public Budget approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return billing.decide(id, Budget.State.APROVADO, identity.details(jwt));
	}

	@PostMapping("/{id}/rejeitar")
	public Budget reject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return billing.decide(id, Budget.State.REJEITADO, identity.details(jwt));
	}

	@PostMapping("/{id}/cancelar")
	public Budget cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return billing.decide(id, Budget.State.CANCELADO, identity.details(jwt));
	}

}
