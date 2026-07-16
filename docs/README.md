# SCADA Editor Backend — Документация

## Навигация

| Файл | Содержимое |
|------|-----------|
| [README.md](README.md) | Этот файл — общий обзор, навигация |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Архитектура системы, схемы, потоки данных |
| [API.md](API.md) | Полный справочник по всем REST-эндпоинтам |
| [DATA_MODEL.md](DATA_MODEL.md) | Сущности БД, схемы, связи |
| [COMMAND_PATTERN.md](COMMAND_PATTERN.md) | Command/Undo система — как это работает |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Как запустить, настроить, добавить новый функционал |
| [AUTH_TESTING.md](AUTH_TESTING.md) | Тесты микросервиса auth — структура, запуск, Testcontainers |

---

## Что это за проект

Backend для SCADA-редактора. Позволяет:
- Редактировать иерархию промышленных узлов (Node) с параметрами (Param)
- Создавать компоненты визуализации (Component) с иерархией project → scene → component
- Управлять шаблонами (Template/FacePlate)
- Отменять действия (Undo/Redo) через Command Pattern
- Работать в реальном времени через WebSocket (STOMP)

## Модули

```
scada-editor-backend/
├── gateway/     — API Gateway, :8080 — единая точка входа
├── auth/        — Аутентификация, :8081 — JWT токены
├── channel/     — Узлы и параметры SCADA, :8082 — WebSocket + REST
├── editor/      — Компоненты и шаблоны, :8083 — Command Pattern
└── shared/      — Общие интерфейсы (Command, CommandResult, NotFoundException)
```

## Быстрый старт

```bash
# Скопировать переменные окружения
cp .env.example .env
# Задать секрет в .env: JWT_SECRET=...

# Собрать и запустить
docker-compose up --build
```

Подробнее — [DEVELOPMENT.md](DEVELOPMENT.md)
