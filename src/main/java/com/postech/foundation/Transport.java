package com.postech.foundation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Transport {

	private static final Logger LOG = LoggerFactory.getLogger(Transport.class);

	private final Outbox outbox;

	private final Handler handler;

	private final RabbitTemplate rabbit;

	private final ObjectMapper json;

	private final Faults faults;

	private final String kind;

	private final Tracer tracer;

	private final Propagator propagator;

	public Transport(Outbox outbox, Handler handler, RabbitTemplate rabbit, ObjectMapper json, Faults faults,
			@Value("${foundation.kind}") String kind, Tracer tracer, Propagator propagator) {
		this.outbox = outbox;
		this.handler = handler;
		this.rabbit = rabbit;
		this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		this.faults = faults;
		this.kind = kind;
		this.tracer = tracer;
		this.propagator = propagator;
	}

	public void publish(Message message, String exchange, String key) throws Exception {
		CorrelationData correlation = new CorrelationData();
		rabbit.send(exchange, key, message, correlation);
		if (!correlation.getFuture().get(5, TimeUnit.SECONDS).isAck() || correlation.getReturned() != null)
			throw new IllegalStateException("BROKER_NOT_CONFIRMED_OR_UNROUTABLE");
	}

	@Scheduled(fixedDelayString = "${foundation.publish-delay:500}")
	public void drain() {
		for (int n = 0; n < 10; n++) {
			var next = outbox.claim();
			if (next.isEmpty())
				return;
			var value = next.get();
			try {
				MessageProperties props = new MessageProperties();
				props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
				props.setContentType("application/json");
				props.setMessageId(value.id().toString());
				if (value.traceparent() != null)
					props.setHeader("traceparent", value.traceparent());
				if (value.traceparent() != null) {
					var span = propagator
						.extract(Map.of("traceparent", value.traceparent()),
								(Map<String, String> carrier, String key) -> carrier.get(key))
						.name("outbox.publish")
						.start();
					try (var scope = tracer.withSpan(span)) {
						publish(new Message(value.body().getBytes(StandardCharsets.UTF_8), props), value.exchange(),
								value.routingKey());
					}
					finally {
						span.end();
					}
				}
				else
					publish(new Message(value.body().getBytes(StandardCharsets.UTF_8), props), value.exchange(),
							value.routingKey());
				faults.check("after-publish");
				outbox.sent(value);
				LOG.info("outbox_published messageId={}", value.id());
			}
			catch (Exception error) {
				if (error instanceof InterruptedException)
					Thread.currentThread().interrupt();
				outbox.failed(value);
				LOG.warn("outbox_pending messageId={} reason={}", value.id(), error.getClass().getSimpleName());
				return;
			}
		}
	}

	@RabbitListener(queues = "${foundation.queue}")
	public void receive(Message raw, Channel channel) throws Exception {
		long tag = raw.getMessageProperties().getDeliveryTag();
		try {
			if (raw.getBody().length > 65536)
				throw new IllegalArgumentException("OVERSIZE");
			Envelope event = json.readerFor(Envelope.class)
				.with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.readValue(raw.getBody());
			event.validate(kind);
			try (var a = MDC.putCloseable("osId", event.osId().toString());
					var b = MDC.putCloseable("messageId", event.messageId().toString());
					var c = MDC.putCloseable("correlationId", event.correlationId().toString());
					var d = MDC.putCloseable("sagaId", event.sagaId().toString())) {
				handler.accept(event);
				faults.check("after-commit");
				channel.basicAck(tag, false);
				LOG.info("message_committed redelivered={}", raw.getMessageProperties().isRedelivered());
			}
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException permanent) {
			channel.basicReject(tag, false);
			LOG.warn("message_dlq reason={}", permanent.getClass().getSimpleName());
		}
		catch (Exception transientFailure) {
			int retries = ((Number) raw.getMessageProperties().getHeaders().getOrDefault("retry-count", 0)).intValue();
			if (retries >= 3) {
				channel.basicReject(tag, false);
				LOG.warn("message_dlq retries={}", retries);
				return;
			}
			MessageProperties props = new MessageProperties();
			props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			props.setContentType("application/json");
			props.setMessageId(raw.getMessageProperties().getMessageId());
			props.getHeaders().putAll(raw.getMessageProperties().getHeaders());
			props.setHeader("retry-count", retries + 1);
			props.setExpiration(Long.toString(new long[] { 5000, 30000, 120000 }[retries]));
			String service = kind.equals("execution") ? "execucao-service" : kind + "-service";
			try {
				publish(new Message(raw.getBody(), props), service + ".retry", "retry");
				channel.basicAck(tag, false);
			}
			catch (Exception retryFailure) {
				channel.basicNack(tag, false, true);
				throw retryFailure;
			}
		}
	}

}
