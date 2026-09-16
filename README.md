# scada-editor-backend

Серверная часть SCADA: редактор мнемосхем, база каналов, режим мониторинга с записью в ПЛК и
фоновые задачи проектов. Gradle multi-module (Kotlin DSL), Java 17, Spring Boot.

## Модули

| Модуль | Порт | Роль |
|---|---|---|
| `gateway` | 8080 | Единственный вход снаружи: маршрутизация и проверка JWT. Spring Cloud Gateway |
| `auth` | 8081 | Выдача JWT |
| `channel` | 8082 | Узлы и параметры каналов, STOMP WebSocket |
| `editor` | 8083 | Компоненты, шаблоны, данные проектов, определения фоновых задач |
| `runtime` | 8085 | Мониторинг, Kafka, скрипты на GraalVM JS. Своей БД нет |
| `automation` | 8086 | Исполнение фоновых задач проекта: регуляторы, watchdog. Схема `automation` |
| `script-core` | — | Библиотека: общий код скриптов GraalVM и разбор конверта телеметрии |

Одна база `savushkin`, у каждого сервиса своя схема. Миграций нет (`ddl-auto: update`), эталонные
данные — в `dumps/`.

## Требуется

- JDK 17
- PostgreSQL с базой `savushkin`
- Kafka — телеметрия и определения задач
- Docker Desktop — для тестов `editor` и `auth` (Testcontainers) и для режима `-Mode docker`

Переменные окружения — в `.env.example`; часть нужна только локальному запуску, часть только
Docker-режиму.

## Запуск

```powershell
.\start-all.ps1                    # весь стенд
.\start-all.ps1 -Mode docker       # в контейнерах
.\start-all.ps1 -Sim               # с симулятором ПЛК
.\start-all.ps1 -Status            # что сейчас поднято
.\start-all.ps1 -Stop              # остановить
```

Один сервис:

```powershell
.\gradlew :editor:bootRun
.\gradlew :editor:bootRun --args="--server.port=8093"   # второй экземпляр рядом
```

> `docker compose up --build` **не пересобирает Java-код**: Dockerfile'ы копируют готовый jar
> с хоста. Перед сборкой образов нужен `.\gradlew bootJar`.

## Сборка и тесты

```powershell
.\gradlew bootJar                  # jar'ы всех сервисов в build/libs
.\gradlew :editor:test             # тесты модуля
.\gradlew :auth:test --tests "com.example.auth.controller.AuthControllerIT"
```

Линтеров и форматтеров в проекте нет.

## Как устроено

**Тег адресуется путём канала** — `Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST`. Это одновременно
ключ сообщения в Kafka, значение `tag_id` у свойства компонента и адрес в команде записи.
Числовой адресации тега на проводе нет.

**Подпись JWT проверяет только `gateway`.** Вниз он передаёт `X-User-Id` и `X-Username`, затирая
пришедшие от клиента значения; у остальных сервисов `permitAll()`. Снаружи всё ходит только
через шлюз.

**Скрипты поделены между сервером и браузером** по правилу «меняет объект или общее состояние —
сервер, только рисует — фронт». На сервере (`runtime`, GraalVM) исполняются скрипты действий и
реакции на смену тега; фоновые задачи проекта по периоду исполняет `automation`.

**История правок в `editor`** — версии документов: снимок в `document_version`, отмена
восстанавливает версию новой записью, а не отматывает историю. В `channel` для этого свой
механизм — журнал команд с отменой (`/api/channel/undo`).

## Смежные репозитории

- `scada-editor-frontend` — Next.js, порт 3000
- `scada-gateway` — шлюз ПЛК (OPC UA + Modbus), порт 8888
