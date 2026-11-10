package com.postech.foundation;

import java.util.UUID;

public record Budget(UUID id, UUID osId, UUID attemptId, UUID sagaId, UUID correlationId, BudgetSnapshot snapshot,
		State state, long version) {
	public enum State {

		PENDENTE, APROVADO, REJEITADO, CANCELADO

	}

	public Budget decide(State decision) {
		if (decision == State.PENDENTE)
			throw new IllegalArgumentException("INVALID_DECISION");
		if (state == decision)
			return this;
		if (state != State.PENDENTE)
			throw new IllegalArgumentException("BUDGET_ALREADY_DECIDED");
		return new Budget(id, osId, attemptId, sagaId, correlationId, snapshot, decision, version + 1);
	}
}
