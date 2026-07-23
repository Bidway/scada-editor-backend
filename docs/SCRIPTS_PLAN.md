# План: рабочие скрипты на BN1_MCA1 — команда в ПЛК + разграничение бэк/фронт

> Рабочий документ. Составлен 21.07.2026, ветка `microservice`.
> **Статус выполнения — в конце файла.** Он ведётся по ходу работ, чтобы можно было
> продолжить с любого места, не перечитывая исследование заново.

## Контекст

**Задача.** В проекте «Барановичи-1» → сцена `BN1_MCA1` кнопка должна открывать клапан,
состояние клапана и заполненность танка — меняться, прогресс-бар — показывать уровень.
Плюс формально разграничить, какие скрипты исполняются на бэке, а какие на фронте.

**Что обнаружено при диагностике.**

Скриптовый слой исправен — и бэковый (GraalVM в `runtime`, протестирован 16.07.2026,
см. [MONITOR_CONTEXT.md](MONITOR_CONTEXT.md) §14), и фронтовый (`src/lib/runtime/*`).
Не работает потому, что **в проекте нет ни одной строчки логики**:

| Что | Где должно лежать | Факт в БД |
|---|---|---|
| Привязки (фронт) | `editor.binding` | **0 строк** |
| Серверные скрипты | `editor.scripts` | **0 строк** |
| `onChange` свойств | `editor.component_property.on_change` | пусто у всех |
| Обработчик `onClick` | `component_state.image.events` | отсутствует у кнопки |

Состав сцены 951: Танк(957, свойство-тег `fullness`=23) → Клапан2(968, `ST`=21) и
Клапан1(972, свойство 22 **без имени**); Кнопка(962) **без свойств**. Прогресс-бар и
текст «0» — примитивы внутри `composition` Танка (собственных свойств не имеют).

**Дефекты, которые выстрелят сразу после заведения логики:**

1. **Сырое значение тега не доходит до свойства.** `TagValueRouter.dispatchToSession`
   шлёт только `TagUpdate(tagId, …)`; `TagSubscriptionIndex.rawPropertyIdsForTag()`
   существует, но **не вызывается ниоткуда**. Поэтому `properties[]` наполняется только
   серверными `onChange`, и привязка «значение ← свойство» на живом теге не сработает.
   Это же блокирует прогресс-бар: своих свойств у него нет, ссылаться он может только
   на `fullness`(23) через `propertyRefs`.
2. **`sendAction` — мёртвый код.** `runtimeConnection.ts:160` его экспортирует, но
   не вызывает никто. Серверный `Script` с фронта запустить невозможно в принципе.
3. **Свойство 22 без имени** — `collectTagScope` (`bindingScope.ts:77`) требует непустой
   `name`, и `props` на бэке индексируется по имени. Тег Клапана1 выпадает из обоих скоупов.
4. **Привязка на компоненте без свойств роняет сейв всей сцены.**
   `firstSavedPropertyId` (`buildComponentTree.ts:11`) вернёт `0`,
   `ComponentScriptBindingApplier.java:51` ответит 400 «Component property not found: 0».

**Канал команд в ПЛК уже существует.** Выяснено дизассемблированием
`deliver/images/scada-gateway-1.0.tar.gz` (образ шлюза, `app.jar`):

```
runtime → Kafka «scada-commands» → CommandConsumer.onCommand(CommandMessage)
        → OpcUaClientServiceDB.writeTag(…) → OPC UA → ПЛК
        → результат в Kafka «scada-command-results»
```

- Теги сцены в конфиге шлюза: Клапан2 `ST` → `ns=2;s=385` BOOLEAN;
  Клапан1 → `ns=2;s=389` BOOLEAN; Танк `fullness` → Modbus 40671 float32.
- **Писать можно только в OPC UA.** `ModbusClientService` умеет лишь
  `readFloat/readInt16/readBoolean` — записи по Modbus нет вообще. При этом все 307
  OPC UA-тегов BOOLEAN, а все 2164 Modbus-тега FLOAT: записываемого аналогового тега
  не существует. Поэтому массой танка командовать нельзя — только читать.

**Принятые решения:**
- Кнопка шлёт **настоящую команду в ПЛК**; права доступа, подтверждение и журнал — вне объёма.
- **Модель тега — только строка** (путь через точку, `tagName` = `id_node`). Числовых
  идентификаторов тега в нашем коде нет нигде.
- Заполненность танка — **через свойство `fullness`**, которое обновляется значением
  своего тега. Расчётной логики не вводим.
- **Фронт в этот заход не трогаем** — только описание работ (Часть 3).
- Стенд поднимаем; бэковые скрипты пишутся и тестируются в рамках этой работы.

---

## Разграничение: что на бэке, что на фронте

| Признак | Где | Механизм |
|---|---|---|
| Меняет состояние **объекта** (запись тега, команда в ПЛК) | **бэк** | `Script` + `ACTION` |
| Результат обязан быть **одинаков для всех операторов** | **бэк** | `onChange` |
| Только **отрисовка** (цвет, состояние, текст, размер, прогресс) | **фронт** | `Binding.script` |
| Реакция на **клик оператора** | фронт → делегирует бэку, если пишет тег | `events.onClick` → `ACTION` |

Бэковый скрипт исполняется один раз на весь стенд — консистентен и безопасен, но
занимает поток консьюмера Kafka ([MONITOR_CONTEXT.md](MONITOR_CONTEXT.md) §10).
Фронтовый исполняется у каждого оператора: бесплатен для сервера, но недоверенный и
локальный. Отсюда правило: **влияет на объект или на общее состояние — бэк;
только рисует — фронт.**

---

## Часть 1. Канал команд в `runtime` (бэк)

**1.1. Кэш `tagName → tagId` из телеметрии — вместо обращения в `channel`.**
Конверт `TelemetryMessage` несёт **и `tagName`, и `tagId`** одновременно
(§14 MONITOR_CONTEXT), а `TagKafkaConsumer.extractValue()` его уже разбирает.
Достаточно попутно складывать `tagId` и тип значения в процессную карту.
Обращения в `channel` не требуется, наша модель остаётся строковой.

Новый `kafka/GatewayTagRegistry.java`:
`Map<String tagName, TagMeta{Long gatewayId, String dataType}>`, где `dataType`
выводится из значения конверта (`true`/`false` → `BOOLEAN`, число → `FLOAT`).
Карта процессная, не чистится при уходе сессий.

> Числовой `tagId` — техническое поле **чужого** контракта шлюза: `CommandConsumer`
> при `getTagId() == null` пишет в лог «Команда без тега» и выходит. В наш код этот
> id не протекает: он подставляется в одном месте, на границе с Kafka.

**1.2. `kafka/CommandProducer.java`.** `KafkaProducer<String, String>`, топик из конфига
`kafka.commands-topic: scada-commands` (env `KAFKA_COMMANDS_TOPIC`). Key сообщения =
`tagName`. Тело — JSON `CommandMessage`:
`{commandId (UUID), controllerId, tagId, tagName, dataType, value, requestedBy, timestamp}`.

**Критично:** шлюз десериализует spring-kafka `JsonDeserializer` с
`trusted.packages=com.scada.gateway.kafka.dto` и без type-mapping, поэтому обязателен
заголовок `__TypeId__ = com.scada.gateway.kafka.dto.CommandMessage`. Это первое, что
проверять при отладке.

**1.3. API записи для скрипта.** В `ScriptEngineService` добавить в биндинги
`writeTag(propertyName, value)` как `org.graalvm.polyglot.proxy.ProxyExecutable`
(совместимо с текущим `HostAccess.NONE`). Вызовы копятся в списке и после выполнения
скрипта уходят в `CommandProducer`; имя свойства резолвится в `tag_id` (путь) через
`TagSubscriptionIndex`, путь — в `TagMeta` через реестр 1.1. Если тег ещё не появлялся
в телеметрии — `log.warn` и пропуск (см. «Риски»).

**1.4. Прокинуть writes из ACTION.** `RuntimeSessionService.handleAction` возвращает
только `List<PropertyUpdate>`; расширить результат командами, чтобы `handleAction`
и `TagValueRouter.runOnChangeAndPublish` использовали общий путь публикации.

**1.5. Документация.** `runtime` перестаёт быть read-only: обновить
[ARCHITECTURE.md](ARCHITECTURE.md) (он и так врёт про `tagId`, см. §11 MONITOR_CONTEXT)
и таблицу скриптового слоя в §8 MONITOR_CONTEXT — добавить строку про команды.

## Часть 2. Стенд и сценарий (бэк)

**2.1. Kafka видна из контейнеров.** Сейчас `advertised.listeners=PLAINTEXT://localhost:9092`
— контейнер получит метаданные с `localhost` и не подключится. В
`C:\kafka_2.13-4.3.1\config\server.properties`:
```properties
listeners=PLAINTEXT://:9092,DOCKER://:19092,CONTROLLER://:9093
advertised.listeners=PLAINTEXT://localhost:9092,DOCKER://host.docker.internal:19092,CONTROLLER://localhost:9093
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,DOCKER:PLAINTEXT,SSL:SSL,SASL_PLAINTEXT:SASL_PLAINTEXT,SASL_SSL:SASL_SSL
```
Требует рестарта брокера.

**2.2. Разрешить запись в клапаны.** Скопировать `replay_config.yaml` из образа
симулятора в `docker/sim/replay_config.yaml`, поменять `access: RO` → `RW` у тегов
`385` и `389`. Образ **не пересобирается** — файл монтируется томом. Обоснование:
`plc.py:180` `set_writable(access == "RW")`, а `plc.py:311` для RW-тегов читает узел
обратно вместо затирания архивом («иначе записанная команда жила бы 0.5с»).
В поставке все 2471 тегов — `RO`, поэтому без этой подмены шлюз получит `BadNotWritable`.

**2.3. `docker-compose.producer.host.yml`** — продюсер против **нативного** брокера:
`scada-postgres` + `scada-simulator` + `scada-gateway`,
`SPRING_KAFKA_BOOTSTRAP_SERVERS: host.docker.internal:19092`,
`KAFKA_TOPICS_TELEMETRY: scada.tags`, `SIM_HOST: scada-simulator`, том из 2.2.
Существующий `docker-compose.producer.yml` рассчитан на Kafka **внутри** compose и
здесь не годится. Образы — `docker load` из `deliver/images/*.tar.gz` (Kafka-образ не нужен).

**2.4. Сценарий в BN1_MCA1.**

> **Важно про область видимости `writeTag`.** `TagCommandService.sinkFor(session, componentId)`
> резолвит имя свойства **в пределах того компонента, которому принадлежит скрипт**.
> Поэтому кнопке нужно **собственное свойство-тег**, указывающее на тег клапана, —
> иначе `writeTag('ST', …)` не найдёт, что писать. Это заодно и «свойство-якорь»,
> которого требует контракт привязок.

Отдельного API для `editor.scripts` нет (`ComponentController` умеет только целое
дерево, `ComponentPropertyController` — свойства), поэтому сценарий заводится SQL —
точечно и без риска перезаписать дерево:

```sql
-- 1. Клапан1: свойство было безымянным — без имени оно не видно ни скриптам, ни привязкам
UPDATE editor.component_property SET name = 'ST' WHERE id = 22;

-- 2. Кнопке — своё свойство-тег на клапан №2 (цель команды) + якорь для привязок.
--    default_value обязателен непустой: NULL уронит создание сессии (§9 MONITOR_CONTEXT).
INSERT INTO editor.component_property
  (component_id, name, property_type, tag_id, description, value_type, default_value, logging, on_change)
VALUES
  (962, 'ST', 'Тег', 'Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST', '', '', '', false, '');

-- 3. Серверные скрипты кнопки — единственное, что пишет в ПЛК
INSERT INTO editor.scripts (component_id, name, script) VALUES
  (962, 'Открыть клапан', 'writeTag(''ST'', true);'),
  (962, 'Закрыть клапан', 'writeTag(''ST'', false);');

-- 4. Клапан2: не-теговое свойство под результат серверного onChange
INSERT INTO editor.component_property
  (component_id, name, property_type, tag_id, description, value_type, default_value, logging, on_change)
VALUES
  (968, 'Состояние', 'Строка', '', '', '', 'неизвестно', false, '');

-- 5. onChange на теговом свойстве клапана: отражает положение в свойство «Состояние».
--    Формат — сырой JS (унифицирован 16.07.2026, §8 MONITOR_CONTEXT).
UPDATE editor.component_property
   SET on_change = 'props["Состояние"] = tag ? "Открыт" : "Закрыт";'
 WHERE id = 21;
```

Заполненность: свойство `fullness`(23) обновляется значением своего тега — на нём и
держится прогресс-бар (нужен фикс 3.2, поэтому визуально заработает после Части 3).

## Часть 3. Фронт — описание работ (по факту кода на 22.07.2026, фронт не трогаем)

Путь фронта (`Z:\Project Java\scada-editor-frontend`, слой `src/lib/runtime/`):
клик по фигуре в мониторе → `emitRuntimeEvent` (`runtimeEventBus.ts`) →
`useRuntimeEngine.runEvent` компилирует и исполняет `element.events.onClick` через
`eventScript.ts`. Обратно: WS `UPDATE {tags, properties}` → `openRuntimeConnection.onUpdate`
(`runtimeConnection.ts`) кладёт в буферы → `flush` (5 Гц) исполняет затронутые биндинги
(`executeBinding`) и одним `applyRuntimeBatch` перерисовывает сцену.

**3.1. Оживить `sendAction` — единственная нить между кликом и нашим бэком.**
`runtimeConnection.ts:160` экспортирует `sendAction(scriptId)` (`{"type":"ACTION",scriptId}`),
но **никто его не зовёт**: `conn` создаётся в `useEffect` (`useRuntimeEngine.ts:234`) и до
`runEvent` (`:178`, `useCallback` со стабильными ref) не доходит. Нужно: держать `conn` в
`connRef` и дать обработчику клика функцию `runScript("имя")`. Резолв: `el.scripts`
(`ElementScript{ id: string; name; content }`, `editorElement.type.ts:35`) → `sendAction(Number(script.id))`.
Внедрение — в `eventScript.ts`: добавить `"runScript"` в параметры `new Function(...)`
(`compileEventScript`) и прокинуть колбэк в `executeEventScript`; заодно внести `runScript`
в `RESERVED_WORDS` (`bindingScope.ts:8`), чтобы одноимённое свойство его не затеняло.
Тогда `onClick` кнопки = `runScript("Открыть клапан")` — и клик уходит в наш `ACTION`.

**3.2. Сырое значение тега → в буфер свойств (прогресс-бар).** Прогресс-бар своих
свойств не имеет и ссылается на `fullness`(23) через `propertyRefs`, т.е. читает
`valuesByPropRef` по propertyId. Но сырое значение тега приходит в `pending`/`valuesRef`
по `tag_id` (`useRuntimeEngine.ts:73-78`), в буфер свойств не попадая. Нужно: в
`bindingIndex.ts` построить `tag_id → propertyId[]` из `el.properties` (там есть и `id`,
и `tag_id`), и в `flush` при изменении тега писать значение и в `valuesByPropRef` для
связанных свойств. Чинит `direct`-привязки и прогресс-бар. (Альтернатива — бэковый
`TagSubscriptionIndex.rawPropertyIdsForTag`, но он дороже по трафику и сейчас не вызывается.)

**3.3. Не ронять сейв сцены.** Бэк требует, чтобы `bindings[].component_property_id`
ссылался на существующее свойство ЭТОГО компонента; у кнопки без свойств это `0` → сейв
всей сцены падает 400. Гейт уже есть в UI (`bindingScope.ts:hasSavedProperty` / `BindingsTab`),
но в `encodeBindings` (`buildComponentTree.ts`) нужно пропускать такие привязки с
`console.warn`, чтобы молчаливого падения всего сохранения не было. У кнопки теперь есть
свойство-якорь `ST`(24) (шаг 2.4), так что для сцены это подстраховка.

**3.4. Привязки сцены** (заводятся в редакторе после 3.1–3.3, чистая отрисовка — фронт):
Клапан2 — `if (ST.V === true) setState("Нормальное"); else setState("Закрыт")`;
Клапан1 — то же на «Нормальное»/«Зактрыто» (имена состояний в БД именно такие,
с опечаткой); прогресс-бар — `direct`-привязка на `fullness`(23) через `propertyRefs`.

**Что видно на практике** (при нашем `idNode`-контракте, симулятор чистую команду не
применяет — и это ок):
- клик → `runScript` → `ACTION` → бэковый `Script` → `writeTag` → команда `{idNode,value}`
  в `scada-commands` (видно в топике и логе runtime `Command sent: idNode=…`);
- `onChange` клапана (свойство 21) при смене тега телеметрией → свойство «Состояние»(25) =
  «Открыт/Закрыт» → `PROPERTY_UPDATE` всем → биндинг на «Состояние» перерисовывает;
- перекраска клапана и прогресс-бар — чистая отрисовка из живой телеметрии (фронт),
  работает независимо от того, применил ли симулятор команду.

---

## Верификация

Снизу вверх, каждый шаг — до перехода к следующему:

1. **Телеметрия жива:** `kafka-get-offsets.bat --bootstrap-server localhost:9092 --topic scada.tags` — offset растёт.
2. **Теги в индексе:** в логе `runtime` — `Runtime session … started for project 950 (3 tags)`.
   `0 tags` = свойства не распознаны.
3. **Реестр наполнился:** в `GatewayTagRegistry` есть запись для `…V_ST_1.LINE1V0.ST`.
4. **Команда уходит:** консьюмер на `scada-commands` видит `CommandMessage` с
   `tagName: "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST"`, `dataType: "BOOLEAN"`, `value: true`.
5. **ПЛК принял:** на `scada-command-results` — `success: true`.
   `BadNotWritable` = не применился том из 2.2.
6. **Круг замкнулся:** в `scada.tags` по ключу того же тега приходит новое значение.

Шаги 3–6 гоняются node-скриптом по образцу `monitor-e2e.js` (§7 MONITOR_CONTEXT):
`POST /api/runtime/sessions` → WS → `{"type":"ACTION","scriptId":N}` → чтение обоих
топиков. Автотестов в `runtime` нет вообще (`src/test` отсутствует) — проверка ручная.

Сквозная проверка в браузере (клик → перекраска клапана, прогресс-бар) станет
возможна только после Части 3.

## Риски

- **`__TypeId__`.** Неверный заголовок — шлюз молча отбросит команду. Ловится шагом 4→5.
- **Холодный старт.** Команду можно послать только после первого прихода тега
  телеметрией: `auto.offset.reset=latest`, полный круг шлюза по 2471 каналу ~90 с.
  Если окажется неудобным — запасной вариант: разовый батч-резолв в `channel`.
- **Реплей против команды.** Если том 2.2 не подхватился, значение вернётся через ~0.5 с.
- **Скрипты на потоке консьюмера.** `@EventListener` синхронный (§10 MONITOR_CONTEXT);
  команда добавляет к `onChange` ещё и сетевой вызов Kafka. На 3 тегах некритично,
  при росте — выносить в отдельный executor.

---

## Статус выполнения

Остановлено 21.07.2026 по просьбе заказчика. Ветка `microservice`, не коммичено.

| Шаг | Статус |
|---|---|
| 1.1 `GatewayTagRegistry` + наполнение из телеметрии | **готово** |
| 1.2 `CommandProducer` + топик в конфиге | **готово** |
| 1.3 `writeTag` в скриптах (`TagWriteSink`, `ProxyExecutable`) | **готово** |
| 1.4 Подключение к `ACTION` и `onChange` | **готово** |
| Компиляция `:runtime:compileJava` | **проходит** |
| 1.5 Документация (`ARCHITECTURE.md`, §8 `MONITOR_CONTEXT.md`) | не начат |
| 2.1 Kafka DOCKER-listener | **готово, брокер перезапущен** |
| 2.2 `docker/sim/replay_config.yaml` с RW у 385/389 | **готово** |
| 2.3 `docker-compose.producer.host.yml` | файл готов, **запуск упёрся в ошибку** |
| 2.4 Сценарий в БД (SQL выше) | не начат |
| Верификация (6 шагов) | не начата |
| Часть 3 (фронт) | описано, в этот заход не реализуется |

### Что сделано в коде (`runtime`, все файлы новые или дополненные)

| Файл | Что |
|---|---|
| `kafka/GatewayTagRegistry.java` | **новый.** `tagName → {gatewayTagId, dataType}`, наполняется из конверта телеметрии |
| `kafka/CommandProducer.java` | **новый.** Публикация `CommandMessage` с заголовком `__TypeId__` |
| `script/TagWriteSink.java` | **новый.** Приёмник вызовов `writeTag` из скрипта |
| `session/TagCommandService.java` | **новый.** Резолв «имя свойства → путь тега → реквизиты → отправка» |
| `kafka/TagKafkaConsumer.java` | `extractValue` принимает key и попутно наполняет реестр |
| `script/ScriptEngineService.java` | биндинг `writeTag` через `ProxyExecutable`, сигнатуры `runOnChange`/`runAction` + `TagWriteSink`, прогрев включает `writeTag` |
| `session/TagSubscriptionIndex.java` | карта `propertyId → tag_id`, методы `tagIdOfProperty` / `tagIdOfComponentProperty` |
| `session/RuntimeSessionService.java` | передаёт sink в `runAction` |
| `kafka/TagValueRouter.java` | передаёт sink в `runOnChange` |
| `config/KafkaProperties.java`, `resources/application.yml` | `commands-topic` (env `KAFKA_COMMANDS_TOPIC`) |

Вне репозитория изменён `C:\kafka_2.13-4.3.1\config\server.properties` — добавлен
слушатель `DOCKER://:19092` с advertised `host.docker.internal:19092`.

### На чём остановились

`docker compose -f docker-compose.producer.host.yml up -d` падает:

```
unable to get image 'localhost/scada-simulator:1.0': request returned 500 Internal Server Error
```

Образы загружены и видны в `docker images` (`localhost/scada-simulator:1.0`,
`localhost/scada-gateway:1.0`, `postgres:16`). Причина, скорее всего, в префиксе
`localhost/` — он подставлен rootless-podman'ом при сборке, и Docker-движок
спотыкается при разборе такого имени как registry-хоста.

**Первое, что попробовать при возобновлении** — перетегировать и убрать префикс:

```bash
docker tag localhost/scada-simulator:1.0 scada-simulator:1.0
docker tag localhost/scada-gateway:1.0  scada-gateway:1.0
```
затем в `docker-compose.producer.host.yml` заменить `image: localhost/scada-…:1.0`
на `image: scada-…:1.0` (в двух сервисах).

### Как продолжить

Скажите: **«продолжи по docs/SCRIPTS_PLAN.md»** — этого достаточно, весь контекст в этом файле.

Порядок возобновления:

1. **Проверить инфраструктуру.** PostgreSQL (5432) и Redis (6379) — служба и процесс;
   Kafka должна слушать **и 9092, и 19092** (второй — для контейнеров).
   Если Kafka не поднята:
   `Start-Process powershell -ArgumentList '-NoExit','-Command',"Set-Location 'C:\kafka_2.13-4.3.1'; .\bin\windows\kafka-server-start.bat .\config\server.properties"`
2. **Поднять сервисы:** `.\start-all.ps1 -ServicesOnly` (нужны как минимум editor:8083 и runtime:8085).
3. **Починить запуск продюсера** — перетегировать образы (см. выше), затем
   `docker compose -f docker-compose.producer.host.yml up -d`.
   Убедиться, что шлюз подключился к брокеру: `docker logs scada-gateway | grep -i kafka`.
4. **Дождаться телеметрии:** offset топика должен расти —
   `C:\kafka_2.13-4.3.1\bin\windows\kafka-get-offsets.bat --bootstrap-server localhost:9092 --topic scada.tags`.
   Полный круг по 2471 каналу ~90 секунд.
5. **Завести сценарий** — SQL из шага 2.4 выше.
6. **Перезапустить `runtime`** (подхватить новый код и свойства) и пройти
   6 шагов раздела «Верификация».
7. Закрыть шаг 1.5 (документация) и отдать Часть 3 фронту.

Полезные команды проверки:
```bash
# что видит шлюз в топике команд
docker exec scada-kafka true 2>/dev/null || echo "брокер на хосте, не в контейнере"
C:\kafka_2.13-4.3.1\bin\windows\kafka-console-consumer.bat --bootstrap-server localhost:9092 \
  --topic scada-commands --property print.key=true --timeout-ms 10000
C:\kafka_2.13-4.3.1\bin\windows\kafka-console-consumer.bat --bootstrap-server localhost:9092 \
  --topic scada-command-results --timeout-ms 10000
```

---

## Обновление 22.07.2026 — модель команды переведена на `idNode`

**Что выяснилось про стенд.** Дизассемблирование `scada-gateway` показало, что шлюз
внутренне непоследователен: **телеметрия** публикует `tagId = channel_id` (для клапана
`V_ST_1.LINE1V0.ST` это 385, = номер OPC UA-узла `ns=2;s=385`), а **команда**
(`writeTag` → `tagCache.get(id)`) ждёт **внутренний генерируемый PK** (`id = 1`).
Поэтому взятый из телеметрии `385` в пространстве `id` попадает в чужой Modbus-тег
контроллера 2 → `success:false, «Контроллер не подключён»`. Проверено: команда с
`tagId=1` → `success:true, «Записано значение true»`, тег в `scada.tags` стал `true`.
Подробности — в памяти проекта `gateway-telemetry-vs-command-id`.

**Принятое решение.** Данные из Kafka — это симуляция; **не подстраиваемся** под её
особенности (числовой id, «Modbus только чтение», холодный старт). Наш контракт —
**только строка**: приходит `idNode` + `value`, обратно шлём то же самое,
протокол-агностично (OPC UA/Modbus выбирает драйвер устройства по `idNode`). Числовой
id из плана (шаги 1.1–1.3, заголовок `__TypeId__`, реестр из телеметрии) — **отменён**.

**Что стало в коде (`:runtime:compileJava` — `BUILD SUCCESSFUL`):**

| Файл | Изменение |
|---|---|
| `kafka/CommandProducer.java` | шлёт чистый конверт `{commandId, idNode, value, dataType, requestedBy, timestamp}`, key = `idNode`; без числового id и без `__TypeId__` |
| `session/TagCommandService.java` | резолв «имя свойства → `idNode`» (через `TagSubscriptionIndex`) → `CommandProducer.send(idNode, value)` |
| `kafka/TagKafkaConsumer.java` | извлекает только `value` из конверта; наполнение реестра убрано |
| `kafka/GatewayTagRegistry.java` | **удалён** (был мостом к числовому id шлюза) |
| `script/ScriptEngineService.java`, `script/TagWriteSink.java` | без изменений — движок и так не знает про Kafka |

### Разграничение фронт/бэк (уточнено)

Правило: **влияет на объект или на общее состояние — бэк; только рисует — фронт.**

| Что | Слой | Механизм в коде | Триггер |
|---|---|---|---|
| Запись тега / команда в ПЛК | **бэк** | `writeTag()` в `Script` → `TagCommandService` → `CommandProducer` | `ACTION` по WS |
| Производное состояние, одинаковое для всех операторов | **бэк** | `onChange` свойства → `ScriptEngineService.runOnChange` → PROPERTY_UPDATE всем | изменение привязанного тега |
| Отрисовка (цвет, текст, размер, прогресс) | **фронт** | `Binding.script` (недоверенный, локальный у оператора) | значения тегов/свойств из WS |
| Реакция на клик оператора | **фронт → бэк** | `events.onClick`; если пишет тег — делегирует бэку через `ACTION` | клик |

Почему так: бэковый скрипт исполняется один раз на весь стенд (консистентно и безопасно,
но занимает поток консьюмера), фронтовый — у каждого оператора (бесплатно для сервера,
но недоверенно и локально). Запись тега и общее производное состояние обязаны быть
одинаковыми для всех — значит бэк; чистая отрисовка у каждого своя — значит фронт.

### Состояние стенда

Поднято в порядке «сначала Kafka/контейнеры, потом сервисы» (ограничение по ОЗУ):
Kafka (9092+19092), docker-стек продюсера (телеметрия идёт), `editor:8083`, `runtime:8085`,
сценарий сцены заведён SQL. Часть 3 (фронт) — по-прежнему только описание.
