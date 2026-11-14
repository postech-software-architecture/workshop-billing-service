package com.postech.foundation;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Notifications {

	private final JdbcTemplate db;

	private final Billing billing;

	private final ObjectProvider<BudgetNotifier> notifier;

	public Notifications(JdbcTemplate db, Billing billing, ObjectProvider<BudgetNotifier> notifier) {
		this.db = db;
		this.billing = billing;
		this.notifier = notifier;
	}

	@Scheduled(fixedDelayString = "${notifications.poll-ms:10000}")
	public void deliver() {
		BudgetNotifier channel = notifier.getIfAvailable();
		if (channel == null)
			return;
		UUID lease = UUID.randomUUID();
		var jobs = db.queryForList(
				"UPDATE budget_notifications SET state='ENVIANDO',lease_token=?,lease_until=now()+interval '60 seconds',attempts=attempts+1 WHERE id=(SELECT id FROM budget_notifications WHERE state<>'ENVIADO' AND next_at<=now() AND (lease_until IS NULL OR lease_until<now()) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING *",
				lease);
		if (jobs.isEmpty())
			return;
		var job = jobs.getFirst();
		try {
			channel.send((UUID) job.get("client_id"), billing.find((UUID) job.get("budget_id"), false));
			db.update(
					"UPDATE budget_notifications SET state='ENVIADO',lease_until=NULL,last_error=NULL WHERE id=? AND lease_token=?",
					job.get("id"), lease);
		}
		catch (RuntimeException error) {
			db.update(
					"UPDATE budget_notifications SET state='RETRY',lease_until=NULL,next_at=now()+interval '1 minute',last_error='DELIVERY_UNAVAILABLE' WHERE id=? AND lease_token=?",
					job.get("id"), lease);
		}
	}

}
