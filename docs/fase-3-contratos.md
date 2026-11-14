# Contratos e decisões da extração

## Responsabilidade e consistência

Cada serviço contém seu próprio código, migrations ou configuração Mongo, inbox e outbox. Não há biblioteca de domínio compartilhada nem leitura de banco de outro serviço. Os contratos JSON são cópias versionadas verificadas em conjunto.

OS mantém o atendimento e a composição. Billing mantém propostas e decisões. Execução mantém diagnóstico e tarefas. Os tipos puros legados presentes no módulo de OS são compatibilidade interna dos modelos/testes reaproveitados: não há controller, repository JPA ou tabela ativa de orçamento em OS, nem rota local para iniciar/concluir reparo.

`attemptId` identifica a tentativa técnica da OS. Uma rejeição comercial gera outra `orcamentoVersao` e outro `orcamentoId`, conservando o diagnóstico da tentativa. Uma eventual nova tentativa de reparo/compensação será coordenada na fase financeira. Reserva é rastreada por movimento original + tentativa + versão, e só pode terminar em `CONSUMIDO` ou `LIBERADO` uma vez.

Transações locais incluem negócio e outbox; consumidores incluem inbox. ACK ocorre depois do retorno transacional. O publicador confirma envio no broker e só então marca a outbox. Reentrega de transporte é esperada. Não se promete entrega exatamente uma vez pela rede.

## Snapshot monetário

- Moeda `BRL`, valores inteiros em centavos, limite `9007199254740991`.
- Quantidade decimal em texto, positiva, até três casas decimais e `9999999.999`.
- Até 200 itens por proposta; IDs distintos; tipos `SERVICO`, `PECA`, `INSUMO`.
- Total da linha = quantidade × preço unitário, arredondado `HALF_UP` para um centavo. Total da proposta = soma das linhas.
- Preço e descrição são congelados ao adicionar o item. Alterar catálogo posteriormente não reescreve a proposta.
- Nova composição após rejeição é explícita: os itens correntes são limpos e precisam ser adicionados novamente. Os snapshots históricos e as movimentações permanecem preservados.

## Rotas canônicas

Os prefixos `/os`, `/billing` e `/execution` são do gateway local, não dos controllers.

| Dono | Rota | Regra |
|---|---|---|
| OS | `POST /api/auth/login` | Login existente, com conta real no banco de OS |
| OS | `POST /api/v1/ordens-servico` | `Idempotency-Key`; corpo `clienteDocumento`, `veiculoPlaca`, dados de veículo novo quando necessários, `observacoes` |
| OS | `GET /api/v1/ordens-servico`, `GET /{id}` | Cliente vê somente suas ordens; funcionários autorizados podem consultar |
| OS | `GET /api/v1/ordens-servico/{numero}/status` | Consulta pública mínima de número/status/atualização, sem dados pessoais ou itens financeiros |
| OS | `POST /api/v1/ordens-servico/{id}/itens` | `Idempotency-Key`; `catalogoId`, `tipo`, `quantidade` |
| OS | `POST /api/v1/ordens-servico/{id}/composicao/encerrar` | Repetição devolve a referência já gerada; ainda não significa orçamento confirmado |
| OS | `GET /api/v1/ordens-servico/{id}/historico` | Histórico global; alias `/historico-status` |
| OS | `POST /api/v1/ordens-servico/{id}/entregar` | Somente após finalização técnica projetada |
| Billing | `GET /api/v1/orcamentos/{id}` | Cliente proprietário, administrador ou atendente |
| Billing | `POST /api/v1/orcamentos/{id}/aprovar`, `/rejeitar`, `/cancelar` | Repetir a mesma decisão não gera outro evento; decisão conflitante é rejeitada |
| Billing | `GET /api/v1/orcamentos/{id}/notificacao` | Estado, tentativas e último erro sanitizado |
| Execução | `GET /api/v1/trabalhos?status=...&pagina=0`, `GET /{id}` | Fila paginada de 20; mecânico ou administrador |
| Execução | `POST /api/v1/trabalhos/{id}/diagnostico/iniciar`, `/concluir` | `If-Match: <versão>`; conclusão recebe `diagnostico` |
| Execução | `POST /api/v1/trabalhos/{id}/reparo/iniciar`, `/concluir` | Exige autorização previamente recebida; `If-Match` |
| Execução | `POST /api/v1/trabalhos/{id}/itens/{itemId}/iniciar`, `/concluir` | Somente item de serviço e apenas um serviço simultâneo; `If-Match` |

Os arquivos `contracts/openapi.json` de cada projeto são gerados a partir do contexto dos controllers nos testes. O DTO de abertura e o formato de consulta do fluxo novo estão nesses arquivos, não nos contratos históricos de fundação.

## Identidade e comunicação interna

JWT: HS256, emissor `workshop-auth`, audiência `workshop-service`, sujeito UUID e expiração obrigatória. Login preservado usa os usuários, roles e hashes do domínio original. O usuário fictício da tabela `foundation_users` foi desativado e não é mais consultado.

Billing e Execução revalidam roles/estado em `POST /internal/v1/identidade/introspectar` usando credencial própria de serviço. Billing recebe `clienteId` da identidade atual para comparar com o snapshot; não aceita proprietário informado pelo cliente na decisão.

O endpoint de contato `GET /internal/v1/clientes/{id}/contato` aceita somente a credencial de Billing. Retorna o mínimo para notificação. O gateway local bloqueia `/internal/**` sob qualquer prefixo.

## Notificações e pagamento

Notificação é gravada junto com o orçamento. Sem canal habilitado, fica `PENDENTE_CANAL`; falha de entrega fica `RETRY`, com tentativas e erro sanitizado. A mensagem não gera outra proposta. Envio SMTP tem timeout e lease; uma queda após envio e antes da confirmação pode repetir o e-mail, sem repetir o efeito financeiro.

Para habilitar o adapter no ambiente apropriado: `NOTIFICATIONS_ENABLED=true`, `NOTIFICATIONS_FROM`, `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` e, se exigidos pelo servidor, credenciais e TLS do Spring Mail. Nenhum segredo foi colocado nos arquivos versionados. O adapter foi preparado, mas nenhum e-mail externo foi enviado nesta implementação.

`PagamentoGateway` define criação, consulta, cancelamento e estorno. **Não há implementação de sucesso fictício em produção.** A confirmação do provedor, validação de valor/moeda/referência, webhook idempotente, reconciliação e compensações são trabalho da fase 4.
