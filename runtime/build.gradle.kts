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
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.vladmihalcea:hibernate-types-60:2.21.1")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

    // Lombok
    implementation("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // GraalVM JavaScript — движок для onChange/Script (компилятор простых if/else скриптов)
    implementation("org.graalvm.polyglot:polyglot:24.1.0")
    implementation("org.graalvm.polyglot:js:24.1.0")

    // Общий технический код скриптов и разбор телеметрии (общий с сервисом automation).
    implementation(project(":script-core"))

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.14")

    // jjwt здесь больше не нужен: подпись токена проверяет gateway, в runtime личность
    // приходит заголовком X-Username с проверенного запроса.
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker Desktop на этой машине слушает testcontainers-совместимый API на
    // dockerDesktopLinuxEngine, а не на дефолтном docker_engine — без этого
    // NpipeSocketClientProviderStrategy падает на BadRequestException. См. editor/build.gradle.kts.
    // Второй кусок той же проблемы — версия API, см. src/test/resources/docker-java.properties.
    environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}

// -Xlint включён по scada-2li: ловит ошибки уровня компилятора (raw types, unchecked,
// deprecation), а не стиль — линтеры стиля отвергнуты отдельно по scada-2p9. Значимость
// вывода для ErrorProne/SpotBugs оценивается по факту первого прогона.
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:all")
}

// Отключаем «плоский» jar: сервис деплоится как исполняемый bootJar и как
// библиотека никуда не подключается. Заодно в build/libs остаётся один jar,
// поэтому COPY build/libs/*.jar в Dockerfile не ломается.
tasks.named("jar") {
    enabled = false
}
