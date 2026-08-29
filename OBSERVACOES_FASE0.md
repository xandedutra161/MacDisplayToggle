# Fase 0 — Registro de execução e observações

Gate da Fase 0 do `PLANO_DE_DESENVOLVIMENTO.md` (§4). Preencher durante os testes
manuais na máquina real (M4, macOS Tahoe 26.5.2). **Todos os testes destrutivos com a
tampa ABERTA e watchdog externo armado** (`./scripts/watchdog.sh <uuid> <segundos> &`).

## Critérios de aceite (gates duros em negrito)

- [ ] **Religamento comprovado por enumeração** (display de volta à lista online), nunca pelo retorno da API (§2.3 item 1)
- [ ] **Religa sem replug físico, repetidamente (≥5 ciclos seguidos)** — roteiro: `test-cycle <uuid>` 5×
- [ ] Religa após o monitor entrar em standby durante o estado desabilitado — roteiro: `disable <uuid> --failsafe 600` → aguardar auto-standby do monitor → `enable <uuid>` em outro terminal → verificar por enumeração (degradável)
- [ ] Sobrevive a sleep/wake do Mac com display desabilitado — roteiro: `disable <uuid> --failsafe 600` → `pmset sleepnow` (tampa aberta) → acordar → `list` (o macOS pode ter religado sozinho — §2.3 item 5) → se continuar desabilitado, `enable` com sucesso (degradável)
- [ ] **Religa em execução separada do processo que desabilitou** (estado vindo do disco) — roteiro: `disable --failsafe 600` num terminal, `enable` em outro

Regra de decisão: se um gate duro falhar no M4/Tahoe, o projeto para e reavaliamos
(alternativa: orquestrar o `betterdisplaycli`). Standby e sleep/wake degradáveis:
falha intermitente documentada aqui + validação do watcher de re-aplicação vira
critério de aceite da Fase 1.

## Observações a registrar (não são gates)

| # | Observação | Resultado |
|---|---|---|
| a | Disable com `--for-session` vs padrão (`kCGConfigurePermanently`): comportamento igual? logout/restart reverte o ForSession? → **fixa a escolha do flag** (§2.1) | |
| b | Efeito real de enable/disable redundante (`--allow-redundant`): só pisca (handshake reinicia)? erro? (§2.2) | |
| c | Built-in aparece na lista SLS com ID 1? (heurística do Lunar — §2.2; o `list` imprime) | |
| d | Modo/refresh rate preservado após religar? (Crisp viu voltar no modo padrão) | |
| e | ID numérico mudou após um ciclo disable→enable? (o `test-cycle` imprime) | |

## Log de execuções

| Data/hora | Comando | Resultado | Notas |
|---|---|---|---|
| | | | |
