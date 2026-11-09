package com.postech.foundation;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FinancialApiTest {

	final UUID id = UUID.randomUUID(), owner = UUID.randomUUID();

	final Billing billing = mock(Billing.class);

	final Finance finance = mock(Finance.class);

	final RemoteIdentity identity = mock(RemoteIdentity.class);

	final PaymentWebhook webhook = mock(PaymentWebhook.class);

	final JdbcTemplate db = mock(JdbcTemplate.class);

	final Jwt jwt = Jwt.withTokenValue("unit").header("alg", "HS256").subject(UUID.randomUUID().toString()).build();

	final RemoteIdentity.Result actor = new RemoteIdentity.Result(true, jwt.getSubject(), owner.toString(),
			List.of("CLIENTE"));

	final BudgetSnapshot snapshot = new BudgetSnapshot(id, 1, owner,
			List.of(new BudgetSnapshot.Item(id, "SERVICO", "Revisao", "1", 100, 100)), 100, "BRL");

	final Budget budget = new Budget(id, id, id, id, id, snapshot, Budget.State.PENDENTE, 1);

	final MockMvc http = MockMvcBuilders
		.standaloneSetup(new BudgetApi(billing, identity, db), new PaymentApi(finance, billing, identity, webhook, db))
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
		.setControllerAdvice(new Errors())
		.build();

	@BeforeEach
	void actor() {
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
		when(billing.find(id, false)).thenReturn(budget);
		when(identity.details(jwt)).thenReturn(actor);
		when(db.queryForMap(anyString(), eq(id))).thenReturn(Map.of("state", "PENDENTE_CANAL", "attempts", 0));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void ownerCanReadBudgetNotificationAndMakeCommercialDecisions() throws Exception {
		http.perform(get("/api/v1/orcamentos/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.snapshot.totalCentavos").value(100));
		http.perform(get("/api/v1/orcamentos/" + id + "/notificacao"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PENDENTE_CANAL"));
		for (var entry : Map
			.of("aprovar", Budget.State.APROVADO, "rejeitar", Budget.State.REJEITADO, "cancelar",
					Budget.State.CANCELADO)
			.entrySet()) {
			when(billing.decide(id, entry.getValue(), actor)).thenReturn(budget.decide(entry.getValue()));
			http.perform(post("/api/v1/orcamentos/" + id + "/" + entry.getKey())).andExpect(status().isOk());
			verify(billing).decide(id, entry.getValue(), actor);
		}
	}

	@Test
	void otherCustomerCannotReadFinancialData() throws Exception {
		when(identity.details(jwt)).thenReturn(
				new RemoteIdentity.Result(true, jwt.getSubject(), UUID.randomUUID().toString(), List.of("CLIENTE")));
		http.perform(get("/api/v1/orcamentos/" + id)).andExpect(status().isForbidden());
	}

	@Test
	void paymentReadIsOwnedAndIncludesRecoverableOperationState() throws Exception {
		when(finance.find(id, false))
			.thenReturn(new Payment(id, id, id, "reference", 100, "BRL", "ORD1", Payment.State.PENDENTE, 1));
		http.perform(get("/api/v1/pagamentos/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pagamento.state").value("PENDENTE"));
		http.perform(post("/api/v1/operacoes-financeiras/" + id + "/retomar")).andExpect(status().isOk());
		verify(finance).resume(id);
	}

	@Test
	void webhookRouteOnlyDelegatesToDurableVerifier() throws Exception {
		http.perform(post("/api/v1/webhooks/mercado-pago").param("data.id", "ORD1")
			.header("X-Request-Id", "request")
			.header("X-Signature", "signature")
			.contentType("application/json")
			.content("{}")).andExpect(status().isOk());
		verify(webhook).receive(eq("signature"), eq("request"), eq("ORD1"), any());
		verifyNoInteractions(finance);
	}

}
