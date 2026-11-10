package com.postech.foundation;

import java.util.UUID;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "notifications.enabled", havingValue = "true")
public class SmtpBudgetNotifier implements BudgetNotifier {

	private final JavaMailSender mail;

	private final RestClient contacts;

	private final String credential, from;

	public SmtpBudgetNotifier(JavaMailSender mail, @Value("${foundation.identity-url}") String url,
			@Value("${foundation.service-token}") String credential, @Value("${notifications.from}") String from) {
		this.mail = mail;
		this.credential = credential;
		this.from = from;
		var factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
		factory.setReadTimeout(Duration.ofSeconds(2));
		contacts = RestClient.builder().baseUrl(url).requestFactory(factory).build();
	}

	public record Contact(String email) {
	}

	public void send(UUID clientId, Budget budget) {
		var contact = contacts.get()
			.uri("/internal/v1/clientes/{id}/contato", clientId)
			.header("X-Service-Token", credential)
			.retrieve()
			.body(Contact.class);
		if (contact == null || contact.email() == null || contact.email().isBlank())
			throw new IllegalStateException("CONTACT_UNAVAILABLE");
		var message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(contact.email());
		message.setSubject("Orcamento da oficina - versao " + budget.snapshot().orcamentoVersao());
		StringBuilder text = new StringBuilder("Orcamento " + budget.id() + "\nOS " + budget.osId() + "\n\n");
		for (var item : budget.snapshot().itens())
			text.append(item.descricao())
				.append(" - ")
				.append(item.quantidade())
				.append(" - R$ ")
				.append(java.math.BigDecimal.valueOf(item.totalCentavos(), 2))
				.append('\n');
		text.append("\nTotal: R$ ")
			.append(java.math.BigDecimal.valueOf(budget.snapshot().totalCentavos(), 2))
			.append("\nConsulte ou decida pelo canal autenticado da oficina.");
		message.setText(text.toString());
		mail.send(message);
	}

}
