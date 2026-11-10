package com.postech.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentWebhook {

	private final JdbcTemplate db;

	private final String secret, owner, application;

	public PaymentWebhook(JdbcTemplate db, @Value("${MP_WEBHOOK_SECRET:}") String secret,
			@Value("${MP_SELLER_ID:}") String owner, @Value("${MP_APPLICATION_ID:}") String application) {
		this.db = db;
		this.secret = secret;
		this.owner = owner;
		this.application = application;
	}

	@Transactional
	public void receive(String signature, String requestId, String resourceId, JsonNode body) {
		if (secret.isBlank() || owner.isBlank() || application.isBlank())
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_NOT_CONFIGURED");
		if (!valid(signature, requestId, resourceId, secret) || body == null
				|| !"order".equals(body.path("type").asText())
				|| !resourceId.equals(body.path("data").path("id").asText())
				|| !owner.equals(body.path("user_id").asText())
				|| !application.equals(body.path("application_id").asText()) || !body.has("live_mode")
				|| body.path("live_mode").asBoolean(true))
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_NOTIFICATION");
		// The body never supplies financial truth. Wake a GET reconciliation of a known
		// local resource.
		String key = hexDigest(resourceId + "|" + requestId + "|" + signature);
		if (db.update(
				"INSERT INTO provider_notifications(id,deduplication_key,resource_id,state) VALUES(?,?,?,'RECEIVED') ON CONFLICT DO NOTHING",
				UUID.randomUUID(), key, resourceId) == 0)
			return;
		db.update(
				"UPDATE financial_operations SET state='WAITING',next_at=now() WHERE kind='CREATE' AND state<>'MANUAL_REVIEW' AND payment_id IN(SELECT id FROM payments WHERE provider_order_id=?)",
				resourceId);
	}

	static boolean valid(String signature, String requestId, String resourceId, String secret) {
		if (signature == null || signature.length() > 1024 || requestId == null
				|| !requestId.matches("[A-Za-z0-9_-]{1,128}") || resourceId == null
				|| !resourceId.matches("[A-Za-z0-9_-]{1,128}"))
			return false;
		var parts = new HashMap<String, String>();
		for (String part : signature.split(",")) {
			var pair = part.trim().split("=", 2);
			if (pair.length != 2 || parts.putIfAbsent(pair[0], pair[1]) != null)
				return false;
		}
		String ts = parts.get("ts"), digest = parts.get("v1");
		if (ts == null || !ts.matches("[0-9]{1,20}") || digest == null || !digest.matches("[a-fA-F0-9]{64}"))
			return false;
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] expected = mac.doFinal(("id:" + resourceId + ";request-id:" + requestId + ";ts:" + ts + ";")
				.getBytes(StandardCharsets.UTF_8));
			return MessageDigest.isEqual(expected, HexFormat.of().parseHex(digest));
		}
		catch (java.security.GeneralSecurityException error) {
			throw new IllegalStateException(error);
		}
	}

	private static String hexDigest(String input) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
