package com.postech.foundation;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FinanceWorker {

	private final Finance store;

	private final ObjectProvider<PagamentoGateway> gateways;

	public FinanceWorker(Finance store, ObjectProvider<PagamentoGateway> gateways) {
		this.store = store;
		this.gateways = gateways;
	}

	@Scheduled(fixedDelayString = "${payments.poll-ms:3000}")
	public void tick() {
		var gateway = gateways.getIfAvailable();
		if (gateway == null)
			return;
		store.claim().ifPresent(job -> {
			try {
				var p = job.payment();
				PagamentoGateway.Result observed;
				if (p.providerOrderId() == null) {
					// Reconcile an unknown creation by replaying the SAME persisted
					// provider idempotency key.
					observed = gateway.create(new PagamentoGateway.Charge(p.id(), p.id(), p.budgetId(), p.attemptId(),
							p.externalReference(), p.totalCentavos(), p.moeda()));
				}
				else
					observed = gateway.consult(p.providerOrderId());
				if (job.kind().equals("CANCEL") && observed.status().equals("PENDENTE"))
					observed = gateway.cancel(job.operationId(), observed.providerOrderId());
				if (job.kind().equals("REFUND") && observed.status().equals("CONFIRMADO"))
					observed = gateway.refund(job.operationId(), observed.providerOrderId(), p.totalCentavos());
				store.apply(job, observed);
			}
			catch (IllegalArgumentException invalid) {
				store.failed(job, true);
			}
			catch (RuntimeException unavailable) {
				store.failed(job, false);
			}
		});
	}

}
