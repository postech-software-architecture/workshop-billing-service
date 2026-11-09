package com.postech.foundation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SqlOutbox implements Outbox {

	private final JdbcTemplate db;

	public SqlOutbox(JdbcTemplate db) {
		this.db = db;
	}

	@Transactional
	public Optional<Pending> claim() {
		UUID lease = UUID.randomUUID();
		return db
			.query("""
					UPDATE outbox SET lease_token=?,lease_until=now()+interval '20 seconds',attempts=attempts+1
					WHERE id=(SELECT id FROM outbox WHERE published=false AND next_at<=now()
					  AND (lease_until IS NULL OR lease_until<now()) ORDER BY next_at LIMIT 1 FOR UPDATE SKIP LOCKED)
					RETURNING id,body,exchange_name,routing_key,traceparent,lease_token,attempts
					""",
					(r, n) -> new Pending(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
							r.getString(5), r.getObject(6, UUID.class), r.getInt(7)),
					lease)
			.stream()
			.findFirst();
	}

	public void sent(Pending m) {
		db.update("UPDATE outbox SET published=true,lease_until=NULL WHERE id=? AND lease_token=?", m.id(), m.lease());
	}

	public void failed(Pending m) {
		db.update(
				"UPDATE outbox SET lease_until=NULL,next_at=now()+(? * interval '1 second') WHERE id=? AND lease_token=?",
				Math.min(60, 1L << Math.min(m.attempts(), 6)), m.id(), m.lease());
	}

}
