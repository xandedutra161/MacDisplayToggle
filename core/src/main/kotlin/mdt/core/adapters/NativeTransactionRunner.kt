package mdt.core.adapters

import java.util.concurrent.CompletableFuture
import mdt.core.Transactions
import mdt.core.ports.TransactionRunner

object NativeTransactionRunner : TransactionRunner {
    override val inFlight: Boolean get() = Transactions.inFlight

    override fun fireIfIdle(displayId: Int, enabled: Boolean, flag: Int): CompletableFuture<Int>? =
        Transactions.fireIfIdle(displayId, enabled, flag)
}
