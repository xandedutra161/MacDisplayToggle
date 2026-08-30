package mdt.core.ports

/** Recebe eventos de reconfiguração: (displayId, flags de CGDisplayChangeSummaryFlags). */
fun interface DisplayEventListener {
    fun onDisplayEvent(displayId: Int, flags: Int)
}

/**
 * Fonte de eventos de reconfiguração de displays. A implementação nativa registra
 * `CGDisplayRegisterReconfigurationCallback`; fakes de teste emitem eventos direto.
 * O `Watcher` só coordena fila/settle/polling — a mecânica de callback/runloop vive
 * atrás desta porta.
 */
interface DisplayEventSource {
    /**
     * Registra o listener. O listener deve apenas ENFILEIRAR — nada de transação
     * nem trabalho pesado dentro dele.
     * @return true se registrou (eventos chegarão); false se a fonte não está
     * disponível (o chamador cai para polling).
     */
    fun register(listener: DisplayEventListener): Boolean

    /** Remove o listener registrado (idempotente). */
    fun unregister()

    /**
     * Entrega eventos BLOQUEANDO a thread atual (runloop nativo). Só retorna quando
     * o loop parar — por [stopDelivering] ou por falta de fontes de runloop.
     * @return quanto tempo rodou, em ms (diagnóstico: JVM sem AppKit retorna
     * imediatamente por falta de fontes de runloop).
     */
    fun deliverBlocking(): Long

    /** Interrompe [deliverBlocking] a partir de outra thread (idempotente). */
    fun stopDelivering()
}
