package com.postech.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Value snapshot. Decimal quantities, integer cents, HALF_UP per line. No catalog lookup.
 */
public record BudgetSnapshot(UUID orcamentoId, int orcamentoVersao, UUID clienteId, List<Item> itens,
		long totalCentavos, String moeda) {
	public record Item(UUID itemId, String tipo, String descricao, String quantidade, long valorUnitarioCentavos,
			long totalCentavos) {
		public Item {
			if (itemId == null || !Set.of("SERVICO", "PECA", "INSUMO").contains(tipo) || descricao == null
					|| descricao.isBlank() || descricao.length() > 500 || quantidade == null || quantidade.length() > 64
					|| !quantidade.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))
				throw new IllegalArgumentException("INVALID_ITEM");
			var quantity = new BigDecimal(quantidade);
			if (quantity.signum() <= 0 || quantity.scale() > 3 || quantity.compareTo(new BigDecimal("9999999.999")) > 0
					|| valorUnitarioCentavos < 0 || totalCentavos < 0 || totalCentavos > 9007199254740991L
					|| valorUnitarioCentavos > 9007199254740991L
					|| quantity.multiply(BigDecimal.valueOf(valorUnitarioCentavos))
						.setScale(0, RoundingMode.HALF_UP)
						.longValueExact() != totalCentavos)
				throw new IllegalArgumentException("INVALID_AMOUNT");
		}
	}

	public BudgetSnapshot {
		if (orcamentoId == null || clienteId == null || orcamentoVersao < 1 || !"BRL".equals(moeda) || itens == null
				|| itens.isEmpty() || itens.size() > 200)
			throw new IllegalArgumentException("INVALID_BUDGET");
		itens = List.copyOf(itens);
		var ids = new HashSet<UUID>();
		long sum = 0;
		for (Item item : itens) {
			if (!ids.add(item.itemId()))
				throw new IllegalArgumentException("DUPLICATE_ITEM");
			sum = Math.addExact(sum, item.totalCentavos());
		}
		if (sum != totalCentavos || sum > 9007199254740991L)
			throw new IllegalArgumentException("TOTAL_MISMATCH");
	}

	public static BudgetSnapshot read(JsonNode node, com.fasterxml.jackson.databind.ObjectMapper json) {
		try {
			return json.treeToValue(node, BudgetSnapshot.class);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException error) {
			throw new IllegalArgumentException("INVALID_BUDGET", error);
		}
	}
}
