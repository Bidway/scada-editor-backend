# scada-editor-backend

Серверная часть SCADA: редактор мнемосхем, база каналов, режим мониторинга с записью в ПЛК.
Gradle multi-module (Kotlin DSL), Java 17, Spring Boot.

## Модули

| Модуль | Порт | Роль |
|---|---|---|
| `gateway` | 8080 | Единственный вход снаружи. Spring Cloud Gateway |
| `auth` | 8081 | Выдача JWT |
| `channel` | 8082 | Узлы и параметры каналов, STOMP WS |
| `editor` | 8083 | Компоненты, шаблоны, наборы значений |
| `runtime` | 8085 | Мониторинг, Kafka, скрипты на GraalVM JS. Своей БД нет |
| `shared` | — | Общие типы Command Pattern для `channel` и `editor` |

Одна база `savushkin`, у каждого сервиса своя схема. Миграций нет (`ddl-auto: update`).

## Запуск

```powershell
.\start-all.ps1                  # весь стенд; -Mode docker | -Sim | -Status | -Stop
```

Запуск отдельного сервиса и другие команды — в `CLAUDE.md`.

## Дальше

- **`CLAUDE.md`** — точка входа для разработки в этом репозитории: правила, грабли,
  устройство проекта.
- **beads** (`bd ready`, `bd list`) — задачи и известные долги. Не GitHub Issues и не файл
  в репозитории.
- `.env.example` — переменные окружения для запуска и локально (`bootRun`), и в Docker;
  не все нужны обоим режимам.
