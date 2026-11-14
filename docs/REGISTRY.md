# Primeira publicação no GHCR

Executar Actions → publish-and-deploy → Run workflow na main, com
`publish_only=true`. Essa opção compila/testa e publica a imagem por SHA, sem
autenticar na AWS nem aplicar recursos Kubernetes. A publicação usa o GITHUB_TOKEN
automático do próprio repo; não é necessário cadastrar PAT administrativo.

O primeiro package GHCR nasce privado. Depois da publicação, conferir Packages →
package do serviço → Package settings → Change visibility. Para este ambiente de
repos públicos, tornar a imagem pública permite ao EKS baixar sem credencial de
registry. Essa mudança é permanente e deve ser realizada conscientemente pelo
proprietário; não é feita pelo workflow.

Com a imagem acessível, executar o mesmo workflow com `publish_only=false`.
O preflight confere acesso anônimo ao manifesto antes de autenticar na AWS e
aplicar o deployment. Se continuar privada/indisponível, para antes de alterar
recursos do EKS. Este caminho de deploy está preparado para imagens públicas;
um registry privado exige configurar autenticação de pull própria e adaptar o
preflight. Não copiar o token administrativo do CLI para um Kubernetes Secret.

As Variables AWS_REGION, EKS_CLUSTER_NAME e RDS_HOST ficam no prod. AWS vem dos
Organization secrets; credenciais de runtime já vêm dos Secrets da plataforma.
Pagamentos/SMTP do Billing continuam desabilitados por padrão até suas validações.

Referência: https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry
