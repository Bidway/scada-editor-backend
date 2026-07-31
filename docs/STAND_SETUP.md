# Перенос стенда на другой компьютер

Инструкция по развёртыванию рабочего стенда (бэкенд + Kafka-симулятор ПЛК + фронтенд)
на чистой Windows-машине. Запуск — локальный, без Docker, через `start-all.ps1`.

---

## 0. Главное, что нельзя забыть

**Схема БД создаётся сама, данные — нет.** Миграций (Flyway/Liquibase) в проекте нет,
Hibernate работает в режиме `ddl-auto: update` — он создаст пустые таблицы, и на этом всё.
Демо-сцена, дерево тегов, скрипты и биндинги заведены вручную (частично SQL-ом) и живут
только в базе. Без дампа стенд поднимется, но показывать будет нечего.

Что реально лежит в БД `savushkin` (снимок на 29.07.2026):

| Таблица | Строк | Что это |
|---|---:|---|
| `channel.node` | 3449 | дерево узлов SCADA (импорт с реального шлюза) |
| `channel.param` | 17542 | параметры узлов |
| `editor.component` | 15 | компоненты сцены BN1_MCA1 |
| `editor.component_property` | 12 | привязки свойств к тегам |
| `editor.component_state` | 12 | графические состояния |
| `editor.component_event` | 1 | `onClick` кнопки 962 |
| `editor.scripts` | 2 | серверные скрипты «Открыть/Закрыть клапан» |
| `editor.binding` | 3 | биндинги, в т.ч. code-биндинг «Цвет клапана» |
| `editor.recipe` / `recipe_value` | 2 | рецепты (уставки) |
| `auth.users` | 5 | учётки |

Размер базы — 14 МБ (с индексами), сжатый дамп в формате `-Fc` — около **0.2 МБ**,
влезает куда угодно. Помимо трёх схем в дамп попадает ещё `public.command_log`.

---

## 1. Снять с текущей машины (**до** переезда)

```powershell
$env:PGPASSWORD = 'postgres'
& 'C:\Program Files\PostgreSQL\17\bin\pg_dump.exe' -U postgres -d savushkin -Fc -f Z:\savushkin.dump
```

Проверить дамп, не отходя от рабочей машины (восстановление во временную БД и сверка строк):

```powershell
$bin = 'C:\Program Files\PostgreSQL\17\bin'
& "$bin\psql.exe"       -U postgres -c "CREATE DATABASE savushkin_restore_test"
& "$bin\pg_restore.exe" -U postgres -d savushkin_restore_test Z:\savushkin.dump
& "$bin\psql.exe"       -U postgres -d savushkin_restore_test -c "select count(*) from channel.node"
& "$bin\psql.exe"       -U postgres -c "DROP DATABASE savushkin_restore_test"
```
Ожидаем 3449 узлов и `exit=0` у `pg_restore`.

Забрать с собой:

1. `Z:\savushkin.dump` — база (**критично, в git её нет**);
2. репозиторий бэкенда `scada-editor-backend`, ветка `microservice`;
3. репозиторий фронтенда `scada-editor-frontend`;
4. этот файл.

Каталог `tools\kafka-sim\node_modules` копировать не нужно — поставится сам.

---

## 2. Что поставить на новой машине

| Софт | Версия | Примечание |
|---|---|---|
| **JDK** | 17+ | Gradle-тулчейн жёстко на 17 (`build.gradle.kts`), на 11 не соберётся |
| **PostgreSQL** | 17 | служба по умолчанию `postgresql-x64-17` — её ищет скрипт |
| **Redis** или **Memurai** | любой | порт 6379 |
| **Node.js** | 18+ | нужен и фронту, и Kafka-симулятору |
| **Apache Kafka** | 4.x (Scala 2.13) | бинарная сборка, KRaft, без ZooKeeper |
| **Git** | любой | |

Интернет на первый запуск обязателен: Gradle тянет зависимости, npm — пакеты.

---

## 3. Kafka

1. Скачать бинарную сборку 4.x с https://kafka.apache.org/downloads (`kafka_2.13-4.x.x.tgz`,
   раздел **Binary downloads**).
2. Распаковать в путь **без пробелов и кириллицы** — bat-скрипты Kafka с ними не работают.
   По умолчанию скрипт ищет `C:\kafka_2.13-4.3.1`; иначе передавать `-KafkaHome`.
3. В `config\server.properties` задать:

```properties
log.dirs=C:/kafka-logs
listeners=PLAINTEXT://:9092,CONTROLLER://:9093
advertised.listeners=PLAINTEXT://localhost:9092
auto.create.topics.enable=true
```

**Форматировать хранилище вручную не надо** — `start-all.ps1` при первом запуске сам
сгенерирует Cluster ID и вызовет `kafka-storage.bat format --standalone`.

Топики (`scada.tags`, `scada-commands`, `scada-command-results`) создаются на лету.

---

## 4. База данных

```powershell
$env:PGPASSWORD = 'postgres'
$bin = 'C:\Program Files\PostgreSQL\17\bin'

& "$bin\psql.exe" -U postgres -c "CREATE DATABASE savushkin"
& "$bin\pg_restore.exe" -U postgres -d savushkin Z:\savushkin.dump
```

`pg_restore` создаст схемы `auth`, `channel`, `editor` сам. Файл `docker\postgres\init.sql`
нужен только если поднимаетесь с нуля, без дампа.

Проверка:
```powershell
& "$bin\psql.exe" -U postgres -d savushkin -c "select count(*) from channel.node"   # ожидаем 3449
```

Реквизиты по умолчанию — `postgres/postgres`, БД `savushkin` (см. `application.yml` каждого
сервиса). Если на новой машине пароль другой — либо совпасть с ним, либо выставить
переменные окружения `DB_USERNAME` / `DB_PASSWORD` / `DB_NAME` перед запуском скрипта.

---

## 5. Репозитории

**Бэкенд:**
```powershell
git clone <url> C:\work\scada-editor-backend
cd C:\work\scada-editor-backend
git checkout microservice
```

**Фронтенд:**
```powershell
git clone <url> C:\work\scada-editor-frontend
cd C:\work\scada-editor-frontend
npm install
```
Создать `.env` в корне фронта (в git его нет):
```
BACKEND_URL=http://localhost:8080
```

---

## 6. Настроить `start-all.ps1` под новые пути

В скрипте пути к окружению зашиты в параметры по умолчанию (`start-all.ps1:24-32`).
Либо поправить их в файле, либо передавать флагами:

| Параметр | Значение по умолчанию | Когда менять |
|---|---|---|
| `-ProjectRoot` | каталог скрипта | обычно не нужно |
| `-KafkaHome` | `C:\kafka_2.13-4.3.1` | другая версия/путь Kafka |
| `-RedisHome` | `C:\redis` | если Redis портативный, а не служба |
| `-PgService` | `postgresql-x64-17` | другая версия PostgreSQL |
| `-FrontendDir` | `Z:\Project Java\scada-editor-frontend` | **почти наверняка менять** |

Пример запуска с новыми путями:
```powershell
cd C:\work\scada-editor-backend
.\start-all.ps1 -KafkaHome C:\kafka_2.13-4.3.1 -FrontendDir C:\work\scada-editor-frontend
```

Полезные флаги: `-NoSim` (без симулятора ПЛК), `-ServicesOnly` (не трогать инфраструктуру).

---

## 7. Что должно подняться

| Порт | Компонент | Окно |
|---|---|---|
| 5432 | PostgreSQL | служба |
| 6379 | Redis | служба или окно `redis` |
| 9092 | Kafka | окно `kafka` |
| — | **Kafka-симулятор ПЛК** | окно `kafka-sim` |
| 8081 | auth | окно `auth` |
| 8082 | channel | окно `channel` |
| 8083 | editor | окно `editor` |
| 8085 | runtime | окно `runtime` |
| 8080 | gateway | окно `gateway` |
| 3000 | фронтенд (Next.js) | окно `frontend` |

Первый `gradlew bootRun` компилирует модули и качает зависимости — несколько минут на сервис.

---

## 8. Проверка стенда

**Симулятор запущен:**
```powershell
Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
  Where-Object CommandLine -like '*sim.mjs*' | Select-Object ProcessId, CommandLine
```
Должен быть **ровно один** процесс `node.exe sim.mjs`.

**Телеметрия реально идёт в Kafka:**
```powershell
& 'C:\kafka_2.13-4.3.1\bin\windows\kafka-console-consumer.bat' `
  --bootstrap-server localhost:9092 --topic scada.tags `
  --property print.key=true --max-messages 4 --timeout-ms 10000
```
Ожидаемый вид сообщений:
```
Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST   {"type":"TELEMETRY",...,"value":true,"ts":...}
Барановичи-1.BN1_MCA1.V_M_1.LINE1V0.M     {"type":"TELEMETRY",...,"value":63,"ts":...}
```
Тег `...V_M_1.LINE1V0.M` (fullness танка) дрейфует 0→100 шагом 3.5 каждые 2 с — если при
повторном запуске консьюмера значение изменилось, симулятор живой, а не отдаёт старое.

**Симулятор слушает команды:**
```powershell
& 'C:\kafka_2.13-4.3.1\bin\windows\kafka-consumer-groups.bat' `
  --bootstrap-server localhost:9092 --describe --group scada-sim
```
Группа `scada-sim` должна быть `Stable` с одним участником на `scada-commands`.

**Сервисы:** http://localhost:8080 (gateway), http://localhost:8083/swagger-ui.html (editor).

**Полный цикл:** открыть http://localhost:3000, зайти в сцену BN1_MCA1, нажать кнопку
открытия клапана. В окне `kafka-sim` должно появиться:
```
[cmd] ◄ ST := true  (commandId=...)
[cmd] ► ST применён и возвращён телеметрией
```
и клапан на сцене должен перекраситься (code-биндинг «Цвет клапана»).

---

## 9. Известные грабли

| Симптом | Причина / решение |
|---|---|
| В окне `kafka-sim` пусто, в консоли скрипта `[!] Kafka (9092) не слушает — симулятор пропущен` | Kafka не успела встать за 90 с. Дождаться окна `kafka` и перезапустить `.\start-all.ps1` |
| Телеметрия дублируется | Скрипт пропускает сервисы по занятому порту, **но у симулятора порта нет** — повторный запуск `start-all.ps1` поднимает второй `node sim.mjs`. Лишний снять `Stop-Process` |
| Фронт стартует второй раз | В скрипте проверка порта **5173** (наследие Vite), а Next.js слушает **3000**. Порт 5173 всегда свободен → скрипт всегда пытается запустить фронт. Проверять вручную |
| Runtime рвёт WebSocket с 401/403 | WS runtime требует JWT в query (`/ws/runtime/{sessionId}?token=<jwt>`). Если фронт токен не шлёт — поднять runtime с `RUNTIME_WS_REQUIRE_AUTH=false` |
| Окно `kafka` закрывается сразу | Пробелы/кириллица в пути к Kafka, либо нет JDK 17+ в `PATH` |
| Kafka не стартует после жёсткого выключения | Удалить каталог из `log.dirs` целиком и запустить скрипт снова — он переформатирует хранилище |
| `Не смог стартовать postgresql-x64-17` | Запустить PowerShell от администратора либо стартовать службу вручную |
| Сервисы падают на подключении к БД | Не совпали реквизиты: ожидаются `postgres/postgres`, БД `savushkin`. Или задать `DB_USERNAME`/`DB_PASSWORD`/`DB_NAME` |
| Сцена пустая, дерево тегов пустое | Не залит дамп — Hibernate создал пустые таблицы (см. раздел 0) |

---

## 10. Альтернатива — инфраструктура в Docker

Если не хочется ставить Kafka/Redis/Postgres локально, в корне есть `docker-compose.yml`:

```powershell
docker compose up -d postgres redis kafka
```
Kafka отдаёт наружу `localhost:9092`, Postgres — `localhost:5432`, поэтому
`start-all.ps1` увидит занятые порты, напишет «уже работает» и просто запустит сервисы
и симулятор. Дамп в этом случае заливается в контейнерный Postgres тем же `pg_restore`
(`-h localhost -p 5432`). Реквизиты берутся из `.env`.
