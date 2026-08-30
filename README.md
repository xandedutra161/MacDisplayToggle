# MacDisplayToggle

App de barra de menu para macOS que **desliga e religa monitores externos de
verdade**: o monitor some da configuração do sistema (disconnect real, não
apenas tela preta) e volta **sem precisar replugar o cabo**.

Regra de produto fixa: **a tela embutida do MacBook nunca é um alvo**. Ela não
aparece como opção na interface e o núcleo bloqueia qualquer tentativa de
desligá-la — a tela interna só é considerada para diagnóstico, contagem de
segurança e recuperação.

Escrito em **Kotlin + Compose Multiplatform (Desktop/JVM) + JNA**, com interop
direto com APIs nativas do macOS, sem Swift/ObjC compilado.

## Funcionalidades

- Lista os monitores externos conectados;
- Desliga um monitor externo com disconnect real;
- Religa monitores desligados pelo próprio app;
- Reverte o desligamento automaticamente em 20 s se você não confirmar;
- Reaplica o estado desejado após wake, quando o macOS religa um monitor sozinho;
- Recupera monitores órfãos de uma sessão anterior no próximo launch;
- Vive só na barra de menu, sem ícone no Dock.

## O que ele não faz

- Não desliga a tela embutida do MacBook;
- Não controla brilho, DDC, resolução, HiDPI, espelhamento ou displays virtuais;
- Não pretende ser uma ferramenta completa de gerenciamento de monitores.

## Requisitos

- Apple Silicon (Intel não é suportado — o disconnect real não funciona de
  forma confiável nessa família);
- macOS 13 Ventura ou superior;
- Sessão gráfica local (as APIs de display não funcionam via SSH).

O app usa uma API privada do macOS e por isso é distribuído fora da App Store,
sem sandbox. Validado em máquina real Apple Silicon (M4, macOS Tahoe 26.5.2).

## Instalação

```sh
./gradlew :app:createDistributable
cp -R app/build/compose/binaries/main/app/MacDisplayToggle.app /Applications/
open /Applications/MacDisplayToggle.app
```

Para gerar um DMG:

```sh
./gradlew :app:packageDmg
```

## Uso

O app fica na barra de menu. Clique no ícone de monitor para abrir o popup com
os monitores externos. Ao desligar um monitor, o app pede confirmação e religa
automaticamente em 20 s se você não clicar em "Manter".

O botão "Religar todos" religa apenas monitores que o próprio MacDisplayToggle
desligou. Ao sair, o app também religa apenas o que ele mesmo desligou.

Há também uma CLI de diagnóstico e recuperação:

```sh
./gradlew :cli:installDist
cli/build/install/macdisplaytoggle/bin/macdisplaytoggle list
```

O comando `list` é diagnóstico e mostra tudo (incluindo tela embutida e
entradas internas do sistema); os comandos destrutivos (`disable`,
`test-cycle`) operam apenas monitores externos e exigem confirmação.

## Segurança

As regras críticas vivem no núcleo, não apenas na interface:

- A tela embutida nunca é desligada;
- O último display ativo real nunca é desligado;
- Displays placeholder do macOS não contam como displays reais;
- A identidade do monitor é persistida **antes** do desligamento;
- Religar só é considerado sucesso após a enumeração confirmar o retorno;
- O launch reconcilia estado antigo antes de iniciar o watcher;
- Ao encerrar, o app religa apenas o que ele mesmo desligou.

## Recuperação de emergência

Se um monitor externo ficar preso desligado:

1. Abra a tampa do MacBook para ter uma tela local;
2. Rode `cli/build/install/macdisplaytoggle/bin/macdisplaytoggle enable <id|uuid>`;
3. Rode `sudo killall -HUP WindowServer` (a sessão gráfica reinicia);
4. Reinicie o Mac;
5. Replugue o cabo ou use outra porta.

## Limitações conhecidas

- Usa API privada (`SLSConfigureDisplayEnabled`), que pode quebrar em updates
  do macOS;
- Desplugar o cabo com o monitor desligado pode fazer o sistema descartar o
  registro do monitor; replug ou troca de porta resolve;
- Monitores DisplayLink não são tratados;
- Algumas máquinas podem não religar via API privada, mesmo em Apple Silicon;
- Por rodar na JVM, usa mais memória que uma implementação nativa em Swift.

## Como foi feito

Este projeto nasceu **vibecodando**, para resolver um problema simples que eu
tinha no dia a dia. O código funciona e foi validado em máquina real, mas com
certeza tem espaço para melhorias — sugestões e PRs são bem-vindos.

## Desenvolvimento

```sh
./gradlew check              # compila e roda os testes unitários
./gradlew :app:run           # app em modo de desenvolvimento
./gradlew :cli:installDist   # CLI técnica
```

Módulos:

- `:core` — regras de segurança, portas/adapters, bindings JNA, transações,
  estado persistido e watcher;
- `:cli` — harness técnico e ferramenta de recuperação;
- `:app` — barra de menu e popup em Compose Desktop.

Os testes unitários usam fakes e não tocam em display real. Testes destrutivos
são manuais: tampa do MacBook aberta e watchdog externo armado
(`./scripts/watchdog.sh <uuid|id> <segundos> &`). Detalhes em
[CONTRIBUTING.md](CONTRIBUTING.md); vulnerabilidades, em
[SECURITY.md](SECURITY.md).
