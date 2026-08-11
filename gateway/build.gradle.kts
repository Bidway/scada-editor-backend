plugins {
    java
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"

description = "gateway"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}
extra["springCloudVersion"] = "2023.0.1"

dependencies {
    // основной стартер gateway
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")

    // load balancer (устраняет ошибку ReactiveLoadBalancer)
    implementation("org.springframework.cloud:spring-cloud-starter-loadbalancer")

    // actuator (удобно смотреть routes)
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // логирование
    implementation("org.springframework.boot:spring-boot-starter-logging")

    // тесты
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 3.3 не тянет launcher транзитивно, а Gradle 9 больше не подставляет его
    // сам — без этой строки :gateway:test падает с "Failed to load JUnit Platform".
    // Остальные модули на 3.5.7, где starter-test тянет его сам (scada-oee).
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Source: https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-webflux
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Source: https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-security
//    implementation("org.springframework.boot:spring-boot-starter-security")


    // Source: https://mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-oauth2-resource-server
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    implementation("io.jsonwebtoken:jjwt-api:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")
}
dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
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
