package com.postech.foundation.bdd;

import io.cucumber.java.en.*;

public class BehaviorSteps {

	private com.postech.foundation.Budget budget, approved;

	@Given("um orcamento pendente do cliente")
	public void given() {
		var id = java.util.UUID.randomUUID();
		var snap = new com.postech.foundation.BudgetSnapshot(id, 1, id,
				java.util.List
					.of(new com.postech.foundation.BudgetSnapshot.Item(id, "SERVICO", "Revisao", "1", 100, 100)),
				100, "BRL");
		budget = new com.postech.foundation.Budget(id, id, id, id, id, snap,
				com.postech.foundation.Budget.State.PENDENTE, 1);
	}

	@When("o cliente aprova duas vezes a mesma proposta")
	public void when() {
		approved = budget.decide(com.postech.foundation.Budget.State.APROVADO);
		org.junit.jupiter.api.Assertions.assertSame(approved,
				approved.decide(com.postech.foundation.Budget.State.APROVADO));
	}

	@Then("a proposta possui uma unica decisao e preserva seus valores")
	public void then() {
		org.junit.jupiter.api.Assertions.assertEquals(2, approved.version());
		org.junit.jupiter.api.Assertions.assertSame(budget.snapshot(), approved.snapshot());
	}

}
