# Режим монитора — контекст для Claude

> **Как пользоваться:** пришли мне этот файл целиком, когда доделаешь теги и биндинги.
> Здесь всё, что нужно, чтобы я восстановил контекст без повторного раскапывания кода.
> Состояние на **15.07.2026**, ветка `microservice`.

---

## 1. Задача

В `runtime` делается **режим мониторинга**. В редакторе есть компоненты с теговыми
свойствами, привязанными к базе каналов. Значения тегов приходят из **единого Kafka-топика**
(key = путь узла), монитор раздаёт их на фронт по **WebSocket**. В перспективе с этими
тегами работают скрипты.

**Главное, что нужно помнить (я один раз уже понял это неправильно):**

> `tag_id` — **строка с путём узла**, а не число. Это **готовый Kafka-key**.
> `tag_id: "Барановичи-1.BN1_MCA1.AI_M.AI2.M"`
>
> Связи **монитор → база каналов НЕТ и быть не должно**. Монитор получает от редактора
> проект уже со связями. Резолвить `tag_id` куда-либо не нужно.

## 2. Архитектура (после починки 15.07.2026)

```
editor.component_property.tag_id = "Барановичи-1.BN1_MCA1.AI_M.AI2.M"   ← он же Kafka key
        │
        │  runtime: GET /api/editor/components/{projectId}   (ОДИН раз на сессию, не горячий путь)
        ▼
TagSubscriptionIndex          признак тега = непустой tag_id (property_type НЕ участвует)
        ▼
Kafka topic "scada.tags", key = tag_id     ← сюда пишут контроллеры/драйверы
        │  TagKafkaConsumer (1 на сервис, свой поток) → KafkaTagMessageEvent
        ▼
TagValueRouter    Map<tagId, TagRuntimeState{sessionIds, lastValue}>   (+ onChange через GraalVM)
        ▼
SessionOutboundBuffer → OutboundFlusher (батч раз в 40мс) → WS /ws/runtime/{sessionId}
                                                              (фронт сам исполняет Binding.script)
```

Модуль `channel` в этой схеме **не участвует вообще**. Kafka есть только в `runtime`.

## 3. Ключевые файлы

Все пути от `runtime/src/main/java/com/example/runtime/`:

| Файл | Роль |
|---|---|
| `kafka/TagKafkaConsumer.java` | Один долгоживущий `KafkaConsumer` на daemon-потоке. **Не** spring-kafka, не `@KafkaListener` — руками |
| `kafka/TagValueRouter.java` | Горячий путь: `@EventListener` → роутинг по key → буфер сессий + onChange |
| `kafka/TagRuntimeState.java` | Общее на все сессии состояние тега: `sessionIds`, `lastValue` |
| `session/TagSubscriptionIndex.java` | Индекс дерева проекта, строится 1 раз на сессию (BFS) |
| `session/RuntimeSessionService.java` | Жизненный цикл сессии, `handleAction` |
| `stream/OutboundFlusher.java` | `@Scheduled` батч раз в `flush-interval-ms` |
| `stream/SessionOutboundBuffer.java` | Две очереди + кэп 10 000 на вид |
| `ws/RuntimeWebSocketHandler.java` | Raw WebSocket (без STOMP/SockJS) |
| `script/ScriptEngineService.java` | GraalVM JS, пул контекстов + watchdog |
| `client/EditorClient.java` | Единственный внешний вызов: дерево проекта у editor |

## 4. Протокол

**Старт сессии (обязательно до WS):**
```
POST http://localhost:8085/api/runtime/sessions
{ "projectId": 968 }
→ { "sessionId": "...", "wsPath": "/ws/runtime/...", "projectTree": {...} }
```

**WebSocket:** `ws://localhost:8085/ws/runtime/{sessionId}` — **напрямую на :8085**,
gateway роутит только `/api/**` и WS через себя не пускает.

Сервер → фронт (батч раз в ~40мс):
```json
{"type":"UPDATE",
 "tags":[{"tagId":"Барановичи-1.BN1_MCA1.AI_M.AI2.M","value":"42.7","ts":1784118699638}],
 "properties":[{"propertyId":21,"propertyName":"level","value":1,"ts":1784118699638}]}
```
Фронт → сервер (единственный принимаемый тип):
```json
{"type":"ACTION","scriptId":123}
```

`tags[].value` — **всегда сырая строка** из Kafka. `properties[].value` — то, что положил скрипт.

## 5. Конфигурация

`runtime/src/main/resources/application.yml`, порт **8085**:

| Ключ | Значение | Env |
|---|---|---|
| `kafka.tags-topic` | `scada.tags` | `KAFKA_TAGS_TOPIC` |
| `kafka.bootstrap-servers` | `localhost:9092` / в docker `kafka:19092` | `KAFKA_BOOTSTRAP_SERVERS` |
| `kafka.consumer-group-id` | `runtime-service` | — |
| `runtime.editor-base-url` | `http://editor:8083` | `EDITOR_HOST` / `EDITOR_PORT` |
| `runtime.flush-interval-ms` | `40` | — |
| `runtime.script.context-pool-size` | `4` | — |
| `runtime.script.execution-timeout-ms` | `200` | — |

Consumer: `StringDeserializer` для key и value, `auto.offset.reset=latest`, автокоммит.

## 6. Тестовые данные на стенде

- Проект **968 «Мойка»** → 969 «Развязка» → 970 «Клапон» → **свойство 21** — единственное
  теговое свойство во всей базе.
- Свойство 21: `property_type='Тег'`, `tag_id='Барановичи-1.BN1_MCA1.AI_M.AI2.M'`,
  `name=''`, `default_value=''`, `on_change='""'` (пустой → скрипт не запускается).
- Проект 950 «Test2» — тегов **нет**, для проверки монитора не годится.
- В `channel.node` этот путь есть (id 3365), у него 7 параметров — но все они **метаданные**
  (Период, Дельта, Описание). **Живого значения в базе каналов нет** — снапшот оттуда не сделать.

## 7. Как проверить за 2 минуты

```bash
docker compose ps                     # kafka healthy, runtime/editor/postgres Up
./gradlew :runtime:bootJar && docker compose build runtime && docker compose up -d runtime
node monitor-e2e.js                   # скрипт ниже
```

Признак, что теги вообще доехали до индекса (смотреть в первую очередь):
```bash
docker logs scada-editor-backend-runtime-1 2>&1 | grep "Runtime session"
# ХОРОШО:  started for project 968 (1 tags)
# ПЛОХО:   started for project 968 (0 tags)   ← тег не распознан
```

<details>
<summary><b>monitor-e2e.js</b> — сквозной тест Kafka → WS (node 24+, зависимостей не нужно)</summary>

```js
const { execFileSync } = require('child_process');
const BASE = 'http://localhost:8085';
const PROJECT_ID = 968;
const TAG = 'Барановичи-1.BN1_MCA1.AI_M.AI2.M';
const KAFKA = 'scada-editor-backend-kafka-1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function produce(key, value) {
  // base64 + execFileSync: без шелла Windows и без MSYS-подмены путей — ключ доезжает байт-в-байт
  const b64 = Buffer.from(`${key}:${value}`, 'utf8').toString('base64');
  execFileSync('docker', ['exec', '-i', KAFKA, 'sh', '-c',
    `echo '${b64}' | base64 -d | /opt/kafka/bin/kafka-console-producer.sh ` +
    `--bootstrap-server localhost:19092 --topic scada.tags ` +
    `--property parse.key=true --property key.separator=:`], { stdio: 'pipe' });
  console.log(`  -> produced  ${key} = ${value}`);
}

async function createSession() {
  const r = await fetch(`${BASE}/api/runtime/sessions`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ projectId: PROJECT_ID }),
  });
  const body = await r.text();
  if (!r.ok) throw new Error(`session create ${r.status}: ${body}`);
  return JSON.parse(body);
}

function openWs(sessionId, sink) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://localhost:8085/ws/runtime/${sessionId}`);
    ws.onmessage = (e) => { sink.push(e.data); console.log(`  <- WS       ${e.data}`); };
    ws.onopen = () => resolve(ws);
    ws.onerror = () => reject(new Error('ws error'));
    setTimeout(() => reject(new Error('ws open timeout')), 5000);
  });
}

(async () => {
  console.log('\n=== TEST A: publish ПОСЛЕ коннекта WS ===');
  const a = await createSession(); const sinkA = [];
  const wsA = await openWs(a.sessionId, sinkA);
  await sleep(300); produce(TAG, '42.7'); await sleep(1200); wsA.close(); await sleep(200);

  console.log('\n=== TEST B: publish ДО коннекта WS (регресс OutboundFlusher) ===');
  const b = await createSession();
  produce(TAG, '77.3'); await sleep(600);
  const sinkB = []; const wsB = await openWs(b.sessionId, sinkB);
  await sleep(1200); wsB.close(); await sleep(200);

  const okA = sinkA.some((m) => m.includes('42.7') && m.includes(TAG));
  const okB = sinkB.some((m) => m.includes('77.3') && m.includes(TAG));
  console.log(`\nTEST A  Kafka -> WS          : ${okA ? 'PASS' : 'FAIL'}`);
  console.log(`TEST B  буфер до коннекта WS : ${okB ? 'PASS' : 'FAIL'}`);
  process.exit(okA && okB ? 0 : 1);
})().catch((e) => { console.error('FATAL:', e.message); process.exit(1); });
```
</details>

## 8. Скриптовый слой — три РАЗНЫХ места, не путать

| Что | Где хранится | Кто исполняет | Триггер |
|---|---|---|---|
| `ComponentProperty.onChange` | `editor.component_property.on_change` | **runtime**, GraalVM | Каждое изменение привязанного тега |
| `Script` | `editor.scripts` | **runtime**, GraalVM | WS `{"type":"ACTION","scriptId":N}` с фронта |
| `Binding.script` | `editor.binding` | **фронтенд**, не бэк | Фронт сам, бэк только пересылает в дереве |

**Что видит скрипт** (`ScriptEngineService`):
- `tag` — новое значение тега. Только в `onChange`. `TagValueRouter.coerce()` пытается
  `Double.parseDouble`, иначе оставляет строку.
- `props` — **мутируемый** объект значений свойств компонента **по имени** (все свойства
  компонента, не только теговые). `props.xxx = ...` после выполнения превращается в
  `PropertyUpdate` → уходит на фронт. Diff считает вызывающий, сравнивая before/after.

Стартовые значения `props` = `default_value` (**строки!**), пока скрипт их не перезапишет.

## 9. Мины, на которые я наступлю (проверь при возврате)

1. **`default_value = NULL` уронит создание сессии.** `TagSubscriptionIndex` кладёт
   `default_value` в `ConcurrentHashMap`, а тот не принимает null → NPE → 500 на
   `POST /sessions`. Сейчас не стреляет только потому, что у единственного свойства
   там `''`. Первое же свойство без `default_value` — и монитор ляжет. То же самое в
   `TagValueRouter.runOnChangeAndPublish` (`props.put(name, current)`).
   **Если добавил свойства — проверь это первым делом.**
2. **`name = ''` у свойства.** `props` индексируется по имени → скрипт не сможет сослаться
   на безымянное свойство (`props[""]`). Для onChange/ACTION имена обязаны быть непустыми.
3. **Глобальный scope GraalVM переиспользуется.** Контексты берутся из пула на 4 штуки;
   `var x = ...` в одном скрипте виден следующему скрипту на том же контексте. Изоляции нет.
4. **`property_type` не участвует в распознавании тега** — только непустой `tag_id`
   (решение принято осознанно: `property_type` — свободная строка, в базе `'Тег'`).
   Свойство с `tag_id`, но не-тег по смыслу, всё равно попадёт в подписку.
5. **Одна WS-сессия на `sessionId`.** Второй коннект молча перезатрёт первый.
   Закрытие WS убивает сессию целиком (`closeSession`) — переподключение требует нового
   `POST /sessions`.

## 10. Осознанно отложено (решения приняты, не переоткрывать без причины)

- **Стартовый снапшот.** Новая сессия не видит значений, пока тег не обновится
  (`auto.offset.reset=latest`, `lastValue` пуст при старте). Решили: **позже**, продюсера
  всё равно нет. Когда появится → compacted-топик + отдельный consumer с `seekToBeginning`.
  Из `channel.param.value` снапшот **невозможен** — там метаданные (см. §6).
- **Скрипты блокируют Kafka poll-тред.** `@EventListener` синхронный → `onChange` через
  GraalVM (до 300мс на скрипт) выполняется прямо на треде консьюмера. При росте числа
  тегов/сессий → выход за `max.poll.interval.ms` (5 мин) → ребаланс → топик встанет для
  **всех** сессий. Лечится отдельным executor-ом. **Станет актуально ровно тогда, когда
  ты доделаешь скрипты — вернуться сюда в первую очередь.**
- **WS мимо gateway** → `:8085` открыт в обход JWT-фильтра. Отдельная задача про безопасность.
- **Мёртвое:** `/api/channel/param/kafka-bindings` в channel остался без потребителей;
  колонки `channel.param.kafka_key` / `kafka_topic` — все NULL, наследие старой схемы.
  `runtime` зависит от `spring-kafka`, хотя использует только `kafka-clients`.
  Тестов в `runtime` нет вообще (`src/test` отсутствует).

## 11. ⚠️ Что в репозитории устарело

**`docs/ARCHITECTURE.md` и `docs/DATA_MODEL.md` врут** — они описывают старую схему
(«`tagId` — строковое представление `NodeParam.id`», «runtime резолвит tagId → idNode
батчем через channel»). Этого кода больше нет. Не верь им, верь этому файлу.

## 12. Что сделано 15.07.2026

Монитор был **полностью неработоспособен** — ни одно значение не доходило до фронта.
Три независимых блокера, каждый фатален сам по себе, все из-за неверной модели `tag_id`:

1. `TagSubscriptionIndex` искал `property_type == "TAG"`, в базе `"Тег"` → 0 тегов в индексе.
2. `Long.parseLong("Барановичи-1...")` → `NumberFormatException` → тег отброшен.
3. `TagValueRouter` не находил резолва → пропускал каждое сообщение.

Плюс: контейнер работал на jar от 14 июля (внутри `KafkaTopicSubscriptionManager` из старой
архитектуры), топика `scada.tags` не существовало, консьюмер ни разу не стартовал.

Починено: удалены `ChannelClient` + `KafkaBindingDto` + `channel-base-url` + `CHANNEL_HOST`;
признак тега = непустой `tag_id`; `TagValueRouter` схлопнут с двух map на одну (`tag_id == id_node`)
с атомарными `compute`/`computeIfPresent`; `OutboundFlusher` больше не дренирует буфер до
подключения WS (терял всё между `POST /sessions` и коннектом); в буфер добавлен кэп.
Итог: `-168/+80` в 11 файлах, оба e2e-теста PASS.

## 13. Что мне спросить у тебя при возврате

- Теговых свойств стало больше одного? На каких проектах смотреть?
- Появился реальный продюсер значений, или всё ещё публикуем вручную?
- Скрипты (`onChange` / `Script`) уже заполнены — есть на чём проверить исполнение?
- Биндинги делаются на фронте (как сейчас задумано) или что-то должно уехать на бэк?
- Пора ли снимать откладывания из §10 (снапшот, executor для скриптов)?
