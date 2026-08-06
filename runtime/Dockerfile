FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Шлюз публикует телеметрию сжатой snappy (compression-type: snappy в его конфиге),
# а разжимает её consumer — то есть нативная библиотека snappy нужна именно здесь.
# snappy-java подкладывает libsnappyjava.so, слинкованную с glibc, и на Alpine (musl)
# она не грузится: "Error loading shared library ld-linux-x86-64.so.2".
#
# gcompat — прослойка glibc→musl, ~100 КБ против смены базового образа на jammy
# (+80 МБ). Без неё падает UnsatisfiedLinkError, причём это Error, а не Exception:
# до правки надзорного цикла в TagKafkaConsumer он убивал поток consumer'а насмерть,
# и телеметрия молча переставала идти при живом HTTP и открытых WebSocket'ах.
RUN apk add --no-cache gcompat

COPY build/libs/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
