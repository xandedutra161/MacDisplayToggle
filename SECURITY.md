# Política de segurança

## Contexto de risco

O MacDisplayToggle usa APIs **privadas** do macOS (SkyLight/CoreGraphics via
JNA) para desabilitar e religar monitores externos. Isso implica:

- comportamento pode mudar sem aviso entre versões do macOS;
- um erro pode deixar um display desligado até intervenção manual — por isso o
  núcleo recusa a tela embutida e o último display ativo real, persiste a
  identidade antes de desabilitar e mantém reconciliação/failsafe;
- o app não pede privilégios de administrador e não fala com a rede.

## Versões suportadas

Projeto em desenvolvimento, sem release estável. Apenas o branch `main` recebe
correções.

## Reportando vulnerabilidades

Use o recurso privado do GitHub: **Security → Report a vulnerability** no
repositório (GitHub Private Vulnerability Reporting). Descreva o impacto, os
passos de reprodução e o ambiente (chip, versão do macOS, monitor e conexão).

Não abra issue pública para vulnerabilidades exploráveis. Problemas que "apenas"
deixam um display preso (sem componente de segurança) podem ir em issue normal —
o playbook de recuperação está no README (abrir a tampa do MacBook,
`macdisplaytoggle enable`, `sudo killall -HUP WindowServer`, replug do cabo).
