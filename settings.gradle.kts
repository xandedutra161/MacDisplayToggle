pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "MacDisplayToggle"

// Layout preparado para as fases do PLANO_DE_DESENVOLVIMENTO.md:
// :cli  — Fase 0 (PoC de validação, CLI sem UI)
// :core — Fase 1 (DisplayManager como biblioteca)
// :app  — Fase 2 (UI de barra de menu em Compose)
include(":core", ":cli", ":app")
