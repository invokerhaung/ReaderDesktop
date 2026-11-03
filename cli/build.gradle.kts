plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.gson)
    implementation(libs.logback.classic)
}

application {
    mainClass.set("io.legado.desktop.cli.MainKt")
}
