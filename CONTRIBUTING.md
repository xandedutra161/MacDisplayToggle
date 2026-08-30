# Contribuindo com o MacDisplayToggle

Obrigado pelo interesse! Este projeto controla monitores externos no macOS via
API privada (SkyLight/CoreGraphics), então as regras de segurança abaixo não são
burocracia — elas evitam deixar uma máquina sem tela.

## Regra de produto (inegociável)

O app opera **apenas monitores externos**. A tela embutida do MacBook nunca é um
alvo operável. Essa regra vive em três camadas ao mesmo tempo e nenhum PR pode
enfraquecer qualquer uma delas:

1. documentação: README e mensagens falam em "monitores externos";
2. interface: a tela embutida não aparece como opção na UI nem nos fluxos
   normais da CLI;
3. núcleo: `DisplayManager.disable` recusa `builtin` mesmo se UI/CLI errarem,
   e a trava do último display ativo real não tem override.

## Ambiente

- macOS em Apple Silicon (validado em M4 / Tahoe 26.5.2).
- JDK 21+.
- Gradle via wrapper (`./gradlew`), sem instalação global.

## Build e testes

```bash
./gradlew check              # compila tudo e roda os testes unitários
./gradlew :cli:installDist   # binário em cli/build/install/macdisplaytoggle/bin/
./gradlew :app:run           # app de barra de menu (Compose)
```

Os testes unitários usam fakes (`core/src/test/kotlin/mdt/core/testing`) e não
tocam em display real — rodam com segurança em qualquer máquina e no CI.

## Testes destrutivos (manuais)

Desligar um monitor de verdade segue um protocolo fixo: tampa do MacBook
**aberta** (a tela embutida é a via de recuperação), watchdog externo armado
(`./scripts/watchdog.sh <uuid|id> <segundos> &`), religador de emergência
disponível e nunca dois displays no mesmo teste. Não automatize esses testes.

## Antes de abrir um PR

- `./gradlew check` verde.
- `git diff --check` sem erros de whitespace.
- Regras novas de segurança/produto acompanhadas de teste unitário.
- Fluxos normais de app/CLI passam pela `ExternalDisplayToggleFacade`; acesso
  direto ao core fica restrito a diagnóstico (`list`, chamadas redundantes).
- Mudanças em chamadas JNA em passos pequenos e verificáveis, com o efeito
  observado em máquina real descrito no PR.
- Descreva o hardware usado (chip, macOS, monitor e conexão) quando o PR tocar
  comportamento nativo.

## Arquitetura em uma linha

`domain` (regras puras) → `ports` (interfaces) → `adapters`/`persistence`/`jna`
(detalhes) → `application` (facade consumida por `:app` e `:cli`).
