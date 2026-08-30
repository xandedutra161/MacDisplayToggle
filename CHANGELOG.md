# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).
Ainda não há release público; tudo abaixo está em desenvolvimento no `main`.

## [Não lançado]

### Adicionado

- App de barra de menu (Compose) para desligar/religar monitores externos.
- CLI `macdisplaytoggle` (diagnóstico, recuperação e testes técnicos).
- Watcher com reconciliação pós-wake, limpeza de cabo removido e recuperação
  emergencial quando não resta display ativo.
- Testes unitários das regras de segurança do núcleo (external-only, último
  display ativo, persistência antes do disable, rollback).
- CI em macOS (`./gradlew check`) e documentos de contribuição/segurança.

### Alterado

- Reestruturação interna em domain/ports/adapters/application; binário da CLI
  renomeado de `mdt-poc` para `macdisplaytoggle`.
