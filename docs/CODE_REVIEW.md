# Ревью кода: костыли, неточности, недостающие проверки

Выборочный проход по `runtime`, `editor`, `channel`, `gateway`, `auth` от 2026-07-23
(ветка `microservice`, коммит `77d6ebf`). Порядок — по убыванию серьёзности.

**Прогресс разбора (обновлено 2026-07-27):** исправлены пункты **#4, #8, #9** и
**#13** (кроме подпункта про JWT-секрет — отложен осознанно). Исправленное помечено
`✅`. Остальное — к разбору.

---

## Реальные падения

### 1. NPE при старте сессии, если у свойства нет `default_value` ✅

> **Исправлено 2026-07-27** (вместе с #2 — единая модель null). Свойства без
> `default_value` не кладутся в `initialPropertyValues`; отсутствие ключа = «значение
> не задано» и одинаково трактуется по всей цепочке (`propertyValues`, `props` скрипта).

**Где:** `runtime/.../session/TagSubscriptionIndex.java:32,61`

```java
private final Map<Long, Object> initialPropertyValues = new ConcurrentHashMap<>();
...
initialPropertyValues.put(propertyId, property.getDefault_value());
```

`ConcurrentHashMap` не принимает `null`-значения, а `default_value` в БД
необязателен. Одно свойство без дефолта — и `TagSubscriptionIndex.build()` падает
NPE, сессия не создаётся вовсе (клиент получает 500 от `POST /api/runtime/sessions`).

**Чинить:** либо `HashMap` для начальных значений, либо явный sentinel вместо `null`,
либо пропускать свойства без дефолта. Учесть, что то же значение потом кладётся в
`RuntimeSession.propertyValues` — тоже `ConcurrentHashMap`.

---

### 2. NPE и утечка контекста GraalVM, если скрипт присвоит `props.x = null` ✅

> **Исправлено 2026-07-27.** (1) `props` теперь `HashMap` (короткоживущая, однопоточная),
> поэтому допускает null; `propertyValues` остаётся `ConcurrentHashMap`, но null в него
> не пишется — сброс свойства = удаление ключа (`storePropertyValue`), а `Map.copyOf`
> заменён на `new HashMap<>(props)`. (2) `MapProxyObject.putMember` приводит значение к
> контекстонезависимому Java-объекту через новый `GraalValues.toJava` (тот же конвертер
> использует `ScriptEngineService`), поэтому ссылок на закрытый контекст не остаётся.

**Где:** `runtime/.../script/MapProxyObject.java:37`, `runtime/.../kafka/TagValueRouter.java:118`

```java
map.put(key, value.isNull() ? null : value.as(Object.class));
```

Две проблемы в одной строке:

1. `props` — это `ConcurrentHashMap` (создаётся в `TagValueRouter:118` и
   `RuntimeSessionService:95`), поэтому `props.x = null` в скрипте даёт NPE прямо
   внутри движка.
2. `value.as(Object.class)` для JS-объекта или массива возвращает значение,
   привязанное к живому контексту. Контекст сразу после выполнения уходит обратно
   в пул (`release(ctx)`) и переиспользуется другим потоком, а это значение к тому
   моменту уже лежит в `session.propertyValues` и позже сериализуется Jackson'ом
   в WS-фрейм. Итог — либо `Context is closed`, либо гонка.

**Чинить:** приводить значение к Java-примитиву прямо в `putMember`, ровно как это
уже делает `ScriptEngineService.toJavaValue()`, и решить, чем представлять `null`.

---

### 3. Контекст без прогрева = гарантированный таймаут ✅

> **Исправлено 2026-07-27.** `borrow()` (пул исчерпан) и `replace()` (после сбоя)
> теперь прогревают новый контекст через `warmUp()` — самоподдерживающийся сбой из
> холодных контекстов устранён. В ветке таймаута добавлен `future.cancel(true)`, чтобы
> не оставлять висящий поток `script-exec` на зациклившемся скрипте.

**Где:** `runtime/.../script/ScriptEngineService.java:218,239`

В javadoc `warmUp()` (строки 71–76) прямо написано: первый `eval` на свежем
контексте инициализирует движок и **не укладывается в `timeoutMs`**. Но оба места,
где контекст создаётся после старта приложения, прогрев не делают:

```java
// borrow(), пул исчерпан:
log.warn("Script context pool exhausted, creating a temporary context");
return newContext();          // без warmUp

// replace(), после сбоя/таймаута:
pool.offer(newContext());     // без warmUp
```

Получается самоподдерживающийся сбой: один таймаут кладёт в пул холодный контекст,
следующее выполнение на нём снова таймаутит, снова кладёт холодный, и так далее.

Там же: при `future.get(...)` по таймауту `future` **не отменяется** — поток
`script-exec` остаётся висеть на зациклившемся скрипте, хотя контекст уже
закрывается из-под него.

**Чинить:** вызывать `warmUp()` для любого нового контекста; добавить
`future.cancel(true)` в ветке таймаута.

---

### 4. Redis-локи: два NPE и неатомарный unlock ✅

> **Исправлено 2026-07-27.** `Boolean.TRUE.equals(...)` вместо анбоксинга в `tryLock`;
> `unlock` переписан на атомарный compare-and-delete через Lua-скрипт (снимает лок,
> только если он ещё принадлежит вызывающему); все ключи получили префикс `lock:`.

**Где:** `channel/.../service/imlp/LockServiceImpl.java:25,39-43`

```java
if(redis.opsForValue().setIfAbsent(idNode, userId.toString(), Duration.ofSeconds(LOCK_TTL_SECONDS))){
```
`setIfAbsent` возвращает `Boolean`; при `null` (а он возможен) авто-анбоксинг даёт NPE.

```java
if(redis.opsForValue().get(idNode)==null) { ... }
else if(redis.opsForValue().get(idNode).equals(userId.toString())){
```
Два отдельных `get`: между ними ключ может истечь по TTL — второй вернёт `null`,
и `.equals()` падает NPE. Плюс сравнение владельца и удаление не атомарны: чужой
лок, взятый в промежутке, будет снят.

Отдельно: ключ кладётся в Redis голым `idNode`, без префикса — общее пространство
ключей со всем остальным, что там лежит.

**Чинить:** `Boolean.TRUE.equals(...)` вместо анбоксинга; unlock — через Lua
compare-and-delete одним вызовом; префикс `lock:` для ключей.

---

### 5. `children` без null-check, хотя `states` проверяется ✅

> **Исправлено 2026-07-27.** Обход `children` обёрнут в `if (dto.getChildren() != null)`
> по образцу `states`: `clear()` выполняется всегда, наполнение — только при непустом поле.

**Где:** `editor/.../service/Impl/ComponentServiceImpl.java:139,151`

```java
if (dto.getStates() != null) { ... }        // проверено
...
List<Component> children = dto.getChildren().stream()   // не проверено -> NPE
```

Клиент, приславший компонент без `children`, получает 500 вместо внятного 400.

---

### 6. `GET /api/editor/components` отдаёт JPA-сущности напрямую ✅

> **Исправлено 2026-07-27.** `getAll` теперь возвращает `List<ComponentResponseDto>`
> через существующий `componentMapper.toDtoList` (как и `getById`): `parent_id` —
> скаляр, `children` рекурсят только вниз, цикла и `LazyInitializationException` нет.
> Эндпоинт задокументирован в `API.md`, поэтому починен, а не удалён.

**Где:** `editor/.../controller/ComponentController.java:76`, `editor/.../model/component/Component.java:38-43`

```java
@GetMapping
public List<Component> getAll() { return service.getAll(); }
```

У `Component` двунаправленная связь `parent` ⇄ `children` без `@JsonIgnore` /
`@JsonManagedReference`. Jackson уходит в бесконечную рекурсию, а `parent` ещё и
`FetchType.LAZY` — вне транзакции это `LazyInitializationException`. Эндпоинт,
судя по всему, нерабочий; никакой DTO для него нет.

---

## Костыли и неточности

### 7. Bean Validation не используется вообще ✅

> **Исправлено 2026-07-27.** `spring-boot-starter-validation` подключён в `runtime`,
> `auth`, `channel`, `editor`. Ограничения и `@Valid` на входных точках:
> `CreateSessionRequest.projectId` `@NotNull` (закрыт баг с `/components/null`);
> `RegisterDto` — `@NotBlank @Size` на логин/пароль; `CreateNodeDto` — `@NotNull type`,
> `@NotBlank idNode`; `PropertyCreateDto.name` `@NotBlank` (только на POST — PUT-обновление
> оставлено свободным для частичных правок). В `runtime`/`editor`/`channel`
> `GlobalExceptionHandler` добавлен обработчик `MethodArgumentNotValidException` → 400
> (иначе широкий `@ExceptionHandler(Exception.class)` вернул бы 500). В `auth` своего
> advice нет — там 400 отдаёт Spring Boot по умолчанию.

Ни одного `@Valid` в проекте, `spring-boot-starter-validation` не подключён.
Следствия:

- `CreateSessionRequest.projectId == null` → `EditorClient` уходит по URI
  `/api/editor/components/null` (`runtime/.../client/EditorClient.java:30`);
- `RegisterDto` принимает пустой логин и пароль из одного символа
  (`auth/.../controller/AuthController.java:31`);
- `CreateNodeDto`, `PropertyCreateDto` — без ограничений на обязательные поля.

---

### 8. Все три `GlobalExceptionHandler` глотают стектрейс ✅

> **Исправлено 2026-07-27.** В `runtime`, `editor`, `channel` на классы навешен
> `@Slf4j`, в `handleGeneral` добавлен `log.error("Unhandled exception -> 500", ex)`.
> Тело ответа клиенту не изменилось.


**Где:** `runtime/.../exception/GlobalExceptionHandler.java:25`,
`editor/.../exception/GlobalExceptionHandler.java:24`,
`channel/.../exception/GlobalExceptionHandler.java:34`

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
    return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
}
```

`ex` не используется — ни `log.error`, ни чего-либо ещё. Любая 500-я исчезает
бесследно, отладка идёт вслепую. Самая дешёвая правка с наибольшей отдачей.

---

### 9. Разнобой в кодах ошибок, ломающий межсервисный контракт ✅

> **Исправлено 2026-07-27.** `editor` `getById` бросает `NotFoundException` (404);
> в `channel` `ParamServiceImpl` все четыре сырых `RuntimeException` заменены на
> `NotFoundException` (404). Теперь при 404 от editor у runtime срабатывает
> `handleUpstreamNotFound` — межсервисный контракт замкнут. Осталось на заметку:
> `IllegalStateException("… not found")` в `buildComponent`/`updateComponent` при
> create/update по-прежнему дают 400 (не трогалось, спорно между 400 и 404).


- `editor/.../service/Impl/ComponentServiceImpl.java:84` — `getById` бросает
  `IllegalArgumentException` → **400 вместо 404**, хотя `editor.exception.NotFoundException`
  в проекте существует и обработчик под него есть.
- `channel/.../service/imlp/ParamServiceImpl.java:47,55,58,79` — сырые
  `new RuntimeException("Node not found")` → **500 вместо 404**, при том что
  `channel.exception.NotFoundException` тоже есть.

Практическое последствие: `runtime/.../exception/GlobalExceptionHandler.java:15`
ловит от editor именно `HttpClientErrorException.NotFound` — которого он никогда
не получит, потому что editor на несуществующий проект отвечает 400.

---

### 10. Брошенные runtime-сессии живут вечно ✅

> **Исправлено 2026-07-27.** Добавлен `RuntimeSessionReaper` — `@Scheduled`-планировщик,
> раз в минуту закрывающий сессии без живого WebSocket старше
> `runtime.session.abandoned-timeout-minutes` (по умолчанию 5 мин) через
> `sessionService.closeSession` (снимает и подписки в `TagValueRouter.tagStates`).

**Где:** `runtime/.../session/RuntimeSessionStore.java`

Сессия создаётся по REST, WS может не подключиться никогда. Ничто не чистит
`RuntimeSessionStore` и подписки в `TagValueRouter.tagStates`, кроме явного
`DELETE /api/runtime/sessions/{id}` или закрытия WS. Каждая брошенная сессия
продолжает получать значения тегов в свой буфер (буфер ограничен 10 000 записей —
`SessionOutboundBuffer:21` — но сама сессия и её индекс остаются в памяти).

**Чинить:** планировщик, закрывающий сессии без WS дольше N минут; у сессии уже
есть `createdAt`.

---

### 11. `sourceCache` растёт без ограничения ✅

> **Исправлено 2026-07-27.** `sourceCache` — теперь ограниченный LRU
> (`Collections.synchronizedMap` над `LinkedHashMap` с `removeEldestEntry`, предел
> `MAX_CACHED_SOURCES = 512`). Вытесненный `Source` при надобности пересоздаётся.

**Где:** `runtime/.../script/ScriptEngineService.java:46,123`

```java
private final Map<String, Source> sourceCache = new ConcurrentHashMap<>();
...
sourceCache.computeIfAbsent(scriptSource, s -> Source.create("js", s));
```

Ключ — весь текст скрипта. Каждая правка скрипта в редакторе добавляет новую
запись навсегда, старая не вытесняется.

---

### 12. WebSocket без аутентификации 🔶

> **Частично исправлено 2026-07-27.** Проблема дублирующего подключения закрыта:
> `RuntimeWebSocketHandler.afterConnectionEstablished` отклоняет второе подключение,
> если у сессии уже есть живой WS, а `afterConnectionClosed` рвёт сессию только когда
> уходит текущий WS (устаревшее/отклонённое подключение её не трогает).
> **Осталась аутентификация WS** (пункты про gateway-маршрут и `permitAll`) — это
> изменение контракта с фронтом, вынесено на отдельное решение.

- В `gateway/src/main/resources/application.yml` нет маршрута на `/ws/runtime/**` —
  только `/api/runtime/**`. Значит фронт ходит на runtime:8085 напрямую, минуя
  gateway и его `JwtAuthenticationFilter`.
- `runtime/.../config/SecurityConfig.java:23` — `anyRequest().permitAll()`.

Кто знает `sessionId` — подключается. Дополнительно:
`RuntimeWebSocketHandler.afterConnectionEstablished` не проверяет, что к сессии
уже подключён другой WS, — второе подключение молча перезаписывает
`session.webSocketSession`, а закрытие любого из двух убивает сессию для обоих.

---

### 13. Мелочи

> **Исправлено 2026-07-27:** первые пять подпунктов (✅). JWT-секрет (⏸) отложен
> осознанно — это ops/безопасность, а не код-качество; удаление дефолта сломает
> стенд (нужен `JWT_SECRET` в окружении всех сервисов).

- ✅ **Мёртвый эндпоинт.** `channel/.../controller/NodeController.java:21-27` —
  `connectNode` объявлен как `GET`, тип возврата `ResponseEntity<NodeResponse>`,
  фактически возвращает `noContent()`, а сервис
  (`NodeServiceImpl.java:153`) бросает `501 NOT_IMPLEMENTED`.
  → Эндпоинт и метод сервиса (интерфейс + реализация) удалены целиком.
- ✅ **Ловля исключения внутри `@Transactional`.**
  `editor/.../service/UndoService.java:56` — сбойная операция уже пометила
  транзакцию rollback-only, поэтому на коммите всё равно прилетит
  `UnexpectedRollbackException`, а вызывающий по возвращённому списку `failed`
  думает, что остальное прошло.
  → `undoLogs` выполняет каждую отмену в отдельной транзакции через новый бин
  `UndoExecutor` (`REQUIRES_NEW`); сбой одной больше не откатывает остальные,
  список `failed` корректен. `undoBatch` (атомарный по замыслу) не тронут.
- ✅ **Слишком жадный `coerce`.** `runtime/.../kafka/TagValueRouter.java:150-163` —
  `Double.parseDouble` превратит `"NaN"`, `"Infinity"`, `"1d"` в числа, а строковый
  статус `"0012"` — в `12.0`.
  → Перед парсингом строгая проверка регуляркой (без ведущих нулей, без суффиксов,
  без `NaN`/`Infinity`) плюс отсечка не-finite результата (`1e400` → строка).
- ✅ **Молчаливый 401.** `gateway/.../filter/JwtAuthenticationFilter.java:51` —
  `userId.toString()` даёт NPE, если claim `userId` отсутствует; NPE попадает в
  общий `catch (Exception e)` и превращается в 401 без всякого следа в логе.
  → Явная проверка `userId == null || username == null` с `log.warn`; в общем
  `catch` добавлен `log.debug` (SLF4J напрямую — в gateway нет lombok).
- ✅ **Свои `ObjectMapper` вместо бина.** `runtime/.../kafka/CommandProducer.java:48`
  и `runtime/.../kafka/TagKafkaConsumer.java:37` создают `new ObjectMapper()`,
  хотя в контексте есть настроенный бин (его же инжектит `RuntimeWebSocketHandler`).
  → Оба класса получают `ObjectMapper` через конструктор.
- ⏸ **JWT-секрет с fallback в репозитории.** `gateway`, `runtime` и прочие
  `application.yml` содержат `${JWT_SECRET:f8d7e2...}` — рабочее значение по
  умолчанию прямо в коде. Для стенда приемлемо, для прода — нет. *(Отложено.)*
