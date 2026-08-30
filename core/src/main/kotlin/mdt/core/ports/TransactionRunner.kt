package mdt.core.ports

import java.util.concurrent.CompletableFuture
import mdt.core.domain.DisplayError

interface TransactionRunner {
    val inFlight: Boolean

    fun fireIfIdle(displayId: Int, enabled: Boolean, flag: Int): CompletableFuture<Int>?

    fun fire(displayId: Int, enabled: Boolean, flag: Int): CompletableFuture<Int> =
        fireIfIdle(displayId, enabled, flag)
            ?: throw DisplayError("transação de configuração anterior ainda em voo")
}
