# MacDisplayToggle

Utilitário de barra de menu para macOS que **desabilita e religa monitores externos
de verdade** (disconnect real — o display some da configuração do sistema, como o
*Disconnect* do BetterDisplay) e os religa **sem replug do cabo**. Projeto de estudo
de **Kotlin + Compose Multiplatform (Desktop/JVM) + JNA** (interop com APIs nativas
privadas do macOS, sem uma linha de Swift/ObjC compilado).

- Fonte da verdade técnica: **`PLANO_DE_DESENVOLVIMENTO.md`** (progresso marcado por fase)
- Registros de validação: `OBSERVACOES_FASE0.md` · `FASE1` · `FASE2`
- Status: **Fases 0–3 concluídas** (2026-08-29) — app funcional, empacotado e validado
  na máquina real (M4, macOS Tahoe 26.5.2)

## Requisitos

- **Apple Silicon** (Intel sem suporte — o disconnect real não funciona de forma
  confiável lá, ver PLANO §1);
- **macOS 13 (Ventura) ou superior** (validado no Tahoe 26.x);
- Sem App Store e sem sandbox (usa API privada do SkyLight — PLANO §2.4);
  assinatura ad-hoc, uso pessoal.

## Instalar / rodar

```sh
./gradlew :app:createDistributable
cp -R app/build/compose/binaries/main/app/MacDisplayToggle.app /Applications/
open /Applications/MacDisplayToggle.app
```

(ou `./gradlew :app:packageDmg` → `app/build/compose/binaries/main/dmg/MacDisplayToggle-1.0.0.dmg`;
para desenvolvimento, `./gradlew :app:run`.)

O app vive só na **barra de menu** (sem Dock — `LSUIElement`). Clique no ícone de
monitor: popup com a lista de displays e um toggle por monitor. Desabilitar pede
confirmação e **religa sozinho em 20 s** se você não clicar em "Manter". "Religar
todos" está sempre visível; "Sair" religa tudo o que o app tiver desabilitado.

## Regras de segurança (no núcleo, não na UI)

- **Nunca** desabilita a **tela embutida** — o app é para monitores externos
  (decisão de produto; religar a embutida é permitido);
- **Nunca** desabilita o último display ativo real (placeholder do macOS não conta);
- Religamento sempre **comprovado por enumeração** (nunca pelo retorno da API),
  com retry; identidade (UUID/ID/serial) persistida em
  `~/Library/Application Support/MacDisplayToggle/state.json` **antes** de desabilitar;
- Se o app morrer com um display desabilitado, a próxima inicialização detecta o
  órfão e oferece religar (`reconcile`); um watcher re-aplica o estado desejado
  após wake e **restaura um display se a contagem de ativos chegar a zero**;
- Ao sair, religa **apenas** o que o próprio app desabilitou.

## Limitações conhecidas

- API privada (`SLSConfigureDisplayEnabled`) — pode quebrar em qualquer update do
  macOS; o app resolve os símbolos em runtime (fallbacks SLS→CGS e CG→ColorSync)
  e falha graciosamente;
- Cabo desplugado **enquanto desabilitado**: o WindowServer descarta o registro —
  replug (ou outra porta) resolve; o app limpa o estado ao detectar o sumiço;
- Conflito com DisplayLink não tratado (fora de escopo); minoria de máquinas
  (relatos em M3) pode não religar via API — validado OK neste M4;
- App JVM: ~150–250 MB de RAM residente (trade-off consciente do estudo de KMP);
- Roteiros de standby do monitor e sleep/wake reais seguem como validação manual
  opcional (mecanismo equivalente já validado por simulação — `OBSERVACOES_FASE1.md`).

## Playbook de emergência (display preso desabilitado)

1. **Abrir a tampa do MacBook** (dá uma tela) → religar pelo app ou pela CLI;
2. CLI de socorro: `cli/build/install/mdt-poc/bin/mdt-poc enable <id|uuid>`
   (estado salvo em disco; não funciona via SSH — precisa da sessão gráfica);
3. Abrir o **Lunar** (religa tudo no startup);
4. `sudo killall -HUP WindowServer` (derruba a sessão gráfica);
5. Reboot · 6. Replug do cabo (ou outra porta).

## Desenvolvimento

Módulos: `:core` (núcleo `DisplayManager` + watcher + bindings JNA), `:cli`
(harness de validação: `list`, `test-cycle`, `disable --failsafe`, `enable`,
`reconcile`, `watch`), `:app` (UI Compose).

```sh
./gradlew :cli:installDist
cli/build/install/mdt-poc/bin/mdt-poc list   # não-destrutivo
```

Testes destrutivos: SEMPRE com a tampa aberta e watchdog externo
(`./scripts/watchdog.sh <uuid> <segundos> &`) — protocolo completo no PLANO §4.
