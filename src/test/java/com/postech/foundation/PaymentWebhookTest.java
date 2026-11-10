package com.postech.foundation;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentWebhookTest {

	final String signature = "ts=1700000000,v1=d23d0babc3aef67e5d1091afe8f5e7f969607b866dcd55c5591bd403e626c3b4";

	@Test
	void knownIndependentHmacVectorAuthenticatesExactResource() {
		assertTrue(PaymentWebhook.valid(signature, "req-123", "ORD1", "local-test-secret"));
		assertFalse(PaymentWebhook.valid(signature, "req-123", "ORD2", "local-test-secret"));
		assertFalse(PaymentWebhook.valid(signature, "other", "ORD1", "local-test-secret"));
		assertFalse(PaymentWebhook.valid(signature, "req-123", "ORD1", "wrong-secret"));
	}

	@Test
	void malformedSignaturesAndDuplicateComponentsFailClosed() {
		for (String value : Arrays.asList(null, "", "v1=abc", "ts=bad,v1=abc", signature + ",ts=1")) {
			assertFalse(PaymentWebhook.valid(value, "req-123", "ORD1", "local-test-secret"));
		}
		assertFalse(PaymentWebhook.valid(signature, null, "ORD1", "local-test-secret"));
	}

	@Test
	void validNotificationIsDurableButBodyNeverConfirmsPayment() throws Exception {
		var db = mock(JdbcTemplate.class);
		when(db.update(startsWith("INSERT INTO provider_notifications"), any(UUID.class), anyString(), eq("ORD1")))
			.thenReturn(1);
		var hook = new PaymentWebhook(db, "local-test-secret", "seller", "app");
		var json = new com.fasterxml.jackson.databind.ObjectMapper();
		hook.receive(signature, "req-123", "ORD1", json.readTree(
				"{\"type\":\"order\",\"user_id\":\"seller\",\"application_id\":\"app\",\"live_mode\":false,\"data\":{\"id\":\"ORD1\",\"status\":\"processed\"}}"));
		verify(db).update(startsWith("UPDATE financial_operations"), eq("ORD1"));
		verify(db, never()).update(startsWith("UPDATE payments"), any(Object[].class));
	}

	@Test
	void missingConfigurationDoesNotAcceptOrPersistWebhook() {
		var db = mock(JdbcTemplate.class);
		assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> new PaymentWebhook(db, "", "", "").receive(signature, "req-123", "ORD1", null));
		verifyNoInteractions(db);
	}

}
