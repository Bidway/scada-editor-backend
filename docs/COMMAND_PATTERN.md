# Command Pattern — Система команд и Undo

## Зачем это нужно

Каждое изменение данных (создание, обновление, удаление) **записывается в `command_log`** с полным снимком состояния. Это позволяет:
1. **Аудит** — кто, что и когда изменил
2. **Undo** — отменить одно или несколько действий
3. **Batch Undo** — атомарная отмена группы связанных действий (например, создание узла вместе с его параметрами)

---

## Ключевые классы

```
Command<T>           — интерфейс: execute() → CommandResult<T>
CommandResult<T>     — результат команды (содержит undoPayload для отмены)
CommandManager       — выполняет команду и сохраняет лог
CommandLog           — запись в БД о выполненной команде
UndoHandler          — интерфейс: supports(type) + undo(log, userName)
CommandBatch         — группирует команды под одним batchId
```

## Схема выполнения команды

```
Service
  │
  ├── 1. Подготовить Entity/DTO в сервисе (логика — здесь!)
  │
  ├── 2. new XxxCommand(repo, entity, mapper, userName, batch)
  │
  └── 3. commandManager.execute(command)
              │
              ├── command.execute()       ← сохраняет/удаляет в БД
              │       └── returns CommandResult<T> {
              │               undoPayload: JSON-снимок для отмены,
              │               result: сохранённая сущность
              │           }
              │
              └── commandRepository.save(CommandLog.from(result))  ← логирует
```

## CommandResult — что в нём хранится

```java
CommandResult<T> {
    String userName      // кто выполнил
    String entityType    // "Node", "component", "template", ...
    Long   entityId      // id затронутой сущности (null если batch)
    String commandType   // "CREATE_NODE", "DELETE_PARAM", "UPDATE_PROPERTY", ...
    UUID   batchId       // null если одиночная команда
    Integer sequence     // порядок внутри batch
    JsonNode payload     // что было сделано (обычно сохранённая сущность)
    JsonNode undoPayload // что нужно для отмены (снимок ДО или {id: ...})
    T result             // возвращаемое значение (для сервиса)
}
```

## Стратегии undoPayload

| Команда | undoPayload | Что делает Undo |
|---------|-------------|----------------|
| `CREATE_*` | `{"id": 42}` или `{"ids": [1,2,3]}` | `deleteById(id)` |
| `UPDATE_*` | Полный JSON объекта **ДО** изменения | `repo.save(snapshot)` |
| `DELETE_*` | Полный JSON объекта **перед** удалением | `restored.setId(null); repo.save(restored)` |

> **Важно для DELETE:** снимок делается **до** вызова `repository.delete()`.

---

## Как добавить новую команду (пошагово)

### 1. Создать класс команды

```java
// channel/src/main/java/com/example/channel/command/CreateXxxCommand.java
@RequiredArgsConstructor
public class CreateXxxCommand implements Command<Xxx> {

    private final XxxRepository repository;
    private final Xxx entity;           // подготовлен в сервисе
    private final ObjectMapper mapper;
    private final String userName;
    private final CommandBatch batch;   // null если одиночная операция

    @Override
    public CommandResult<Xxx> execute() {
        Xxx saved = repository.save(entity);
        JsonNode payload = mapper.valueToTree(saved);
        JsonNode undoPayload = mapper.valueToTree(Map.of("id", saved.getId()));
        return new CommandResult<>(
            userName, "Xxx", saved.getId(), "CREATE_XXX",
            batch != null ? batch.getBatchId() : null,
            batch != null ? batch.nextSequence() : null,
            payload, undoPayload, saved
        );
    }
}
```

### 2. Создать UndoHandler

```java
// channel/src/main/java/com/example/channel/command/undo/CreateXxxUndoHandler.java
@Component
@RequiredArgsConstructor
public class CreateXxxUndoHandler implements UndoHandler {

    private final XxxRepository repo;

    @Override
    public boolean supports(String commandType) {
        return "CREATE_XXX".equals(commandType);
    }

    @Override
    public CommandResult undo(CommandLog log, String userName) {
        repo.deleteById(log.getEntityId());
        return new CommandResult<>(userName, "Xxx", log.getEntityId(),
            "UNDO_CREATE_XXX", log.getBatchId(), log.getSequence(), null, null, null);
    }
}
```

> `@Component` — Spring автоматически добавит хендлер в `List<UndoHandler>` в `UndoService`.  
> Никаких регистраций вручную не нужно.

### 3. Вызвать команду в сервисе

```java
// В сервисе
public Xxx createXxx(CreateXxxDto dto, String userName) {
    Xxx entity = buildXxx(dto);   // вся логика — здесь
    return commandManager.execute(
        new CreateXxxCommand(repository, entity, mapper, userName, null)
    );
}
```

---

## Batch Undo — группировка команд

Используется когда нужно отменить несколько операций за раз (например, удаление узла вместе со всеми параметрами).

```java
CommandBatch batch = CommandBatch.start();  // генерирует общий batchId

commandManager.execute(new DeleteParamCommand(repo, param1, mapper, userName, batch));
commandManager.execute(new DeleteParamCommand(repo, param2, mapper, userName, batch));
commandManager.execute(new DeleteNodeCommand(repo, node, mapper, userName, batch));

// Все три записи в command_log получат одинаковый batch_id
// Отмена: POST /api/channel/undo/batch/{batchId}
// Undo выполняется в обратном порядке (sequence DESC)
```

---

## Все зарегистрированные commandType

### channel

| commandType | UndoHandler | Что делает Undo |
|-------------|-------------|----------------|
| `CREATE_NODE` | `CreateNodeUndoHandler` | `nodeRepo.deleteById(entityId)` |
| `DELETE_NODE` | `DeleteNodeUndoHandler` | `nodeRepo.save(snapshot)` |
| `CREATE_PARAM` | `CreateParamUndoHandler` | `paramRepo.deleteById(entityId)` |
| `UPDATE_PARAM` | `UpdateParamUndoHandler` | `paramRepo.save(snapshot_before)` |
| `DELETE_PARAM` | `DeleteParamUndoHandler` | `paramRepo.save(snapshot)` |

### editor

| commandType | UndoHandler | Что делает Undo |
|-------------|-------------|----------------|
| `CREATE_COMPONENT` | `CreateComponentUndoHandler` | `componentRepo.deleteAllById(ids)` |
| `UPDATE_COMPONENT` | `UpdateComponentUndoHandler` | `componentRepo.save(snapshot)` |
| `DELETE_COMPONENT` | `DeleteComponentUndoHandler` | `componentRepo.save(snapshot)` |
| `CREATE_PROJECT` | `CreateProjectUndoHandler` | `componentRepo.deleteById(id)` |
| `CREATE_SCENE` | `CreateSceneUndoHandler` | `componentRepo.deleteById(id)` |
| `CREATE_PROPERTY` | `CreatePropertyUndoHandler` | `propertyRepo.deleteById(id)` |
| `UPDATE_PROPERTY` | `UpdatePropertyUndoHandler` | `propertyRepo.save(snapshot)` |
| `DELETE_PROPERTY` | `DeletePropertyUndoHandler` | `propertyRepo.save(snapshot)` |
| `CREATE_TEMPLATE` | `CreateTemplateUndoHandler` | `templateRepo.deleteById(id)` |
| `UPDATE_TEMPLATE` | `UpdateTemplateUndoHandler` | `templateRepo.save(snapshot)` |
| `DELETE_TEMPLATE` | `DeleteTemplateUndoHandler` | `templateRepo.save(snapshot)` |

---

## Разница между channel и editor CommandManager

| | channel | editor |
|--|---------|--------|
| `execute()` возвращает | `CommandResult<T>` | `T` (только результат) |
| Использование | `commandManager.execute(cmd).getResult()` | `commandManager.execute(cmd)` |

Это историческая разница — при возможности стоит унифицировать.
