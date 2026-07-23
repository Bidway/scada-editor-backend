# Архитектура системы

## Общая схема

```
Клиент (браузер / Postman)
        │
        │  HTTP  :8080
        ▼
┌────────────────────┐
│      GATEWAY       │  — проверяет JWT, добавляет X-User-Id и X-Username в заголовки
│  Spring Cloud GW   │
└────────┬───────────┘
         │ HTTP (внутри)
    ┌────┴───────────────────────────────────────────────┐
    │                                   │                │
    ▼                                   ▼                ▼
┌──────────┐   ┌─────────────┐   ┌──────────────┐  ┌──────────────┐
│  AUTH    │   │   CHANNEL   │   │    EDITOR    │  │   RUNTIME    │
│  :8081   │   │   :8082     │   │    :8083     │  │    :8085     │
│ JWT auth │   │ Nodes/Param │   │ Components/  │  │ Read-only    │
└──────────┘   │ WebSocket   │   │ Templates    │  │ мониторинг   │
               └─────────────┘   └──────────────┘  └──────────────┘
                    │                                     │      ▲
              WebSocket STOMP                        WebSocket   │ разовый REST
              (пока напрямую,                        (raw, /ws/  │ при старте сессии:
              не через gateway)                       runtime/*) │ GET editor tree,
                    │                                     ▲      │ POST channel
                    │ контроллеры/ПЛК пишут              │      │ kafka-bindings
                    │ значения тегов в ОДИН общий топик   │      │ (tagId → idNode)
                    ▼                                     │      │
              ┌───────────┐                                │      │
              │   KAFKA   │────────────────────────────────┘──────┘
              │ (1 topic) │
              └───────────┘
```

`runtime` не хранит данные проекта — читает дерево у `editor` и резолвит tagId → idNode у
`channel` только один раз при старте сессии мониторинга (не на горячем пути). Kafka-топик —
**один общий на весь проект** (задаётся конфигурацией `runtime`, не хранится в БД `channel`);
все контроллеры/ПЛК пишут значения туда, различая теги по **key сообщения = `Node.idNode`**
(один Node — одно живое значение). Сами значения тегов идут в `runtime` напрямую из Kafka,
без обращений к `channel`/`editor` на каждое обновление. `runtime` не выполняет команд и не
участвует в undo/redo ([COMMAND_PATTERN.md](COMMAND_PATTERN.md)) — он полностью read-only,
`editor` и `channel` остаются единственными точками редактирования.

## Поток аутентификации

```
1. POST /api/auth/login → { token: "eyJ..." }
2. Все последующие запросы: Authorization: Bearer eyJ...
3. Gateway парсит токен → извлекает userId, username
4. Добавляет заголовки: X-User-Id: 42, X-Username: alice
5. Downstream-сервис читает X-Username из заголовка (не парсит JWT сам)
```

## Технологический стек

| Слой | Технология |
|------|-----------|
| Framework | Spring Boot 3.5.7 (auth, channel, editor, runtime), 3.3.13 (gateway) |
| Gateway | Spring Cloud Gateway 2023.0.1 |
| Security | Spring Security + JJWT 0.11.5 |
| Persistence | Spring Data JPA + Hibernate (auth/channel/editor; `runtime` без БД) |
| DB | PostgreSQL 16 |
| Cache/Lock | Redis 7 |
| Real-time (channel) | WebSocket STOMP (Spring) |
| Real-time (runtime) | Raw WebSocket (без STOMP/SockJS) — батчинг тегов, см. ниже |
| Потоковая передача тегов | Apache Kafka (KRaft, без Zookeeper) |
| Скрипты в runtime | GraalVM JavaScript (`org.graalvm.polyglot`), `onChange`/`Script` |
| Mapping | MapStruct |
| Boilerplate | Lombok |
| API Docs | SpringDoc OpenAPI (Swagger) |
| Build | Gradle (Kotlin DSL — channel/editor/gateway/runtime, Groovy DSL — auth) |
| Java | 17 |

## Порты

| Сервис | Порт | Назначение |
|--------|------|-----------|
| gateway | 8080 | Единая точка входа для всех HTTP |
| auth | 8081 | Регистрация / логин |
| channel | 8082 | SCADA-узлы, параметры, WebSocket |
| editor | 8083 | Компоненты, шаблоны, undo |
| runtime | 8085 | Режим мониторинга: сессии, WebSocket `/ws/runtime/{sessionId}` |
| PostgreSQL | 5432 | База данных |
| Redis | 6379 | Блокировки узлов |
| Kafka | 9092 (внешний), 19092 (внутри сети docker-compose) | Живые значения тегов |

## Схемы БД

Каждый сервис использует свою схему в одной БД:

| Схема | Сервис | Таблицы |
|-------|--------|---------|
| `auth` | auth | `user` |
| `channel` | channel | `node`, `param`, `description`, `template`, `template_param`, `command_log` |
| `editor` | editor | `component`, `component_state`, `component_property`, `component_event`, `script`, `binding`, `template_faceplate`, `template_component`, `template_component_property`, `template_component_state`, `template_script`, `command_log` |

`runtime` **не имеет собственной схемы БД** — данные сессии мониторинга (индекс тегов/скриптов,
текущие значения свойств) живут только в памяти процесса на время жизни сессии.

## Routing в Gateway

```yaml
# gateway/src/main/resources/application.yml
routes:
  - id: auth-service     →  /api/auth/**    →  http://auth:8081
  - id: channel-service  →  /api/channel/** →  http://channel:8082
  - id: editor-service   →  /api/editor/**  →  http://editor:8083
  - id: runtime-service  →  /api/runtime/** →  http://runtime:8085
```

> **WebSocket** (`/ws/**`, `/ws/runtime/**`) пока не проксируется через Gateway — клиент подключается напрямую к `channel:8082` / `runtime:8085`.

## Security в downstream-сервисах

Все сервисы (auth, channel, editor) имеют `SecurityConfig` с `permitAll()` — потому что проверка токена происходит **только на уровне Gateway**. Downstream-сервисы доверяют заголовкам `X-User-Id` / `X-Username`, которые добавляет Gateway.

```java
// Это сделано намеренно — downstream сервисы находятся за Gateway
http.authorizeHttpRequests(auth -> auth.requestMatchers("**").permitAll())
```

## Redis — блокировки узлов (channel)

`LockService` позволяет пользователям захватывать эксклюзивный доступ к узлам для редактирования. Блокировка хранится в Redis с TTL.

```
POST /api/channel/lock    — захватить список узлов
POST /api/channel/unlock  — освободить список узлов
```

## Runtime — режим мониторинга (Kafka + GraalVM JS)

`runtime` — read-only сервис поверх модели `editor`: показывает SCADA-проект в режиме
мониторинга (не редактирования), подключает тэговые свойства компонентов к живым значениям
из Kafka и выполняет простые скрипты (`onChange`, `Script`) на GraalVM JavaScript.

### Конвенция `tagId` и Kafka-биндинг

- `ComponentProperty.tagId` (editor) — это **строковое представление `NodeParam.id`** (числовой
  первичный ключ параметра-канала в `channel`). `NodeParam` — вспомогательная метаданная
  (тип/формат опроса тега), а не источник живого значения.
- Живое значение тега приходит из Kafka **по key = `Node.idNode`** (путь узла, например
  `site_A.pump_01`) — один Node даёт одно живое значение, независимо от того, сколько
  `NodeParam` на него ссылаются.
- Kafka-топик — **один общий на весь проект**, задаётся конфигурацией `runtime`
  (`kafka.tags-topic`, env `KAFKA_TAGS_TOPIC`), а не хранится в БД `channel`.
- При старте сессии `runtime` резолвит **tagId → idNode** батчем через `channel`
  (`POST /api/channel/param/kafka-bindings`). Если `tagId` не парсится как `Long` или для
  него не находится `NodeParam` — свойство просто не получает живых обновлений (без ошибки
  старта сессии).

### Жизненный цикл сессии

```
1. POST /api/runtime/sessions {projectId}
     → EditorClient:  GET  /api/editor/components/{projectId}      (разовый REST)
     → обход дерева → TagSubscriptionIndex (tagId → onChange-скрипты/сырые свойства,
                                             scriptId → Script компонента)
     → ChannelClient: POST /api/channel/param/kafka-bindings {ids}  (разовый REST, батч)
                       ← [{paramId, idNode}, ...]  — idNode и есть Kafka-key тега
     → TagValueRouter.registerSession: tagId -> idNode, idNode -> {tagId, ...}
     ← { sessionId, wsPath: "/ws/runtime/{sessionId}", projectTree }

2. Клиент открывает WebSocket на wsPath (raw, без STOMP/SockJS).

3. TagKafkaConsumer — единственный постоянный consumer единого топика проекта (запускается
   один раз при старте приложения, а не по сессиям/тегам) — получает сообщение {key=idNode,
   value} и публикует событие. TagValueRouter по key=idNode находит все заинтересованные
   tagId/сессии:
       - обновляет общий (для всех сессий) кэш последнего значения тега;
       - кладёт сырое значение в SessionOutboundBuffer каждой заинтересованной сессии
         (для отрисовки на фронте через Binding.script — он не трогается бэком);
       - если у тега есть привязанный onChange — выполняет его через ScriptEngineService
         и тоже кладёт в буфер результат как обновление свойства.

4. OutboundFlusher каждые runtime.flush-interval-ms (по умолчанию 40 мс) забирает
   накопленное и шлёт ОДНИМ WS-фреймом на сессию — так много часто обновляющихся тегов
   не превращаются в лавину мелких сообщений.

5. Клиент шлёт по тому же WS {"type":"ACTION","scriptId":...} (например, нажатие кнопки) →
   RuntimeSessionService выполняет Script через ScriptEngineService и сразу (не дожидаясь
   флаша) отправляет изменившиеся свойства обратно.

6. DELETE /api/runtime/sessions/{id} или закрытие WS → сессия снимается с интереса к своим
   тегам (сам Kafka-consumer продолжает работать — он общий для всего сервиса).
```

### Контракт WS-сообщений

```json
// runtime -> клиент (батч из OutboundFlusher или мгновенный ответ на ACTION)
{
  "type": "UPDATE",
  "tags": [{"tagId": "123", "value": "42.5", "ts": 1731000000000}],
  "properties": [{"propertyId": 55, "propertyName": "state", "value": "on", "ts": 1731000000000}]
}

// клиент -> runtime
{"type": "ACTION", "scriptId": 7}
```

### Скрипты (`ScriptEngineService`)

- Движок — GraalVM JavaScript (`org.graalvm.polyglot:js`), пул переиспользуемых `Context`
  (`allowAllAccess(false)`, без доступа к Java/файлам/сети), кэш скомпилированных `Source` по
  тексту скрипта, watchdog-таймаут (`runtime.script.execution-timeout-ms`) с принудительным
  `Context.close(true)` при зависании.
- Скрипту доступны переменные: `tag` — новое значение тега (только для `onChange`; строка,
  автоматически приводится к числу, если это возможно) и `props` — мутируемый объект текущих
  значений свойств **того же компонента** по имени (`props.state = tag > 10 ? "on" : "off"`).
  Изменения `props.*` после выполнения скрипта сравниваются со снимком до запуска — все
  отличия уходят как `PROPERTY_UPDATE`.
- `onChange` выполняется **только** когда меняется тег, к которому привязано именно это
  свойство (реверс-индекс `tagId → onChange`), а не на каждое сообщение Kafka подряд.
- `Script` компонента выполняется **только** по ACTION с фронта (кнопка и т.п.), не по тегам.
- `Binding.script` **не выполняется на бэке вообще** — хранится в `editor`, отдаётся фронту
  целиком вместе с деревом проекта и интерпретируется там же для перерисовки компонента.
