package mdt.core

import com.sun.jna.Pointer
import mdt.core.ffi.DisplayReconfigurationCallback
import mdt.core.ffi.NativeApis
import mdt.core.ffi.cgErrorName
import mdt.core.ffi.kCGConfigurePermanently
import mdt.core.ffi.reconfigFlagNames
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Watcher de mudanças de configuração (PLANO §4/Fase 1):
 * - `CGDisplayRegisterReconfigurationCallback` numa thread dedicada rodando
 *   `CFRunLoopRun()` — obrigatório na JVM; a Fase 0 provou que SEM runloop até a
 *   enumeração via polling fica stale (descoberta 2), então o runloop também é o
 *   que mantém as listas CG frescas neste processo;
 * - eventos só são ENFILEIRADOS no callback (nada de transação dentro dele);
 * - worker com settle delay (tempestades de reconexão) que reconcilia:
 *   re-aplica o disconnect desejado (wake religa sozinho — §2.3 item 5),
 *   restaura um display se a contagem de ativos reais chegar a 0
 *   (padrão `restoreIfNoActiveDisplay` do Crisp), e limpa estado de displays
 *   que sumiram da SLS (cabo removido — §2.4);
 * - fallback para polling se o callback não registrar/rodar.
 */
class Watcher internal constructor(
    private val manager: DisplayManager,
    private val onLog: (String) -> Unit,
    private val pollOnly: Boolean,
    private val settleMs: Long,
) {
    private val events = LinkedBlockingQueue<Pair<Int, Int>>() // (display, flags)

    @Volatile
    private var running = true

    @Volatile
    private var usingCallbacks = false

    @Volatile
    private var runLoopRef: Pointer? = null

    // Referência FORTE ao callback: se o GC recolher, o JNA invalida o trampoline nativo.
    private var callbackRef: DisplayReconfigurationCallback? = null
    private var workerThread: Thread? = null
    private var lastOnline: List<Int>? = null

    internal fun start() {
        if (pollOnly) {
            onLog("watcher: modo polling forçado — ATENÇÃO: sem runloop as listas podem ficar stale (Fase 0, descoberta 2)")
        }
        workerThread = Thread(::workerBody, "display-watch-worker").apply { isDaemon = true; start() }
    }

    /**
     * Registra o callback e BLOQUEIA a thread chamadora em CFRunLoopRun(). Deve ser a
     * thread onde o CoreGraphics inicializou (a fonte Mach da conexão CGS é agendada
     * no runloop corrente da inicialização — com thread dedicada "limpa", o runloop
     * fica sem fontes e retorna na hora, validado na Fase 1). Na Fase 2 (app), o
     * runloop main do AppKit cumpre este papel naturalmente.
     * @return true se o runloop rodou (callbacks ativos); false se caiu para polling.
     */
    fun runLoopBlocking(): Boolean {
        if (pollOnly) return false
        runLoopBody()
        return false // só retorna quando o runloop termina (stop ou sem fontes)
    }

    /**
     * Fase 2 (app): registra o callback SEM bloquear em runloop próprio. Eventos de
     * transações do PRÓPRIO processo chegam inline (validado na Fase 1) e o registro
     * mantém as listas frescas (receita ListFreshness); eventos EXTERNOS chegam se o
     * runloop do host (AppKit/AWT) os entregar — senão o polling de 3 s cobre.
     */
    fun registerCallbackOnly() {
        if (pollOnly || callbackRef != null) return
        val cb = makeCallback()
        callbackRef = cb
        val err = NativeApis.cg.CGDisplayRegisterReconfigurationCallback(cb, null)
        if (err == 0) {
            onLog("watcher: callback registrado (sem runloop dedicado; eventos inline + polling 3 s)")
        } else {
            callbackRef = null
            onLog("watcher: registro do callback falhou (${cgErrorName(err)}) — só polling")
        }
    }

    private fun makeCallback(): DisplayReconfigurationCallback = object : DisplayReconfigurationCallback {
        override fun invoke(display: Int, flags: Int, userInfo: Pointer?) {
            events.offer(display to flags) // só enfileirar — nada de trabalho aqui
        }
    }

    fun stop() {
        running = false
        runLoopRef?.let { NativeApis.cf.CFRunLoopStop(it) }
        callbackRef?.let { NativeApis.cg.CGDisplayRemoveReconfigurationCallback(it, null) }
        workerThread?.interrupt()
    }

    private fun runLoopBody() {
        val cb = makeCallback()
        callbackRef = cb
        val err = NativeApis.cg.CGDisplayRegisterReconfigurationCallback(cb, null)
        if (err != 0) {
            onLog("watcher: registro do callback falhou (${cgErrorName(err)}) — caindo para polling")
            return
        }
        usingCallbacks = true
        runLoopRef = NativeApis.cf.CFRunLoopGetCurrent()
        onLog("watcher: callback registrado; iniciando CFRunLoopRun na thread atual")
        val t0 = System.nanoTime()
        NativeApis.cf.CFRunLoopRun()
        val ranMs = (System.nanoTime() - t0) / 1_000_000
        usingCallbacks = false
        if (running && ranMs < 500) {
            onLog(
                "watcher: CFRunLoopRun retornou em ${ranMs}ms — JVM sem AppKit não tem fontes de runloop " +
                    "(validado na Fase 1): callbacks indisponíveis; operando por POLLING com o callback " +
                    "REGISTRADO, o que mantém as listas frescas (receita ListFreshness)"
            )
        }
    }

    private fun workerBody() {
        try {
            while (running) {
                // Com callbacks: tick de segurança a cada 10 s. Sem: polling de 3 s.
                val first = events.poll(if (usingCallbacks) 10_000 else 3_000, TimeUnit.MILLISECONDS)
                if (!running) return
                if (first == null) {
                    reconcileTick("tick periódico")
                    continue
                }
                onLog("watcher: evento display=${first.first} flags=${reconfigFlagNames(first.second)}")
                // settle: esperar a tempestade de eventos passar antes de agir
                while (true) {
                    val more = events.poll(settleMs, TimeUnit.MILLISECONDS) ?: break
                    onLog("watcher: evento display=${more.first} flags=${reconfigFlagNames(more.second)}")
                }
                reconcileTick("pós-evento (settle ${settleMs}ms)")
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun reconcileTick(reason: String) {
        try {
            val snap = manager.snapshot()
            // Diagnóstico de frescor (Fase 0, descoberta 2): logar mudanças de lista
            val online = snap.filter { it.online }.map { it.id }
            if (lastOnline != null && lastOnline != online) {
                onLog("watcher: lista online mudou $lastOnline → $online [$reason]")
            }
            lastOnline = online
            val state = StateStore.load()
            if (snap.count { it.isActiveReal } == 0) {
                restoreEmergency(snap, state)
                return
            }
            for (saved in state.disabledByUs) {
                val onlineId = Ops.matchOnline(saved)
                if (onlineId != null) {
                    val remaining = snap.count { it.isActiveReal && it.id != onlineId }
                    if (remaining >= 1) {
                        onLog("watcher: ${saved.label()} voltou online (wake? — §2.3 item 5); re-aplicando disconnect [$reason]")
                        try {
                            Ops.disableVerified(onlineId, kCGConfigurePermanently)
                        } catch (e: Throwable) {
                            onLog("watcher: re-aplicação falhou: ${e.message}")
                        }
                    } else {
                        onLog("watcher: NÃO re-aplico ${saved.label()} — seria o último display ativo real; removendo do estado desejado")
                        StateStore.forget(saved)
                    }
                } else {
                    // Continua desabilitado. Sumiu fisicamente? (cabo removido — §2.4)
                    val presentInSls = (saved.uuid?.let { Displays.findByUuidInSls(it) } != null) ||
                        saved.id in Displays.slsIds() ||
                        Displays.findBySerialInSls(saved.vendor, saved.model, saved.serial) != null
                    val placeholderActive = snap.any { it.isPlaceholder && it.active }
                    if (!presentInSls && !placeholderActive) {
                        onLog("watcher: ${saved.label()} sumiu da lista SLS (cabo removido? — §2.4) — limpando estado")
                        StateStore.forget(saved)
                    }
                }
            }
        } catch (e: Throwable) {
            onLog("watcher: erro no reconcile: ${e.message}")
        }
    }

    private fun restoreEmergency(snap: List<DisplayInfo>, state: PocState) {
        onLog("watcher: ZERO displays ativos reais — restauração de emergência (restoreIfNoActiveDisplay)")
        // Preferir o que SALVAMOS como builtin: CGDisplayIsBuiltin responde lixo para
        // IDs stale (§ Fase 1) — o flag persistido é confiável, o consultado não.
        for (saved in state.disabledByUs.sortedByDescending { it.builtin }) {
            if (manager.enable(saved) != null) {
                onLog("watcher: restaurado ${saved.label()}")
                return
            }
        }
        // Não-nossos: tentar direto via lista SLS (sem registrar no nosso estado)
        for (d in snap.filter { it.isDisabled && !it.isPlaceholder }) {
            if (Ops.enableVerified(d.toSaved()) != null) {
                onLog("watcher: restaurado id=${d.id} (via lista SLS)")
                return
            }
        }
        // Último recurso: heurística do Lunar — built-in costuma ter ID 1 no Apple Silicon (§2.2)
        onLog("watcher: último recurso — enable(1) (heurística do Lunar)")
        Ops.enableVerified(SavedDisplay(id = 1))
    }
}
