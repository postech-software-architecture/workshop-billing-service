package com.postech.foundation;

import java.util.Optional;
import java.util.UUID;

public interface Outbox {

	record Pending(UUID id, String body, String exchange, String routingKey, String traceparent, UUID lease,
			int attempts) {
	}

	Optional<Pending> claim();

	void sent(Pending message);

	void failed(Pending message);

}
