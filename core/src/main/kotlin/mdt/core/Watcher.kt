package mdt.core

import mdt.core.application.DisplayReconciler
import mdt.core.jna.reconfigFlagNames
import mdt.core.ports.DisplayEventSource
import mdt.core.ports.EventSink
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Watcher de mudanças de configuração. Coordena três coisas:
 * - eventos vindos do [DisplayEventSource] (callback nativo) são só ENFILEIRADOS;
 * - worker com settle delay (tempestades de reconexão) que chama o
 *   [DisplayReconciler]: re-aplica o disconnect desejado (wake religa sozinho),
 *   restaura um display se a contagem de ativos reais chegar a 0
 *   (padrão `restoreIfNoActiveDisplay`), e limpa estado de displays que
 *   sumiram da SLS (cabo removido);
 * - fallback para polling se o callback não registrar/rodar. A validação em
 *   máquina real provou que SEM o registro do callback até a enumeração via
 *   polling fica stale — por isso o registro acontece mesmo no modo polling.
 */
class Watcher internal constructor(
    private val reconciler: DisplayReconciler,
    private val eventSource: DisplayEventSource,
    private val onLog: EventSink,
    private val pollOnly: Boolean,
    private val settleMs: Long,
    private val pollIntervalMs: Long = 3_000,
) {
    private val events = LinkedBlockingQueue<Pair<Int, Int>>() // (display, flags)

    @Volatile
    private var running = true

    @Volatile
    private var usingCallbacks = false

    @Volatile
    private var registered = false
    private var workerThread: Thread? = null

    internal fun start() {
        if (pollOnly) {
            onLog.log("watcher: modo polling forçado — ATENÇÃO: sem runloop as listas podem ficar stale")
        }
        workerThread = Thread(::workerBody, "display-watch-worker").apply { isDaemon = true; start() }
    }

    /**
     * Registra o callback e BLOQUEIA a thread chamadora na entrega de eventos. Deve
     * ser a thread onde o CoreGraphics inicializou (validado em máquina real; com
     * thread dedicada "limpa" o runloop fica sem fontes e retorna na hora). No
     * app, o runloop main do AppKit cumpre este papel naturalmente.
     * @return true se o runloop rodou (callbacks ativos); false se caiu para polling.
     */
    fun runLoopBlocking(): Boolean {
        if (pollOnly) return false
        if (!ensureRegistered("caindo para polling")) return false
        usingCallbacks = true
        onLog.log("watcher: callback registrado; iniciando CFRunLoopRun na thread atual")
        val ranMs = eventSource.deliverBlocking()
        usingCallbacks = false
        if (running && ranMs < 500) {
            onLog.log(
                "watcher: CFRunLoopRun retornou em ${ranMs}ms — JVM sem AppKit não tem fontes de runloop: " +
                    "callbacks indisponíveis; operando por POLLING com o callback " +
                    "REGISTRADO, o que mantém as listas frescas (receita ListFreshness)"
            )
        }
        return false // só retorna quando o runloop termina (stop ou sem fontes)
    }

    /**
     * Modo app: registra o callback SEM bloquear em runloop próprio. Eventos de
     * transações do PRÓPRIO processo chegam inline (validado em máquina real) e o registro
     * mantém as listas frescas (receita ListFreshness); eventos EXTERNOS chegam se o
     * runloop do host (AppKit/AWT) os entregar — senão o polling de 3 s cobre.
     */
    fun registerCallbackOnly() {
        if (pollOnly || registered) return
        if (ensureRegistered("só polling")) {
            onLog.log("watcher: callback registrado (sem runloop dedicado; eventos inline + polling 3 s)")
        }
    }

    fun stop() {
        running = false
        eventSource.stopDelivering()
        eventSource.unregister()
        workerThread?.interrupt()
    }

    private fun ensureRegistered(fallbackNote: String): Boolean {
        if (registered) return true
        registered = eventSource.register { display, flags ->
            events.offer(display to flags) // só enfileirar — nada de trabalho aqui
        }
        if (!registered) onLog.log("watcher: registro do callback falhou — $fallbackNote")
        return registered
    }

    private fun workerBody() {
        try {
            while (running) {
                // Com callbacks: tick de segurança a cada 10 s. Sem: polling (3 s por padrão).
                val first = events.poll(if (usingCallbacks) 10_000 else pollIntervalMs, TimeUnit.MILLISECONDS)
                if (!running) return
                if (first == null) {
                    reconciler.reconcile("tick periódico")
                    continue
                }
                onLog.log("watcher: evento display=${first.first} flags=${reconfigFlagNames(first.second)}")
                // settle: esperar a tempestade de eventos passar antes de agir
                while (true) {
                    val more = events.poll(settleMs, TimeUnit.MILLISECONDS) ?: break
                    onLog.log("watcher: evento display=${more.first} flags=${reconfigFlagNames(more.second)}")
                }
                reconciler.reconcile("pós-evento (settle ${settleMs}ms)")
            }
        } catch (_: InterruptedException) {
        }
    }
}
