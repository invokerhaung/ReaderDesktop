plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":rhino"))
    api(libs.kotlinx.coroutines.core)
    api(libs.rhino)
    api(libs.jsoup)
    api(libs.json.path)
    api(libs.okhttp)
    api(libs.gson)
    api(libs.opencc4j)

    implementation(libs.hutool.crypto)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
