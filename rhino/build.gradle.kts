plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.rhino)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okio)
}
