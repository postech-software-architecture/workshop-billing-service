package com.postech.foundation;

import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BudgetSnapshotTest {

	BudgetSnapshot.Item item(String quantity, long unit, long total) {
		return new BudgetSnapshot.Item(UUID.randomUUID(), "INSUMO", "Oleo", quantity, unit, total);
	}

	@Test
	void decimalQuantityRoundsPerLineInCents() {
		assertEquals(531, item("0.125", 4250, 531).totalCentavos());
		assertEquals(1, item("0.5", 1, 1).totalCentavos());
	}

	@Test
	void rejectsNegativeZeroExcessPrecisionAndInconsistentAmount() {
		assertThrows(IllegalArgumentException.class, () -> item("0", 100, 0));
		assertThrows(IllegalArgumentException.class, () -> item("-1", 100, 100));
		assertThrows(IllegalArgumentException.class, () -> item("0.0001", 10000, 1));
		assertThrows(IllegalArgumentException.class, () -> item("2", 100, 100));
		assertThrows(IllegalArgumentException.class, () -> item("1", -100, -100));
	}

	@Test
	void rejectsDuplicateItemsEmptySnapshotAndTotalMismatch() {
		var id = UUID.randomUUID();
		var row = item("1", 100, 100);
		assertThrows(IllegalArgumentException.class, () -> new BudgetSnapshot(id, 1, id, List.of(), 0, "BRL"));
		assertThrows(IllegalArgumentException.class,
				() -> new BudgetSnapshot(id, 1, id, List.of(row, row), 200, "BRL"));
		assertThrows(IllegalArgumentException.class, () -> new BudgetSnapshot(id, 1, id, List.of(row), 99, "BRL"));
	}

	@Test
	void snapshotAndItemsAreImmutable() {
		var rows = new ArrayList<>(List.of(item("1", 100, 100)));
		var id = UUID.randomUUID();
		var snapshot = new BudgetSnapshot(id, 1, id, rows, 100, "BRL");
		rows.clear();
		assertEquals(1, snapshot.itens().size());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.itens().clear());
	}

	@Test
	void generatedPayloadMatchesContractAndWrongConsumerRejectsIt() {
		var id = UUID.randomUUID();
		var snapshot = new BudgetSnapshot(id, 1, id, List.of(item("1", 100, 100)), 100, "BRL");
		var json = new com.fasterxml.jackson.databind.ObjectMapper();
		var event = new Envelope(id, "billing.gerar-orcamento.v1", 1, Instant.now(), "os-service", id, id, id, id, null,
				id, 1, json.valueToTree(snapshot));
		event.validate("billing");
		assertThrows(IllegalArgumentException.class, () -> event.validate("execution"));
	}

}
