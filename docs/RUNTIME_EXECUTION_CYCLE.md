# Цикл исполнения: скрипты и биндинги, шаг за шагом

> Как связаны фронт (`scada-editor-frontend`) и runtime (`:8085`) в режиме «Монитор»:
> подробный разбор каждого действия — что происходит, где в коде, какие данные, на
> каком потоке. Составлено 22.07.2026. Связанные документы:
> [FRONTEND_RUNTIME_INTEGRATION.md](FRONTEND_RUNTIME_INTEGRATION.md) (контракт каналов),
> [SCRIPTS_PLAN.md](SCRIPTS_PLAN.md) (сценарий сцены BN1_MCA1).

## Общая картина

Четыре вида логики и где они живут:


| Логика              | Слой    | Триггер                      | Что делает                                       |
| ------------------- | ------- | ---------------------------- | ------------------------------------------------ |
| `Script` компонента | **бэк** | `ACTION` с фронта            | `writeTag(...)` → команда в ПЛК                  |
| `onChange` свойства | **бэк** | смена подписанного тега      | производное свойство → всем операторам           |
| `events[]` `onClick`  | фронт   | клик оператора               | локальная логика + `runScript` → делегирует бэку |
| `Binding.script`    | фронт   | смена тега/свойства в скоупе | `setState`/`setProp` → перерисовка               |


Правило: **пишет объект или общее состояние — бэк; только рисует — фронт.**

---



## Фаза 0. Подготовка сессии (один раз при входе в «Монитор»)

**0.1. Фронт поднимает движок.** `useRuntimeEngine(active)` при `active && projectId`
вызывает `openRuntimeConnection(950)` (`runtimeConnection.ts`). Параллельно
`buildBindingIndex(elements)` компилирует все привязки сцены в индекс: `byTagId`
(тег → какие биндинги он триггерит), `byPropertyId` (свойство → биндинги). Компиляция
один раз, на горячем пути только вызов.

**0.2. Создание сессии (HTTP).** `POST /api/runtime/sessions {projectId:950}` идёт
фронт → BFF (добавляет `Bearer`) → gateway :8080 → runtime. `RuntimeSessionService.createSession`
дёргает `EditorClient.getProjectTree(950)` (GET у editor), строит `TagSubscriptionIndex`
(карты `tag_id → свойства`, `tag_id → onChange`, `имя свойства → tag_id`), регистрирует
сессию в `TagValueRouter`, возвращает `{sessionId, wsPath, projectTree}`.

**0.3. WebSocket.** Фронт открывает **напрямую** `ws://localhost:8085 + wsPath` (не через
gateway — тот не проксирует WS-upgrade). `RuntimeWebSocketHandler.afterConnectionEstablished`
привязывает сокет к сессии. С этого момента канал живой.

---



## Фаза 1. Клик → команда в ПЛК

**1. Клик оператора.** Слой интеракции Canvas (только в мониторе) ловит клик по фигуре и
зовёт `emitRuntimeEvent(elementKey, "onClick")` (`runtimeEventBus.ts`) →
`useRuntimeEngine.runEvent`. Поток: главный JS-поток браузера.

**2. Исполнение** `onClick` **(фронт).** `runEvent` берёт обработчик с `event_type = "onClick"`
из `el.events` (с 23.07.2026 — таблица `editor.component_event`, массив на уровне
компонента; раньше лежало в `image.events` состояния), собирает скоуп
(`collectTagScope(el.properties)` — свойства-теги элемента по имени + `propertyRefs`),
компилирует код через `new Function(...)` (`compileEventScript`) и исполняет
(`executeEventScript`). Код кнопки:

```js
if (ST.V === 'true') runScript('Закрыть клапан'); else runScript('Открыть клапан');
```

`ST` — тег-свойство кнопки; `ST.V` берётся из `valuesRef` (последнее значение тега) через
`buildTagObject`. Скрипту даны функции `setProperty/setProp/setState` (локальные) и
`runScript` (мост к бэку).

**3.** `runScript` **→ резолв скрипта.** `runScript("Открыть клапан")` ищет в `el.scripts`
скрипт с таким **именем**, берёт `Number(script.id)` и зовёт `connRef.current.sendAction(id)`.
Резолв по имени — поэтому смена числового id при пересохранении сцены его не ломает.

**4.** `sendAction` **→ WS.** `runtimeConnection.sendAction` шлёт по сокету
`{"type":"ACTION","scriptId":N}`. На этом работа фронта в цикле команды закончена.

**5. Приём** `ACTION` **(бэк).** `RuntimeWebSocketHandler.handleTextMessage` парсит
`InboundMessage`, видит `type=ACTION` и зовёт `sessionService.handleAction(sessionId, N)`.
Поток: Tomcat NIO (`nio-8085-exec-*`).

**6.** `handleAction` **(бэк).** `RuntimeSessionService` находит `Script` по id в дереве сессии,
собирает `props` (текущие значения свойств компонента), создаёт приёмник записей
`tagCommandService.sinkFor(session, componentId)` и зовёт `scriptEngine.runAction(source, props, sink)`.
Возвращает `List<PropertyUpdate>` — если скрипт менял свойства.

**7.** `runAction` **→ GraalVM (бэк).** `ScriptEngineService` берёт **прогретый** JS-контекст из
пула, биндит `tag=null`, `props`, `writeTag=ProxyExecutable(sink)`, и исполняет исходник на
воркер-потоке (`script-exec`) под watchdog-таймаутом. Скрипт:

```js
writeTag('ST', true);
```

вызывает `ProxyExecutable` → приводит значение к Java-типу (`true`) → `sink.write("ST", true)`.
Движок ничего не знает про Kafka — только «заявка на запись».

**8. Резолв записи → команда (бэк).** `TagCommandService.write(session, componentId, "ST", true)`:
`session.getIndex().tagIdOfComponentProperty(componentId, "ST")` → путь тега
`Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST` (`idNode`). Затем `commandProducer.send(idNode, true)`.

**9.** `CommandProducer` **→ Kafka (бэк).** Собирает JSON
`{commandId, idNode, value, dataType:"BOOLEAN", requestedBy:"scada-runtime", timestamp}`,
ключ = `idNode`, шлёт в топик `scada-commands`. Поток: I/O продюсера.

> Побочно: если `handleAction` вернул изменённые свойства, они **сразу** уходят на WS как
> `UPDATE{tags:null, properties:[…]}` (минуя батч) — мгновенный отклик на действие. У кнопки
> сцены скрипт свойств не меняет, поэтому тут пусто.

---



## Фаза 2. Телеметрия → экран

**10. Симулятор применяет команду.** `tools/kafka-sim/sim.mjs` (consumer `scada-commands`)
читает `{idNode, value}`, кладёт в своё состояние, **сразу публикует новое значение** в    `scada.tags` (ключ = `idNode`, тело `{value}`) и результат в `scada-command-results`. Это
замена реального ПЛК.

**11. Приём телеметрии (бэк).** `TagKafkaConsumer` (поток `kafka-tags-consumer`) поллит
`scada.tags`, для каждого сообщения `extractValue` достаёт поле `value` из конверта и
публикует Spring-событие `KafkaTagMessageEvent(key=idNode, value)`.

**12.** `TagValueRouter` **— маршрутизация и** `onChange` **(бэк).** `@EventListener` (**синхронно,
на том же потоке консьюмера**) обновляет `tagStates[idNode]` и для каждой сессии,
подписанной на этот тег, `dispatchToSession`:

- кладёт `TagUpdate(idNode, value, ts)` в исходящий буфер сессии (для тегов);
- берёт `onChangeBindingsForTag(idNode)` и для каждого зовёт
`scriptEngine.runOnChange(source, tagValue, props, sink)`. `onChange` свойства клапана:

```js
props["Состояние"] = tag ? "Открыт" : "Закрыт";
```

  даёт `PropertyUpdate(25, "Состояние", "Открыт", ts)` → тоже в исходящий буфер.

> `onChange` идёт **на потоке консьюмера** — тяжёлый скрипт тормозит всю телеметрию
> (§10 плана). На 3 тегах некритично; при росте — выносить в отдельный executor.

**13.** `OutboundFlusher` **батчит и шлёт (бэк).** Периодически (~40 мс) сливает буфер сессии в
одно `OutboundMessage{type:"UPDATE", tags:[…], properties:[…]}`, и `RuntimeWebSocketHandler.send`
пишет JSON в сокет.

**14. Приём** `UPDATE` **(фронт).** `runtimeConnection.onmessage` парсит, `onUpdate(tags, properties)`
раскладывает по буферам коалесинга: теги → `pendingRef[tagId]=value`, свойства →
`pendingPropsRef[propertyId]=value`. Last-write-wins на тег.

**15. Тик** `flush` **(фронт, 5 Гц).** `useRuntimeEngine.flush`:

- для изменившихся тегов пишет `valuesRef` и собирает затронутые биндинги из `index.byTagId`;
- для изменившихся свойств — `valuesByPropRef` и `index.byPropertyId`;
- каждый затронутый биндинг исполняет `executeBinding`. Биндинг клапана:

```js
if (ST.V === "true") setState("Нормальное"); else setState("Закрыт");
```

  `ST.V` — из `valuesRef` по `tag_id` (сырое значение тега); интент `setState`;

- все интенты собираются и применяются **одним** `store.applyRuntimeBatch({stateNameByKey, propsByKey})`.

**16. Перерисовка.** Один `set()` в сторе → React/Konva перерисовывают клапан в новом
состоянии → круг меняет цвет (красный «Нормальное» / оранжевый «Закрыт»).

---



## Два триггера — с какого места стартует цикл

- **Действие оператора** (кнопка) стартует с шага 1 и проходит **весь** круг 1→16.
- **Просто телеметрия** (без клика — напр. дрейф `fullness` для прогресс-бара) стартует
сразу с шага **11** и идёт 11→16: пришло значение → `onChange` (если есть) → батч →
`flush` → биндинг → перерисовка. Клик и `writeTag` тут не участвуют.



## Разграничение в терминах шагов


| Шаг                       | Слой    | Почему                                            |
| ------------------------- | ------- | ------------------------------------------------- |
| 2–4 `onClick`/`runScript` | фронт   | реакция на клик конкретного оператора             |
| 6–9 `Script`/`writeTag`   | **бэк** | пишет объект — обязано быть одинаково для всех    |
| 12 `onChange`             | **бэк** | производное состояние, единое для всех операторов |
| 15 `Binding`              | фронт   | только отрисовка, у каждого своя                  |




## Частые грабли (проверено на стенде)

- **Нет** `onClick` **у кнопки** → клик не долетает до `runScript`. Обработчик должен лежать
в `component_event` (в дереве — `events[]` компонента, `event_type = "onClick"`). В `image`
состояния его класть бессмысленно: ключ `events` вычищается при сохранении. Резолв скрипта —
по **имени**, поэтому стоек к смене id.
- `PUT /api/editor/components` **синхронизирует коллекции целиком**: `states`, `scripts`,
`bindings`, `events` заменяются содержимым DTO, отсутствующее поле = пустой список.
Частичный `PUT` тихо стирает состояния и скрипты компонента — сохранять только полным узлом.
- `direct`**-привязка вместо** `setState` для смены вида объекта не годится: она пишет
невидимое свойство `value`. Для перекраски/состояния нужен **code-биндинг с** `setState(...)`.
- `ST.V` **— строка** (`"true"`/`"false"`), не булево (`buildTagObject` парсит только числа).
Сравнивать через `=== "true"`.
- `direct` **на** `propertyId` **тега** читает буфер свойств, куда сырое значение тега не
попадает (дырка 3.2) — там пусто. Тег читается по `tag_id`, не по `propertyId`.

