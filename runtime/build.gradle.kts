plugins {
    java
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "runtime"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.kafka:spring-kafka")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Lombok
    implementation("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // GraalVM JavaScript — движок для onChange/Script (компилятор простых if/else скриптов)
    implementation("org.graalvm.polyglot:polyglot:24.1.0")
    implementation("org.graalvm.polyglot:js:24.1.0")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.14")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Отключаем «плоский» jar: сервис деплоится как исполняемый bootJar и как
// библиотека никуда не подключается. Заодно в build/libs остаётся один jar,
// поэтому COPY build/libs/*.jar в Dockerfile не ломается.
tasks.named("jar") {
    enabled = false
}
