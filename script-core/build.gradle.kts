plugins {
    `java-library`
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "script-core"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Технический код исполнения скриптов, общий для editor (проверка) и automation (исполнение).
    // Без Spring и без БД — только GraalVM.
    api("org.graalvm.polyglot:polyglot:24.1.0")
    implementation("org.graalvm.polyglot:js:24.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:all")
}
