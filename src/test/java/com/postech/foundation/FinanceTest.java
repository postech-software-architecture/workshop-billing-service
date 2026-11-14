package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FinanceTest {

	final JdbcTemplate db = mock(JdbcTemplate.class);

	final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

	final SqlMessages outbox = mock(SqlMessages.class);

	final Billing budgets = mock(Billing.class);

	final Finance store = new Finance(db, json, outbox, budgets);

	final UUID id = UUID.randomUUID(), budget = UUID.randomUUID(), attempt = UUID.randomUUID(), os = UUID.randomUUID(),
			saga = UUID.randomUUID();

	final BudgetSnapshot snapshot = new BudgetSnapshot(budget, 1, UUID.randomUUID(),
			List.of(new BudgetSnapshot.Item(UUID.randomUUID(), "SERVICO", "Revisao", "1", 100, 100)), 100, "BRL");

	Envelope creation() {
		return new Envelope(UUID.randomUUID(), "billing.criar-cobranca.v1", 1, Instant.now(), "os-service", os, saga,
				attempt, os, null, os, 1, json.valueToTree(Map.of("orcamentoId", budget, "orcamentoVersao", 1,
						"operationId", id, "totalCentavos", 100, "moeda", "BRL")));
	}

	Envelope cancel() {
		return new Envelope(UUID.randomUUID(), "billing.cancelar-cobranca.v1", 1, Instant.now(), "os-service", os, saga,
				attempt, os, null, os, 1,
				json.valueToTree(Map.of("operationId", UUID.randomUUID(), "pagamentoId", id)));
	}

	Payment payment(Payment.State state, String resource) {
		return new Payment(id, budget, attempt, "workshop_" + id.toString().replace("-", ""), 100, "BRL", resource,
				state, 1);
	}

	@SuppressWarnings("unchecked")
	void found(Payment p) {
		when(db.query(startsWith("SELECT * FROM payments"), any(RowMapper.class), eq(id))).thenReturn(List.of(p));
	}

	Finance.Job job(Payment p, String kind, int attempts) {
		return new Finance.Job(UUID.randomUUID(), UUID.randomUUID(), kind, attempts, p, creation());
	}

	void leased(Finance.Job job) {
		when(db.queryForList("SELECT id FROM financial_operations WHERE id=? AND lease_token=?", job.operationId(),
				job.lease()))
			.thenReturn(List.of(Map.of("id", job.operationId())));
	}

	FinanceTest() {
		when(db.update(startsWith("INSERT INTO inbox"), any(UUID.class))).thenReturn(1);
		when(budgets.find(budget, true))
			.thenReturn(new Budget(budget, os, attempt, saga, os, snapshot, Budget.State.APROVADO, 2));
	}

	void existing(Payment p) throws Exception {
		found(p);
		when(db.queryForList("SELECT id FROM payments WHERE id=?", id)).thenReturn(List.of(Map.of("id", id)));
		when(db.queryForObject("SELECT envelope FROM payment_context WHERE payment_id=?", String.class, id))
			.thenReturn(json.writeValueAsString(creation()));
	}

	@Test
	void cancellationOfPaidResourceSchedulesRefundOnlyAfterTrustedCommand() throws Exception {
		existing(payment(Payment.State.CONFIRMADO, "ORD1"));
		store.accept(cancel());
		verify(db).update(startsWith("UPDATE payments SET state"), eq("ESTORNO_PENDENTE"), eq("ORD1"), eq(2L), eq(id));
		verify(db).update(startsWith("INSERT INTO financial_operations"), any(UUID.class), eq(id), eq("REFUND"),
				anyString(), anyString());
		verifyNoInteractions(outbox);
	}

	@Test
	void neverDispatchedChargeIsCancelledLocallyWithoutCreatingProviderResource() throws Exception {
		existing(payment(Payment.State.SOLICITADO, null));
		when(db.queryForList(startsWith("SELECT attempts FROM financial_operations"), eq(id)))
			.thenReturn(List.of(Map.of("attempts", 0)));
		store.accept(cancel());
		verify(db).update(startsWith("UPDATE payments SET state"), eq("CANCELADO"), isNull(), eq(2L), eq(id));
		verify(outbox).emit(argThat(e -> e.messageType().equals("billing.cobranca-nao-criada.v1")), eq(false));
	}

	@Test
	void pendingResourceSchedulesCancellationWithStableOperationIdentity() throws Exception {
		existing(payment(Payment.State.PENDENTE, "ORD1"));
		var command = cancel();
		store.accept(command);
		verify(db).update(startsWith("INSERT INTO financial_operations"),
				eq(UUID.fromString(command.payload().path("operationId").asText())), eq(id), eq("CANCEL"), anyString(),
				anyString());
	}

	@Test
	void claimedJobRetainsOriginalPaymentContextAndIncrementsAttempt() throws Exception {
		var p = payment(Payment.State.PENDENTE, "ORD1");
		found(p);
		UUID op = UUID.randomUUID();
		when(db.queryForList(startsWith("SELECT o.* FROM financial_operations")))
			.thenReturn(List.of(Map.of("id", op, "payment_id", id, "kind", "CREATE", "attempts", 2)));
		when(db.queryForObject("SELECT envelope FROM payment_context WHERE payment_id=?", String.class, id))
			.thenReturn(json.writeValueAsString(creation()));
		var claimed = store.claim().orElseThrow();
		assertEquals(op, claimed.operationId());
		assertEquals(3, claimed.attempts());
		assertEquals(os, claimed.context().osId());
		assertNotNull(claimed.lease());
	}

	@Test
	void idleWorkerClaimAndManualResumeDoNotCreateNewPayment() {
		assertTrue(store.claim().isEmpty());
		UUID op = UUID.randomUUID();
		store.resume(op);
		verify(db).update(startsWith("UPDATE financial_operations SET state='WAITING'"), eq(op));
		verifyNoInteractions(outbox, budgets);
	}

	@Test
	void chargePersistsIntentBeforeAnyExternalEffect() {
		store.accept(creation());
		verify(db).update(startsWith("INSERT INTO payments"), eq(id), eq(budget), eq(attempt), anyString(), eq(100L),
				eq("BRL"), eq("SOLICITADO"), eq(1L));
		verify(db).update(startsWith("INSERT INTO financial_operations"), eq(id), eq(id), eq("CREATE"),
				eq(id.toString()), anyString());
		verifyNoInteractions(outbox);
	}

	@Test
	void mismatchedChargeAmountDoesNotScheduleExternalOperation() {
		var e = creation();
		((com.fasterxml.jackson.databind.node.ObjectNode) e.payload()).put("totalCentavos", 99);
		assertThrows(IllegalArgumentException.class, () -> store.accept(e));
		verify(db, never()).update(startsWith("INSERT INTO payments"), any(Object[].class));
	}

	@Test
	void duplicateTransportMessageDoesNotCreateOperation() {
		when(db.update(startsWith("INSERT INTO inbox"), any(UUID.class))).thenReturn(0);
		store.accept(creation());
		verifyNoInteractions(budgets, outbox);
	}

	@Test
	void cancellationBeforeCreationLeavesTombstoneAndExplicitNoChargeAcknowledgement() {
		store.accept(cancel());
		verify(db).update(startsWith("INSERT INTO financial_tombstones"), eq(id), eq(os), eq(attempt), eq(saga),
				anyString());
		verify(outbox).emit(argThat(e -> {
			e.validate("os");
			return e.messageType().equals("billing.cobranca-nao-criada.v1") && !e.payload().has("providerOrderId");
		}), eq(false));
	}

	@Test
	void delayedCreationCannotBypassCancellationTombstone() {
		when(db.queryForList("SELECT * FROM financial_tombstones WHERE payment_id=?", id))
			.thenReturn(List.of(Map.of("os_id", os, "attempt_id", attempt, "saga_id", saga)));
		store.accept(creation());
		verifyNoInteractions(budgets, outbox);
	}

	@Test
	void verifiedConfirmationIsPersistedAndPublishedTogether() {
		var p = payment(Payment.State.PENDENTE, "ORD1");
		found(p);
		var job = job(p, "CREATE", 1);
		leased(job);
		store.apply(job,
				new PagamentoGateway.Result("ORD1", "CONFIRMADO", 100, "BRL", p.externalReference(), "seller", "qr"));
		verify(db).update(startsWith("UPDATE payments SET state"), eq("CONFIRMADO"), eq("ORD1"), eq(2L), eq(id));
		verify(outbox).emit(argThat(e -> {
			e.validate("os");
			return e.messageType().equals("billing.pagamento-confirmado.v1")
					&& e.payload().path("totalCentavos").asLong() == 100;
		}), eq(false));
	}

	@Test
	void acceptedRefundDoesNotPublishCompletionBeforeProviderConfirms() {
		var p = payment(Payment.State.ESTORNO_PENDENTE, "ORD1");
		found(p);
		var job = job(p, "REFUND", 1);
		leased(job);
		store.apply(job,
				new PagamentoGateway.Result("ORD1", "CONFIRMADO", 100, "BRL", p.externalReference(), "seller", null));
		verifyNoInteractions(outbox);
		verify(db).update(startsWith("UPDATE financial_operations SET state"), eq("WAITING"), eq(job.operationId()),
				eq(job.lease()));
	}

	@Test
	void exhaustedCompensationIsVisibleAndNotReportedAsSuccessful() {
		var p = payment(Payment.State.ESTORNO_PENDENTE, "ORD1");
		found(p);
		var job = job(p, "REFUND", 10);
		leased(job);
		store.failed(job, false);
		verify(db).update(startsWith("UPDATE financial_operations SET lease_until"), eq("MANUAL_REVIEW"),
				eq("PROVIDER_UNAVAILABLE"), eq(job.operationId()), eq(job.lease()));
		verify(outbox).emit(argThat(e -> e.messageType().equals("billing.compensacao-financeira-pendente.v1")),
				eq(false));
	}

	@Test
	void lostLeaseCannotPublishFinancialEffects() {
		var p = payment(Payment.State.PENDENTE, "ORD1");
		found(p);
		store.apply(job(p, "CREATE", 1),
				new PagamentoGateway.Result("ORD1", "CONFIRMADO", 100, "BRL", p.externalReference(), "seller", null));
		verifyNoInteractions(outbox);
		verify(db, never()).update(startsWith("UPDATE payments"), any(Object[].class));
	}

	@Test
	void creationTimeoutRecordsUnknownInsteadOfAssumingFailure() {
		var p = payment(Payment.State.SOLICITADO, null);
		found(p);
		var job = job(p, "CREATE", 1);
		leased(job);
		store.failed(job, false);
		verify(db).update(startsWith("UPDATE payments SET state"), eq("RESULTADO_DESCONHECIDO"), isNull(), eq(2L),
				eq(id));
		verifyNoInteractions(outbox);
	}

	@Test
	void currentOwnerAndAmountMismatchDoesNotPublishAnything() {
		var p = payment(Payment.State.PENDENTE, "ORD1");
		found(p);
		var job = job(p, "CREATE", 1);
		leased(job);
		assertThrows(IllegalArgumentException.class, () -> store.apply(job,
				new PagamentoGateway.Result("ORD1", "CONFIRMADO", 99, "BRL", p.externalReference(), "seller", null)));
		verifyNoInteractions(outbox);
	}

}
