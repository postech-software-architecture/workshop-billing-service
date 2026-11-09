# Orçamento e finanças

## Responsabilidade e dados

Este serviço é o único dono de: snapshot imutável de orçamento, decisões, notificações, pagamentos e operações externas. Persistência: banco lógico PostgreSQL próprio no RDS existente, com usuário exclusivo e Flyway. Nenhuma FK ou consulta SQL atravessa os bancos. Inbox e outbox são gravadas na transação local do efeito de negócio.

Java 21/Spring Boot permitem reaproveitar o domínio e as verificações da entrega anterior. PostgreSQL protege relações e consistência comercial/financeira; MongoDB mantém o agregado técnico e suas versões. RabbitMQ desacopla os participantes e permite reentrega. Esses fornecedores são escolhas do projeto.

## Comunicação e segurança

Recebe solicitações de orçamento/cobrança/compensação pela fila. Publica resultados comerciais/financeiros. Consulta identidade em OS por HTTP; integra Mercado Pago por HTTP e recebe webhook autenticado.

Contratos [HTTP](../contracts/openapi.json) e [mensagens](../contracts/mensagens.schema.json). JWT exige issuer workshop-auth, audience workshop-service, validade e sujeito; papéis vigentes são consultados na identidade. A indisponibilidade da identidade recusa acesso em vez de autorizá-lo automaticamente. Rotas internas usam credencial técnica e não devem entrar no ingress público.

## Saga orquestrada

Escolhemos **orquestração persistida em OS**. OS conhece o ciclo global do atendimento; centralizar próximos comandos, prazos e histórico permite observar e recuperar compensações sem espalhar a decisão global entre três serviços. Billing continua decidindo somente sobre finanças e Execução somente sobre trabalho técnico.

Fluxo: abertura → diagnóstico → composição/reserva → orçamento/decisão → cobrança → confirmação financeira consultada → reparo autorizado → conclusão → entrega. Pagamento antes do reparo e estoque dentro de OS são decisões do plano.

O custo é depender do coordenador para avançar o fluxo, com maior responsabilidade em OS. Seu estado fica no banco; outbox e scheduler retomam comandos depois de reinício. Há retries limitados, leases e recuperação administrativa. Mensagens podem se repetir: inbox e identidades de negócio impedem efeitos duplicados; não prometemos entrega exatamente uma vez.

Na compensação, OS confirma o cancelamento técnico antes da ação financeira e só reverte estoque após confirmar o resultado exigido. Reparo iniciado é um efeito físico: recusa de cancelamento exige recuperação de negócio. Timeout financeiro não significa que a cobrança não existiu; a chave persistida é conservada. Estorno solicitado não equivale a confirmado.

## Implantação e limites

Deployment, Service, probes, HPA/PDB e NetworkPolicy próprios em [k8s](../k8s). [CI](../.github/workflows/ci.yml) verifica build/testes/qualidade; [deploy](../.github/workflows/deploy.yml) publica imagem por SHA e executa apply/rollout após workflow_dispatch em main. A plataforma compartilhada é responsável por provisionar bancos separados, broker e entrada; cluster compartilhado não significa deploy conjunto obrigatório.

Dados/broker de apoio têm uma réplica: PVC não é backup nem alta disponibilidade. Secrets, TLS, armazenamento, rede, New Relic e restauração precisam de configuração e validação reais. Logs correlacionados e OTLP estão preparados. Docker, Mercado Pago e cloud da versão atual não foram homologados.
