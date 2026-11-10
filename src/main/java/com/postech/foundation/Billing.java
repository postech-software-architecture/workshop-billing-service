package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Billing implements Handler {

	private org.springframework.beans.factory.ObjectProvider<Finance> finance;

	@org.springframework.beans.factory.annotation.Autowired
	void finance(org.springframework.beans.factory.ObjectProvider<Finance> finance) {
		this.finance = finance;
	}

	private final JdbcTemplate db;

	private final ObjectMapper json;

	private final SqlMessages messages;

	public Billing(JdbcTemplate db, ObjectMapper json, SqlMessages messages) {
		this.db = db;
		this.json = json;
		this.messages = messages;
	}

	@Transactional
	public void accept(Envelope e) {
		e.validate("billing");
		if (java.util.Set
			.of("billing.criar-cobranca.v1", "billing.cancelar-cobranca.v1", "billing.estornar-pagamento.v1")
			.contains(e.messageType())) {
			finance.getObject().accept(e);
			return;
		}
		if (db.update("INSERT INTO inbox(message_id) VALUES(?) ON CONFLICT DO NOTHING", e.messageId()) == 0)
			return;
		UUID id = UUID.fromString(e.payload().path("orcamentoId").asText());
		// Both cancellation and generation serialize on the business identity, even
		// before insertion.
		db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?)) IS NULL", Boolean.class, id.toString());
		if (e.messageType().equals("billing.cancelar-orcamento.v1")) {
			var rows = db.queryForList("SELECT id FROM budgets WHERE id=?", id);
			if (rows.isEmpty()) {
				var priorCancellation = db.queryForList("SELECT * FROM budget_cancellations WHERE id=?", id);
				if (!priorCancellation.isEmpty()) {
					var prior = priorCancellation.getFirst();
					if (!e.osId().equals(prior.get("os_id")) || !e.attemptId().equals(prior.get("attempt_id"))
							|| !e.sagaId().equals(prior.get("saga_id"))
							|| e.payload().path("orcamentoVersao").asInt() != ((Number) prior.get("budget_version"))
								.intValue())
						throw new IllegalArgumentException("CANCELLATION_CONTEXT_MISMATCH");
					return;
				}
				db.update(
						"INSERT INTO budget_cancellations(id,os_id,attempt_id,saga_id,budget_version) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
						id, e.osId(), e.attemptId(), e.sagaId(), e.payload().path("orcamentoVersao").asInt());
				messages.emit(response(e, id, 1, "billing.orcamento-cancelado.v1", e.payload()), false);
				return;
			}
			Budget budget = find(id, true);
			if (!budget.osId().equals(e.osId()) || !budget.attemptId().equals(e.attemptId())
					|| !budget.sagaId().equals(e.sagaId())
					|| budget.snapshot().orcamentoVersao() != e.payload().path("orcamentoVersao").asInt())
				throw new IllegalArgumentException("BUDGET_CONTEXT_MISMATCH");
			saveDecision(budget, Budget.State.CANCELADO, e.messageId(), "os-service");
			return;
		}
		BudgetSnapshot snapshot = BudgetSnapshot.read(e.payload(), json);
		var existing = db.queryForList("SELECT id FROM budgets WHERE os_id=? AND budget_version=?", e.osId(),
				snapshot.orcamentoVersao());
		if (!existing.isEmpty()) {
			Budget prior = find((UUID) existing.getFirst().get("id"), true);
			if (!prior.snapshot().equals(snapshot) || !prior.attemptId().equals(e.attemptId())
					|| !prior.sagaId().equals(e.sagaId()))
				throw new IllegalArgumentException("BUSINESS_KEY_CONFLICT");
			return;
		}
		var cancelled = db.queryForList("SELECT * FROM budget_cancellations WHERE id=?", id);
		if (!cancelled.isEmpty()) {
			var tombstone = cancelled.getFirst();
			if (!e.osId().equals(tombstone.get("os_id")) || !e.attemptId().equals(tombstone.get("attempt_id"))
					|| !e.sagaId().equals(tombstone.get("saga_id"))
					|| snapshot.orcamentoVersao() != ((Number) tombstone.get("budget_version")).intValue())
				throw new IllegalArgumentException("CANCELLATION_CONTEXT_MISMATCH");
			return;
		}
		db.update(
				"INSERT INTO budgets(id,os_id,attempt_id,saga_id,correlation_id,budget_version,snapshot,state,version) VALUES(?,?,?,?,?,?,?,'PENDENTE',1)",
				id, e.osId(), e.attemptId(), e.sagaId(), e.correlationId(), snapshot.orcamentoVersao(),
				encode(snapshot));
		// Delivery is a separate durable concern. A missing channel never rolls back a
		// valid budget.
		db.update("INSERT INTO budget_notifications(id,budget_id,client_id,state) VALUES(?,?,?,'PENDENTE_CANAL')",
				UUID.randomUUID(), id, snapshot.clienteId());
		messages.emit(
				response(e, id, 1, "billing.orcamento-gerado.v1",
						json.valueToTree(Map.of("orcamentoId", id, "orcamentoVersao", snapshot.orcamentoVersao()))),
				false);
	}

	public Budget find(UUID id, boolean lock) {
		var rows = db.query("SELECT * FROM budgets WHERE id=?" + (lock ? " FOR UPDATE" : ""),
				(r, n) -> new Budget(r.getObject("id", UUID.class), r.getObject("os_id", UUID.class),
						r.getObject("attempt_id", UUID.class), r.getObject("saga_id", UUID.class),
						r.getObject("correlation_id", UUID.class), decode(r.getString("snapshot")),
						Budget.State.valueOf(r.getString("state")), r.getLong("version")),
				id);
		if (rows.isEmpty())
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		return rows.getFirst();
	}

	@Transactional
	public Budget decide(UUID id, Budget.State decision, RemoteIdentity.Result actor) {
		Budget prior = find(id, true);
		authorize(prior, actor);
		return saveDecision(prior, decision, null, actor.usuarioId());
	}

	public static void authorize(Budget budget, RemoteIdentity.Result actor) {
		if (!actor.active())
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
		boolean staff = actor.roles().stream().anyMatch(r -> r.equals("ADMINISTRADOR") || r.equals("ATENDENTE"));
		if (!staff && !(actor.roles().contains("CLIENTE")
				&& budget.snapshot().clienteId().toString().equals(actor.clienteId())))
			throw new ResponseStatusException(HttpStatus.FORBIDDEN);
	}

	private Budget saveDecision(Budget prior, Budget.State decision, UUID cause, String actor) {
		Budget next = prior.decide(decision);
		if (next == prior)
			return prior;
		db.update("UPDATE budgets SET state=?,version=?,decided_at=now() WHERE id=?", next.state().name(),
				next.version(), next.id());
		db.update("INSERT INTO budget_decisions(budget_id,decision,actor) VALUES(?,?,?)", next.id(),
				next.state().name(), actor);
		String suffix = switch (decision) {
			case APROVADO -> "aprovado";
			case REJEITADO -> "rejeitado";
			case CANCELADO -> "cancelado";
			default -> throw new IllegalArgumentException("INVALID_DECISION");
		};
		messages.emit(new Envelope(UUID.randomUUID(), "billing.orcamento-" + suffix + ".v1", 1, Instant.now(),
				"billing-service", next.osId(), next.sagaId(), next.attemptId(), next.correlationId(), cause, next.id(),
				next.version(),
				json.valueToTree(
						Map.of("orcamentoId", next.id(), "orcamentoVersao", next.snapshot().orcamentoVersao()))),
				false);
		return next;
	}

	private Envelope response(Envelope e, UUID id, long version, String type,
			com.fasterxml.jackson.databind.JsonNode payload) {
		return new Envelope(UUID.randomUUID(), type, 1, Instant.now(), "billing-service", e.osId(), e.sagaId(),
				e.attemptId(), e.correlationId(), e.messageId(), id, version, payload);
	}

	private String encode(Object value) {
		try {
			return json.writeValueAsString(value);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private BudgetSnapshot decode(String value) {
		try {
			return json.readValue(value, BudgetSnapshot.class);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

}
