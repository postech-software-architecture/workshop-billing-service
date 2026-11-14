package com.postech.foundation;

import java.util.UUID;

/**
 * Financial state is changed only from a verified provider resource, never from a webhook
 * body alone.
 */
public record Payment(UUID id, UUID budgetId, UUID attemptId, String externalReference, long totalCentavos,
		String moeda, String providerOrderId, State state, long version) {
	public enum State {

		SOLICITADO, RESULTADO_DESCONHECIDO, PENDENTE, CONFIRMADO, RECUSADO, EXPIRADO, CANCELAMENTO_PENDENTE, CANCELADO,
		ESTORNO_PENDENTE, ESTORNADO

	}

	public Payment {
		if (id == null || budgetId == null || attemptId == null || externalReference == null
				|| !externalReference.matches("[A-Za-z0-9_-]{1,64}") || totalCentavos <= 0
				|| totalCentavos > 9007199254740991L || !"BRL".equals(moeda) || state == null || version < 1)
			throw new IllegalArgumentException("INVALID_PAYMENT");
	}

	public Payment unknown() {
		if (state != State.SOLICITADO)
			return this;
		return change(State.RESULTADO_DESCONHECIDO, providerOrderId);
	}

	public boolean mayCreate() {
		return state == State.SOLICITADO && providerOrderId == null;
	}

	public Payment verified(String resourceId, String reference, long amount, String currency, State observed) {
		if (resourceId == null || resourceId.isBlank() || !externalReference.equals(reference)
				|| totalCentavos != amount || !moeda.equals(currency)
				|| (providerOrderId != null && !providerOrderId.equals(resourceId)))
			throw new IllegalArgumentException("PROVIDER_RESOURCE_MISMATCH");
		if (!java.util.Set
			.of(State.PENDENTE, State.CONFIRMADO, State.RECUSADO, State.EXPIRADO, State.CANCELADO, State.ESTORNADO)
			.contains(observed))
			throw new IllegalArgumentException("INVALID_PROVIDER_STATE");
		if (observed == state)
			return this;
		boolean allowed = switch (state) {
			case SOLICITADO, RESULTADO_DESCONHECIDO, PENDENTE ->
				java.util.Set.of(State.PENDENTE, State.CONFIRMADO, State.RECUSADO, State.EXPIRADO, State.CANCELADO)
					.contains(observed);
			case CANCELAMENTO_PENDENTE -> observed == State.CANCELADO || observed == State.CONFIRMADO;
			case ESTORNO_PENDENTE -> observed == State.ESTORNADO;
			default -> false;
		};
		if (!allowed)
			return this; // An older observation cannot undo a terminal state.
		return change(observed, resourceId);
	}

	public Payment cancel() {
		if (state == State.CANCELADO || state == State.CANCELAMENTO_PENDENTE)
			return this;
		if (state != State.PENDENTE && state != State.RESULTADO_DESCONHECIDO && state != State.SOLICITADO)
			throw new IllegalArgumentException("PAYMENT_NOT_CANCELLABLE");
		return change(State.CANCELAMENTO_PENDENTE, providerOrderId);
	}

	public Payment cancelBeforeDispatch() {
		if (!mayCreate())
			throw new IllegalArgumentException("UNKNOWN_EXTERNAL_EFFECT");
		return change(State.CANCELADO, null);
	}

	public Payment refund(boolean workCancellationConfirmed) {
		if (!workCancellationConfirmed)
			throw new IllegalArgumentException("WORK_CANCELLATION_REQUIRED");
		if (state == State.ESTORNADO || state == State.ESTORNO_PENDENTE)
			return this;
		if (state != State.CONFIRMADO)
			throw new IllegalArgumentException("PAYMENT_NOT_REFUNDABLE");
		return change(State.ESTORNO_PENDENTE, providerOrderId);
	}

	private Payment change(State next, String resource) {
		return new Payment(id, budgetId, attemptId, externalReference, totalCentavos, moeda, resource, next,
				version + 1);
	}
}
