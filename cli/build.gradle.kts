plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("mdt.cli.MainKt")
    applicationName = "macdisplaytoggle"
}
