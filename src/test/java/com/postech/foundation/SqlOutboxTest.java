package com.postech.foundation;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SqlOutboxTest {

	final JdbcTemplate db = mock(JdbcTemplate.class);

	final SqlOutbox outbox = new SqlOutbox(db);

	@Test
	void emptyOutboxDoesNotInventPublication() {
		assertTrue(outbox.claim().isEmpty());
	}

	@Test
	void claimsCarryDatabaseIdentityTraceAndLease() throws Exception {
		UUID id = UUID.randomUUID();
		when(db.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Outbox.Pending>>any(), any(UUID.class)))
			.thenAnswer(call -> {
				var row = mock(java.sql.ResultSet.class);
				when(row.getObject(1, UUID.class)).thenReturn(id);
				when(row.getString(2)).thenReturn("{}");
				when(row.getString(3)).thenReturn("workshop.events");
				when(row.getString(4)).thenReturn("event");
				when(row.getString(5)).thenReturn("trace");
				when(row.getObject(6, UUID.class)).thenReturn(call.getArgument(2));
				when(row.getInt(7)).thenReturn(2);
				RowMapper<Outbox.Pending> mapper = call.getArgument(1);
				return List.of(mapper.mapRow(row, 0));
			});
		var pending = outbox.claim().orElseThrow();
		assertEquals(id, pending.id());
		assertEquals("trace", pending.traceparent());
		assertNotNull(pending.lease());
		outbox.sent(pending);
		outbox.failed(pending);
		verify(db).update(startsWith("UPDATE outbox SET published=true"), eq(id), eq(pending.lease()));
		verify(db).update(startsWith("UPDATE outbox SET lease_until=NULL"), eq(4L), eq(id), eq(pending.lease()));
	}

	@Test
	void backoffIsCappedEvenAfterManyAttempts() {
		var id = UUID.randomUUID();
		var lease = UUID.randomUUID();
		outbox.failed(new Outbox.Pending(id, "{}", "ex", "key", null, lease, 100));
		verify(db).update(startsWith("UPDATE outbox SET lease_until=NULL"), eq(60L), eq(id), eq(lease));
	}

}
