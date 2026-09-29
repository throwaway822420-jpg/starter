import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Windows (and Linux/macOS) desktop app: the shared simulation in a Swing window.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20240303")
    implementation("com.formdev:flatlaf:3.6")
    // Win32 calls for the live-wallpaper mode (unused on other systems)
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.hydrophobiccollapse.desktop.MainKt")
    applicationName = "HydrophobicCollapse"
    applicationDefaultJvmArgs = listOf("-Dsun.awt.noerasebackground=true", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=4")
}
