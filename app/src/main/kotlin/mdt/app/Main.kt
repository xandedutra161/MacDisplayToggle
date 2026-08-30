package mdt.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mdt.core.application.DefaultExternalDisplayToggleFacade
import mdt.core.DisplayManager
import java.awt.BasicStroke
import java.awt.Color
import java.awt.EventQueue
import java.awt.Image
import java.awt.MouseInfo
import java.awt.Point
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage

fun main() {
    // Sem ícone no Dock durante o dev (o pacote .app fixa via LSUIElement no Info.plist).
    // Precisa ser definido ANTES de qualquer inicialização do AWT.
    System.setProperty("apple.awt.UIElement", "true")

    val manager = DisplayManager { println("core: $it") }
    val facade = DefaultExternalDisplayToggleFacade(manager)
    // Ordem obrigatória: reconciliar ANTES de armar o watcher
    val launchReport = facade.reconcileAtLaunch(autoEnableOrphans = false)
    val watcher = facade.startWatcher()
    // O runloop do AppKit (que o AWT roda na main) pode
    // entregar eventos externos ao callback; senão o polling de 3 s cobre
    watcher.registerCallbackOnly()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = AppState(facade, scope)
    if (launchReport.orphansDetected.isNotEmpty()) {
        state.lastMessage =
            "${launchReport.orphansDetected.size} monitor(es) externo(s) desligado(s) por sessão anterior — use \"Religar todos\""
    }
    state.refresh()

    application {
        var popupVisible by remember { mutableStateOf(launchReport.orphansDetected.isNotEmpty()) }
        var anchorX by remember { mutableStateOf(600) }

        // Janela âncora INVISÍVEL e permanente: sem nenhuma janela na composição o
        // application{} encerra — o tray AWT cru não conta.
        Window(
            onCloseRequest = {},
            visible = false,
            undecorated = true,
            title = "MacDisplayToggle",
        ) {}

        DisposableEffect(Unit) {
            println("app: UI pronta — ícone na barra de menu (clique para abrir o popup)")
            onDispose {}
        }

        DisposableEffect(Unit) {
            val tray = setupTray { click ->
                anchorX = click.x
                popupVisible = !popupVisible
                if (popupVisible) state.refresh()
            }
            onDispose { tray?.let { SystemTray.getSystemTray().remove(it) } }
        }

        if (popupVisible) {
            val width = 380
            val posX = (anchorX - width / 2).coerceAtLeast(8)
            Window(
                onCloseRequest = { popupVisible = false },
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true,
                state = rememberWindowState(
                    position = WindowPosition.Absolute(posX.dp, 30.dp),
                    size = DpSize(width.dp, 480.dp),
                ),
                title = "MacDisplayToggle",
            ) {
                DisposableEffect(Unit) {
                    val listener = object : WindowFocusListener {
                        override fun windowGainedFocus(e: WindowEvent) {}
                        override fun windowLostFocus(e: WindowEvent) {
                            popupVisible = false
                        }
                    }
                    window.addWindowFocusListener(listener)
                    onDispose { window.removeWindowFocusListener(listener) }
                }
                PopupUi(
                    state = state,
                    onQuit = {
                        state.busy = true
                        state.lastMessage = "encerrando: religando os monitores externos que NÓS desligamos…"
                        scope.launch {
                            runCatching {
                                watcher.stop()
                                facade.releaseOnShutdown() // só religa o que o próprio app desligou
                            }
                            EventQueue.invokeLater { exitApplication() }
                        }
                    },
                )
            }
        }
    }
}

private fun setupTray(onClick: (Point) -> Unit): TrayIcon? {
    if (!SystemTray.isSupported()) {
        System.err.println("SystemTray não suportado neste ambiente")
        return null
    }
    val icon = TrayIcon(trayImage(), "MacDisplayToggle")
    icon.isImageAutoSize = true
    icon.addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            // O AWT não expõe a posição do ícone no macOS — ancorar no clique
            onClick(MouseInfo.getPointerInfo()?.location ?: Point(600, 0))
        }
    })
    SystemTray.getSystemTray().add(icon)
    return icon
}

private fun trayImage(): Image {
    val dark = runCatching {
        val p = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").start()
        p.waitFor()
        p.inputStream.bufferedReader().readText().contains("Dark")
    }.getOrDefault(false)
    val color = if (dark) Color(0xE8, 0xE8, 0xE8) else Color(0x1C, 0x1C, 0x1C)
    return BaseMultiResolutionImage(drawMonitorGlyph(18, color), drawMonitorGlyph(36, color))
}

private fun drawMonitorGlyph(size: Int, color: Color): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = color
    val s = size / 18f
    g.stroke = BasicStroke(1.6f * s)
    g.drawRoundRect((2 * s).toInt(), (3 * s).toInt(), (14 * s).toInt(), (9 * s).toInt(), (3 * s).toInt(), (3 * s).toInt())
    g.drawLine((9 * s).toInt(), (12 * s).toInt(), (9 * s).toInt(), (14 * s).toInt())
    g.drawLine((6 * s).toInt(), (15 * s).toInt(), (12 * s).toInt(), (15 * s).toInt())
    g.dispose()
    return img
}
