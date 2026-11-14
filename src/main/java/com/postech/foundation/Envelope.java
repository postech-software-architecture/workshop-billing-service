package com.postech.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record Envelope(UUID messageId, String messageType, int schemaVersion, Instant occurredAt, String producer,
		UUID osId, UUID sagaId, UUID attemptId, UUID correlationId, UUID causationId, UUID aggregateId,
		long aggregateVersion, JsonNode payload) {
	public void validate(String kind) {
		if (messageId == null || occurredAt == null || osId == null || sagaId == null || attemptId == null
				|| correlationId == null || aggregateId == null || aggregateVersion < 1 || schemaVersion != 1
				|| payload == null || !payload.isObject())
			throw new IllegalArgumentException("INVALID_ENVELOPE");
		MessageContract.payload(this, kind);
	}
}
