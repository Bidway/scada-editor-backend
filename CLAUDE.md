# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Проект и документация — на русском. Комментарии и коммиты пиши по-русски.

Здесь только то, что нужно, чтобы не сломать ничего с первого шага. Всё остальное — в `docs/`,
таблица навигации в конце файла.

---

## Что это

Серверная часть SCADA: редактор мнемосхем, база каналов, режим мониторинга с записью в ПЛК.
Gradle multi-module (Kotlin DSL), Java 17, Spring Boot.

| Модуль | Порт | Роль |
|---|---|---|
| `gateway` | 8080 | Единственный вход снаружи. Spring Cloud Gateway |
| `auth` | 8081 | Выдача JWT |
| `channel` | 8082 | Узлы и параметры каналов, STOMP WS |
| `editor` | 8083 | Компоненты, шаблоны, наборы значений |
| `runtime` | 8085 | Мониторинг, Kafka, скрипты на GraalVM JS. Своей БД нет |
| `shared` | — | Мёртвый код, см. ниже |

Одна база `savushkin`, у каждого сервиса своя схема. Миграций нет (`ddl-auto: update`),
данные живут только в дампах.

---

## Команды

```powershell
.\gradlew :editor:bootRun                                  # запустить один сервис
.\gradlew :editor:bootRun --args="--server.port=8093"      # второй экземпляр рядом
.\gradlew :editor:test                                     # тесты модуля
.\gradlew :auth:test --tests "com.example.auth.controller.AuthControllerIT"   # один класс
.\gradlew :editor:bootJar                                  # jar (build/libs)

.\start-all.ps1                  # весь стенд; -Mode docker | -Sim | -Status | -Stop
```

Линтеров и форматтеров в проекте нет. Значимые тесты — только в `auth` (Testcontainers,
нужен Docker Desktop) и `runtime`; в остальных модулях пустые smoke-классы.

Разбор запуска, переменных окружения и грабель стенда — `docs/operations/DEVELOPMENT.md`.

---

## Что нужно знать до правок

**Тег адресуется только путём канала.** `Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST` — он же
Kafka-key телеметрии, он же `editor.component_property.tag_id`, он же адрес в команде записи.
Числовых id тега на проводе нет ни в одну сторону: у шлюза их два, и наружу публикуется не тот.
Не возвращай числовую адресацию. Следствие: **`runtime` не обращается к `channel` вообще.**

**Подпись JWT проверяет только gateway**, вниз он проставляет `X-User-Id` / `X-Username`, затирая
клиентские значения. У downstream-сервисов `permitAll()` — это осознанно. Изменяющие эндпоинты
читают `@RequestHeader("X-Username")` и токен не разбирают.

**Изменения данных в `channel` и `editor` идут через Command Pattern**, а не прямым вызовом
репозитория: `commandManager.execute(new XxxCommand(...))` пишет `command_log` со снимком для
отмены. Как добавить команду и undo — `docs/COMMAND_PATTERN.md`.

**Логика поделена между бэком и фронтом по правилу «пишет объект или общее состояние — бэк;
только рисует — фронт».** На бэке (`runtime`, GraalVM) исполняются `scripts.script` по `ACTION`
с фронта и `component_property.on_change` по смене тега; на фронте — `component_event.script` и
`binding.script`. Разбор по шагам — `docs/RUNTIME_EXECUTION_CYCLE.md`.

**Модуль `shared` — мёртвый код.** Он в `settings.gradle.kts`, но ни один модуль не объявляет
`implementation(project(":shared"))`. Рабочие копии `Command` / `CommandResult` / `UndoHandler`
лежат в `channel/config/command/` и `editor/config/command/` — правь их. Ошибка молчаливая:
правка в `shared` соберётся и ничего не изменит.

**Каталоги `bin/` содержат устаревшие копии ресурсов** (вывод Eclipse). При поиске исключай
`build/` и `bin/`, иначе найдёшь не тот `application.yml`.

**`docker compose up --build` не пересобирает Java-код.** Все Dockerfile'ы делают
`COPY build/libs/*.jar`, то есть берут готовый jar с хоста: без `bootJar` в образ молча уедет
прошлая сборка.

---

## Документация

Справочник в `docs/` — **он в `.gitignore`, живёт только на этой машине**. Заглядывай туда перед
нетривиальными правками, это быстрее, чем реконструировать по коду.

| Вопрос | Файл |
|---|---|
| Долги и известные неточности | `docs/TODO.md` |
| Схема системы, порты, стек | `docs/ARCHITECTURE.md` |
| Все REST-эндпоинты и WebSocket | `docs/API.md` |
| Таблицы и колонки | `docs/DATA_MODEL.md` |
| Команды, журнал изменений, undo | `docs/COMMAND_PATTERN.md` |
| Полный цикл «клик → ПЛК → экран» | `docs/RUNTIME_EXECUTION_CYCLE.md` |
| Устройство сервиса, его API и грабли | `docs/modules/<сервис>.md` |
| Запуск, переменные, чеклист эндпоинта | `docs/operations/DEVELOPMENT.md` |
| Контракт для фронта и для шлюза | `docs/integration/` |

Правило поддержки: **правка кода задевает один файл документации.** Эндпоинт → `API.md` + файл
модуля; колонка → `DATA_MODEL.md` + файл модуля; формат на проводе → файл модуля + `integration/`
того, кто его читает. Дату актуальности — в шапку, причину — рядом с фактом.

**Заметил расхождение кода с документацией, мёртвый код или отложенное решение — не исправляй
молча по ходу чужой задачи, а допиши пункт в `docs/TODO.md`** (где, в чём суть, чем грозит, что
сделать, дата). Исправленное переноси там же в «Закрыто».

---

## Смежные репозитории

- `Z:\Claude\Projects\scada-editor-frontend` — Next.js, порт 3000
- `Z:\Claude\Projects\scada-gateway` — шлюз ПЛК (OPC UA + Modbus), порт 8888, собирается JDK 21


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:6cd5cc61 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->
