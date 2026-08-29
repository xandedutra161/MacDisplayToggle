package mdt.cli

import mdt.core.DisplayError
import mdt.core.DisplayInfo
import mdt.core.DisplayManager
import mdt.core.Displays
import mdt.core.Ops
import mdt.core.PanicGuard
import mdt.core.PocState
import mdt.core.SavedDisplay
import mdt.core.StateStore
import mdt.core.Transactions
import mdt.core.ffi.NativeApis
import mdt.core.ffi.cgErrorName
import mdt.core.ffi.kCGConfigureForSession
import mdt.core.ffi.kCGConfigurePermanently
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.system.exitProcess

private val manager by lazy { DisplayManager { msg -> println("  core: $msg") } }

// ---------------------------------------------------------------- list

fun cmdList() {
    val snapshot = Displays.snapshot()
    println("MacDisplayToggle — displays (lista pública × lista SLS)")
    println()
    val fmt = "%-11s %-17s %-8s %-19s %-19s %-11s %s"
    println(String.format(fmt, "ID", "ESTADO", "BUILTIN", "VENDOR", "MODEL", "SERIAL", "UUID"))
    for (d in snapshot) {
        println(
            String.format(
                fmt,
                Integer.toUnsignedString(d.id),
                stateLabel(d),
                if (d.builtin) "sim" else "-",
                hex(d.vendor) + fourcc(d.vendor),
                hex(d.model) + fourcc(d.model),
                hex(d.serial),
                d.uuid ?: "?",
            )
        )
    }
    if (snapshot.any { it.isDisabled }) {
        println("  (IDs desabilitados/stale: builtin/vendor/model/serial podem ser lixo — PLANO §2.3)")
    }
    println()
    val activeReal = snapshot.filter { it.isActiveReal }
    println("ativos reais (sem placeholder): ${activeReal.size} — a trava recusa disable quando ficaria 0")
    println("notebook (bateria via IOKit): ${if (manager.isNotebook) "sim" else "não"} (§2.3 item 6)")
    println("lista pública online: ${Displays.onlineIds()}")
    println("lista pública ativa : ${Displays.activeIds()}")
    println("lista SLS (privada) : ${Displays.slsIds()}")
    println()
    val state = StateStore.load()
    if (state.disabledByUs.isEmpty()) {
        println("estado persistido (${StateStore.path}): vazio")
    } else {
        println("estado persistido (${StateStore.path}):")
        state.disabledByUs.forEach {
            println("  - ${it.label()} serial=${hex(it.serial)} salvo em ${it.savedAt} — religue com 'enable'")
        }
    }
    println()
    val builtins = snapshot.filter { it.builtin && !it.isPlaceholder }
    if (builtins.isEmpty()) {
        println("observação §2.2(c): nenhum built-in enumerado (tampa fechada? nos testes destrutivos ela fica ABERTA)")
    } else {
        val confere = builtins.any { it.id == 1 }
        println(
            "observação §2.2(c): built-in com ${builtins.joinToString { "id=${it.id}" }} → " +
                "heurística do Lunar (ID=1) ${if (confere) "CONFERE" else "NÃO confere"}"
        )
    }
    println()
    println("símbolos resolvidos:")
    println("  ${NativeApis.slsGetDisplayList}")
    println("  ${NativeApis.configureDisplayEnabled}")
    println("  ${NativeApis.createUuidFromDisplayId}")
}

// ---------------------------------------------------------------- test-cycle

fun cmdTestCycle(rawArgs: List<String>) {
    val a = Args(rawArgs)
    val forSession = a.flag("--for-session")
    val yes = a.flag("--yes")
    val wait = (a.option("--wait") ?: "15").toIntOrNull()?.takeIf { it in 3..3600 }
        ?: throw DisplayError("--wait inválido (3–3600 s)")
    val targetArg = a.positional() ?: throw DisplayError("informe <id|uuid> — descubra com 'list'")
    a.finish()

    val snapshot = Displays.snapshot()
    val target = resolveInSnapshot(targetArg, snapshot)
    if (target.isPlaceholder) throw DisplayError("o alvo é o display placeholder do macOS, não um display real")
    if (!target.online) throw DisplayError("display ${target.id} não está na lista online (já desabilitado?) — para religar use 'enable'")
    ensureNotLastActiveReal(snapshot, target)

    val flag = if (forSession) kCGConfigureForSession else kCGConfigurePermanently
    val flagName = if (forSession) "kCGConfigureForSession (experimento §2.1)"
    else "kCGConfigurePermanently (padrão fixado na Fase 0)"

    println("== TEST-CYCLE: desabilitar → aguardar ${wait}s → religar ==")
    println("alvo: ${describe(target)}")
    println("flag do Complete no disable: $flagName")
    confirmDestructive(target.toSaved(), yes, watchdogSecs = wait + 45)

    val t0 = System.currentTimeMillis()
    val saved = manager.disable(target.id, flag) // regras + identidade em disco no núcleo (Fase 1)
    PanicGuard.arm(saved)
    countdown(wait, "religando em")
    println("religando…")
    val idBack = manager.enable(saved)
    val dt = (System.currentTimeMillis() - t0) / 1000.0

    if (idBack != null) {
        PanicGuard.disarm()
        println()
        println("== RESULTADO: SUCESSO em ${"%.1f".format(Locale.ROOT, dt)}s — religado e COMPROVADO POR ENUMERAÇÃO (id online: $idBack) ==")
        println("  - flag usado: $flagName")
        println("  - ID pós-religamento: $idBack ${if (idBack != target.id) "(MUDOU — antes ${target.id})" else "(inalterado)"}")
    } else {
        println()
        println("== RESULTADO: FALHA — o display NÃO voltou à lista online ==")
        printEmergency()
        println("(ao sair, o failsafe de encerramento fará mais uma rodada de tentativas)")
        exitProcess(1)
    }
}

// ---------------------------------------------------------------- disable

fun cmdDisable(rawArgs: List<String>) {
    val a = Args(rawArgs)
    val forSession = a.flag("--for-session")
    val yes = a.flag("--yes")
    val allowRedundant = a.flag("--allow-redundant")
    val failsafeOpt = a.option("--failsafe")?.let {
        it.toIntOrNull() ?: throw DisplayError("--failsafe inválido: $it")
    }
    val targetArg = a.positional() ?: throw DisplayError("informe <id|uuid> — descubra com 'list'")
    a.finish()

    val snapshot = Displays.snapshot()
    val target = resolveInSnapshot(targetArg, snapshot)
    if (target.isPlaceholder) throw DisplayError("o alvo é o display placeholder do macOS, não um display real")
    val flag = if (forSession) kCGConfigureForSession else kCGConfigurePermanently

    if (!target.online) {
        if (!allowRedundant) {
            throw DisplayError(
                "display ${target.id} já está fora da lista online — no-op recusado (§2.2). " +
                    "Para o experimento do disable redundante use --allow-redundant."
            )
        }
        println("experimento §2.2(b): DISABLE REDUNDANTE em display já desabilitado (id=${target.id})")
        val err = Ops.awaitTransaction(Transactions.fire(target.id, false, flag), 15)
        println("  retorno da transação: ${err?.let(::cgErrorName) ?: "ainda em voo após 15 s"}")
        println("  registrar o efeito em OBSERVACOES_FASE0.md")
        return
    }

    val failsafe = failsafeOpt
        ?: throw DisplayError("--failsafe <segundos> é OBRIGATÓRIO no disable (protocolo Fase 0), ex.: --failsafe 600")
    if (failsafe < 10) throw DisplayError("--failsafe mínimo: 10 s")
    ensureNotLastActiveReal(snapshot, target)

    println("== DISABLE com failsafe interno de ${failsafe}s ==")
    println("alvo: ${describe(target)}")
    confirmDestructive(target.toSaved(), yes, watchdogSecs = failsafe + 60)

    val saved = manager.disable(target.id, flag)
    PanicGuard.arm(saved)
    println("desabilitado. Para religar antes do failsafe: outro terminal → 'mdt-poc enable ${saved.uuid ?: saved.id}' · ou Ctrl+C aqui.")

    if (waitFailsafe(saved, failsafe)) {
        StateStore.forget(saved)
        PanicGuard.disarm()
        println("encerrando: o display voltou a ficar online fora deste processo.")
        return
    }

    println("failsafe disparou — religando…")
    val idBack = manager.enable(saved)
    if (idBack != null) {
        PanicGuard.disarm()
        println("religado (id=$idBack) — comprovado por enumeração.")
    } else {
        println("FALHA: o failsafe não conseguiu religar.")
        printEmergency()
        exitProcess(1)
    }
}

// ---------------------------------------------------------------- enable

fun cmdEnable(rawArgs: List<String>) {
    val a = Args(rawArgs)
    val allowRedundant = a.flag("--allow-redundant")
    val targetArg = a.positional()
    a.finish()

    val state = StateStore.load()
    val saved: SavedDisplay = when {
        targetArg != null -> findSaved(state, targetArg) ?: buildSavedFromArg(targetArg)
        state.disabledByUs.size == 1 -> state.disabledByUs[0]
        state.disabledByUs.isEmpty() -> throw DisplayError("nenhum display salvo como desabilitado — informe <id|uuid>")
        else -> throw DisplayError(
            "mais de um display salvo — informe <id|uuid>:\n" +
                state.disabledByUs.joinToString("\n") { "  - ${it.label()}" }
        )
    }

    val onlineNow = Ops.matchOnline(saved)
    if (onlineNow != null) {
        if (!allowRedundant) {
            println("display já está online (id=$onlineNow) — nada a fazer (no-op recusado, §2.2).")
            StateStore.forget(saved) // estado obsoleto: religado por fora
            return
        }
        println("experimento §2.2(b): ENABLE REDUNDANTE em display já online (id=$onlineNow)")
        val err = Ops.awaitTransaction(Transactions.fire(onlineNow, true, kCGConfigurePermanently), 15)
        println("  retorno da transação: ${err?.let(::cgErrorName) ?: "ainda em voo após 15 s"}")
        println("  (Fase 0 já registrou: aborta com 1001, sem efeito visual)")
        return
    }

    println("religando ${saved.label()}…")
    val idBack = manager.enable(saved)
    if (idBack == null) {
        println("FALHA: o display não voltou à lista online.")
        printEmergency()
        exitProcess(1)
    }
}

// ---------------------------------------------------------------- reconcile (Fase 1)

fun cmdReconcile(rawArgs: List<String>) {
    val a = Args(rawArgs)
    val auto = a.flag("--auto")
    a.finish()

    val r = manager.reconcileAtLaunch(autoEnableOrphans = auto)
    println(
        "reconcile: ${r.alreadyOnline.size} removido(s) do estado (já online) · " +
            "${r.orphansDetected.size} órfão(s) detectado(s) · " +
            "${r.orphansEnabled.size} órfão(s) religado(s) · ${r.orphansStuck.size} preso(s)"
    )
    if (r.orphansStuck.isNotEmpty()) {
        printEmergency()
        exitProcess(1)
    }
}

// ---------------------------------------------------------------- watch (Fase 1)

fun cmdWatch(rawArgs: List<String>) {
    val a = Args(rawArgs)
    val poll = a.flag("--poll")
    a.finish()

    println("== WATCHER Fase 1 ==")
    println("notebook (bateria via IOKit): ${if (manager.isNotebook) "sim" else "não"}")
    // Reconciliar SEMPRE antes de armar o watcher (§ Fase 1) — nunca re-aplicar no launch
    manager.reconcileAtLaunch(autoEnableOrphans = false)
    val watcher = manager.startWatcher(pollOnly = poll)
    println("watcher rodando — Ctrl+C para sair; eventos e ações do núcleo aparecem abaixo")
    // CFRunLoop na MAIN (onde o CG inicializou) — exigência descoberta na Fase 1:
    // em thread dedicada o runloop fica sem fontes e o callback nunca dispara
    watcher.runLoopBlocking()
    try {
        while (true) Thread.sleep(60_000) // caiu para polling — worker segue reconciliando
    } catch (_: InterruptedException) {
    } finally {
        watcher.stop()
    }
}

// ---------------------------------------------------------------- helpers

private fun resolveInSnapshot(arg: String, snapshot: List<DisplayInfo>): DisplayInfo {
    val asId = arg.toUIntOrNull()?.toInt()
    if (asId != null) {
        return snapshot.firstOrNull { it.id == asId }
            ?: throw DisplayError("display id $arg não encontrado em nenhuma lista — confira com 'list'")
    }
    val u = arg.uppercase()
    val matches = snapshot.filter { it.uuid?.startsWith(u) == true }
    return when {
        matches.isEmpty() -> throw DisplayError("uuid '$arg' não encontrado — confira com 'list'")
        matches.size > 1 -> throw DisplayError("uuid '$arg' é ambíguo (${matches.size} displays) — use mais caracteres")
        else -> matches[0]
    }
}

private fun findSaved(state: PocState, arg: String): SavedDisplay? {
    val asId = arg.toUIntOrNull()?.toInt()
    if (asId != null) return state.disabledByUs.firstOrNull { it.id == asId }
    val u = arg.uppercase()
    return state.disabledByUs.firstOrNull { it.uuid?.uppercase()?.startsWith(u) == true }
}

private fun buildSavedFromArg(arg: String): SavedDisplay {
    val asId = arg.toUIntOrNull()?.toInt()
    if (asId != null) return SavedDisplay(id = asId, uuid = Displays.uuidOf(asId), savedAt = nowIso())
    val u = arg.uppercase()
    // Displays desabilitados só aparecem na lista SLS (§2.2)
    val matches = Displays.slsIds()
        .mapNotNull { id -> Displays.uuidOf(id)?.let { id to it } }
        .filter { it.second.startsWith(u) }
    return when {
        matches.size == 1 -> SavedDisplay(id = matches[0].first, uuid = matches[0].second, savedAt = nowIso())
        matches.isEmpty() -> throw DisplayError("uuid '$arg' não está nem no estado salvo nem na lista SLS")
        else -> throw DisplayError("uuid '$arg' é ambíguo na lista SLS — use mais caracteres")
    }
}

/** Pré-checagem para a UX (recusar ANTES do prompt); a trava canônica vive no núcleo. */
private fun ensureNotLastActiveReal(snapshot: List<DisplayInfo>, target: DisplayInfo) {
    val activeReal = snapshot.filter { it.isActiveReal }
    val remaining = activeReal.count { it.id != target.id }
    if (target.isActiveReal && remaining < 1) {
        throw DisplayError(
            "TRAVA DE SEGURANÇA: id=${target.id} é o último display ativo real (${activeReal.size} ativo). " +
                "Desabilitá-lo apagaria/dormiria a máquina (§2.3 item 3). Sem override — " +
                "abra a tampa do MacBook para ter um segundo display ativo."
        )
    }
}

private fun confirmDestructive(saved: SavedDisplay, yes: Boolean, watchdogSecs: Int) {
    println()
    println("PRÉ-CONDIÇÕES do protocolo (PLANO §4 — inegociáveis):")
    println("  [ ] Tampa do MacBook ABERTA (built-in ativo como tela de recuperação)")
    println("  [ ] Watchdog EXTERNO armado em outro terminal:")
    println("        ./scripts/watchdog.sh ${saved.uuid ?: Integer.toUnsignedString(saved.id)} $watchdogSecs &")
    println("  [ ] Lunar instalado (religador de emergência) · SSH habilitado e testado")
    printEmergency()
    if (yes) {
        println("(--yes: prosseguindo sem confirmação)")
        return
    }
    print("Digite SIM para prosseguir: ")
    if (readLine()?.trim() != "SIM") throw DisplayError("abortado pelo usuário")
}

private fun printEmergency() {
    println(
        "RECUPERAÇÃO DE EMERGÊNCIA (PLANO §2.4): abrir a tampa do MacBook → 'mdt-poc enable' · " +
            "abrir o Lunar · sudo killall -HUP WindowServer · reboot · replug do cabo"
    )
}

private fun countdown(seconds: Int, label: String) {
    var remaining = seconds
    while (remaining > 0) {
        if (remaining % 5 == 0 || remaining <= 3) println("  $label ${remaining}s…")
        Thread.sleep(1_000)
        remaining--
    }
}

/**
 * Espera o failsafe expirar, detectando religamento externo (outro processo, wake do
 * macOS — §2.3 item 5). [mdt.core.ListFreshness] mantém as listas frescas durante a
 * espera (sem isso o processo fica cego a mudanças externas — Fase 0, descoberta 2).
 * @return true se o display voltou a ficar online por fora.
 */
private fun waitFailsafe(saved: SavedDisplay, seconds: Int): Boolean {
    mdt.core.ListFreshness.ensure()
    val end = System.currentTimeMillis() + seconds * 1_000L
    var lastPrint = 0L
    while (System.currentTimeMillis() < end) {
        val now = System.currentTimeMillis()
        if (now - lastPrint >= 30_000) {
            val rem = ((end - now) / 1000).coerceAtLeast(0)
            println("  failsafe em ${rem}s (religa sozinho; Ctrl+C religa agora)")
            lastPrint = now
        }
        Ops.matchOnline(saved)?.let { id ->
            println("  display voltou a ficar online (id=$id) — religado por outro processo ou pelo próprio macOS (wake — §2.3 item 5)")
            return true
        }
        Thread.sleep(2_000)
    }
    return false
}

private fun describe(d: DisplayInfo): String =
    "id=${Integer.toUnsignedString(d.id)} \"${d.name}\" uuid=${d.uuid ?: "?"} builtin=${if (d.builtin) "sim" else "não"} " +
        "vendor=${hex(d.vendor)}${fourcc(d.vendor)} model=${hex(d.model)}${fourcc(d.model)} " +
        "serial=${hex(d.serial)} estado=${stateLabel(d)}"

private fun stateLabel(d: DisplayInfo): String = when {
    d.isPlaceholder -> "PLACEHOLDER"
    d.isDisabled -> "DESABILITADO"
    d.active -> "ATIVO"
    else -> "ONLINE (inativo)"
}

private fun hex(v: Int): String = "0x" + Integer.toHexString(v).uppercase()

private fun fourcc(v: Int): String {
    val bytes = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    return if (bytes.all { it.toInt() in 0x20..0x7E }) " '${String(bytes, Charsets.US_ASCII)}'" else ""
}

private fun nowIso(): String = OffsetDateTime.now().withNano(0).toString()
