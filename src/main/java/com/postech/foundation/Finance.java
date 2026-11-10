package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Finance {

	private final JdbcTemplate db;

	private final ObjectMapper json;

	private final SqlMessages messages;

	private final Billing budgets;

	public Finance(JdbcTemplate db, ObjectMapper json, SqlMessages messages, Billing budgets) {
		this.db = db;
		this.json = json;
		this.messages = messages;
		this.budgets = budgets;
	}

	public record Job(UUID operationId, UUID lease, String kind, int attempts, Payment payment, Envelope context) {
	}

	@Transactional
	public void accept(Envelope e) {
		if (db.update("INSERT INTO inbox(message_id) VALUES(?) ON CONFLICT DO NOTHING", e.messageId()) == 0)
			return;
		boolean create = e.messageType().equals("billing.criar-cobranca.v1");
		UUID paymentId = UUID.fromString(e.payload().path(create ? "operationId" : "pagamentoId").asText());
		db.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?)) IS NULL", Boolean.class, paymentId.toString());
		if (create) {
			var tombstone = db.queryForList("SELECT * FROM financial_tombstones WHERE payment_id=?", paymentId);
			if (!tombstone.isEmpty()) {
				checkContext(e, tombstone.getFirst());
				return;
			}
			UUID budgetId = UUID.fromString(e.payload().path("orcamentoId").asText());
			Budget budget = budgets.find(budgetId, true);
			if (budget.state() != Budget.State.APROVADO || !budget.osId().equals(e.osId())
					|| !budget.attemptId().equals(e.attemptId()) || !budget.sagaId().equals(e.sagaId())
					|| budget.snapshot().orcamentoVersao() != e.payload().path("orcamentoVersao").asInt()
					|| budget.snapshot().totalCentavos() != e.payload().path("totalCentavos").asLong()
					|| !"BRL".equals(e.payload().path("moeda").asText()))
				throw new IllegalArgumentException("CHARGE_CONTEXT_MISMATCH");
			var existing = db.queryForList("SELECT id FROM payments WHERE budget_id=? AND attempt_id=?", budgetId,
					e.attemptId());
			if (!existing.isEmpty()) {
				if (!paymentId.equals(existing.getFirst().get("id")))
					throw new IllegalArgumentException("CHARGE_ID_CONFLICT");
				return;
			}
			Payment p = new Payment(paymentId, budgetId, e.attemptId(),
					"workshop_" + paymentId.toString().replace("-", ""), budget.snapshot().totalCentavos(), "BRL", null,
					Payment.State.SOLICITADO, 1);
			db.update(
					"INSERT INTO payments(id,budget_id,attempt_id,external_reference,total_centavos,currency,state,version) VALUES(?,?,?,?,?,?,?,?)",
					p.id(), p.budgetId(), p.attemptId(), p.externalReference(), p.totalCentavos(), p.moeda(),
					p.state().name(), p.version());
			db.update("INSERT INTO payment_context(payment_id,envelope,budget_version) VALUES(?,?,?)", p.id(),
					encode(e), budget.snapshot().orcamentoVersao());
			operation(paymentId, paymentId, "CREATE", e);
			return;
		}
		var existing = db.queryForList("SELECT id FROM payments WHERE id=?", paymentId);
		if (existing.isEmpty()) {
			var old = db.queryForList("SELECT * FROM financial_tombstones WHERE payment_id=?", paymentId);
			if (!old.isEmpty())
				checkContext(e, old.getFirst());
			db.update(
					"INSERT INTO financial_tombstones(payment_id,os_id,attempt_id,saga_id,command) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
					paymentId, e.osId(), e.attemptId(), e.sagaId(), encode(e));
			messages.emit(new Envelope(UUID.randomUUID(), "billing.cobranca-nao-criada.v1", 1, Instant.now(),
					"billing-service", e.osId(), e.sagaId(), e.attemptId(), e.correlationId(), e.messageId(), paymentId,
					1, e.payload()), false);
			return;
		}
		Payment p = find(paymentId, true);
		Envelope original = context(paymentId);
		if (!e.osId().equals(original.osId()) || !e.attemptId().equals(original.attemptId())
				|| !e.sagaId().equals(original.sagaId()))
			throw new IllegalArgumentException("FINANCIAL_CONTEXT_MISMATCH");
		boolean refund = e.messageType().equals("billing.estornar-pagamento.v1")
				|| p.state() == Payment.State.CONFIRMADO;
		var creation = db.queryForList(
				"SELECT attempts FROM financial_operations WHERE payment_id=? AND kind='CREATE' FOR UPDATE", paymentId);
		if (!refund && p.providerOrderId() == null
				&& ((p.mayCreate() && !creation.isEmpty()
						&& ((Number) creation.getFirst().get("attempts")).intValue() == 0)
						|| p.state() == Payment.State.CANCELADO)) {
			if (p.state() != Payment.State.CANCELADO)
				save(p, p.cancelBeforeDispatch());
			db.update("UPDATE financial_operations SET state='DONE' WHERE payment_id=?", paymentId);
			messages.emit(new Envelope(UUID.randomUUID(), "billing.cobranca-nao-criada.v1", 1, Instant.now(),
					"billing-service", e.osId(), e.sagaId(), e.attemptId(), e.correlationId(), e.messageId(), paymentId,
					p.version() + 1, e.payload()), false);
			return;
		}
		Payment next = refund ? p.refund(true) : p.cancel();
		save(p, next);
		db.update("UPDATE payment_context SET cancellation_confirmed=true WHERE payment_id=?", paymentId);
		UUID operation = UUID.fromString(e.payload().path("operationId").asText());
		operation(operation, paymentId, refund ? "REFUND" : "CANCEL", e);
	}

	private void operation(UUID id, UUID payment, String kind, Envelope context) {
		db.update(
				"INSERT INTO financial_operations(id,payment_id,kind,idempotency_key,state,context) VALUES(?,?,?,?,'WAITING',?) ON CONFLICT(payment_id,kind) DO NOTHING",
				id, payment, kind, id.toString(), encode(context));
	}

	@Transactional
	public Optional<Job> claim() {
		var rows = db.queryForList(
				"SELECT o.* FROM financial_operations o JOIN payments p ON p.id=o.payment_id WHERE o.state='WAITING' AND o.next_at<=now() AND (o.lease_until IS NULL OR o.lease_until<now()) AND NOT EXISTS(SELECT 1 FROM financial_operations busy WHERE busy.payment_id=p.id AND busy.lease_until>now()) ORDER BY CASE o.kind WHEN 'REFUND' THEN 0 WHEN 'CANCEL' THEN 1 ELSE 2 END,o.next_at FOR UPDATE OF p SKIP LOCKED LIMIT 1");
		if (rows.isEmpty())
			return Optional.empty();
		var row = rows.getFirst();
		UUID lease = UUID.randomUUID(), id = (UUID) row.get("id"), payment = (UUID) row.get("payment_id");
		db.update(
				"UPDATE financial_operations SET lease_token=?,lease_until=now()+interval '60 seconds',attempts=attempts+1 WHERE id=?",
				lease, id);
		return Optional.of(new Job(id, lease, (String) row.get("kind"), ((Number) row.get("attempts")).intValue() + 1,
				find(payment, false), context(payment)));
	}

	public Payment find(UUID id, boolean lock) {
		var rows = db.query("SELECT * FROM payments WHERE id=?" + (lock ? " FOR UPDATE" : ""),
				(r, n) -> new Payment(r.getObject("id", UUID.class), r.getObject("budget_id", UUID.class),
						r.getObject("attempt_id", UUID.class), r.getString("external_reference"),
						r.getLong("total_centavos"), r.getString("currency"), r.getString("provider_order_id"),
						Payment.State.valueOf(r.getString("state")), r.getLong("version")),
				id);
		if (rows.isEmpty())
			throw new org.springframework.web.server.ResponseStatusException(
					org.springframework.http.HttpStatus.NOT_FOUND);
		return rows.getFirst();
	}

	@Transactional
	public void apply(Job job, PagamentoGateway.Result result) {
		Payment prior = find(job.payment().id(), true);
		if (!leased(job))
			return;
		Payment next = prior.verified(result.providerOrderId(), result.externalReference(), result.totalCentavos(),
				result.moeda(), Payment.State.valueOf(result.status()));
		save(prior, next);
		db.update("UPDATE payment_context SET qr_data=COALESCE(?,qr_data) WHERE payment_id=?", result.qrData(),
				next.id());
		boolean terminal = Set
			.of(Payment.State.CONFIRMADO, Payment.State.RECUSADO, Payment.State.EXPIRADO, Payment.State.CANCELADO,
					Payment.State.ESTORNADO)
			.contains(next.state());
		boolean done = job.kind().equals("REFUND") ? next.state() == Payment.State.ESTORNADO : terminal;
		db.update(
				"UPDATE financial_operations SET state=?,lease_until=NULL,next_at=now()+interval '30 seconds' WHERE id=? AND lease_token=?",
				done ? "DONE" : "WAITING", job.operationId(), job.lease());
		db.update(
				"UPDATE provider_notifications SET state='PROCESSED',processed_at=now() WHERE resource_id=? AND state<>'PROCESSED'",
				result.providerOrderId());
		if (next.version() != prior.version())
			publish(job.context(), next);
		// A repeated cancellation after a crash must also publish its already confirmed
		// outcome.
		else if (done && job.kind().equals("CANCEL") && next.state() == Payment.State.CANCELADO)
			publish(job.context(), next);
	}

	@Transactional
	public void failed(Job job, boolean invalidResource) {
		Payment p = find(job.payment().id(), true);
		if (!leased(job))
			return;
		save(p, p.unknown());
		boolean manual = invalidResource || job.attempts() >= 10;
		db.update(
				"UPDATE financial_operations SET lease_until=NULL,state=?,last_error=?,next_at=now()+interval '1 minute' WHERE id=? AND lease_token=?",
				manual ? "MANUAL_REVIEW" : "WAITING", invalidResource ? "RESOURCE_MISMATCH" : "PROVIDER_UNAVAILABLE",
				job.operationId(), job.lease());
		if (manual) {
			var payload = Map.of("pagamentoId", p.id(), "operationId", job.operationId(), "codigo",
					invalidResource ? "RESOURCE_MISMATCH" : "RETRIES_EXHAUSTED", "retriavel", !invalidResource);
			emit(job.context(), p, "billing.compensacao-financeira-pendente.v1", payload);
		}
	}

	@Transactional
	public void resume(UUID operationId) {
		db.update(
				"UPDATE financial_operations SET state='WAITING',attempts=0,next_at=now(),lease_until=NULL WHERE id=? AND state='MANUAL_REVIEW'",
				operationId);
	}

	private boolean leased(Job job) {
		return !db
			.queryForList("SELECT id FROM financial_operations WHERE id=? AND lease_token=?", job.operationId(),
					job.lease())
			.isEmpty();
	}

	private void save(Payment old, Payment next) {
		if (old == next)
			return;
		db.update("UPDATE payments SET state=?,provider_order_id=?,version=?,updated_at=now() WHERE id=?",
				next.state().name(), next.providerOrderId(), next.version(), next.id());
		db.update("INSERT INTO payment_audit(id,payment_id,previous_state,next_state,version) VALUES(?,?,?,?,?)",
				UUID.randomUUID(), next.id(), old.state().name(), next.state().name(), next.version());
	}

	private void publish(Envelope context, Payment p) {
		String suffix = switch (p.state()) {
			case CONFIRMADO -> "pagamento-confirmado";
			case RECUSADO -> "pagamento-recusado";
			case EXPIRADO -> "pagamento-expirado";
			case CANCELADO -> "cobranca-cancelada";
			case ESTORNADO -> "pagamento-estornado";
			default -> null;
		};
		if (suffix == null)
			return;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("orcamentoId", p.budgetId());
		payload.put("orcamentoVersao", context.payload().path("orcamentoVersao").asInt());
		payload.put("pagamentoId", p.id());
		payload.put("providerOrderId", p.providerOrderId());
		payload.put("externalReference", p.externalReference());
		payload.put("totalCentavos", p.totalCentavos());
		payload.put("moeda", p.moeda());
		emit(context, p, "billing." + suffix + ".v1", payload);
	}

	private void emit(Envelope c, Payment p, String type, Object payload) {
		messages.emit(new Envelope(UUID.randomUUID(), type, 1, Instant.now(), "billing-service", c.osId(), c.sagaId(),
				c.attemptId(), c.correlationId(), c.messageId(), p.id(), p.version(), json.valueToTree(payload)),
				false);
	}

	private Envelope context(UUID payment) {
		try {
			return json.readValue(
					db.queryForObject("SELECT envelope FROM payment_context WHERE payment_id=?", String.class, payment),
					Envelope.class);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private String encode(Object value) {
		try {
			return json.writeValueAsString(value);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void checkContext(Envelope e, Map<String, Object> row) {
		if (!e.osId().equals(row.get("os_id")) || !e.attemptId().equals(row.get("attempt_id"))
				|| !e.sagaId().equals(row.get("saga_id")))
			throw new IllegalArgumentException("TOMBSTONE_CONTEXT_MISMATCH");
	}

}
