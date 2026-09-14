plugins {
    `java-library`
    // Только ради BOM Spring Boot: версии Jackson совпадают с сервисами, куда библиотека подключается.
    id("io.spring.dependency-management") version "1.1.7"
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.7")
    }
}

dependencies {
    // Технический код исполнения скриптов, общий для editor (проверка) и automation (исполнение).
    // Без Spring и без БД — только GraalVM.
    api("org.graalvm.polyglot:polyglot:24.1.0")
    implementation("org.graalvm.polyglot:js:24.1.0")
    // Разбор конверта телеметрии Kafka (общий для runtime и automation).
    api("com.fasterxml.jackson.core:jackson-databind")

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
