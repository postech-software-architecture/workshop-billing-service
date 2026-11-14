package com.postech.foundation;

import java.util.UUID;

public interface BudgetNotifier {

	void send(UUID clientId, Budget budget);

}
