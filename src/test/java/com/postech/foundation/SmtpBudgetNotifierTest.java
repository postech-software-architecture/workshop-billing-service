package com.postech.foundation;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class SmtpBudgetNotifierTest {

	Budget budget() {
		UUID id = UUID.randomUUID();
		var snapshot = new BudgetSnapshot(id, 1, id,
				List.of(new BudgetSnapshot.Item(id, "SERVICO", "Revisao", "1", 100, 100)), 100, "BRL");
		return new Budget(id, id, id, id, id, snapshot, Budget.State.PENDENTE, 1);
	}

	@Test
	void notificationUsesTrustedContactAndImmutableBudgetWithoutSendingRealEmail() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/v1/clientes", exchange -> {
			assertEquals("billing-test-key", exchange.getRequestHeaders().getFirst("X-Service-Token"));
			byte[] body = "{\"email\":\"customer@example.com\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			var sender = mock(JavaMailSender.class);
			var notifier = new SmtpBudgetNotifier(sender, "http://127.0.0.1:" + server.getAddress().getPort(),
					"billing-test-key", "workshop@example.com");
			var budget = budget();
			notifier.send(budget.snapshot().clienteId(), budget);
			verify(sender)
				.send(argThat((SimpleMailMessage message) -> Arrays.equals(new String[] { "customer@example.com" },
						message.getTo()) && message.getText().contains("Revisao")
						&& message.getText().contains("Total: R$ 1.00")));
		}
		finally {
			server.stop(0);
		}
	}

}
