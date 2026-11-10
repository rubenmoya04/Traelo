plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // En Android org.json ya viene en el sistema; aqui solo se compila contra el y se usa en los tests.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed", "passed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Benchmark de sincronizacion con red simulada: ./gradlew :core:benchmark
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Mide la sincronizacion con un repo sintetico de 3000 archivos y red simulada."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.rubenmoya.traelo.core.bench.SyncBenchmarkKt")
    maxHeapSize = "1g"
    (project.findProperty("benchArgs") as String?)?.let { args(it.split(" ")) }
}
