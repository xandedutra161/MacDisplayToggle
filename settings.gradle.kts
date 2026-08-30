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

// :core — regras de segurança, portas/adapters e bindings nativos
// :cli  — harness técnico e ferramenta de recuperação
// :app  — barra de menu e popup em Compose Desktop
include(":core", ":cli", ":app")
