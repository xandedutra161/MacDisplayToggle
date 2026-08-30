package mdt.cli

import mdt.core.domain.DisplayError
import kotlin.system.exitProcess

private const val USAGE = """MacDisplayToggle — CLI técnica e ferramenta de recuperação

USO:
  macdisplaytoggle list
  macdisplaytoggle test-cycle <id|uuid> [--wait <s>]      [--for-session] [--yes]
  macdisplaytoggle disable    <id|uuid> --failsafe <s>    [--for-session] [--allow-redundant] [--yes]
  macdisplaytoggle enable     [<id|uuid>]                 [--allow-redundant]
  macdisplaytoggle reconcile  [--auto]
  macdisplaytoggle watch      [--poll]

COMANDOS:
  list        diagnostico completo: displays ativos + desabilitados (lista pública × lista SLS)
  test-cycle  salva identidade em disco → desabilita → religa sozinho após --wait s (padrão 15)
  disable     salva identidade e desabilita um monitor externo; religa quando o --failsafe (obrigatório) expirar,
              quando o display voltar por fora (outro processo/wake) ou no Ctrl+C
  enable      religa a partir do estado salvo (ou do <id|uuid> dado) — botão de pânico.
              Não funciona via SSH: as APIs exigem processo dentro da sessão gráfica.
  reconcile   reconciliação de inicialização — remove estado obsoleto e detecta
              órfãos de sessão anterior (crash); --auto religa os órfãos
  watch       roda o watcher em foreground (callback CGDisplayReconfiguration +
              CFRunLoop; re-aplica disconnect desejado pós-wake, restaura se ativos=0,
              limpa estado de cabo removido); Ctrl+C para sair

FLAGS:
  --for-session      usa kCGConfigureForSession no disable
  --allow-redundant  permite a chamada no-op p/ o experimento de chamada redundante (senão é recusada)
  --yes              pula a confirmação interativa (para o watchdog/scripts)
  --poll             força o watcher a operar sem callback/CFRunLoop (comparação/debug)

SEGURANÇA:
  · Testes destrutivos SÓ com a tampa do MacBook ABERTA (Clamshell Sleep)
  · Watchdog EXTERNO antes de cada teste:  ./scripts/watchdog.sh <uuid|id> <segundos> &
  · O app desabilita apenas monitores externos; a tela embutida só aparece em diagnostico/recuperacao
  · Trava do último display ativo real no NÚCLEO, sem override
  · Nunca desabilitar dois displays no mesmo teste"""

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(USAGE)
        exitProcess(2)
    }
    try {
        when (args[0]) {
            "list" -> cmdList()
            "test-cycle" -> cmdTestCycle(args.drop(1))
            "disable" -> cmdDisable(args.drop(1))
            "enable" -> cmdEnable(args.drop(1))
            "reconcile" -> cmdReconcile(args.drop(1))
            "watch" -> cmdWatch(args.drop(1))
            "help", "--help", "-h" -> println(USAGE)
            else -> {
                System.err.println("comando desconhecido: ${args[0]}\n")
                println(USAGE)
                exitProcess(2)
            }
        }
    } catch (e: DisplayError) {
        System.err.println("ERRO: ${e.message}")
        exitProcess(1)
    }
}

/** Parser mínimo: flags booleanas, opções com valor e um posicional. */
class Args(list: List<String>) {
    private val rest = list.toMutableList()

    fun flag(name: String): Boolean = rest.remove(name)

    fun option(name: String): String? {
        val i = rest.indexOf(name)
        if (i < 0) return null
        if (i + 1 >= rest.size) throw DisplayError("$name exige um valor")
        val v = rest[i + 1]
        rest.removeAt(i + 1)
        rest.removeAt(i)
        return v
    }

    fun positional(): String? = rest.firstOrNull { !it.startsWith("--") }?.also { rest.remove(it) }

    fun finish() {
        if (rest.isNotEmpty()) throw DisplayError("argumentos não reconhecidos: $rest")
    }
}
