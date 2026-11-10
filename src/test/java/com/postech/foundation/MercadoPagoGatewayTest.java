package com.postech.foundation;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MercadoPagoGatewayTest {

	final ObjectMapper json = new ObjectMapper();

	String resource(String state) {
		return "{\"id\":\"ORD1\",\"type\":\"qr\",\"user_id\":\"seller\",\"external_reference\":\"ref1\",\"total_amount\":\"123.45\",\"currency\":\"BRL\",\"status\":\""
				+ state + "\",\"status_detail\":\"accredited\",\"type_response\":{\"qr_data\":\"qr-payload\"}}";
	}

	@Test
	void creationUsesStableKeyDynamicQrAndVerifiedGet() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		List<String> keys = new ArrayList<>(), bodies = new ArrayList<>();
		server.createContext("/v1/orders", exchange -> {
			String response;
			if (exchange.getRequestMethod().equals("POST")) {
				keys.add(exchange.getRequestHeaders().getFirst("X-Idempotency-Key"));
				bodies
					.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
				response = "{\"id\":\"ORD1\"}";
			}
			else
				response = resource("created");
			byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		try {
			var gateway = new MercadoPagoGateway("http://127.0.0.1:" + server.getAddress().getPort(), "unit-test-token",
					"POS1", "seller");
			UUID op = UUID.randomUUID();
			var charge = new PagamentoGateway.Charge(op, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
					"ref1", 12345, "BRL");
			assertEquals("PENDENTE", gateway.create(charge).status());
			gateway.create(charge);
			assertEquals(List.of(op.toString(), op.toString()), keys);
			var body = json.readTree(bodies.getFirst());
			assertEquals("123.45", body.path("total_amount").asText());
			assertEquals("dynamic", body.path("config").path("qr").path("mode").asText());
			assertEquals("123.45", body.path("transactions").path("payments").get(0).path("amount").asText());
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void cancelAndTotalRefundUseDocumentedOrderRoutes() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		List<String> calls = new ArrayList<>();
		String[] state = { "created" };
		server.createContext("/v1/orders", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (exchange.getRequestMethod().equals("POST")) {
				calls.add(path + " key=" + exchange.getRequestHeaders().getFirst("X-Idempotency-Key") + " body="
						+ exchange.getRequestBody().readAllBytes().length);
				state[0] = path.endsWith("/cancel") ? "canceled" : "refunded";
			}
			byte[] bytes = resource(state[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(path.equals("/v1/orders/ORD1") ? 200 : 201, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		try {
			var gateway = new MercadoPagoGateway("http://127.0.0.1:" + server.getAddress().getPort(), "unit-test-token",
					"POS1", "seller");
			UUID cancel = UUID.randomUUID(), refund = UUID.randomUUID();
			assertEquals("CANCELADO", gateway.cancel(cancel, "ORD1").status());
			assertEquals("ESTORNADO", gateway.refund(refund, "ORD1", 12345).status());
			assertEquals(List.of("/v1/orders/ORD1/cancel key=" + cancel + " body=0",
					"/v1/orders/ORD1/refund key=" + refund + " body=0"), calls);
			assertThrows(IllegalArgumentException.class, () -> gateway.refund(refund, "ORD1", 1));
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void providerOwnerAndCurrencyAreMandatory() throws Exception {
		var gateway = new MercadoPagoGateway("http://localhost", "token", "POS", "seller");
		assertThrows(IllegalArgumentException.class,
				() -> gateway.parse(json.readTree(resource("processed").replace("seller", "intruder"))));
		assertThrows(IllegalArgumentException.class,
				() -> gateway.parse(json.readTree(resource("processed").replace("BRL", "USD"))));
		assertEquals("CONFIRMADO", gateway.parse(json.readTree(resource("processed"))).status());
		assertEquals("ESTORNADO", gateway.parse(json.readTree(resource("refunded"))).status());
	}

}
