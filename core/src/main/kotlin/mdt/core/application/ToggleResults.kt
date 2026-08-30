package mdt.core.application

import mdt.core.domain.DisableBlock

/**
 * Resultados tipados das bordas da facade (melhoria 5 da reestruturação):
 * motivos previsíveis viram tipos em vez de `null`/exceção; `DisplayError`
 * fica reservado para falha inesperada de infraestrutura — e mesmo ela chega
 * aqui mapeada em `Failed`, com a mensagem preservada para diagnóstico.
 */
sealed interface DisableResult {
    /**
     * Desligado e comprovado por enumeração.
     * [pendingRevert] presente quando o disable foi agendado com auto-revert.
     */
    data class Disabled(
        val handle: DisplayHandle,
        val pendingRevert: PendingExternalDisable? = null,
    ) : DisableResult

    /** Recusado por regra de produto/segurança — motivo tipado do domínio. */
    data class Blocked(val reason: DisableBlock) : DisableResult

    /** O id não está em nenhuma lista (pública ou SLS). */
    data class NotFound(val displayId: Int) : DisableResult

    /** A transação nativa falhou ou não teve efeito; estado persistido já foi revertido. */
    data class Failed(val message: String) : DisableResult
}

sealed interface EnableResult {
    /** Religado e COMPROVADO POR ENUMERAÇÃO. */
    data class Enabled(val handle: DisplayHandle, val onlineId: Int) : EnableResult

    /**
     * O display não voltou à lista online na janela de verificação com retry —
     * o registro continua no estado (segue "desabilitado por nós" a recuperar);
     * próximo passo é o playbook de emergência.
     */
    data class VerificationTimedOut(val handle: DisplayHandle) : EnableResult

    /** Falha inesperada de infraestrutura ao religar. */
    data class Failed(val handle: DisplayHandle, val message: String) : EnableResult
}
