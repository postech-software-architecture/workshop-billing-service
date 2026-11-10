# workshop-billing-service

Projeto independente Java 21 / Spring Boot 3.4.1 da entrega SOAT 4. Implementação local concluída até a preparação da fase 5 em 07/11/2026.

Verificação final: 103 testes, 1 cenário BDD, cobertura de linhas 83.92%, cobertura de instruções 86.43%, gate mínimo de 80% de linhas aprovado e SpotBugs alto sem achados.

```text
mvn -B -Pquality clean verify
```

Contratos: [HTTP](contracts/openapi.json) e [mensagens](contracts/mensagens.schema.json). Infraestrutura da aplicação em `k8s/`; pipeline independente em `.github/workflows`. Variáveis em `src/main/resources/application.properties`; nomes de secrets em `k8s/secret.env.example`. Não versionar os valores reais.

Testes de contexto usam H2 e não substituem migrations/concorrência PostgreSQL. Integração local verificada em 10/11/2026: Compose (PostgreSQL, MongoDB, RabbitMQ, OTel) e os overlays Academy aplicados num k3s local, com BDD completo da plataforma até ENTREGUE e compensações (estorno, recusa, expiração e cancelamento) concluídas em COMPENSADA. O Mercado Pago foi substituído pelo mock da Orders API em workshop-infra-platform/integracao; conta de teste real, SMTP e deploy no Academy continuam pendentes. A main é protegida por ruleset (PR, check verify obrigatório, sem force push/deleção).

A distribuição completa contém a documentação de arquitetura/Saga, roteiros de aceite e inventário de hashes em `outputs/entrega-fase-4`.

## Arquitetura e estratégia da Saga

Adotamos **Saga orquestrada em OS**, que já acompanha o atendimento e concentra estado global, prazos e decisões de compensação. Isso torna a recuperação rastreável; cada participante conserva suas regras e transação local. A dependência do coordenador é tratada com estado persistido, outbox, retries e retomada administrativa. Veja [arquitetura, tecnologias e limites](docs/ARQUITETURA.md).

## Evidências acessíveis neste repositório

- [Testes locais registrados](docs/evidencias/tests.json)
- [Cobertura JaCoCo](docs/evidencias/jacoco.csv)
- [Análise SpotBugs](docs/evidencias/spotbugs.xml)

Esses arquivos registram a execução local de 10/11/2026, com comparação do contrato gerado aprovada. O CI publica relatórios atuais no artifact reports. Não são prova de integração real. A métrica de cobertura é LINE; exclusões de DTOs, mapeamento JPA e implementações MapStruct geradas estão explícitas no pom.xml.

## Atualização dos contratos HTTP

`contracts/openapi.json` é o contrato canônico; `openapi.json` é uma cópia de compatibilidade idêntica. ApplicationWiringTest consulta /v3/api-docs e bloqueia divergência de paths/components e da cópia. Metadata/servers documentam os prefixos do gateway local e podem diferir do endpoint interno.

Depois de alterar controllers/DTOs: rode o teste gerador, use target/openapi-phase3.json para atualizar paths/components do contrato, replique o canônico na raiz e rode novamente `mvn -B -Pquality verify`. Os testes não atualizam snapshots silenciosamente. Em produção, /v3/api-docs é relativo à aplicação; os prefixos /os, /billing e /execution são do gateway Compose.

## Adequação Academy em 09/11/2026

Deploy usa AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY/AWS_SESSION_TOKEN, Environment prod e cluster existente. Não cria role nem usa OIDC. O renderer em scripts/render-academy.py preenche a imagem por SHA e egress privado SQL; RDS_HOST vem de Variable do Environment. Usar a configuração da plataforma antes do deploy. O corte de borda é explícito no repo auth.

OS/Billing usam bancos lógicos e usuários próprios no RDS existente; Execução usa Mongo no cluster. Não substituir a infraestrutura anterior ou seus states. Os arquivos genéricos de rede em k8s/base são base de composição; o renderer Academy substitui a política SQL pelos /32 privados resolvidos. Os placeholders não devem ser aplicados diretamente.
