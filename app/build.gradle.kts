// Fase 2: UI de barra de menu — Tray AWT + popup Compose sem decoração (PLANO §3).
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
        // Empacotamento (.app via jpackage, LSUIElement no Info.plist) é a Fase 3;
        // no dev, o Dock é escondido via -Dapple.awt.UIElement=true no Main.kt
    }
}
