package mdt.cli

import kotlin.system.exitProcess

private const val USAGE = """MacDisplayToggle — PoC Fase 0 (validar disable/enable real de displays via Kotlin+JNA)

USO:
  mdt-poc list
  mdt-poc test-cycle <id|uuid> [--wait <s>]      [--for-session] [--yes]
  mdt-poc disable    <id|uuid> --failsafe <s>    [--for-session] [--allow-redundant] [--yes]
  mdt-poc enable     [<id|uuid>]                 [--allow-redundant]

COMANDOS:
  list        displays ativos + desabilitados (lista pública × lista SLS) e estado salvo
  test-cycle  salva identidade em disco → desabilita → religa sozinho após --wait s (padrão 15)
  disable     salva identidade e desabilita; religa quando o --failsafe (obrigatório) expirar,
              quando o display voltar por fora (outro processo/wake) ou no Ctrl+C
  enable      religa a partir do estado salvo (ou do <id|uuid> dado) — botão de pânico.
              Não funciona via SSH: as APIs exigem processo dentro da sessão gráfica.

FLAGS:
  --for-session      usa kCGConfigureForSession no disable (experimento PLANO §2.1)
  --allow-redundant  permite a chamada no-op p/ o experimento do PLANO §2.2 (senão é recusada)
  --yes              pula a confirmação interativa (para o watchdog/scripts)

SEGURANÇA (PLANO §4/Fase 0 — inegociável):
  · Testes destrutivos SÓ com a tampa do MacBook ABERTA (Clamshell Sleep — PLANO §2.3 item 3)
  · Watchdog EXTERNO antes de cada teste:  ./scripts/watchdog.sh <uuid|id> <segundos> &
  · Trava do último display ativo real sem override na Fase 0
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
            "help", "--help", "-h" -> println(USAGE)
            else -> {
                System.err.println("comando desconhecido: ${args[0]}\n")
                println(USAGE)
                exitProcess(2)
            }
        }
    } catch (e: PocError) {
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
        if (i + 1 >= rest.size) throw PocError("$name exige um valor")
        val v = rest[i + 1]
        rest.removeAt(i + 1)
        rest.removeAt(i)
        return v
    }

    fun positional(): String? = rest.firstOrNull { !it.startsWith("--") }?.also { rest.remove(it) }

    fun finish() {
        if (rest.isNotEmpty()) throw PocError("argumentos não reconhecidos: $rest")
    }
}
