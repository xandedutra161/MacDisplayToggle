# Fase 1 — Registro de validação do núcleo

Validações executadas em 2026-08-29 na máquina real (M4, Tahoe 26.5.2, tampa aberta,
watchdog externo armado em todos os testes destrutivos), sobre o núcleo `:core`
(`DisplayManager` + `Watcher` + `reconcile` + IOKit).

## Critérios de aceite (PLANO §4/Fase 1)

- [x] **Roteiros sem deixar display preso, incluindo `kill -9`** — ✅ V2: processo do
      `disable --failsafe` morto com SIGKILL (nenhum hook rodou); na execução seguinte
      `reconcile` detectou o órfão e `reconcile --auto` religou na 1ª tentativa,
      comprovado por enumeração. Estado final limpo.
- [x] **Watcher de re-aplicação validado** — ✅ V3 (simulação de wake): display religado
      por fora com o `state.json` restaurado para "desejado desabilitado" (reproduz o
      macOS religando sozinho no wake — §2.3 item 5); o watcher detectou no tick
      seguinte e **re-aplicou o disconnect**, protegido pela trava do último display.
      ⚠️ O roteiro REAL de sleep/wake (`pmset sleepnow`) segue pendente — manual, junto
      com o standby da Fase 0 (dormir o Mac suspenderia a sessão de teste).
- [x] **Trava do último display ativo real verificada ao vivo** — ✅ V2: com o externo
      desabilitado, `disable` do built-in (último ativo real) foi RECUSADO (exit 1);
      filtro de placeholder na contagem (id=2 fantasma nunca contou como ativo real).

## Descobertas de arquitetura (validadas ao vivo)

1. **JVM sem AppKit não tem fontes de CFRunLoop**: `CFRunLoopRun()` retorna em 0 ms
   mesmo na main thread e mesmo com `CGDisplayRegisterReconfigurationCallback`
   registrado — callbacks de mudanças EXTERNAS são inviáveis na CLI. No app da
   Fase 2 (AWT/AppKit), reavaliar: o runloop main do AppKit deve destravar isso.
2. **Receita `ListFreshness`**: com o callback REGISTRADO (mesmo sem runloop e sem
   nunca disparar), as enumerações CG ficam frescas — o watcher em polling detectou
   todas as transições `[3,1] → [1] → [3,1]` em ≤ um tick. Sem o registro, o processo
   fica cego a mudanças externas (processo A da Fase 0, 20+ s stale). Aplicada no
   watcher e no `waitFailsafe` do disable.
3. **Callbacks disparam INLINE para transações do próprio processo** (sem runloop):
   após a re-aplicação do watcher chegaram `Begin`, `Remove+Disabled+
   DesktopShapeChanged` (0x1220) e `Moved+SetMain+DesktopShapeChanged` (0x1006) —
   modelo mental fechado: eventos do próprio processo = callback inline; eventos
   externos = polling (CLI) ou runloop AppKit (Fase 2).
4. Re-resolução de religamento em camadas (implementada em `Ops.enableVerified`):
   UUID via SLS → **serial/vendor/model via SLS (plano B, legíveis mesmo
   desabilitado)** → ID persistido (o que religou de fato em todos os testes).

## O que o núcleo entrega (PLANO §4/Fase 1)

- `DisplayManager`: snapshot, disable/enable com todas as travas no NÚCLEO,
  `enableAllOurs`/`releaseOnShutdown` (só religa o que É nosso),
  `reconcileAtLaunch` (nunca re-aplica no launch; detecta/religa órfãos),
  `disableWithAutoRevert`/`confirmDisable` (timer de reversão opcional — implementado,
  será exercitado pela UI da Fase 2), `isNotebook` (IOKit/AppleSmartBattery — retornou
  `sim` no MacBook, correto).
- `Watcher`: callback + settle delay 1,5 s + polling 3 s com `ListFreshness`;
  re-aplica disconnect desejado, restaura se ativos reais = 0 (preferindo o builtin
  SALVO; heurística Lunar id=1 como último recurso), limpa estado de cabo removido.
- CLI ganhou `reconcile [--auto]` e `watch [--poll]` como harness dos roteiros.

## Log de execuções

| Data/hora | Roteiro | Resultado |
|---|---|---|
| 2026-08-29 ~17:1x | V1: `watch` + `test-cycle` externo | Watcher em polling viu todas as transições; ciclo SUCESSO 15,4 s; runloop 0 ms → descobertas 1–2 |
| 2026-08-29 ~17:2x | V2: trava + `kill -9` + `reconcile`/`--auto` | Trava recusou built-in (exit 1); órfão detectado e religado; estado final limpo |
| 2026-08-29 ~17:3x | V3: simulação de wake | Watcher re-aplicou disconnect no tick seguinte; callbacks inline observados (descoberta 3); cleanup religou e zerou estado |

## Pendências (manuais, quando o usuário quiser)

- Sleep/wake REAL: `disable <uuid> --failsafe 600` → `pmset sleepnow` (tampa aberta) →
  acordar → `list` → `enable` se preciso — fecha também o critério degradável da Fase 0;
- Standby do monitor (roteiro da Fase 0);
- Na Fase 2: testar se o runloop do AppKit destrava callbacks de eventos externos.
