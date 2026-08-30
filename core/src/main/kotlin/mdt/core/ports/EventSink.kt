package mdt.core.ports

/**
 * Canal único de logs do núcleo. UI/CLI injetam o destino (console, painel,
 * arquivo); testes injetam um coletor. Nada no core deve chamar `println`
 * diretamente fora dos defaults abaixo — exceção registrada: o shutdown hook do
 * `PanicGuard` escreve em stderr por ser o último recurso de um processo morrendo.
 */
fun interface EventSink {
    fun log(message: String)

    companion object {
        /** Default de compatibilidade: stdout, como os `println` históricos. */
        val Stdout: EventSink = EventSink { println(it) }

        /** Para avisos de infraestrutura (ex.: estado ilegível) quando ninguém injeta destino. */
        val Stderr: EventSink = EventSink { System.err.println(it) }
    }
}
