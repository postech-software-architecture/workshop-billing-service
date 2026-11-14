package com.postech.foundation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** A malformed date-time is a permanent contract violation (DLQ), never a transient failure to retry. */
class MessageContractFormatTest {

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void dateTimeFormatIsValidatedAsContractViolation() throws Exception {
		var rule = json.readTree("{\"type\":\"string\",\"format\":\"date-time\"}");
		var valid = json.readTree("\"2026-11-10T14:00:00Z\"");
		var malformed = json.readTree("\"not-a-date\"");
		assertDoesNotThrow(() -> MessageContract.check(rule, valid));
		assertThrows(IllegalArgumentException.class, () -> MessageContract.check(rule, malformed));
	}

}
