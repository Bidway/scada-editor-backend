FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# automation читает scada.tags, а шлюз публикует его сжатым snappy: нативной библиотеке snappy-java
# на Alpine (musl) нужна прослойка glibc — та же причина, что у runtime.
RUN apk add --no-cache gcompat

COPY build/libs/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
