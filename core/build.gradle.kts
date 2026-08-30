// Núcleo: regras de segurança, portas/adapters e bindings nativos.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // api: os tipos do JNA (Function em NativeApis) e @Serializable (SavedDisplay)
    // fazem parte da superfície pública consumida por :cli e por :app
    api(libs.jna)
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
