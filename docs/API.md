# API Reference

Все запросы идут через Gateway на **`http://localhost:8080`**.

Для всех эндпоинтов кроме `/api/auth/**` обязателен заголовок:
```
Authorization: Bearer <JWT_TOKEN>
```

---

## AUTH — `/api/auth`

### `POST /api/auth/register`
Регистрация нового пользователя.

**Body:**
```json
{ "login": "alice", "password": "secret123" }
```
**Response 200:**
```json
{ "token": "eyJ...", "message": "Registered successfully" }
```
**Response 400:** `"User exists"`

---

### `POST /api/auth/login`
Вход. Возвращает JWT токен.

**Body:**
```json
{ "login": "alice", "password": "secret123" }
```
**Response 200:**
```json
{ "token": "eyJ...", "message": "Logged in successfully" }
```
**Response 401:** `"Invalid credentials"`

---

## CHANNEL — `/api/channel`

### Узлы (Node)

#### `POST /api/channel/node`
Создать узел. Автоматически создаёт параметры из шаблона (по `type`).

**Headers:** `X-Username: alice`

**Body:**
```json
{
  "idNode": "pump_01",
  "parentId": "site_A",
  "type": 3
}
```
- `idNode` — локальный идентификатор узла
- `parentId` — если задан, итоговый `idNode` = `parentId.idNode`
- `type` — id шаблона из таблицы `template`

**Response 200:**
```json
{
  "batchId": "uuid...",
  "nodeDTO": { "id": 1, "idNode": "site_A.pump_01" },
  "params": [
    { "id": 10, "idNode": "site_A.pump_01", "name": "Давление", "type": "float", "value": "" }
  ]
}
```

#### `DELETE /api/channel/node/{idNode}`
Удалить узел и все его параметры (атомарно, одним batchId).

**Response 200:**
```json
{ "batchId": "uuid..." }
```

#### `GET /api/channel/node/fullHierarchy?rootPath=site_A`
Получить всё дерево узлов начиная с `rootPath` (рекурсивно — все вложенные).

#### `GET /api/channel/node/hierarchy?rootPath=site_A`
Получить только прямых детей узла `rootPath`.

#### `GET /api/channel/node/sites`
Получить список корневых узлов (без родителя).

#### `GET /api/channel/node/templates`
Получить список шаблонов узлов (id + name).

#### `GET /api/channel/node/{idNode}`
> Возвращает **501 Not Implemented** — функционал подключения к узлу не реализован.

---

### Параметры (Param)

#### `POST /api/channel/param`
Создать параметр вручную.

**Headers:** `X-Username: alice`

**Body:**
```json
{ "idNode": "site_A.pump_01", "id": 5, "value": "42.5" }
```
- `id` — id Description (тип параметра)

#### `DELETE /api/channel/param/{id}`
Удалить параметр по id.

#### `PATCH /api/channel/param/update`
Обновить значения нескольких параметров (поддерживает batch для undo).

**Body:**
```json
[
  { "key": 10, "value": "100.0" },
  { "key": 11, "value": "50.0" }
]
```
При обновлении значения рассылаются подписчикам через WebSocket на топик `/topic/param/{id}`.

#### `GET /api/channel/param/description`
Получить список всех типов параметров (Description).

#### `POST /api/channel/param/kafka-bindings`
Батч-резолв тегов (id параметра-канала) в `idNode` их узла. Используется сервисом `runtime`
при старте сессии мониторинга (разовый вызов на сессию, не на горячем пути). `idNode` — это
Kafka-key, по которому `runtime` находит живое значение тега в едином топике проекта (топик
задаётся конфигурацией `runtime`, а не хранится в `channel`).

**Body:**
```json
{ "ids": [10, 11, 12] }
```
**Response 200:**
```json
[
  { "paramId": 10, "idNode": "site_A.pump_01" }
]
```

---

### Блокировки (Lock)

#### `POST /api/channel/lock`
Захватить список узлов для эксклюзивного редактирования.

**Headers:** `X-User-Id: 42`

**Body:**
```json
["site_A.pump_01", "site_A.pump_02"]
```

#### `POST /api/channel/unlock`
Освободить список узлов.

---

### Undo (channel)

#### `GET /api/channel/undo/logs?from=...&to=...`
Получить лог команд за период (ISO datetime).

#### `GET /api/channel/undo/batch/{batchId}`
Получить все команды конкретного batch.

#### `POST /api/channel/undo/batch/{batchId}`
Отменить весь batch атомарно.

**Headers:** `X-Username: alice`

#### `POST /api/channel/undo`
Отменить конкретные команды по id.

**Body:**
```json
[101, 102, 103]
```
**Response:** список id команд, которые не удалось отменить.

---

## EDITOR — `/api/editor`

### Компоненты (Component)

Иерархия: **Project → Scene → Component → Component...**

#### `POST /api/editor/components/project`
Создать проект (корневой элемент).

**Headers:** `X-Username: alice`

**Body:**
```json
{ "name": "Главный щит" }
```

#### `GET /api/editor/components/projects`
Получить все проекты.

#### `POST /api/editor/components/scene`
Создать сцену (привязать к проекту).

**Body:**
```json
{ "name": "Насосная станция", "project_id": 1 }
```

#### `GET /api/editor/components/scenes?projectId=1`
Получить сцены. Параметр `projectId` опционален — если не задан, вернёт все сцены.

#### `POST /api/editor/components`
Создать один или несколько компонентов (дерево).

**Body:** массив `ComponentCreateDto`:
```json
[
  {
    "name": "Клапан",
    "type": "valve",
    "parent_id": 5,
    "version": 1,
    "states": [
      { "name": "Открыт", "isDefault": true, "image": null }
    ],
    "children": [],
    "scripts": [],
    "bindings": []
  }
]
```

#### `PUT /api/editor/components`
Обновить компоненты (те же поля, `id` обязателен).

#### `DELETE /api/editor/components`
Удалить компоненты по списку id.

**Body:** `[1, 2, 3]`

#### `GET /api/editor/components/{id}`
Получить компонент по id.

#### `GET /api/editor/components`
Получить все компоненты.

---

### Свойства компонентов (Property)

#### `POST /api/editor/properties`
Создать свойство.

**Body:**
```json
{ "name": "color", "value": "#ff0000", "componentId": 10 }
```

#### `PUT /api/editor/properties/{id}`
Обновить свойство.

#### `DELETE /api/editor/properties/{id}`
Удалить свойство.

---

### Шаблоны (Template / FacePlate)

#### `GET /api/editor/templates`
Получить все шаблоны.

#### `GET /api/editor/templates/{id}`
Получить шаблон по id.

#### `POST /api/editor/templates`
Создать шаблон.

**Body:**
```json
{
  "name": "Насос",
  "type": "pump",
  "rootComponent": {
    "key": "root",
    "type": "container",
    "name": "Root",
    "children": [],
    "states": [],
    "properties": [],
    "scripts": []
  }
}
```

#### `PUT /api/editor/templates/{id}`
Обновить шаблон.

#### `DELETE /api/editor/templates/{id}`
Удалить шаблон.

---

### Undo (editor)

#### `GET /api/editor/undo/logs?from=...&to=...`
Лог команд за период.

#### `POST /api/editor/undo/batch/{batchId}`
Отменить batch атомарно.

#### `POST /api/editor/undo`
Отменить конкретные команды по id.

---

## RUNTIME — `/api/runtime` (режим мониторинга)

Read-only сервис: никаких эндпоинтов редактирования модели, только управление сессией
мониторинга. Подробности потока данных — в [ARCHITECTURE.md](ARCHITECTURE.md#runtime--режим-мониторинга-kafka--graalvm-js).

#### `POST /api/runtime/sessions`
Запустить сессию мониторинга проекта. Тянет дерево проекта из `editor` и резолвит
`tagId → idNode` через `channel` (оба вызова разовые, при старте; сами значения тегов далее
идут из единого Kafka-топика проекта по key = `idNode`).

**Body:**
```json
{ "projectId": 1 }
```
**Response 200:**
```json
{
  "sessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "wsPath": "/ws/runtime/3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "projectTree": { "id": 1, "name": "Главный щит", "children": [ /* ... */ ] }
}
```

#### `DELETE /api/runtime/sessions/{id}`
Остановить сессию мониторинга: снимает интерес сессии к своим тегам и освобождает
WS-соединение (сам consumer единого Kafka-топика продолжает работать — он общий для
всего сервиса, не привязан к сессиям).

---

## WebSocket

Подключение через gateway: `ws://localhost:8080/ws` (STOMP). Порт 8082 наружу не
публикуется. Аутентификации на этом эндпоинте пока нет — отдельный шаг плана (B7).

```javascript
const client = new Client({
  brokerURL: 'ws://localhost:8080/ws'
});
client.activate();

// Подписка на обновления параметра
client.subscribe('/topic/param/10', (msg) => {
  console.log('Новое значение:', msg.body);
});
```

При обновлении параметра через `PATCH /api/channel/param/update` сервер рассылает новое значение всем подписчикам топика `/topic/param/{paramId}`.

### WebSocket `runtime` (режим мониторинга)

Подключение через gateway: `ws://localhost:8080/ws/runtime/{sessionId}?token=<jwt>`
(`sessionId` из ответа `POST /api/runtime/sessions`). Raw WebSocket, без STOMP/SockJS —
рассчитан на большой поток частых обновлений тегов.

Токен обязателен и идёт в query: браузерный WebSocket не умеет слать `Authorization`
на апгрейде. Подпись проверяет gateway, runtime получает уже проверенную личность
заголовком `X-Username`. Без токена — `401` на апгрейде (браузер покажет это как
`onerror` + close `1006` без причины).

```javascript
const ws = new WebSocket(`ws://localhost:8080/ws/runtime/${sessionId}?token=${jwt}`);

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  // msg.type === "UPDATE"
  // msg.tags: [{ tagId, value, ts }]        — сырые значения тегов, прогнать через Binding.script
  // msg.properties: [{ propertyId, propertyName, value, ts }] — результат onChange/Script
};

// Нажатие кнопки, привязанной к Script с id = 7
ws.send(JSON.stringify({ type: "ACTION", scriptId: 7 }));
```

Батчи из `tags`/`properties` приходят объединённо, раз в `runtime.flush-interval-ms`
(по умолчанию 40 мс); ответ на `ACTION` приходит отдельным сообщением немедленно, без
ожидания следующего флаша.
