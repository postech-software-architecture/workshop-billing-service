package com.postech.foundation;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NotificationsTest {

	@Test
	void missingChannelLeavesDurableQueueUntouched() {
		var db = mock(JdbcTemplate.class);
		var billing = mock(Billing.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<BudgetNotifier> provider = mock(ObjectProvider.class);
		new Notifications(db, billing, provider).deliver();
		verifyNoInteractions(db, billing);
	}

	@Test
	void failedDeliverySchedulesRetryWithoutChangingBudget() {
		var db = mock(JdbcTemplate.class);
		var billing = mock(Billing.class);
		var channel = mock(BudgetNotifier.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<BudgetNotifier> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(channel);
		UUID id = UUID.randomUUID(), budgetId = UUID.randomUUID(), client = UUID.randomUUID();
		when(db.queryForList(startsWith("UPDATE budget_notifications"), any(UUID.class)))
			.thenReturn(List.of(Map.of("id", id, "budget_id", budgetId, "client_id", client)));
		doThrow(new IllegalStateException("offline")).when(channel).send(eq(client), any());
		new Notifications(db, billing, provider).deliver();
		verify(db).update(startsWith("UPDATE budget_notifications SET state='RETRY'"), eq(id), any(UUID.class));
		verify(billing).find(budgetId, false);
		verifyNoMoreInteractions(billing);
	}

}
