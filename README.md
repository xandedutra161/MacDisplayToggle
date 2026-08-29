# MacDisplayToggle

Utilitário de barra de menu para macOS (Apple Silicon) que **desabilita e religa
monitores de verdade** (disconnect real, como o BetterDisplay) — projeto de estudo de
Kotlin + Compose Desktop (JVM) + JNA.

- Fonte da verdade técnica: **`PLANO_DE_DESENVOLVIMENTO.md`**
- Status: **Fase 0 (PoC CLI) implementada** — gate de validação em andamento,
  registro em **`OBSERVACOES_FASE0.md`**

Módulos: `:cli` (Fase 0 — PoC), `:core` (Fase 1 — núcleo), `:app` (Fase 2 — UI Compose).

## Build

```sh
./gradlew :cli:installDist
CLI=cli/build/install/mdt-poc/bin/mdt-poc
"$CLI" list   # não-destrutivo: enumera displays (pública × SLS) e valida os bindings
```

## Comandos da Fase 0

```sh
"$CLI" list                                      # displays ativos + desabilitados + estado salvo
"$CLI" test-cycle <id|uuid> [--wait 15]          # desabilita → religa sozinho (ciclo de validação)
"$CLI" disable <id|uuid> --failsafe 600          # desabilita; religa no failsafe/Ctrl+C/religamento externo
"$CLI" enable  [<id|uuid>]                       # religa a partir do estado salvo (botão de pânico)
```

`--for-session` (experimento §2.1) · `--allow-redundant` (experimento §2.2) ·
`--yes` (pula confirmação).

## Protocolo de teste destrutivo (resumo — detalhes no PLANO §4/Fase 0)

1. **Tampa do MacBook ABERTA** (built-in ativo como tela de recuperação — Clamshell Sleep, §2.3);
2. Watchdog externo em outro terminal: `./scripts/watchdog.sh <uuid> 60 &` (cancele com `kill %1` se o teste terminar bem);
3. Lunar instalado e SSH habilitado;
4. Primeiro teste: `"$CLI" test-cycle <uuid-do-monitor-externo>` — nunca desabilitar dois displays no mesmo teste;
5. Emergência: abrir a tampa → `enable` · abrir o Lunar · `sudo killall -HUP WindowServer` · reboot · replug.

A trava do último display ativo real não tem override na Fase 0. `enable` não funciona
via SSH (as APIs exigem processo na sessão gráfica).
