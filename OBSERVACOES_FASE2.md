# Fase 2 — Registro de implementação e validação da UI

App de barra de menu (módulo `:app`, Compose Multiplatform 1.9.0 + Kotlin 2.2.20),
implementado e com o lado não-visual validado em 2026-08-29. Rodar com:

```sh
./gradlew :app:run
```

## O que está implementado (PLANO §4/Fase 2)

- Ícone na barra de menu via Tray AWT (glifo de monitor desenhado em 18/36 px,
  cor conforme tema claro/escuro no launch); clique abre/fecha o popup;
- Popup Compose **sem decoração, transparente com cantos arredondados**, ancorado
  na coordenada do clique (o AWT não expõe a posição do ícone — PLANO §3) e
  fechado ao perder o foco;
- Lista de displays com nome amigável (decode PNP do vendor EDID — "LG (5C0C)",
  "Tela embutida"), estado e **toggle**; placeholder e entradas SLS sem identidade
  (ghost id=2) ficam ocultos;
- **Confirmação antes de desabilitar** com aviso dos failsafes; o disable usa o
  **timer de auto-reversão do núcleo** (religa sozinho em 20 s) com card
  "Manter / Religar agora" e countdown — o padrão "mudança de resolução" do plano;
- **Aviso de cenário clamshell** (notebook via bateria/IOKit e built-in ausente
  das listas ativas — §2.3 itens 3/6) no popup e reforçado no diálogo;
- **"Religar todos" sempre visível** (religa apenas o que NÓS desabilitamos);
- "Sair" chama `releaseOnShutdown()` (religa só os nossos) antes de encerrar;
- Sem ícone no Dock (`apple.awt.UIElement=true` no dev; `LSUIElement` é a Fase 3);
- No launch: `reconcileAtLaunch` ANTES do watcher; com órfãos, o popup abre
  sozinho com aviso e "Religar todos" resolve.

## Validação automatizada (2026-08-29)

- App sobe limpo, tray presente, processo estável;
- **Descoberta (fecha a questão da Fase 1): com o AWT/AppKit rodando o runloop da
  main, os callbacks de reconfiguração chegam para eventos EXTERNOS** — um
  `test-cycle` disparado pela CLI produziu no app os eventos
  `Remove+Disabled+DesktopShapeChanged` (0x1220) e
  `Moved+SetMain+SetMode+Add+Enabled+DesktopShapeChanged` (0x111e), cada um
  seguido do reconcile pós-settle (1,5 s). O watcher do app é **event-driven**;
  o polling de 3 s fica como retaguarda. Na CLI (JVM puro) segue polling;
- O watcher do app não interferiu no ciclo externo (ordem forget-first validada);
- Bug encontrado e corrigido: `application{}` do Compose **encerra com zero
  janelas na composição** (o tray AWT cru não conta) — corrigido com janela
  âncora invisível permanente.

## Validação manual pendente (usuário — é só usar)

- [ ] Clicar no ícone: popup abre na posição do clique e fecha ao clicar fora;
- [ ] Toggle do LG: diálogo de confirmação → Desabilitar → card "Manter /
      Religar agora" com countdown de 20 s (deixar expirar uma vez: religa sozinho);
- [ ] "Manter" → display fica desabilitado → religar pelo toggle;
- [ ] "Religar todos" com algo desabilitado;
- [ ] "Sair" religa o que estiver desabilitado por nós e encerra;
- [ ] Aparência do ícone/popup nos temas claro/escuro.

## Pendências herdadas (fecham Fases 0–1)

- Sleep/wake real (`pmset sleepnow`) e standby do monitor — roteiros manuais.
