package com.postech.foundation;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.boot.actuate.health.Status;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerHealthTest {

	@Test
	void readinessRequiresRunningConsumer() {
		var registry = mock(RabbitListenerEndpointRegistry.class);
		var listener = mock(MessageListenerContainer.class);
		when(registry.getListenerContainers()).thenReturn(List.of(listener));
		var health = new ConsumerHealth(registry);
		assertEquals(Status.DOWN, health.health().getStatus());
		when(listener.isRunning()).thenReturn(true);
		assertEquals(Status.UP, health.health().getStatus());
		when(registry.getListenerContainers()).thenReturn(List.of());
		assertEquals(Status.DOWN, health.health().getStatus());
	}

}
