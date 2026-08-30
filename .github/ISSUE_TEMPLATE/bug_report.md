---
name: Relato de bug
about: Comportamento errado do app, da CLI ou do watcher
title: ""
labels: bug
assignees: ""
---

## Ambiente (obrigatório — comportamento varia por hardware/macOS)

- Chip: <!-- ex.: M4 -->
- macOS: <!-- ex.: Tahoe 26.5.2 -->
- Monitor(es) externo(s): <!-- marca/modelo -->
- Conexão: <!-- HDMI direto, USB-C/DP, dock/hub (qual) -->
- Como instalou: <!-- .app/DMG, ./gradlew :app:run, CLI -->

## O que aconteceu

<!-- Descreva o comportamento observado. -->

## O que era esperado

## Passos para reproduzir

1.

## Diagnóstico

Cole a saída de `macdisplaytoggle list` (não é destrutivo):

```text

```

Se um display ficou preso desligado: o playbook de recuperação funcionou?
(abrir a tampa → `macdisplaytoggle enable` →
`sudo killall -HUP WindowServer` → replug do cabo)
