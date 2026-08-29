plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.jna)
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("mdt.cli.MainKt")
    applicationName = "mdt-poc"
}
