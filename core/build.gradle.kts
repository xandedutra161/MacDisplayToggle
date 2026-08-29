// Fase 1: DisplayManager como biblioteca — regras de segurança no núcleo (PLANO §4).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // api: os tipos do JNA (Function em NativeApis) e @Serializable (SavedDisplay)
    // fazem parte da superfície pública consumida por :cli e, na Fase 2, por :app
    api(libs.jna)
    api(libs.kotlinx.serialization.json)
}
