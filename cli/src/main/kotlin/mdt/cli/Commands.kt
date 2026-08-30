package mdt.cli

import mdt.core.domain.DisplayError
import mdt.core.domain.DisplayInfo
import mdt.core.DisplayManager
import mdt.core.DisplayOperations
import mdt.core.Displays
import mdt.core.StateStore
import mdt.core.Transactions
import mdt.core.application.DefaultExternalDisplayToggleFacade
import mdt.core.application.DisableResult
import mdt.core.application.DisplayConfigurationScope
import mdt.core.application.DisplayHandle
import mdt.core.application.EnableResult
import mdt.core.application.ExternalDisplayToggleFacade
import mdt.core.domain.DisableBlock
import mdt.core.domain.DisplayPolicy
import mdt.core.jna.NativeApis
import mdt.core.jna.cgErrorName
import mdt.core.jna.kCGConfigureForSession
import mdt.core.jna.kCGConfigurePermanently
import java.util.Locale
import kotlin.system.exitProcess

// Fluxos normais passam pela facade; `list` e os experimentos de chamada redundante
// continuam com acesso direto ao core por serem harness técnico de diagnóstico.
private val manager by lazy { DisplayManager { msg -> println("  core: $msg") } }
private val facade: ExternalDisplayToggleFacade by lazy { DefaultExternalDisplayToggleFacade(manager) }

// Só para os experimentos de chamada redundante: chamadas nativas diretas, sem travas do manager.
private val rawOperations by lazy { DisplayOperations { msg -> println("  core: $msg") } }

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
        println("  (IDs desabilitados/stale: builtin/vendor/model/serial podem ser lixo)")
    }
    println()
    val activeReal = snapshot.filter { it.isActiveReal }
    val operableExternal = snapshot.filter(DisplayPolicy::isOperableExternal)
    println("ativos reais (sem placeholder): ${activeReal.size} — a trava recusa disable quando ficaria 0")
    println("monitores externos operáveis: ${operableExternal.size} — comandos destrutivos recusam a tela embutida")
    println("notebook (bateria via IOKit): ${if (manager.isNotebook) "sim" else "não"}")
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
        println("nenhum built-in enumerado (tampa fechada? nos testes destrutivos ela fica ABERTA)")
    } else {
        val confere = builtins.any { it.id == 1 }
        println(
            "built-in com ${builtins.joinToString { "id=${it.id}" }} → " +
                "heurística ID=1 ${if (confere) "CONFERE" else "NÃO confere"}"
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
    ensureExternalTarget(target)
    if (!target.online) throw DisplayError("display ${target.id} não está na lista online (já desabilitado?) — para religar use 'enable'")
    ensureNotLastActiveReal(snapshot, target)

    val scope = if (forSession) DisplayConfigurationScope.SESSION else DisplayConfigurationScope.PERMANENT
    val flagName = if (forSession) "kCGConfigureForSession"
    else "kCGConfigurePermanently (padrão)"

    println("== TEST-CYCLE: desabilitar → aguardar ${wait}s → religar ==")
    println("alvo: ${describe(target)}")
    println("flag do Complete no disable: $flagName")
    confirmDestructive(target, yes, watchdogSecs = wait + 45)

    val t0 = System.currentTimeMillis()
    val handle = facade.disableExternal(target.id, scope).orThrow() // regras + identidade em disco no núcleo
    facade.armShutdownRecovery(handle)
    countdown(wait, "religando em")
    println("religando…")
    val result = facade.enableExternal(handle)
    val dt = (System.currentTimeMillis() - t0) / 1000.0

    if (result is EnableResult.Enabled) {
        facade.disarmShutdownRecovery()
        val idBack = result.onlineId
        println()
        println("== RESULTADO: SUCESSO em ${"%.1f".format(Locale.ROOT, dt)}s — religado e COMPROVADO POR ENUMERAÇÃO (id online: $idBack) ==")
        println("  - flag usado: $flagName")
        println("  - ID pós-religamento: $idBack ${if (idBack != target.id) "(MUDOU — antes ${target.id})" else "(inalterado)"}")
    } else {
        if (result is EnableResult.Failed) println("  erro de infraestrutura: ${result.message}")
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
    ensureExternalTarget(target)

    if (!target.online) {
        if (!allowRedundant) {
            throw DisplayError(
                "display ${target.id} já está fora da lista online — no-op recusado. " +
                    "Para o experimento do disable redundante use --allow-redundant."
            )
        }
        println("DISABLE REDUNDANTE em display já desabilitado (id=${target.id})")
        val flag = if (forSession) kCGConfigureForSession else kCGConfigurePermanently
        val err = rawOperations.awaitTransaction(Transactions.fire(target.id, false, flag), 15)
        println("  retorno da transação: ${err?.let(::cgErrorName) ?: "ainda em voo após 15 s"}")
        return
    }

    val failsafe = failsafeOpt
        ?: throw DisplayError("--failsafe <segundos> é OBRIGATÓRIO no disable (protocolo de segurança), ex.: --failsafe 600")
    if (failsafe < 10) throw DisplayError("--failsafe mínimo: 10 s")
    ensureNotLastActiveReal(snapshot, target)

    val scope = if (forSession) DisplayConfigurationScope.SESSION else DisplayConfigurationScope.PERMANENT

    println("== DISABLE com failsafe interno de ${failsafe}s ==")
    println("alvo: ${describe(target)}")
    confirmDestructive(target, yes, watchdogSecs = failsafe + 60)

    val handle = facade.disableExternal(target.id, scope).orThrow()
    facade.armShutdownRecovery(handle)
    println("desabilitado. Para religar antes do failsafe: outro terminal → 'macdisplaytoggle enable ${handle.uuid ?: handle.id}' · ou Ctrl+C aqui.")

    if (waitFailsafe(handle, failsafe)) {
        facade.forgetManagedExternal(handle)
        facade.disarmShutdownRecovery()
        println("encerrando: o display voltou a ficar online fora deste processo.")
        return
    }

    println("failsafe disparou — religando…")
    when (val r = facade.enableExternal(handle)) {
        is EnableResult.Enabled -> {
            facade.disarmShutdownRecovery()
            println("religado (id=${r.onlineId}) — comprovado por enumeração.")
        }
        is EnableResult.VerificationTimedOut -> {
            println("FALHA: o failsafe não conseguiu religar.")
            printEmergency()
            exitProcess(1)
        }
        is EnableResult.Failed -> {
            println("FALHA: o failsafe não conseguiu religar (${r.message}).")
            printEmergency()
            exitProcess(1)
        }
    }
}

// ---------------------------------------------------------------- enable

fun cmdEnable(rawArgs: List<String>) {
    val a = Args(rawArgs)
    val allowRedundant = a.flag("--allow-redundant")
    val targetArg = a.positional()
    a.finish()

    val managed = facade.managedExternalDisplays()
    val handle: DisplayHandle = when {
        targetArg != null ->
            facade.findManagedExternalDisplay(targetArg) ?: facade.recoveryExternalDisplay(targetArg)
        managed.size == 1 -> managed[0]
        managed.isEmpty() -> throw DisplayError("nenhum display salvo como desabilitado — informe <id|uuid>")
        else -> throw DisplayError(
            "mais de um display salvo — informe <id|uuid>:\n" +
                managed.joinToString("\n") { "  - ${it.label}" }
        )
    }

    val onlineNow = facade.onlineId(handle)
    if (onlineNow != null) {
        if (!allowRedundant) {
            println("display já está online (id=$onlineNow) — nada a fazer (no-op recusado).")
            facade.forgetManagedExternal(handle) // estado obsoleto: religado por fora
            return
        }
        println("ENABLE REDUNDANTE em display já online (id=$onlineNow)")
        val err = rawOperations.awaitTransaction(Transactions.fire(onlineNow, true, kCGConfigurePermanently), 15)
        println("  retorno da transação: ${err?.let(::cgErrorName) ?: "ainda em voo após 15 s"}")
        println("  (comportamento conhecido: aborta com 1001, sem efeito visual)")
        return
    }

    println("religando ${handle.label}…")
    when (val r = facade.enableExternal(handle)) {
        is EnableResult.Enabled -> {} // o núcleo já logou "religado … comprovado por enumeração"
        is EnableResult.VerificationTimedOut -> {
            println("FALHA: o display não voltou à lista online.")
            printEmergency()
            exitProcess(1)
        }
        is EnableResult.Failed -> {
            println("FALHA: ${r.message}")
            printEmergency()
            exitProcess(1)
        }
    }
}

// ---------------------------------------------------------------- reconcile

fun cmdReconcile(rawArgs: List<String>) {
    val a = Args(rawArgs)
    val auto = a.flag("--auto")
    a.finish()

    val r = facade.reconcileAtLaunch(autoEnableOrphans = auto)
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

// ---------------------------------------------------------------- watch

fun cmdWatch(rawArgs: List<String>) {
    val a = Args(rawArgs)
    val poll = a.flag("--poll")
    a.finish()

    println("== WATCHER ==")
    println("notebook (bateria via IOKit): ${if (facade.snapshot().isNotebook) "sim" else "não"}")
    // Reconciliar SEMPRE antes de armar o watcher — nunca re-aplicar no launch
    facade.reconcileAtLaunch(autoEnableOrphans = false)
    val watcher = facade.startWatcher(pollOnly = poll)
    println("watcher rodando — Ctrl+C para sair; eventos e ações do núcleo aparecem abaixo")
    // CFRunLoop na MAIN (onde o CG inicializou) — exigência validada em máquina real:
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

/**
 * A CLI pré-valida os alvos antes do prompt, então Blocked/NotFound aqui são
 * corrida (snapshot mudou entre a checagem e a transação) — viram o mesmo
 * `DisplayError` que o harness sempre tratou no main.
 */
private fun DisableResult.orThrow(): DisplayHandle = when (this) {
    is DisableResult.Disabled -> handle
    is DisableResult.Blocked -> throw DisplayError(
        when (reason) {
            DisableBlock.PLACEHOLDER -> "o alvo é o display placeholder do macOS, não um display real"
            DisableBlock.BUILTIN -> "a tela embutida não pode ser desabilitada — o app desabilita apenas monitores externos"
            DisableBlock.ALREADY_DISABLED -> "no-op recusado: o display já está desabilitado"
            DisableBlock.LAST_ACTIVE_REAL ->
                "TRAVA DE SEGURANÇA: é o último display ativo real — desabilitá-lo apagaria/dormiria a máquina"
        }
    )
    is DisableResult.NotFound -> throw DisplayError("display $displayId não encontrado em nenhuma lista")
    is DisableResult.Failed -> throw DisplayError(message)
}

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

/** Pré-checagem para a UX (recusar ANTES do prompt); a trava canônica vive no núcleo. */
private fun ensureNotLastActiveReal(snapshot: List<DisplayInfo>, target: DisplayInfo) {
    val activeReal = snapshot.filter { it.isActiveReal }
    val remaining = DisplayPolicy.remainingActiveRealAfterDisable(snapshot, target)
    if (target.isActiveReal && remaining < 1) {
        throw DisplayError(
            "TRAVA DE SEGURANÇA: id=${target.id} é o último display ativo real (${activeReal.size} ativo). " +
                "Desabilitá-lo apagaria/dormiria a máquina. Sem override — " +
                "abra a tampa do MacBook para ter um segundo display ativo."
        )
    }
}

/** Regra de produto: comandos destrutivos operam somente monitores externos. */
private fun ensureExternalTarget(target: DisplayInfo) {
    if (target.builtin) {
        throw DisplayError(
            "a tela embutida não é um alvo operável — o app desabilita apenas monitores externos"
        )
    }
    if (!DisplayPolicy.isOperableExternal(target)) {
        throw DisplayError("o alvo não é um monitor externo operável — confira com 'list'")
    }
}

private fun confirmDestructive(target: DisplayInfo, yes: Boolean, watchdogSecs: Int) {
    println()
    println("PRÉ-CONDIÇÕES do protocolo:")
    println("  [ ] Tampa do MacBook ABERTA (built-in ativo como tela de recuperação)")
    println("  [ ] Watchdog EXTERNO armado em outro terminal:")
    println("        ./scripts/watchdog.sh ${target.uuid ?: Integer.toUnsignedString(target.id)} $watchdogSecs &")
    println("  [ ] SSH habilitado e testado (via de recuperação remota)")
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
        "RECUPERAÇÃO DE EMERGÊNCIA: abrir a tampa do MacBook → 'macdisplaytoggle enable' · " +
            "sudo killall -HUP WindowServer · reboot · replug do cabo"
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
 * macOS). [mdt.core.ListFreshness] mantém as listas frescas durante a
 * espera (sem isso o processo fica cego a mudanças externas).
 * @return true se o display voltou a ficar online por fora.
 */
private fun waitFailsafe(handle: DisplayHandle, seconds: Int): Boolean {
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
        facade.onlineId(handle)?.let { id ->
            println("  display voltou a ficar online (id=$id) — religado por outro processo ou pelo próprio macOS (wake)")
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
