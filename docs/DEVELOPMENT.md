# Разработка и эксплуатация

## Требования

- **Java 17**
- **Docker + Docker Compose** (для запуска через контейнеры)
- **PostgreSQL 16** + **Redis 7** (для локального запуска без Docker)
- **Gradle** (Wrapper включён в каждом модуле — `gradlew`)

---

## Запуск через Docker Compose

```bash
# 1. Скопировать шаблон переменных
cp .env.example .env

# 2. Заполнить .env (обязательно — JWT_SECRET)
# JWT_SECRET=my_long_random_secret_at_least_32_chars
# DB_PASSWORD=mypassword

# 3. Собрать JAR каждого сервиса
cd auth    && .\gradlew bootJar && cd ..
cd channel && .\gradlew bootJar && cd ..
cd editor  && .\gradlew bootJar && cd ..
cd gateway && .\gradlew bootJar && cd ..

# 4. Запустить всё
docker-compose up --build
```

Порядок старта: `postgres` → `redis` → `auth`/`channel`/`editor` → `gateway`.

---

## Локальный запуск (без Docker)

Нужны запущенные PostgreSQL (`:5432`) и Redis (`:6379`).

По умолчанию в `application.yml` прописаны дефолты для dev:
- DB: `postgres:postgres@localhost:5432/savushkin`
- Redis: `localhost:6379`
- JWT Secret: встроен как fallback

```powershell
# Запускать в разных терминалах

# auth (порт 8081)
cd Z:\GitKraken\scada-editor-backend\auth
.\gradlew bootRun

# channel (порт 8082)
cd Z:\GitKraken\scada-editor-backend\channel
.\gradlew bootRun

# editor (порт 8083)
cd Z:\GitKraken\scada-editor-backend\editor
.\gradlew bootRun

# gateway (порт 8080)
cd Z:\GitKraken\scada-editor-backend\gateway
.\gradlew bootRun
```

---

## Переменные окружения

Все сервисы читают переменные с fallback-значениями для dev:

| Переменная | Описание | Dev-умолчание |
|-----------|---------|--------------|
| `JWT_SECRET` | Секрет для подписи JWT | встроенный (только для dev!) |
| `DB_HOST` | Хост PostgreSQL | `localhost` |
| `DB_PORT` | Порт PostgreSQL | `5432` |
| `DB_NAME` | Имя БД | `savushkin` |
| `DB_USERNAME` | Пользователь БД | `postgres` |
| `DB_PASSWORD` | Пароль БД | `postgres` |
| `REDIS_HOST` | Хост Redis | `localhost` |
| `REDIS_PORT` | Порт Redis | `6379` |
| `AUTH_HOST` | Хост auth (только для gateway) | `localhost` |
| `CHANNEL_HOST` | Хост channel (только для gateway) | `localhost` |
| `EDITOR_HOST` | Хост editor (только для gateway) | `localhost` |

> **Никогда не коммитьте настоящий `JWT_SECRET` в git!** Используйте `.env` файл (он в `.gitignore`).

---

## Структура каждого модуля

```
{service}/src/main/java/com/example/{service}/
├── {Service}Application.java         — точка входа Spring Boot
├── config/
│   ├── SecurityConfig.java           — permitAll (проверка на Gateway)
│   ├── SwaggerConfig.java            — настройка SpringDoc
│   ├── WebSocketConfig.java          — (только channel) STOMP endpoint
│   └── command/                      — Command Pattern классы
│       ├── Command.java
│       ├── CommandResult.java
│       ├── CommandManager.java
│       ├── CommandLog.java
│       ├── CommandLogRepository.java
│       └── UndoHandler.java
├── controller/                       — REST контроллеры
├── service/                          — интерфейсы сервисов
│   └── impl/                         — реализации
├── repository/                       — JPA репозитории
├── model/                            — JPA сущности
├── dto/                              — Data Transfer Objects
├── mapper/                           — MapStruct маперы
├── command/                          — конкретные Command-классы
│   └── undo/                         — UndoHandler-бины
└── exception/
    ├── NotFoundException.java
    └── GlobalExceptionHandler.java
```

---

## Swagger UI

После запуска документация доступна по адресу:

| Сервис | URL |
|--------|-----|
| auth | http://localhost:8081/swagger-ui.html |
| channel | http://localhost:8082/swagger-ui.html |
| editor | http://localhost:8083/swagger-ui.html |

> Через gateway Swagger пока не проксируется.

---

## Сборка одного модуля

```powershell
cd Z:\GitKraken\scada-editor-backend\channel

# Скомпилировать
.\gradlew compileJava

# Запустить тесты
.\gradlew test

# Собрать исполняемый JAR
.\gradlew bootJar

# Запустить напрямую
.\gradlew bootRun
```

---

## Добавление нового эндпоинта (чеклист)

1. **Модель** — добавить/обновить `@Entity` в `model/`
2. **Repository** — добавить `JpaRepository` в `repository/`
3. **DTO** — создать Request/Response DTO в `dto/`
4. **Mapper** — добавить MapStruct маппер в `mapper/`
5. **Command** — создать `XxxCommand implements Command<T>` в `command/`
6. **UndoHandler** — создать `XxxUndoHandler implements UndoHandler` в `command/undo/`
7. **Service** — добавить метод в интерфейс и реализацию
8. **Controller** — добавить эндпоинт, принять `@RequestHeader("X-Username")`

> Подробнее про Command + UndoHandler — см. [COMMAND_PATTERN.md](COMMAND_PATTERN.md)

---

## Частые проблемы

### `Spring Boot [3.5.7] is not compatible with this Spring Cloud release train`
**Причина:** gateway использует `Spring Cloud 2023.0.1`, совместимый только с Boot **3.2–3.3**.  
**Решение:** gateway намеренно остаётся на `3.3.13`. Не менять его версию на 3.5.x.

### `Unable to find column type for command_log`
**Причина:** таблица `command_log` не создана (Hibernate DDL ещё не применил).  
**Решение:** убедиться что `ddl-auto: update` в `application.yml` и что сервис поднялся с подключением к БД.

### `No UndoHandler for commandType: XXX`
**Причина:** создана команда, но не создан `UndoHandler` для неё.  
**Решение:** создать класс `@Component` реализующий `UndoHandler` с `supports("XXX")`.

### WebSocket не подключается через `localhost:8080`
**Причина:** WebSocket не проксируется через Gateway.  
**Решение:** подключаться напрямую к `ws://localhost:8082/ws`.

### `401 Unauthorized` при запросе к channel/editor напрямую
**Причина:** эти сервисы ждут заголовок `X-Username` (от Gateway), а не JWT токен.  
**Решение:** при отладке добавлять заголовок вручную: `X-Username: alice`.

---

## Технические решения и их причины

| Решение | Почему |
|---------|--------|
| `permitAll()` во всех downstream-сервисах | Безопасность на уровне Gateway; дублирование проверки излишне |
| Command Pattern вместо прямых вызовов repo | Audit log + Undo из коробки |
| Один `UndoHandler` на один `commandType` | Open/Closed Principle — добавление новой команды не ломает существующие |
| `undoPayload` — полный JSON-снимок для DELETE | Позволяет восстановить сущность без дополнительных запросов к БД |
| `CommandBatch` для групповых операций | Атомарная отмена связанных изменений (узел + его параметры) |
| `idNode` как строковый путь (`site.pump_01`) | Иерархия без рекурсивных JOIN-ов; запрос `LIKE 'site_A.%'` даёт всё поддерево |
