plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Lógica pura del DJ: sin Android, se testea en la JVM en milisegundos.
kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
