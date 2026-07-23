# Модель данных

## Схема `auth`

### `user`
| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | Автоинкремент |
| `login` | varchar UNIQUE | Логин пользователя |
| `password` | varchar | BCrypt-хэш пароля |

JWT содержит: `sub = login`, `userId = id`.

---

## Схема `channel`

### `node`
| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | Автоинкремент |
| `id_node` | varchar UNIQUE | Путь узла, например `site_A.pump_01` |

Иерархия кодируется прямо в `id_node` через разделитель `.`:
- `site_A` — корень (сайт)
- `site_A.pump_01` — дочерний узел

### `param`
| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | Автоинкремент. Это же значение — конвенция `ComponentProperty.tagId` в `editor` (в виде строки) |
| `id_node` | varchar | Ссылка на `node.id_node`. **Это и есть Kafka-key**, по которому `runtime` берёт живое значение тега |
| `id_type` | bigint | Ссылка на `description.id` |
| `value` | varchar | Текущее значение параметра (обновляется вручную через REST, не связано с Kafka) |

`NodeParam` в этой модели — вспомогательная метаданная (описывает, как опрашивать/типизировать
тег), а не отдельный источник живого значения. Живое значение приходит из Kafka по key,
равному `id_node` соответствующего узла — **один Node = одно живое значение**, даже если под
ним несколько `NodeParam`. Kafka-топик **один общий на весь проект**, задаётся конфигурацией
`runtime` (`KAFKA_TAGS_TOPIC`), не хранится в БД `channel`. `runtime` резолвит
`tagId (NodeParam.id) → idNode` батчем через `POST /api/channel/param/kafka-bindings {ids}`
при старте сессии мониторинга — см.
[ARCHITECTURE.md — Runtime](ARCHITECTURE.md#runtime--режим-мониторинга-kafka--graalvm-js).
Сами значения тегов контроллеры/ПЛК публикуют в Kafka напрямую, `channel` не является
продюсером и не участвует в потоке значений в реальном времени.

### `description`
Справочник типов параметров.

| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | Автоинкремент |
| `name` | varchar | Отображаемое имя (`"Давление"`) |
| `type` | varchar | Тип данных (`"float"`, `"bool"`, ...) |

### `template` / `template_param`
Шаблон узла — задаёт набор параметров, которые создаются автоматически при создании узла с данным типом.

```
template (id, name)
    └── template_param (template_id, description_id, position)  ← составной PK
```

### `command_log` (channel)
| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | |
| `user_name` | varchar | Кто выполнил команду |
| `entity_type` | varchar | `"Node"`, `"NodeParam"` |
| `entity_id` | bigint | Id затронутой сущности |
| `command_type` | varchar | `"CREATE_NODE"`, `"DELETE_PARAM"`, ... |
| `batch_id` | uuid | Группировка связанных команд |
| `sequence` | integer | Порядок внутри batch |
| `payload` | jsonb | Данные команды |
| `undo_payload` | jsonb | Снимок для отмены |
| `undone_at` | timestamp | Когда была отменена (null = активна) |
| `created_at` | timestamp | Время выполнения |

---

## Схема `editor`

### `component`
Основная сущность — элемент визуализации.

| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | |
| `name` | varchar | Имя компонента |
| `type` | varchar | `"project"`, `"scene"`, или пользовательский тип |
| `parent_id` | bigint FK → component | Родитель (null = корень) |
| `version` | bigint | Optimistic locking версия |

**Иерархия типов:**
```
project
  └── scene
        └── component (любой тип)
              └── component (любой тип)
                    └── ...
```

### `component_state`
Визуальное состояние компонента (напр., "Открыт" / "Закрыт").

| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | |
| `component_id` | bigint FK | |
| `name` | varchar | Название состояния |
| `image` | jsonb | Изображение состояния — **только графика**. Обработчики событий здесь не хранятся: `image.events` вычищается при сохранении (`ComponentServiceImpl.stripEvents`), их место — `component_event` |
| `is_default` | boolean | Состояние по умолчанию |

### `component_event`
Обработчик события компонента (клик оператора и т.п.). Принадлежит **компоненту**, а не
состоянию: кнопка кликабельна независимо от того, какой картинкой она сейчас нарисована.

| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | |
| `component_id` | bigint FK → component | Удаление каскадируется на уровне JPA (`cascade = ALL`, `orphanRemoval`), как у `binding`/`scripts` |
| `event_type` | varchar(32) | `onClick`, `onDoubleClick`, `onMouseDown`, `onMouseUp`, `onHover`, `onHoverOut`, `onOpen`, `onClose`. Ограничен CHECK — в отличие от `property_type`, это не свободная строка |
| `script` | text | Код обработчика, исполняется **фронтом** |

`UNIQUE (component_id, event_type)` — один обработчик на событие у компонента.

Все события клиентские: событие порождает жест конкретного оператора, поэтому обработчик
локален по природе. Колонки «где исполнять» нет и не нужно — серверная логика запускается
не событием UI, а по `ACTION` (`scripts`) или по смене тега (`component_property.on_change`);
клиентский обработчик делегирует бэку через `runScript(...)`. `onOpen`/`onClose` — события
сцены, а сцена — та же строка в `component` (`type = 'scene'`), поэтому отдельной таблицы не
требуется. Периодические серверные задачи (watchdog в ПЛК и т.п.) сюда **не** относятся: они
не должны зависеть от вёрстки мнемосхемы — им нужна своя сущность уровня проекта.

### `component_property`
Свойство компонента. Может быть обычным (значение хранится/задаётся в редакторе) или
привязанным к тегу канала (`property_type = "TAG"`) — тогда в режиме мониторинга (`runtime`)
его живое значение приходит из Kafka, а не из `editor`.

| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | |
| `component_id` | bigint FK | |
| `name` | varchar | Имя свойства |
| `tag_id` | varchar | **Конвенция:** строковое значение `channel.param.id` — какой тег канала привязан |
| `property_type` | varchar | Напр. `"TAG"` — свойство транслирует значение тега как есть |
| `description` | varchar | Описание свойства |
| `value_type` | varchar | Тип значения (`"number"`, `"bool"`, ...) |
| `default_value` | varchar | Значение по умолчанию / в режиме редактирования |
| `logging` | boolean | Признак логирования изменений |
| `on_change` | text (JSON) | Текст JS-скрипта (в основном if/else), выполняемого сервисом `runtime` **на бэке**, когда меняется тег из `tag_id`. Скрипту доступны `tag` (новое значение) и `props` (значения свойств того же компонента) — см. [ARCHITECTURE.md](ARCHITECTURE.md#скрипты-scriptengineservice) |

### `script`
JavaScript-скрипт, привязанный к компоненту, выполняется сервисом `runtime` **на бэке**,
но только по действию с фронта (ACTION, например нажатие кнопки), а не по изменению тегов.

| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | Это же значение — `scriptId` в WS-сообщении `{"type":"ACTION","scriptId":...}` |
| `component_id` | bigint FK | |
| `name` | varchar | Имя скрипта |
| `script` | text | Код. Доступен `props` — значения свойств того же компонента |

### `binding`
Привязка свойства компонента к скрипту перерисовки. В отличие от `on_change`/`script`,
**не выполняется на бэке `runtime` вообще** — отдаётся фронту целиком вместе с деревом
проекта и интерпретируется самим фронтендом при получении нового значения тега.

| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | |
| `component_id` | bigint FK | |
| `component_property_id` | bigint FK | Какое свойство привязано |
| `name` | varchar | Имя биндинга |
| `script` | text | Скрипт преобразования значения (выполняется фронтендом) |

### `template_faceplate`
Шаблон фейсплейта — многоразовый компонент с предустановленной структурой.

| Колонка | Тип | Описание |
|---------|-----|---------|
| `id` | bigint PK | |
| `name` | varchar | |
| `type` | varchar | |
| `root_component_id` | bigint FK → template_component | |

### `template_component`
Дерево компонентов шаблона (аналогично `component`, но для шаблонов).

### `command_log` (editor)
Идентичная структура с `channel.command_log`.

---

## ER-диаграмма (editor)

```
component ──────────────────────────────────┐
  │ (parent_id)                              │
  └──► component (дерево)                   │
  │                                          │
  ├──► component_state (1:N)                │
  ├──► component_property (1:N) ◄── binding ┘
  ├──► component_event (1:N)
  └──► script (1:N)

template_faceplate ──► template_component (дерево)
                          ├──► template_component_property
                          ├──► template_component_state
                          └──► template_script
```

---

## JWT-структура

```json
{
  "sub": "alice",
  "userId": 42,
  "iat": 1700000000,
  "exp": 1700003600
}
```

Токен генерируется в `auth`, верифицируется в `gateway/JwtAuthenticationFilter`.
Downstream-сервисы получают данные через заголовки, не парсят JWT.

---

## `runtime` — без собственной схемы

Сервис режима мониторинга не хранит данные в БД. На время жизни сессии в памяти процесса
живут: индекс `tagId → onChange/свойства`, индекс `scriptId → Script`, текущие значения
свойств компонентов и последние известные значения тегов (общие для всех сессий, читающих
один и тот же тег). Подробности — в [ARCHITECTURE.md](ARCHITECTURE.md#runtime--режим-мониторинга-kafka--graalvm-js).
