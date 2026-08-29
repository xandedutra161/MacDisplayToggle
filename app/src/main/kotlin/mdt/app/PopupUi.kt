package mdt.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mdt.core.DisplayInfo

@Composable
fun PopupUi(state: AppState, onQuit: () -> Unit) {
    var confirmTarget by remember { mutableStateOf<DisplayInfo?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Ticker do countdown de auto-reversão; quando o núcleo reverte sozinho, limpa e atualiza
    LaunchedEffect(state.pendingRevert) {
        while (state.pendingRevert != null) {
            now = System.currentTimeMillis()
            val p = state.pendingRevert
            if (p != null && now > p.deadlineMs + 2_000) {
                state.pendingRevert = null
                state.refresh()
            }
            delay(400)
        }
    }
    // Atualização periódica enquanto o popup está aberto
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            state.refresh()
        }
    }

    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp).verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MacDisplayToggle",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }

                if (state.clamshellRisk) {
                    // Aviso de cenário clamshell (PLANO §4/Fase 2; §2.3 itens 3/6)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                    ) {
                        Text(
                            "Tampa fechada (clamshell): desabilitar um display agora é arriscado — abra a tampa antes.",
                            Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                state.displays.forEach { d ->
                    DisplayRow(d, state, onAskDisable = { confirmTarget = it })
                }

                state.pendingRevert?.let { p ->
                    val secs = ((p.deadlineMs - now) / 1000).coerceAtLeast(0)
                    Card(Modifier.padding(top = 10.dp).fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Manter desabilitado? Revertendo em ${secs}s…", style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.padding(top = 8.dp)) {
                                Button(onClick = { state.keepDisabled() }) { Text("Manter") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { state.revertNow() }) { Text("Religar agora") }
                            }
                        }
                    }
                }

                state.lastMessage?.let {
                    Text(
                        it,
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Sempre visível (PLANO §4/Fase 2)
                    TextButton(onClick = { state.enableAllOurs() }) { Text("Religar todos") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onQuit) { Text("Sair") }
                }
            }
        }
    }

    confirmTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("Desabilitar \"${d.name}\"?") },
            text = {
                Text(
                    "O display some da configuração do sistema (disconnect real, como o BetterDisplay) " +
                        "e religa sozinho em 20 s se você não confirmar \"Manter\". Failsafes: reversão " +
                        "automática, \"Religar todos\" e religamento ao sair do app." +
                        if (state.clamshellRisk) "\n\n⚠️ Tampa fechada: risco de Clamshell Sleep — abra a tampa antes." else ""
                )
            },
            confirmButton = {
                Button(onClick = {
                    state.requestDisable(d)
                    confirmTarget = null
                }) { Text("Desabilitar") }
            },
            dismissButton = { TextButton(onClick = { confirmTarget = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DisplayRow(d: DisplayInfo, state: AppState, onAskDisable: (DisplayInfo) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    // Tela embutida é intocável (decisão de produto — só religa)
                    d.builtin && !d.isDisabled -> "sempre ativa — o app só desabilita externos"
                    d.isDisabled -> "desabilitado"
                    d.active -> "ativo"
                    else -> "online (inativo)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (d.isDisabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
        Switch(
            checked = !d.isDisabled,
            // embutido: só religável (toggle travado quando já ativo)
            enabled = !state.busy && !(d.builtin && !d.isDisabled),
            onCheckedChange = { wantOn -> if (wantOn) state.enable(d) else onAskDisable(d) },
        )
    }
}
