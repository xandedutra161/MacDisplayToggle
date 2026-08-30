package mdt.core

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mdt.core.application.DisplayReconciler
import mdt.core.ports.EventSink
import mdt.core.testing.FakeDisplayEventSource
import mdt.core.testing.FakeTransactionRunner
import mdt.core.testing.InMemoryDisplayStateRepository
import mdt.core.testing.MutableDisplayGateway
import mdt.core.testing.display

/**
 * Coordenação do watcher (fila/settle/polling) com fonte de eventos fake.
 * A regra de reconciliação em si é coberta por [mdt.core.application.DisplayReconcilerTest];
 * aqui cada reconcile é observado pelo contador de snapshots do gateway.
 */
class WatcherTest {

    private class Harness(registerResult: Boolean = true) {
        val gateway = MutableDisplayGateway(
            mutableListOf(
                display(id = 1, builtin = true),
                display(id = 3),
            ),
        )
        val repository = InMemoryDisplayStateRepository()
        val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val sink = EventSink { logs.add(it) }
        val source = FakeDisplayEventSource(registerResult)

        fun watcher(pollOnly: Boolean = false, settleMs: Long = 250, pollIntervalMs: Long = 60_000): Watcher =
            Watcher(
                reconciler = DisplayReconciler(
                    displayGateway = gateway,
                    stateRepository = repository,
                    displayOperations = DisplayOperations(gateway, FakeTransactionRunner(), sink),
                    enableManagedDisplay = { null },
                    onLog = sink,
                ),
                eventSource = source,
                onLog = sink,
                pollOnly = pollOnly,
                settleMs = settleMs,
                pollIntervalMs = pollIntervalMs,
            )
    }

    private fun awaitTrue(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(condition(), "condição não satisfeita em ${timeoutMs}ms")
    }

    @Test
    fun `tempestade de eventos agrupa em um unico reconcile apos o settle`() {
        val h = Harness()
        val watcher = h.watcher()
        try {
            watcher.start()
            watcher.registerCallbackOnly()
            h.source.emit(3, flags = 0x20) // Remove
            h.source.emit(3, flags = 0x200) // Disabled
            h.source.emit(3, flags = 0x1000) // DesktopShapeChanged

            awaitTrue { h.gateway.snapshotCalls == 1 }
            Thread.sleep(400) // nenhuma rodada extra deve acontecer sem novos eventos
            assertEquals(1, h.gateway.snapshotCalls, "a tempestade deveria virar UM reconcile")
            assertEquals(3, h.logs.count { it.startsWith("watcher: evento display=3") })
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun `sem eventos o tick periodico reconcilia`() {
        val h = Harness()
        val watcher = h.watcher(settleMs = 50, pollIntervalMs = 100)
        try {
            watcher.start()
            awaitTrue { h.gateway.snapshotCalls >= 2 }
            assertTrue(h.logs.none { it.startsWith("watcher: evento") })
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun `falha de registro cai para polling com aviso`() {
        val h = Harness(registerResult = false)
        val watcher = h.watcher(settleMs = 50, pollIntervalMs = 100)
        try {
            watcher.start()
            watcher.registerCallbackOnly()
            assertNull(h.source.listener)
            assertTrue(h.logs.any { it.contains("registro do callback falhou — só polling") })
            awaitTrue { h.gateway.snapshotCalls >= 1 } // polling segue cobrindo
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun `stop desregistra a fonte e encerra o worker`() {
        val h = Harness()
        val watcher = h.watcher(settleMs = 50, pollIntervalMs = 100)
        watcher.start()
        watcher.registerCallbackOnly()
        awaitTrue { h.gateway.snapshotCalls >= 1 }

        watcher.stop()
        assertTrue(h.source.unregistered)
        assertTrue(h.source.deliveryStopped)
        Thread.sleep(150) // um tick pode já estar em voo no instante do stop
        val after = h.gateway.snapshotCalls
        Thread.sleep(300)
        assertEquals(after, h.gateway.snapshotCalls, "worker deveria parar de reconciliar após stop")
    }

    @Test
    fun `runLoop sem fontes retorna e opera por polling com callback registrado`() {
        val h = Harness()
        val watcher = h.watcher(settleMs = 50, pollIntervalMs = 100)
        try {
            watcher.start()
            val ranWithCallbacks = watcher.runLoopBlocking() // fake retorna em 0 ms (JVM sem AppKit)
            assertFalse(ranWithCallbacks)
            assertTrue(h.source.listener != null, "callback deve ficar REGISTRADO (receita ListFreshness)")
            assertTrue(h.logs.any { it.contains("retornou em 0ms") })

            h.source.emit(3) // eventos continuam fluindo pela fila
            awaitTrue { h.logs.any { it.startsWith("watcher: evento display=3") } }
        } finally {
            watcher.stop()
        }
    }

    @Test
    fun `pollOnly nao registra callback`() {
        val h = Harness()
        val watcher = h.watcher(pollOnly = true, settleMs = 50, pollIntervalMs = 100)
        try {
            watcher.start()
            watcher.registerCallbackOnly()
            assertFalse(watcher.runLoopBlocking())
            assertNull(h.source.listener)
            assertTrue(h.logs.any { it.contains("modo polling forçado") })
        } finally {
            watcher.stop()
        }
    }
}
