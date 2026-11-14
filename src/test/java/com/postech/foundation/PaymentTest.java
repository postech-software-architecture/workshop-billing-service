package com.postech.foundation;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentTest {

	Payment requested() {
		return new Payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "workshop_reference", 10000, "BRL",
				null, Payment.State.SOLICITADO, 1);
	}

	Payment confirm(Payment p) {
		return p.verified("provider-1", p.externalReference(), 10000, "BRL", Payment.State.CONFIRMADO);
	}

	@Test
	void timeoutRequiresReconciliationBeforeAnotherCreation() {
		var p = requested();
		assertTrue(p.mayCreate());
		var unknown = p.unknown();
		assertFalse(unknown.mayCreate());
		assertEquals(Payment.State.CONFIRMADO, confirm(unknown).state());
	}

	@Test
	void rejectsResourceWithWrongAmountCurrencyReferenceOrIdentity() {
		var p = requested();
		assertThrows(IllegalArgumentException.class,
				() -> p.verified("provider-1", "wrong", 10000, "BRL", Payment.State.CONFIRMADO));
		assertThrows(IllegalArgumentException.class,
				() -> p.verified("provider-1", p.externalReference(), 9999, "BRL", Payment.State.CONFIRMADO));
		assertThrows(IllegalArgumentException.class,
				() -> p.verified("provider-1", p.externalReference(), 10000, "USD", Payment.State.CONFIRMADO));
		var paid = confirm(p);
		assertThrows(IllegalArgumentException.class,
				() -> paid.verified("provider-2", paid.externalReference(), 10000, "BRL", Payment.State.CONFIRMADO));
	}

	@Test
	void duplicateConfirmationAndOldPendingObservationDoNotDowngrade() {
		var paid = confirm(requested());
		assertSame(paid, confirm(paid));
		assertSame(paid, paid.verified("provider-1", paid.externalReference(), 10000, "BRL", Payment.State.PENDENTE));
	}

	@Test
	void refundWaitsForConfirmedWorkCancellationAndProviderResult() {
		var paid = confirm(requested());
		assertThrows(IllegalArgumentException.class, () -> paid.refund(false));
		var pending = paid.refund(true);
		assertEquals(Payment.State.ESTORNO_PENDENTE, pending.state());
		assertSame(pending, pending.refund(true));
		assertEquals(Payment.State.ESTORNADO,
				pending.verified("provider-1", pending.externalReference(), 10000, "BRL", Payment.State.ESTORNADO)
					.state());
	}

	@Test
	void cancellationRacingWithPaymentMustPreserveConfirmation() {
		var cancellation = requested().cancel();
		assertEquals(Payment.State.CONFIRMADO, confirm(cancellation).state());
		assertThrows(IllegalArgumentException.class, () -> confirm(requested()).cancel());
	}

}
