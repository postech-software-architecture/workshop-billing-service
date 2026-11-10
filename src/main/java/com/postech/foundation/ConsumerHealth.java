package com.postech.foundation;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("consumer")
public class ConsumerHealth implements HealthIndicator {

	private final RabbitListenerEndpointRegistry registry;

	public ConsumerHealth(RabbitListenerEndpointRegistry registry) {
		this.registry = registry;
	}

	public Health health() {
		var containers = registry.getListenerContainers();
		return !containers.isEmpty() && containers.stream().allMatch(c -> c.isRunning()) ? Health.up().build()
				: Health.down().build();
	}

}
