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
		when(db.queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), eq("ORD1"))).thenReturn(true);
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
	void signedSimulatorBodyForUntrackedOrderIsAcknowledgedWithoutAnyWrite() throws Exception {
		var db = mock(JdbcTemplate.class);
		when(db.queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), eq("ORD1"))).thenReturn(false);
		var hook = new PaymentWebhook(db, "local-test-secret", "seller", "app");
		var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
				"{\"action\":\"order.processed\",\"type\":\"order\",\"user_id\":\"seller\",\"application_id\":\"app\",\"live_mode\":true,\"data\":{\"id\":\"ORD1\",\"type\":\"point\",\"status\":\"processed\",\"status_detail\":\"accredited\"}}");
		hook.receive(signature, "req-123", "ORD1", body);
		hook.receive(signature, "req-123", "ORD1", body);
		verify(db, times(2)).queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), eq("ORD1"));
		verifyNoMoreInteractions(db);
	}

	@Test
	void trackedLiveNotificationIsStillRejectedWithoutWrites() throws Exception {
		var db = mock(JdbcTemplate.class);
		when(db.queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), eq("ORD1"))).thenReturn(true);
		var hook = new PaymentWebhook(db, "local-test-secret", "seller", "app");
		var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
				"{\"type\":\"order\",\"user_id\":\"seller\",\"application_id\":\"app\",\"live_mode\":true,\"data\":{\"id\":\"ORD1\"}}");
		var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> hook.receive(signature, "req-123", "ORD1", body));
		assertEquals(401, error.getStatusCode().value());
		verify(db).queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), eq("ORD1"));
		verifyNoMoreInteractions(db);
	}

	@Test
	void trackedNotificationWithMissingModeIsStillRejected() throws Exception {
		var db = mock(JdbcTemplate.class);
		when(db.queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), eq("ORD1"))).thenReturn(true);
		var hook = new PaymentWebhook(db, "local-test-secret", "seller", "app");
		var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
				"{\"type\":\"order\",\"user_id\":\"seller\",\"application_id\":\"app\",\"data\":{\"id\":\"ORD1\"}}");
		assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> hook.receive(signature, "req-123", "ORD1", body));
		verify(db).queryForObject(startsWith("SELECT EXISTS"), eq(Boolean.class), eq("ORD1"));
		verifyNoMoreInteractions(db);
	}

	@Test
	void untrackedResourceDoesNotBypassSignatureOrContextChecks() throws Exception {
		var db = mock(JdbcTemplate.class);
		var hook = new PaymentWebhook(db, "local-test-secret", "seller", "app");
		var json = new com.fasterxml.jackson.databind.ObjectMapper();
		var valid = json.readTree(
				"{\"type\":\"order\",\"user_id\":\"seller\",\"application_id\":\"app\",\"live_mode\":true,\"data\":{\"id\":\"ORD1\"}}");
		assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> hook.receive(null, "req-123", "ORD1", valid));
		for (String field : List.of("type", "user_id", "application_id")) {
			var wrong = valid.deepCopy();
			((com.fasterxml.jackson.databind.node.ObjectNode) wrong).put(field, "other");
			assertThrows(org.springframework.web.server.ResponseStatusException.class,
					() -> hook.receive(signature, "req-123", "ORD1", wrong));
		}
		var wrongId = valid.deepCopy();
		((com.fasterxml.jackson.databind.node.ObjectNode) wrongId.path("data")).put("id", "OTHER");
		assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> hook.receive(signature, "req-123", "ORD1", wrongId));
		verifyNoInteractions(db);
	}

	@Test
	void missingConfigurationDoesNotAcceptOrPersistWebhook() {
		var db = mock(JdbcTemplate.class);
		assertThrows(org.springframework.web.server.ResponseStatusException.class,
				() -> new PaymentWebhook(db, "", "", "").receive(signature, "req-123", "ORD1", null));
		verifyNoInteractions(db);
	}

}
