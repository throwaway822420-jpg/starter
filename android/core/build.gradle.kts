import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything shared by the Android and Windows apps: the physics, the proteins, structure files,
// and the simulation with its renderer (drawn through the small gfx layer each platform implements).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Android ships org.json; the desktop app bundles it
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
