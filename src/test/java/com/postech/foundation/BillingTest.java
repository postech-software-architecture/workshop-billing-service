package com.postech.foundation;

import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BillingTest {

	final JdbcTemplate db = mock(JdbcTemplate.class);

	final SqlMessages messages = mock(SqlMessages.class);

	final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

	final Billing billing = new Billing(db, json, messages);

	final UUID id = UUID.randomUUID(), os = UUID.randomUUID(), attempt = UUID.randomUUID(), saga = UUID.randomUUID(),
			client = UUID.randomUUID();

	final BudgetSnapshot snapshot = new BudgetSnapshot(id, 1, client,
			List.of(new BudgetSnapshot.Item(UUID.randomUUID(), "SERVICO", "Revisao", "1", 100, 100)), 100, "BRL");

	Budget pending() {
		return new Budget(id, os, attempt, saga, os, snapshot, Budget.State.PENDENTE, 1);
	}

	Envelope generate() {
		return new Envelope(UUID.randomUUID(), "billing.gerar-orcamento.v1", 1, Instant.now(), "os-service", os, saga,
				attempt, os, null, os, 1, json.valueToTree(snapshot));
	}

	@Test
	void duplicateMessageStopsBeforeWritingProposal() {
		billing.accept(generate());
		verifyNoInteractions(messages);
		verify(db, never()).queryForObject(anyString(), eq(Boolean.class), anyString());
	}

	@Test
	void firstGenerationWritesSnapshotNotificationAndEvent() {
		when(db.update(startsWith("INSERT INTO inbox"), any(UUID.class))).thenReturn(1);
		billing.accept(generate());
		verify(db).update(startsWith("INSERT INTO budgets"), eq(id), eq(os), eq(attempt), eq(saga), eq(os), eq(1),
				anyString());
		verify(db).update(startsWith("INSERT INTO budget_notifications"), any(UUID.class), eq(id), eq(client));
		verify(messages).emit(
				argThat(e -> e.messageType().equals("billing.orcamento-gerado.v1") && e.osId().equals(os)), eq(false));
	}

	@Test
	void sameBusinessVersionWithNewMessageDoesNotGenerateAgain() {
		when(db.update(startsWith("INSERT INTO inbox"), any(UUID.class))).thenReturn(1);
		when(db.queryForList(startsWith("SELECT id FROM budgets WHERE os_id"), eq(os), eq(1)))
			.thenReturn(List.of(Map.of("id", id)));
		when(db.query(startsWith("SELECT * FROM budgets"), org.mockito.ArgumentMatchers.<RowMapper<Budget>>any(),
				eq(id)))
			.thenReturn(List.of(pending()));
		billing.accept(generate());
		verifyNoInteractions(messages);
	}

	@Test
	void approvalWritesOneDecisionAndOneEventWithoutPaymentCall() {
		when(db.query(startsWith("SELECT * FROM budgets"), org.mockito.ArgumentMatchers.<RowMapper<Budget>>any(),
				eq(id)))
			.thenReturn(List.of(pending()));
		var actor = new RemoteIdentity.Result(true, UUID.randomUUID().toString(), client.toString(),
				List.of("CLIENTE"));
		var approved = billing.decide(id, Budget.State.APROVADO, actor);
		when(db.query(startsWith("SELECT * FROM budgets"), org.mockito.ArgumentMatchers.<RowMapper<Budget>>any(),
				eq(id)))
			.thenReturn(List.of(approved));
		assertSame(approved, billing.decide(id, Budget.State.APROVADO, actor));
		verify(messages, times(1)).emit(any(Envelope.class), eq(false));
		verify(db, times(1)).update(startsWith("INSERT INTO budget_decisions"), eq(id), eq("APROVADO"),
				eq(actor.usuarioId()));
	}

	@Test
	void lateGenerationCannotResurrectCancelledBudget() {
		when(db.update(startsWith("INSERT INTO inbox"), any(UUID.class))).thenReturn(1);
		when(db.queryForList("SELECT * FROM budget_cancellations WHERE id=?", id))
			.thenReturn(List.of(Map.of("os_id", os, "attempt_id", attempt, "saga_id", saga, "budget_version", 1)));
		billing.accept(generate());
		verifyNoInteractions(messages);
		verify(db, never()).update(startsWith("INSERT INTO budgets"), eq(id), eq(os), eq(attempt), eq(saga), eq(os),
				eq(1), anyString());
	}

}
