# Fase 0 — Registro de execução e observações

Gate da Fase 0 do `PLANO_DE_DESENVOLVIMENTO.md` (§4), executado na máquina real
(M4, macOS Tahoe 26.5.2, monitor externo LG 26" id=3 uuid `B800DB24-…`, built-in
id=1, tampa ABERTA durante todos os testes). Protocolo seguido: watchdog externo
por teste, Lunar 6.11.0 instalado (via brew, não rodando), SSH ativo, trava do
último display no código.

## Critérios de aceite (gates duros em negrito)

- [x] **Religamento comprovado por enumeração** (§2.3 item 1) — ✅ 2026-08-29:
      todos os 7 religamentos (6 ciclos + 1 em processo separado) confirmados
      pelo retorno do display à lista pública online, nunca pelo retorno da API
- [x] **Religa sem replug físico, repetidamente (≥5 ciclos seguidos)** —
      ✅ 5/5 ciclos `test-cycle` consecutivos (15,4–15,9 s por ciclo), religamento
      sempre na 1ª tentativa, ID sempre 3
- [ ] Religa após standby do monitor durante o estado desabilitado (degradável)
      — **PENDENTE, manual**: `disable <uuid> --failsafe 600` → aguardar
      auto-standby do monitor → `enable` em outro terminal
- [ ] Sobrevive a sleep/wake do Mac com display desabilitado (degradável) —
      **PENDENTE, manual**: `disable <uuid> --failsafe 600` → `pmset sleepnow`
      (tampa aberta) → acordar → `list` → se ainda desabilitado, `enable`
- [x] **Religa em execução separada do processo que desabilitou** — ✅ processo A
      (`disable --failsafe 300`) + processo B (`enable` com estado vindo do
      `state.json`): religado na 1ª tentativa

**Veredito parcial (2026-08-29): os 3 gates duros PASSARAM no M4/Tahoe 26.5.2 —
a técnica está validada e a Fase 1 está tecnicamente destravada.** Standby e
sleep/wake (degradáveis) pendentes de execução manual; se falharem
intermitentemente, documentar aqui e promover a validação do watcher de
re-aplicação a critério da Fase 1 (regra de decisão do plano).

## Observações registradas

| # | Observação | Resultado |
|---|---|---|
| a | `--for-session` vs `kCGConfigurePermanently` no disable (§2.1) | Ciclo `--for-session` idêntico ao padrão (SUCESSO 15,7 s, 1ª tentativa, ID inalterado). Reversão por logout/restart NÃO testada (manual, opcional). **Decisão: manter `kCGConfigurePermanently` como default** (paridade comprovada + padrão Lunar/BetterDisplay) |
| b | Efeito de chamada redundante (§2.2) | **Enable redundante ABORTA a transação com `kCGErrorIllegalArgument(1001)`, sem efeito visual** — o relato de "erro que aborta" (que o plano marcava como não confirmado) CONFIRMOU; o "só pisca" do Crisp não se reproduziu aqui. Guard de no-op é obrigatório. Disable redundante: não testado (exigiria alvo desabilitado; opcional) |
| c | Built-in na lista SLS com ID 1? (§2.2) | ✅ CONFERE — built-in id=1 em todas as enumerações |
| d | Modo/refresh preservado após religar? | Sem anomalia percebida nos 6 ciclos; **falta confirmação visual explícita do usuário** (Configurações → Displays após um ciclo) |
| e | ID mudou após ciclo disable→enable? | NÃO — id=3 estável em todos os ciclos, inclusive no processo separado |

## Descobertas extras (entram no design da Fase 1)

1. **UUID não resolve para display desabilitado**: `CGDisplayCreateUUIDFromDisplayID`
   (via ColorSync) retorna null para o ID desabilitado — o `list` mostra `uuid=?`.
   A re-resolução que religou de verdade foi o **ID persistido** (fallback do
   §2.3 item 4). vendor/model/serial continuam legíveis no ID desabilitado →
   **matching por serial na lista SLS é o plano B de re-resolução para a Fase 1**
   (mitiga o risco de ID reatribuído enquanto desabilitado).
2. **Enumeração CG fica stale em processo de longa duração sem CFRunLoop**: o
   processo A (disable aguardando failsafe) não enxergou o religamento feito pelo
   processo B por 20+ s, enquanto processos novos viam na hora. Impacto direto no
   watcher da Fase 1: polling/callback precisam de `CFRunLoopRun()` em thread
   dedicada (como o plano já previa para callbacks — a novidade é que **até o
   polling da lista pública** sofre sem runloop); investigar se `SLSGetDisplayList`
   também é cacheada. Consequência prática na Fase 0: o failsafe interno de um
   processo pendurado dispararia um enable redundante (inofensivo — vira erro 1001).
3. `CGDisplayCreateUUIDFromDisplayID` **não existe no CoreGraphics** do Tahoe
   26.5.2 — só resolveu via ColorSync (o fallback do plano foi necessário).
4. A máquina tem uma entrada fantasma **id=2** (só na lista SLS, vendor/model/
   serial = 0, sem UUID), anterior aos nossos testes — não tocada; origem a
   investigar (resto do experimento displayplacer? slot de Sidecar/espelhamento?).
5. Lunar 6.11.0 instalado via `brew install --cask lunar` como religador de
   emergência do protocolo — manter **fechado** durante testes para não interferir.

## Log de execuções

| Data/hora | Comando | Resultado | Notas |
|---|---|---|---|
| 2026-08-29 ~16:45 | `test-cycle B800DB24…` (ciclo 1) | SUCESSO 15,9 s | 1ª tentativa; ID 3 inalterado; watchdog 90 s armado e cancelado |
| 2026-08-29 ~16:46 | `test-cycle` ×4 (ciclos 2–5) | SUCESSO 15,4 s cada | idem; watchdog por ciclo |
| 2026-08-29 16:48 | `disable --failsafe 300` (proc A) → `enable` (proc B) | SUCESSO | religado do disco na 1ª tentativa; A ficou cego ao religamento externo (descoberta 2) e foi morto com SIGKILL com estado já consistente |
| 2026-08-29 ~16:52 | `test-cycle --for-session` | SUCESSO 15,7 s | paridade com Permanently (observação a) |
| 2026-08-29 ~16:53 | `enable --allow-redundant` | transação abortou: 1001 | sem efeito visual (observação b) |
