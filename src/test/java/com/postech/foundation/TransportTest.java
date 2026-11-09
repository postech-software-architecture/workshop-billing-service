package com.postech.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.mockito.Mockito.*;

class TransportTest {

	ObjectMapper json = new ObjectMapper().findAndRegisterModules();

	Outbox outbox = mock(Outbox.class);

	Handler handler = mock(Handler.class);

	RabbitTemplate rabbit = mock(RabbitTemplate.class);

	Channel channel = mock(Channel.class);

	Faults faults = new Faults(false);

	Transport transport = new Transport(outbox, handler, rabbit, json, faults, "execution",
			mock(io.micrometer.tracing.Tracer.class), mock(io.micrometer.tracing.propagation.Propagator.class));

	Message message() throws Exception {
		UUID id = UUID.randomUUID();
		var event = new Envelope(id, "execucao.solicitar-diagnostico.v1", 1, Instant.now(), "os-service", id, id, id,
				id, null, id, 1, json.valueToTree(Map.of("trabalhoId", id, "solicitacao", "revisao")));
		var props = new MessageProperties();
		props.setDeliveryTag(7);
		return new Message(json.writeValueAsBytes(event), props);
	}

	@Test
	void acknowledgeOnlyAfterHandlerCommit() throws Exception {
		transport.receive(message(), channel);
		var order = inOrder(handler, channel);
		order.verify(handler).accept(any());
		order.verify(channel).basicAck(7, false);
	}

	@Test
	void invalidPayloadGoesToDeadLetterWithoutBusinessEffect() throws Exception {
		var props = new MessageProperties();
		props.setDeliveryTag(8);
		transport.receive(new Message("{}".getBytes(StandardCharsets.UTF_8), props), channel);
		verifyNoInteractions(handler);
		verify(channel).basicReject(8, false);
	}

	@Test
	void transientFailureRequiresConfirmedRetryBeforeAck() throws Exception {
		doThrow(new IllegalStateException()).when(handler).accept(any());
		doAnswer(i -> {
			CorrelationData c = i.getArgument(3);
			c.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		transport.receive(message(), channel);
		verify(rabbit).send(eq("execucao-service.retry"), eq("retry"), any(Message.class), any(CorrelationData.class));
		verify(channel).basicAck(7, false);
	}

	@Test
	void retryBudgetExhaustedGoesToDeadLetter() throws Exception {
		doThrow(new IllegalStateException()).when(handler).accept(any());
		var m = message();
		m.getMessageProperties().setHeader("retry-count", 3);
		transport.receive(m, channel);
		verify(channel).basicReject(7, false);
		verifyNoInteractions(rabbit);
	}

	@Test
	void failedPublisherLeavesOutboxPending() {
		var pending = new Outbox.Pending(UUID.randomUUID(), "{}", "exchange", "key", null, UUID.randomUUID(), 1);
		when(outbox.claim()).thenReturn(Optional.of(pending));
		doThrow(new IllegalStateException()).when(rabbit)
			.send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		transport.drain();
		verify(outbox).failed(pending);
		verify(outbox, never()).sent(any());
	}

}
