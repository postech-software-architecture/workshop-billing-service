package com.postech.foundation;

import java.util.UUID;

/** Phase-4 integration port. No production implementation reports simulated payments. */
public interface PagamentoGateway {

	record Charge(UUID operationId, UUID pagamentoId, UUID orcamentoId, UUID attemptId, String externalReference,
			long totalCentavos, String moeda) {
	}

	record Result(String providerOrderId, String status, long totalCentavos, String moeda, String externalReference,
			String ownerId, String qrData) {
	}

	Result create(Charge charge);

	Result consult(String providerOrderId);

	Result cancel(UUID operationId, String providerOrderId);

	Result refund(UUID operationId, String providerOrderId, long totalCentavos);

}
