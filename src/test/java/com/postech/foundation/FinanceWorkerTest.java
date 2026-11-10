package com.postech.foundation;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FinanceWorkerTest {

	final Finance store = mock(Finance.class);

	final PagamentoGateway gateway = mock(PagamentoGateway.class);

	@SuppressWarnings("unchecked")
	final ObjectProvider<PagamentoGateway> provider = mock(ObjectProvider.class);

	final FinanceWorker worker = new FinanceWorker(store, provider);

	Finance.Job job(String kind, boolean known) {
		var p = new Payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "stable_reference", 100, "BRL",
				known ? "ORD1" : null, Payment.State.RESULTADO_DESCONHECIDO, 2);
		return new Finance.Job(UUID.randomUUID(), UUID.randomUUID(), kind, 2, p, null);
	}

	void given(Finance.Job job) {
		when(provider.getIfAvailable()).thenReturn(gateway);
		when(store.claim()).thenReturn(Optional.of(job));
	}

	PagamentoGateway.Result result(String status) {
		return new PagamentoGateway.Result("ORD1", status, 100, "BRL", "stable_reference", "seller", null);
	}

	@Test
	void missingConfigurationDoesNotClaimOrDropPendingOperations() {
		worker.tick();
		verifyNoInteractions(store, gateway);
	}

	@Test
	void unknownCreationReplaysThePersistedCreationIdentity() {
		var job = job("CREATE", false);
		given(job);
		when(gateway.create(any())).thenReturn(result("PENDENTE"));
		worker.tick();
		verify(gateway).create(argThat(
				c -> c.operationId().equals(job.payment().id()) && c.externalReference().equals("stable_reference")));
		verify(store).apply(eq(job), any());
	}

	@Test
	void knownResourceIsReconciledBeforeMutation() {
		var job = job("CANCEL", true);
		given(job);
		when(gateway.consult("ORD1")).thenReturn(result("PENDENTE"));
		when(gateway.cancel(job.operationId(), "ORD1")).thenReturn(result("CANCELADO"));
		worker.tick();
		var order = inOrder(gateway);
		order.verify(gateway).consult("ORD1");
		order.verify(gateway).cancel(job.operationId(), "ORD1");
		verify(store).apply(eq(job), any());
	}

	@Test
	void networkFailurePersistsRetryInsteadOfConfirming() {
		var job = job("CREATE", false);
		given(job);
		when(gateway.create(any())).thenThrow(new IllegalStateException("timeout"));
		worker.tick();
		verify(store).failed(job, false);
		verify(store, never()).apply(any(), any());
	}

	@Test
	void mismatchedProviderResourceRequiresManualReview() {
		var job = job("CREATE", true);
		given(job);
		when(gateway.consult("ORD1")).thenThrow(new IllegalArgumentException("owner mismatch"));
		worker.tick();
		verify(store).failed(job, true);
	}

	@Test
	void refundIsNotAssumedCompleteWhenProviderOnlyAcceptedRequest() {
		var job = job("REFUND", true);
		given(job);
		when(gateway.consult("ORD1")).thenReturn(result("CONFIRMADO"));
		when(gateway.refund(job.operationId(), "ORD1", 100)).thenReturn(result("CONFIRMADO"));
		worker.tick();
		verify(store).apply(job, result("CONFIRMADO"));
	}

}
