# workshop-billing-service

**Orçamentos e pagamentos — SOAT Fase 4.** Java 21 / Spring Boot 3.4.1. Persistência: PostgreSQL lógico exclusivo no RDS. Cada serviço mantém repositório, contratos, transação local e pipeline de implantação próprios.

## Versão validada e qualidade

Auditoria de 15/11/2026, baseada nos artifacts do CI da imagem efetivamente implantada, não em contagens locais antigas.

| Evidência | Resultado |
|---|---|
| Commit da imagem implantada | `b34c55057797de8b5f8a64e63c69074494bf4c64` |
| Testes Maven | 111; 0 falhas, erros ou skips |
| Cobertura de linhas | **85.43%**; gate LINE ≥80% aprovado |
| Cobertura de instruções / branches | 88.05% / 69.92% |
| Qualidade estática | SpotBugs no CI, sem achados no relatório; threshold High |
| CI e imagem | [Execução aprovada](https://github.com/postech-software-architecture/workshop-billing-service/actions/runs/43773150621) |
| Deploy Kubernetes | [Build, testes, publicação, apply e rollout aprovados](https://github.com/postech-software-architecture/workshop-billing-service/actions/runs/43774200032) |

[Resumo e contadores verificados](docs/evidencias/qualidade-ci.json), [CSV JaCoCo](docs/evidencias/jacoco-ci.csv), [arquitetura do serviço](docs/ARQUITETURA.md) e [dossiê da Fase 4](https://github.com/postech-software-architecture/soat-architecture/blob/main/docs/architecture/fase4/README.md). As exclusões de cobertura de DTOs/mapeamentos/MapStruct estão no pom e no resumo; domínio, Saga, mensageria e gateway permanecem no escopo. Testes de contexto não substituem a validação integrada com os bancos reais.

## Comportamento comprovado no Academy

- OS 09: pagamento de teste confirmado, reparo concluído e entrega idempotente.
- OS 10: cancelamento antes do reparo, estorno confirmado no Mercado Pago e devolução única de estoque.
- OS 06: falha histórica por prazo financeiro excedido, 19 retries e compensação automática.
- [BDD completo real](https://github.com/postech-software-architecture/workshop-infra-platform/actions/runs/43780849850): 1 cenário/5 passos, dry-run=false, OS 11 ENTREGUE após compra de teste. O BDD por serviço faz parte da suíte Maven; este cenário integrado tem relatório próprio.
- [Traces, logs e métricas](https://github.com/postech-software-architecture/soat-architecture/blob/main/docs/architecture/fase4/OBSERVABILIDADE.md) coletados dos três serviços. A fragmentação do trace de ensaio e a correlação de logs ainda não demonstrada estão documentadas.

## API e contratos

Contrato canônico [OpenAPI](contracts/openapi.json), [cópia compatível](openapi.json) e [mensagens](contracts/mensagens.schema.json). Na API pública, a base é `https://rnwyojgeaa.execute-api.us-east-1.amazonaws.com/prod` e as rotas deste domínio começam em `/api/v1/orcamentos e /api/v1/pagamentos`. O gateway público usa `/api/v1`; `/os`, `/billing` e `/execution` são prefixos opcionais do gateway Compose.

Login de equipe: `POST /api/auth/login`; cliente: `POST /api/auth/cpf`, ambos no Auth público. Enviar o JWT emitido na autenticação. Rotas internas descritas pelo serviço exigem credencial técnica e não são publicadas integralmente no API Gateway. Não colocar tokens/senhas nos exemplos compartilhados.

As collections Postman e o environment privado ficam locais por decisão do solicitante. O OpenAPI versionado atende à documentação pública dos contratos. ApplicationWiringTest confere paths/components gerados e igualdade das duas cópias; metadata de servers distingue Academy e Compose.

## Build e implantação

```text
mvn -B -Pquality clean verify
```

[CI](.github/workflows/ci.yml) executa testes, JaCoCo, SpotBugs e build Docker no GitHub. [Deploy](.github/workflows/deploy.yml) é independente: disparo manual em main (`workflow_dispatch`), Environment prod, imagem GHCR por SHA, testes antes da publicação, validação no servidor Kubernetes, apply e rollout automáticos. Falha de rollout aciona rollback. Uma alteração exclusivamente documental não exige reimplantar a imagem validada.

Main exige PR e check verify atualizado, bloqueia force push/deleção e não tem atores de bypass no ruleset. Atualmente exige **0 aprovações humanas**, conforme configuração adotada para permitir concluir PRs sem esperar outro participante. O Environment prod existe; nos três microserviços não há reviewer obrigatório configurado. Não confundir existência do Environment com aprovação humana obrigatória.

## Operação no AWS Academy

Credenciais temporárias AWS são centralizadas na organização e precisam de renovação por sessão. Segredos de runtime ficam na plataforma; [guia de centralização](https://github.com/postech-software-architecture/workshop-infra-platform/blob/main/docs/SECRETS.md). A infraestrutura usa a LabRole existente, sem criar IAM/OIDC para contornar restrições. Os renderers Academy em scripts devem ser usados; não aplicar placeholders genéricos diretamente.

OS e Billing têm bancos lógicos/usuários separados na mesma instância RDS; Execução usa MongoDB. Mongo e Rabbit têm uma réplica e PVC: não há alegação de alta disponibilidade ou backup restaurado. O roteiro atual não exige Docker/WSL no PC. SMTP permanece desativado e não foi comprovado. Os arquivos históricos em docs/evidencias não substituem os resultados do CI indicados acima.
