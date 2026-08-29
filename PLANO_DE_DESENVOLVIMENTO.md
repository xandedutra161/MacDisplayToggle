# MacDisplayToggle — Plano de Desenvolvimento

App de barra de menu para macOS que desabilita e reabilita monitores de verdade
(removendo-os da configuração ativa de displays, como o *Disconnect/Connect* do
BetterDisplay), construído com **Kotlin + Compose Multiplatform (Desktop/JVM)** e
interop nativa via **JNA**.

> **Status (2026-08-29): TODAS as 4 fases concluídas** — PoC validado na máquina
> real, núcleo com watcher, app de menu bar validado pelo usuário e `.app`/DMG
> empacotados. Registros: `OBSERVACOES_FASE0.md` / `FASE1` / `FASE2`; visão geral
> no `README.md`. Regra de produto congelada: **tela embutida intocável** (§1).
> Pendências opcionais (manuais, não bloqueantes): roteiros reais de standby do
> monitor e sleep/wake do Mac.
> Marcação de progresso nas fases: ✅ concluída · 🔄 em andamento · ⬜ pendente.

---

## 1. Objetivo e escopo

**O app faz apenas isto:**

- Lista os monitores conectados ao Mac, indicando quais estão ativos e quais estão desabilitados;
- Desabilita um monitor selecionado (disconnect real — o display some da configuração do sistema);
- Reabilita um monitor desabilitado, **sem replug físico do cabo**;
- Vive na barra de menu do macOS (sem ícone no Dock);
- Nunca permite desabilitar o último display ativo;
- **Nunca desabilita a tela embutida** — o app existe para monitores EXTERNOS;
  religar a embutida continua permitido (decisão de produto congelada em
  2026-08-29, regra no núcleo: `DisplayManager.disable`).

**Terminologia:** neste documento, **desabilitar** (= desconectar) é o *disconnect*
do BetterDisplay — remover o display da configuração ativa; **religar** (= reabilitar)
é o *enable*/*reconnect* — trazê-lo de volta sem replug.

**Fora de escopo (de propósito):** resoluções, HiDPI, brilho, DDC (controle do
monitor pelo cabo de vídeo), espelhamento, displays virtuais — para isso existe o
BetterDisplay. Este projeto é um utilitário
de função única e um exercício de estudo de KMP + interop nativa.

**Plataforma-alvo:** Apple Silicon, macOS 13 (Ventura) ou superior.
Em Intel o disconnect real não funciona de forma confiável (os apps de referência
caem para gamma/brilho, que é só tela preta) — Intel fica explicitamente sem suporte.

**Máquina de desenvolvimento/teste:** MacBook com chip M4, macOS Tahoe 26.5.2,
usado em clamshell (tampa fechada) com monitor externo 26" (2560x1080) como único
display ativo — cenário que exige cuidado especial nos testes (ver Clamshell Sleep, §2.3).

---

## 2. Fundamento técnico (o que a pesquisa validou)

### 2.1 A API

Não existe API pública. O mecanismo — confirmado por **disassembly dos binários do
BetterDisplay 4.3.6 e do Lunar 6.11.0** — é uma única função privada, usada tanto
para desconectar quanto para reconectar:

```c
// SkyLight.framework (privado). CGSConfigureDisplayEnabled, no CoreGraphics,
// é um re-export do MESMO símbolo (verificado com dyld_info no macOS 26).
CGError SLSConfigureDisplayEnabled(CGDisplayConfigRef config,
                                   CGDirectDisplayID display,
                                   bool enabled);
```

Sempre dentro de uma transação pública do CoreGraphics:

```c
CGDisplayConfigRef config;
CGBeginDisplayConfiguration(&config);
CGError e = SLSConfigureDisplayEnabled(config, displayID, true /* ou false */);
if (e) { CGCancelDisplayConfiguration(config); return e; }
e = CGCompleteDisplayConfiguration(config, kCGConfigurePermanently);
if (e) { CGCancelDisplayConfiguration(config); return e; }
// O retorno do Complete importa: hardware removido falha aqui com erro 1001 (§2.3 item 4).
```

Sobre o flag do `Complete`: `kCGConfigurePermanently` (= 2) é o que Lunar e
BetterDisplay usam (evidência de disassembly); o DisplayDeck usa
`kCGConfigureForSession`. Em tese, `ForSession` no **disable** tornaria a
recuperação intrínseca (logout/restart do WindowServer reverteria). A Fase 0 deve
testar as duas variantes no disable e registrar o comportamento antes de fixar a
escolha.

### 2.2 O problema do "religar" — e a solução

No Apple Silicon, um display desabilitado **some** de `CGGetOnlineDisplayList`,
das Configurações do Sistema e da árvore de dispositivos. Foi por isso que o teste
com `displayplacer enabled:true` falhou: o displayplacer valida o ID contra a lista
pública antes de agir, não encontra o display e desiste **sem nunca chamar a API**
(bug documentado nas issues #25/#109/#137 dele; o PR #155 corrige, mas não foi mesclado).

O padrão que funciona (usado por BetterDisplay, Lunar, Crisp, InternalDisplayOff, DisplayDeck):

1. **Antes de desabilitar**, persistir em disco: `CGDirectDisplayID` e UUID
   (`CGDisplayCreateUUIDFromDisplayID`) — o UUID é a chave primária de re-resolução
   e o ID o fallback (padrão do Crisp). Guardar também o serial
   (`CGDisplaySerialNumber`) como identidade extra é adição nossa, inspirada no
   re-matching por serial do Lunar;
2. Para religar, **reencontrar o display via `SLSGetDisplayList`** (privada do
   SkyLight — ao contrário da pública, continua enumerando displays desabilitados),
   re-resolvendo por UUID (o ID numérico pode ser reatribuído enquanto
   desabilitado; o cache de ID é o fallback);
3. Chamar `SLSConfigureDisplayEnabled(config, id, true)` na mesma transação Begin/Complete.

Detalhes que importam:

- **Evitar chamadas no-op** (desabilitar o que já está desabilitado, ou vice-versa):
  checar o estado real por enumeração antes de agir. O efeito confirmado de um
  enable redundante é reiniciar o handshake do link (a tela pisca de novo — §2.3
  item 1); o relato de "erro que aborta a transação" **não foi confirmado** no
  código do Crisp — verificar na Fase 0;
- Heurística observada no Lunar (não é garantia da API): no Apple Silicon o display
  embutido costuma ter **ID = 1** (`lunar connect builtin` emite enable(1)) — útil
  como último recurso de recuperação, mas a Apple documenta `CGDirectDisplayID`
  como transiente; confirmar empiricamente na Fase 0 e nunca tratar como invariante;
- `SLSGetDisplayList` tem a mesma forma da `CGGetOnlineDisplayList` (confirmado no
  código do Crisp): `CGError SLSGetDisplayList(uint32_t max, CGDirectDisplayID*, uint32_t* count)`
  — chamar com `(0, NULL, &count)` para obter a contagem e de novo com o buffer;
- `SLSDetectDisplays()` e `IOServiceRequestProbe` são **no-ops verificados** para religar
  displays — não contar com eles como recuperação (o único "re-probe" eficaz é o
  próprio ciclo off→on do framebuffer).

### 2.3 Armadilhas confirmadas em produção (lidas do código do Crisp)

O `PhysicalDisplayToggleService.swift` do Crisp documenta comportamentos verificados
ao vivo que o nosso núcleo precisa tratar desde o início:

1. **Sucesso da transação NÃO é prova de religamento.** Perto de transições de sleep,
   `SLSConfigureDisplayEnabled(true)` + `Complete` retornam sucesso **com o display
   continuando desabilitado** (verificado ao vivo em clamshell). A única prova é
   enumeração: polling até o display reaparecer na lista online. Janela de
   verificação ≥ 4 s — o handshake do link leva 2–4 s, e re-emitir o enable no meio
   do handshake reinicia o link (pisca de novo). Padrão do Crisp: até 3 tentativas
   de enable, cada uma seguida de verificação por enumeração — com o enable
   disparado em **fire-and-forget** numa thread descartável, nunca esperando o
   `Complete` retornar antes de verificar (ele pode bloquear ~10 s além do retorno
   real do display, e uma chamada nativa bloqueada não é cancelável via JNA).
   Não re-emitir enable enquanto um `Complete` anterior ainda estiver em voo.
2. **`CGCompleteDisplayConfiguration` pode bloquear ~10 s** (renegociação —
   "retraining" — do link de vídeo). Rodar transações numa thread trabalhadora e
   aguardá-la com timeout; a chamada nativa **não é cancelável** via JNA — no
   timeout, abandona-se a espera (a thread fica presa até a chamada retornar) e
   não se inicia outra transação até ela retornar.
3. **Clamshell Sleep** (crítico para a nossa máquina de dev): um MacBook de tampa
   fechada dorme **no instante** em que seu único display ativo some. Assertion de
   `PreventSystemSleep` (ex.: `caffeinate`) **não impede**; o wake pode deixar o
   display preso desabilitado atrás de um "sucesso" mentiroso. O Crisp contorna
   segurando um display virtual (`CGVirtualDisplay`) descartável durante a janela.
   Para nós: **Fase 0 testa com a tampa aberta**, e o app recusa desabilitar o
   último display ativo de qualquer forma.
4. **Display placeholder.** Quando o último display real some, o macOS cria um
   display fantasma (vendor `0x756E6B6E` 'unkn', model `0x76697274` 'virt') que
   aparece nas listas — a contagem de "displays ativos reais" precisa filtrá-lo
   (e a displays virtuais). Nesse estado, `SLSGetDisplayList` encolhe para só o
   placeholder e o lookup por UUID falha: usar o **último ID conhecido como
   fallback** — a API aceita ID stale para hardware ainda conectado; hardware
   removido falha no `Complete` com erro 1001.
5. **Wake-from-sleep pode religar sozinho** displays desabilitados — o estado
   desejado deve ser re-aplicado após o wake. Na direção oposta: **nunca**
   re-aplicar disconnect no launch do app (reiniciar o app não pode apagar uma
   tela sozinho).
6. **Detectar "é notebook" pela bateria** (IOKit, `AppleSmartBattery`), não pela
   presença do display embutido nas listas — com a tampa fechada ele some delas.

### 2.4 Riscos conhecidos e permanentes

| Risco | Severidade | Mitigação |
|---|---|---|
| Cabo desplugado enquanto o display está desabilitado → WindowServer descarta o registro; só replug ou reset da configuração resolve | Média | Documentar; detectar sumiço via `SLSGetDisplayList` e limpar estado |
| Minoria de máquinas (relatos em família M3) não religa nem chamando a API direto | Média | Validar no M4 na Fase 0 (M1/M4 mini/M4 Air têm confirmações positivas) |
| API privada pode quebrar em qualquer update do macOS | Alta (impacto), baixa (frequência) | Funciona no Tahoe 26 hoje; resolver símbolo em runtime com fallback SLS→CGS; falhar graciosamente |
| Conflito com DisplayLink (Lunar desativa o disconnect quando ele roda) | Baixa | Detectar e avisar; fora de escopo resolver |
| Clamshell Sleep: desabilitar o único display ativo com a tampa fechada dorme o Mac no ato e pode deixar o display preso desabilitado (§2.3 item 3) | Alta durante a Fase 0 | Testar sempre com a **tampa aberta** (built-in ativo como 2º display) + failsafe por timer; no app, recusar desabilitar o último display ativo |
| API privada ⇒ sem App Store, sem sandbox | — | Distribuição direta (notarização funciona normalmente) |

**Recuperação de emergência** (se um teste deixar a tela desabilitada):
**abrir a tampa do MacBook** (acorda a máquina e dá uma tela; o externo pode continuar
desabilitado — religar então pelo comando `enable` do PoC) · abrir o Lunar (religa
tudo no startup) · `sudo killall -HUP WindowServer` (derruba a sessão) · reboot ·
replug do cabo (ou outra porta).

---

## 3. Decisões de arquitetura

| Decisão | Escolha | Por quê |
|---|---|---|
| UI / runtime | Compose Multiplatform **Desktop (JVM)** | Kotlin/Native para macOS ainda é experimental; JVM é o target estável |
| Interop nativa | **JNA puro, sem Swift/ObjC compilado** | ~15 símbolos C na Fase 0 (CoreGraphics + SkyLight + CoreFoundation) e ~22 no total — a Fase 1 soma callbacks de reconfiguração, CFRunLoop e IOKit (bateria); JNA resolve com `dlopen` em runtime |
| Resolução de símbolos | `SLSConfigureDisplayEnabled` primeiro, fallback `CGSConfigureDisplayEnabled` | Compatibilidade entre versões do macOS |
| Nome amigável do monitor | Fase 1: vendor/model number; Fase 2 (se valer a pena): `NSScreen.localizedName` via `objc_msgSend` pelo JNA | Evitar dylib helper enquanto possível |
| Barra de menu | Ícone via `Tray` (AWT) + **janela popup Compose sem decoração** ancorada à posição do clique (o AWT não expõe a posição do ícone no macOS) | Menus AWT são feios/limitados; popup Compose dá UI 100% Compose |
| Persistência de estado | Arquivo JSON em `~/Library/Application Support/MacDisplayToggle/` | Sobreviver a crash/reboot para religar displays órfãos |
| Empacotamento | jpackage via plugin Gradle do Compose; `LSUIElement=true` no Info.plist | .app standalone (~100–150 MB com JVM embutida), sem ícone no Dock |

**Trade-off assumido:** um app de menu bar em JVM ocupa ~150–250 MB de RAM residente
(vs ~30 MB do equivalente Swift). Aceito conscientemente por ser projeto de estudo de KMP.

---

## 4. Fases de desenvolvimento

### ✅ Fase 0 — PoC de validação (CLI, sem UI) — concluída (2026-08-29): gates duros ✅, observações ✅; standby/sleep-wake reais não executados (degradáveis pela regra de decisão — documentado em `OBSERVACOES_FASE0.md`)

Objetivo: provar na máquina real (M4/Tahoe) que o ciclo desabilitar→religar funciona
via Kotlin+JNA, antes de escrever qualquer UI.

Entregas (✅ todas implementadas em 2026-08-29 — a validação delas é o gate abaixo;
binário: `./gradlew :cli:installDist` → `cli/build/install/mdt-poc/bin/mdt-poc`):

1. Projeto Gradle mínimo — Kotlin/JVM + JNA ≥ 5.14 (versões antigas não suportam
   bem Apple Silicon); Kotlin e plugin do Compose no par estável vigente. Layout
   de módulos já preparado para as fases seguintes: `:core` (Fase 1), `:cli`
   (este PoC), `:app` (Fase 2);
2. Bindings JNA — CoreGraphics: `CGGetOnlineDisplayList`, `CGGetActiveDisplayList`,
   `CGDisplayIsBuiltin`, `CGDisplayIsActive`, `CGDisplayVendorNumber`,
   `CGDisplayModelNumber`, `CGDisplaySerialNumber` (vendor/model: filtro do placeholder
   — §2.3 item 4; serial: identidade persistida — §2.2), `CGDisplayCreateUUIDFromDisplayID`,
   `CGBeginDisplayConfiguration`, `CGCompleteDisplayConfiguration`
   (`kCGConfigureForSession = 1`, `kCGConfigurePermanently = 2`),
   `CGCancelDisplayConfiguration`;
   SkyLight: `SLSGetDisplayList`, `SLSConfigureDisplayEnabled` (fallback CGS);
   CoreFoundation: `CFUUIDCreateString`, `CFStringGetCString` (converter a
   `CFStringRef` resultante em string Java) e `CFRelease` (liberar o `CFUUIDRef`
   e a `CFStringRef`). Carga: passar o **caminho absoluto** ao `Native.load` —
   `/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics`,
   `/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight`,
   `/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation`.
   Desde o Big Sur esses arquivos **não existem no disco** (vivem no dyld shared
   cache); o `dlopen` com o caminho funciona mesmo assim — nunca condicionar a
   carga a uma checagem de existência do arquivo. Se `CGDisplayCreateUUIDFromDisplayID`
   não resolver no CoreGraphics, o símbolo vive hoje no ColorSync (re-exportado) —
   fallback: `/System/Library/Frameworks/ColorSync.framework/ColorSync`;
3. Comando `list`: displays ativos + desabilitados (comparando lista pública × SLS);
4. Comando `test-cycle <id|uuid> [--for-session]`: salva ID/UUID (+ serial, §2.2) em disco → desabilita o display
   indicado → **religa automaticamente após 15 s**, com religamento verificado por
   enumeração (retry até 3×, janela de 4 s — §2.3 itens 1–2) → relata o resultado.
   Alvo do primeiro teste: o monitor externo, com o built-in (tampa aberta) como
   tela de recuperação;
5. Comando `disable <id|uuid> --failsafe <segundos> [--for-session]`: salva
   ID/UUID (+ serial) em disco antes de desabilitar, como o `test-cycle`, e mantém
   o estado por mais tempo (failsafe longo, ex.: 600 s, continua obrigatório) —
   necessário para os roteiros de standby e sleep/wake dos critérios de aceite.
   O flag `[--for-session]` serve ao experimento do §2.1;
6. Comando `enable <id|uuid>`: religa manualmente a partir do estado salvo
   (botão de pânico caso o failsafe falhe). Nota: via SSH tende a **não** funcionar —
   as APIs de configuração de display exigem processo dentro da sessão gráfica;
   os caminhos reais são digitar às cegas no Terminal local ou abrir a tampa.

**Protocolo de segurança dos testes (pré-condições, inegociáveis):**

- Tampa do MacBook **aberta** (built-in ativo como segundo display) — elimina o
  Clamshell Sleep (§2.3 item 3) e garante uma tela de recuperação;
- A trava de "último display ativo" existe **desde o PoC** (não só na Fase 1):
  recusar desabilitar quando a contagem de displays ativos reais (com o filtro de
  placeholder da §2.3 item 4) for ≤ 1 — sem flag de override na Fase 0;
- Failsafe por timer em todo teste destrutivo, mesmo com a tampa aberta;
- Watchdog **externo ao processo**: antes de cada teste destrutivo, disparar um
  processo independente que religa após N segundos (ex.: `sleep 30 && poc enable
  <uuid>` em background, ou launchd one-shot), cancelado quando o teste termina
  bem — o failsafe interno morre junto com a JVM se um binding errado derrubar o
  processo (SIGSEGV é o erro mais provável da Fase 0);
- Antes do primeiro teste destrutivo: **Lunar instalado** (religador de emergência
  validado pela comunidade) e **SSH habilitado e testado** (útil para
  `killall`/reboot remotos; não serve para chamar as APIs de display — ver o
  comando `enable` acima);
- Nunca desabilitar dois displays no mesmo teste.

**Critérios de aceite (gate para a Fase 1):**

- [x] Religamento comprovado por **enumeração** (display de volta à lista online),
      nunca pelo retorno da API (§2.3 item 1) — ✅ 2026-08-29;
- [x] Religa **sem replug físico**, repetidamente (≥5 ciclos seguidos) — ✅ 5/5, 2026-08-29;
- [ ] Religa após o monitor entrar em standby durante o estado desabilitado
      (roteiro: `disable --failsafe 600` → aguardar o auto-standby do monitor →
      `enable` → verificar por enumeração);
- [ ] Sobrevive a sleep/wake do Mac com o display desabilitado (roteiro:
      `disable --failsafe 600` → `pmset sleepnow` com a tampa aberta → acordar →
      `list` para constatar o estado real, pois o macOS pode ter religado sozinho
      (§2.3 item 5) → se continuar desabilitado, `enable` com sucesso);
- [x] Religa em execução **separada** do processo que desabilitou (estado vindo do disco) — ✅ 2026-08-29;
- [x] Registrada a observação: o modo/refresh rate foi **preservado** após religar?
      — ✅ 2026-08-29: confirmado pelo usuário, sem anomalia nos 7 religamentos
      (o modo-padrão do Crisp não se reproduziu; nada a restaurar na Fase 1);
- [x] Registradas as observações pendentes da §2 (✅ 2026-08-29 — detalhes em
      `OBSERVACOES_FASE0.md`): (a) `--for-session` com paridade total no ciclo —
      **flag fixado: `kCGConfigurePermanently`**; (b) enable redundante **aborta a
      transação com erro 1001, sem efeito visual** (o relato não-confirmado do
      plano CONFIRMOU; o "só pisca" não se reproduziu); (c) built-in com ID 1 —
      heurística do Lunar CONFERE.

**Regra de decisão do gate:** enumeração, ciclos repetidos e processo separado são
**gates duros** — se falharem no M4/Tahoe, o projeto para aqui e reavaliamos
(a alternativa seria orquestrar o `betterdisplaycli`, o que muda o produto).
Standby e sleep/wake são **degradáveis**: falha intermitente neles não bloqueia a
Fase 1, desde que a falha seja documentada e a validação do watcher de
re-aplicação entre como critério de aceite da Fase 1. Os itens de observação
registrada não são gates.

### ✅ Fase 1 — Núcleo (`DisplayManager` como biblioteca) — concluída (2026-08-29, registro em `OBSERVACOES_FASE1.md`): critérios ✅ (watcher validado por simulação de wake; roteiro real de sleep/wake segue como pendência documentada, não bloqueante)

- Modelo de domínio: `DisplayInfo(id, uuid, nome, builtin, ativo/desabilitado)`;
- Regras de segurança **no núcleo, não na UI**:
  - recusar desabilitar o último display ativo **real** (excluindo o placeholder e
    displays virtuais da contagem — §2.3 item 4);
  - recusar chamadas no-op, checando o estado real por enumeração antes de agir;
  - religamento sempre verificado por enumeração, com retry (§2.3 item 1);
  - ao encerrar o app, religar **apenas os displays que nós desabilitamos** —
    displays desabilitados por outros apps (Lunar, BetterDisplay) não são nossos
    para mexer;
  - na inicialização, detectar displays deixados desabilitados por sessão anterior
    (crash) e oferecer/executar religamento — mas **nunca** re-aplicar disconnect
    no launch (§2.3 item 5);
  - também na inicialização, **reconciliar** o estado persistido com a realidade:
    displays do conjunto "desejado desabilitado" que já estiverem online saem do
    conjunto (padrão `reconcile` do Crisp) **antes** de armar o watcher/polling —
    sem isso, o primeiro ciclo do polling re-aplicaria o disconnect no launch,
    violando a regra acima;
- Timer de reversão automática opcional (estilo diálogo de mudança de resolução:
  "mantendo em N segundos…");
- Watcher de mudanças de configuração (`CGDisplayRegisterReconfigurationCallback`):
  reagir a plug/unplug físico, invalidar estado obsoleto e **restaurar um display se
  a contagem de ativos reais chegar a zero** (preferindo o built-in **entre IDs
  re-resolvidos por UUID** — `CGDisplayIsBuiltin` responde lixo para IDs stale, que
  devem ordenar como não-builtin —, com settle delay para não disparar em
  tempestades de reconexão — padrão `restoreIfNoActiveDisplay` do Crisp). Atenção (JVM): o callback só dispara se a thread que o registrou tiver
  um `CFRunLoop` rodando — registrar a partir de uma thread dedicada executando
  `CFRunLoopRun()` via JNA, ou cair para polling da lista de displays;
- Re-aplicar o estado desejado após wake-from-sleep (o macOS religa displays sozinho
  no wake — §2.3 item 5). Mecanismo escolhido: o próprio watcher/polling acima —
  um display do conjunto "desejado desabilitado" reaparecendo online ⇒ re-aplicar
  o disconnect, com settle delay e sempre protegido pela regra do último display
  (equivalente ao `reapplyOnWake` do Crisp, sem depender de NSWorkspace);
- Detecção de notebook pela bateria (IOKit: `IOServiceGetMatchingService`,
  `IOServiceMatching`, `IOObjectRelease` — §2.3 item 6), consumida pelos avisos de
  cenário clamshell na UI e pela lógica de restauração;
- Testes manuais roteirizados (não dá para automatizar hardware).

**Critérios de aceite (gate para a Fase 2):**

- [x] Roteiros manuais executados sem deixar nenhum display preso desabilitado
      (incluindo `kill -9` do processo host do núcleo com display desabilitado →
      religamento oferecido na inicialização seguinte) — ✅ 2026-08-29 (V2);
- [x] Watcher de re-aplicação validado — ✅ 2026-08-29 via simulação de wake (V3:
      religado por fora + estado desejado restaurado → re-aplicou no tick seguinte);
      roteiro REAL de sleep/wake segue pendente (manual);
- [x] Regra do último display ativo verificada, incluindo o filtro de placeholder
      (§2.3 item 4) — ✅ 2026-08-29 (V2: disable do built-in recusado ao vivo).

### ✅ Fase 2 — UI de barra de menu (Compose) — concluída (2026-08-29, Compose 1.9.0; **UI validada pelo usuário: "funcionou tudo"**; registro em `OBSERVACOES_FASE2.md`; regra extra congelada: tela embutida intocável)

- Ícone na barra de menu; clique abre popup Compose:
  lista de monitores com nome + estado + toggle (o AWT não expõe a posição do ícone
  no macOS — posicionar o popup pela coordenada do clique do mouse);
- Confirmação antes de desabilitar (com aviso do failsafe);
- Aviso quando a ação for arriscada em cenário clamshell (notebook detectado pela
  bateria — §2.3 item 6);
- Item "Religar todos" sempre visível;
- Sem janela principal, sem Dock (`LSUIElement`).

### ✅ Fase 3 — Empacotamento e distribuição — concluída (2026-08-29): `.app` de 132 MB via jpackage (createDistributable) + DMG 69 MB, `LSUIElement=true` verificado no Info.plist, ícone .icns gerado, assinatura ad-hoc do jpackage confirmada (`codesign -dv`), README final escrito; boot do .app validado

- `.app` via jpackage (plugin Compose), `Info.plist` com `LSUIElement=true`;
- Ícone do app; assinatura ad-hoc para uso pessoal
  (notarização + Developer ID só se for distribuir);
- README com requisitos (Apple Silicon, macOS 13+), limitações e playbook de emergência.

### Ideias futuras (backlog, não planejar agora)

Atalho global de teclado · ~~auto-desabilitar embutido ao conectar externo~~
(conflita com a regra "embutido intocável" congelada em 2026-08-29 — exigiria
revogá-la) ·
CLI própria (`macdisplaytoggle disable <nome>`) · detecção de DisplayLink com aviso ·
iniciar no login (LaunchAgent ou helper).

---

## 5. Referências

**Projetos open source que implementam a técnica (leitura de código):**

- [didriksg/Crisp](https://github.com/didriksg/Crisp) — Swift; **a referência canônica**
  (`Crisp/Services/PhysicalDisplayToggleService.swift`: cache de UUID, lookup via
  `SLSGetDisplayList`, verificação por enumeração, Clamshell Sleep guard, display
  placeholder — é a fonte da §2.3);
- [RonaldPark89/InternalDisplayOff](https://github.com/RonaldPark89/InternalDisplayOff) —
  resolução de símbolo SLS→CGS via dlopen, persistência de ID, religamento pós-crash;
- [laosb/MacDisplayTool](https://github.com/laosb/MacDisplayTool) — Swift mínimo, sem validação de lista;
- [DisplayDeck](https://github.com/oabdrabo/DisplayDeck) (hoje hospedado como
  `pyxis3-ai/displaydeck`; o link redireciona) — ObjC, menu bar, failsafes;
- [jakehilborn/displayplacer](https://github.com/jakehilborn/displayplacer) — o contraexemplo:
  issues [#25](https://github.com/jakehilborn/displayplacer/issues/25),
  [#109](https://github.com/jakehilborn/displayplacer/issues/109),
  [#137](https://github.com/jakehilborn/displayplacer/issues/137) e
  [PR #155](https://github.com/jakehilborn/displayplacer/pull/155) documentam a falha de religamento.

**Apps de referência e pesquisa:**

- [BetterDisplay](https://github.com/waydabber/BetterDisplay) (comportamento-alvo;
  [#4290](https://github.com/waydabber/BetterDisplay/discussions/4290) sobre reconexão travada);
- [Lunar FAQ](https://lunar.fyi/faq) (BlackOut/Disconnect, macOS 13+, Apple Silicon);
- [Blog do alin23 — clamshell reverse engineering](https://alinpanaitiu.com/blog/turn-off-macbook-display-clamshell/)
  (beco sem saída do `SLSDisplayPowerControlClient`: exige entitlement privado — não seguir esse caminho);
- [ComposeNativeTray](https://github.com/kdroidFilter/ComposeNativeTray) (opção de tray; no macOS também usa AWT).
