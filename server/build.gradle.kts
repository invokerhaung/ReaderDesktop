plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.nanohttpd)
    implementation(libs.logback.classic)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("io.legado.desktop.server.MainKt")
}

tasks.named<JavaExec>("run") {
    // 让默认的相对路径（data/、frontend/dist/）以仓库根目录为基准
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}
