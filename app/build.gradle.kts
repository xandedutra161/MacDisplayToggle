import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// UI de barra de menu — Tray AWT + popup Compose sem decoração.
// Empacotamento .app via jpackage (plugin Compose), LSUIElement, ícone.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "mdt.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "MacDisplayToggle"
            packageVersion = "1.0.0"
            description = "Desabilita e religa monitores EXTERNOS de verdade (disconnect real)"
            vendor = "MacDisplayToggle (projeto de estudo KMP)"

            macOS {
                bundleID = "dev.macdisplaytoggle.app"
                iconFile.set(project.file("icons/MacDisplayToggle.icns"))
                // Sem ícone no Dock / sem janela principal.
                // Assinatura: ad-hoc do próprio jpackage (uso pessoal; sem sandbox,
                // API privada ⇒ fora da App Store).
                infoPlist {
                    extraKeysRawXml = """
                        <key>LSUIElement</key>
                        <true/>
                    """.trimIndent()
                }
            }
        }
    }
}
