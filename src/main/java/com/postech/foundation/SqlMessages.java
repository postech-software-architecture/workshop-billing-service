package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SqlMessages {

	private final JdbcTemplate db;

	private final ObjectMapper json;

	private final TraceHeaders traces;

	public SqlMessages(JdbcTemplate db, ObjectMapper json, TraceHeaders traces) {
		this.db = db;
		this.json = json;
		this.traces = traces;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void emit(Envelope event, boolean command) {
		try {
			db.update("INSERT INTO outbox(id,body,exchange_name,routing_key,traceparent) VALUES(?,?,?,?,?)",
					event.messageId(), json.writeValueAsString(event),
					command ? "workshop.commands" : "workshop.events", event.messageType(), traces.current());
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException error) {
			throw new IllegalStateException(error);
		}
	}

}
