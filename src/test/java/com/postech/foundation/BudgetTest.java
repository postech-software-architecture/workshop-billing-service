package com.postech.foundation;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BudgetTest {

	Budget pending() {
		var id = UUID.randomUUID();
		var snapshot = new BudgetSnapshot(id, 1, UUID.randomUUID(),
				List.of(new BudgetSnapshot.Item(UUID.randomUUID(), "INSUMO", "Oleo", "0.125", 4250, 531)), 531, "BRL");
		return new Budget(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), snapshot,
				Budget.State.PENDENTE, 1);
	}

	@Test
	void approvalIsIdempotentAndCannotBeRejectedLater() {
		var result = pending().decide(Budget.State.APROVADO);
		assertSame(result, result.decide(Budget.State.APROVADO));
		assertThrows(IllegalArgumentException.class, () -> result.decide(Budget.State.REJEITADO));
	}

	@Test
	void rejectedSnapshotIsPreservedAndCannotBeApproved() {
		var prior = pending();
		var result = prior.decide(Budget.State.REJEITADO);
		assertSame(prior.snapshot(), result.snapshot());
		assertEquals(2, result.version());
		assertThrows(IllegalArgumentException.class, () -> result.decide(Budget.State.APROVADO));
	}

	@Test
	void ownerAndStaffCanAccessButOtherCustomerCannot() {
		var budget = pending();
		String owner = budget.snapshot().clienteId().toString();
		Billing.authorize(budget, new RemoteIdentity.Result(true, "user", owner, List.of("CLIENTE")));
		Billing.authorize(budget, new RemoteIdentity.Result(true, "staff", null, List.of("ATENDENTE")));
		assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> Billing.authorize(budget,
				new RemoteIdentity.Result(true, "intruder", UUID.randomUUID().toString(), List.of("CLIENTE"))));
		assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> Billing.authorize(budget,
				new RemoteIdentity.Result(true, "mechanic", null, List.of("MECANICO"))));
	}

	@Test
	void cancellationIsTerminal() {
		var result = pending().decide(Budget.State.CANCELADO);
		assertSame(result, result.decide(Budget.State.CANCELADO));
		assertThrows(IllegalArgumentException.class, () -> result.decide(Budget.State.APROVADO));
	}

}
