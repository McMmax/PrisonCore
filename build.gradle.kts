import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.1.20"
    id("com.gradleup.shadow") version "8.3.0"
}

group = "twizzy.tech"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://repo.hypera.dev/snapshots/") // spark-minestom
    maven("https://repo.lucko.me/") // spark-common
    maven("https://oss.sonatype.org/content/repositories/snapshots/") // spark-common's dependencies
    maven("https://mvn.everbuild.org/public")

    maven("https://repo.panda-lang.org/releases") // lite commands
    maven("https://repo.eternalcode.pl/snapshots") // lite commands
}

dependencies {
    implementation("net.minestom:minestom:2025.07.14-1.21.7")

    implementation("dev.hollowcube:polar:1.14.7")

    implementation("com.github.TogAr2:MinestomPvP:-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.5.0-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-common:1.5.0-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-fluids:1.5.0-SNAPSHOT")

    implementation("dev.rollczi:litecommands-minestom:3.10.0-SNAPSHOT")


//    // Lamp Command Framework
//    implementation("io.github.revxrsal:lamp.common:4.0.0-rc.4")
//    implementation("io.github.revxrsal:lamp.minestom:4.0.0-rc.4")

    // Add Lombok
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.6.4")
    implementation("com.github.shynixn.mccoroutine:mccoroutine-minestom-api:2.22.0")
    implementation("com.github.shynixn.mccoroutine:mccoroutine-minestom-core:2.22.0")

    // Kotlin Reflection
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.20")

    implementation("org.yaml:snakeyaml:2.0")

    // Window Libary for interfaces
    implementation("net.goldenstack:window:1.1")

    implementation("dev.lu15:spark-minestom:1.10-SNAPSHOT")


    // MongoDB Driver (Reactive Streams + Multithreading)
    implementation("org.mongodb:mongodb-driver-reactivestreams:4.10.2")

    // Redis Driver (Lettuce)
    implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")

    // Jasync-SQL for PostgreSQL
    implementation("com.github.jasync-sql:jasync-postgresql:2.2.4")

    implementation("eu.koboo:stomui:1.0.2")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21)) // Minestom has a minimum Java version of 21
    }
}

tasks {
    jar {
        manifest {
            attributes["Main-Class"] = "twizzy.tech.MainKt" // Change this to your main class
        }
    }

    build {
        dependsOn(shadowJar)
    }
    shadowJar {
        mergeServiceFiles()
        archiveClassifier.set("") // Prevent the -all suffix on the shadowjar file.
    }
}
tasks.withType<JavaCompile> {
    // Preserve parameter names in the bytecode
    options.compilerArgs.add("-parameters")
}

// optional: if you're using Kotlin
tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        javaParameters = true
    }
}