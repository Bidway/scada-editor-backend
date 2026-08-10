<#
    start-all.ps1 — единый запуск стенда SCADA целиком, одной командой.

    Два режима:

      -Mode host   (по умолчанию) — быстрый цикл разработки.
                   Инфраструктура на хосте (PostgreSQL/Redis/Kafka), БД шлюза и
                   PLC-симулятор в Docker, всё остальное — своим окном на хосте
                   через gradlew bootRun. Пересборка модуля = перезапуск окна.

      -Mode docker — всё в контейнерах, ближе к бою. Перед сборкой образов
                   ОБЯЗАТЕЛЬНО собирает jar'ы: наши Dockerfile'ы копируют готовый
                   build/libs/*.jar с хоста, поэтому `docker compose up --build`
                   сам по себе НЕ пересобирает Java-код и молча поднимает
                   прошлую сборку.

    Источник телеметрии (оба режима):
      по умолчанию — реальный scada-gateway: опрашивает PLC-симулятор по OPC UA и
      Modbus и публикует все 2471 канал BN1_MCA1 в scada.tags;
      -Sim   — лёгкий tools\kafka-sim (3 тега сцены, без Docker и OPC UA);
      -NoSim — без источника вовсе.

    Примеры:
      .\start-all.ps1                     # host-режим, реальный шлюз, фронт
      .\start-all.ps1 -Mode docker        # весь стенд в контейнерах
      .\start-all.ps1 -Sim                # лёгкий симулятор вместо шлюза
      .\start-all.ps1 -ServicesOnly       # только сервисы, инфраструктуру не трогать
      .\start-all.ps1 -NoFrontend         # без фронта
      .\start-all.ps1 -Status             # что сейчас поднято
      .\start-all.ps1 -Stop               # погасить стенд
#>

param(
    [string]$ProjectRoot = $PSScriptRoot,
    [string]$KafkaHome   = 'C:\kafka_2.13-4.3.1',
    [string]$RedisHome   = 'C:\redis',
    [string]$PgService   = 'postgresql-x64-17',
    [string]$FrontendDir = 'Z:\Claude\Projects\scada-editor-frontend',
    [string]$GatewayDir  = 'Z:\Claude\Projects\scada-gateway',
    # next dev слушает 3000. Раньше здесь стояло 5173 (Vite) — проверка «фронт уже
    # работает» смотрела в пустой порт и плодила вторую копию при каждом прогоне.
    [int]$FrontendPort   = 3000,
    [ValidateSet('host', 'docker')]
    [string]$Mode        = 'host',
    [switch]$ServicesOnly,
    [switch]$Sim,
    [switch]$NoSim,
    [switch]$NoFrontend,
    [switch]$Status,
    [switch]$Stop
)

if (-not $ProjectRoot) { $ProjectRoot = 'Z:\GitKraken\scada-editor-backend' }
$ErrorActionPreference = 'Continue'

$Services = @(
    @{ Name = 'auth';    Port = 8081 },
    @{ Name = 'channel'; Port = 8082 },
    @{ Name = 'editor';  Port = 8083 },
    @{ Name = 'runtime'; Port = 8085 },
    @{ Name = 'gateway'; Port = 8080 }   # единственный вход снаружи
)

# ============================== helpers ==============================

function Info($m) { Write-Host "[*] $m"  -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[OK] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[!] $m"  -ForegroundColor Yellow }
function Err($m)  { Write-Host "[X] $m"  -ForegroundColor Red }

function Test-Port([int]$Port) {
    [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Port([int]$Port, [string]$Name, [int]$TimeoutSec = 90) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        if (Test-Port $Port) { Ok "$Name готов (порт $Port)"; return $true }
        Start-Sleep -Milliseconds 500
    }
    Err "$Name не поднялся за $TimeoutSec c (порт $Port)"
    return $false
}

function Start-InWindow([string]$Title, [string]$WorkDir, [string]$Command) {
    $inner = "`$host.ui.RawUI.WindowTitle='$Title'; Set-Location '$WorkDir'; $Command"
    Start-Process -FilePath 'powershell.exe' `
        -ArgumentList '-NoExit', '-Command', $inner -WindowStyle Normal | Out-Null
}

<#
    Санация хранилища Kafka перед стартом брокера. Нативная Kafka на Windows регулярно
    оставляет каталог логов в состоянии, из которого она сама же не поднимается, — обе
    грабли растут из того, что Windows строже Linux обращается с файлами:

      * KRaft держит снапшоты метаданных иммутабельными: после freeze() на файл ставится
        «только чтение» — на Linux это chmod 444, на Windows атрибут ReadOnly. Отслуживший
        снапшот переименовывается в *.deleted и удаляется при следующем старте
        (KafkaRaftLog.recoverSnapshots), но Files.deleteIfExists на ReadOnly-файле в Windows
        отказывает всегда → AccessDeniedException → фатальный выход брокера.

      * LogCleaner компактит __consumer_offsets через .cleaned → .swap → рабочий файл, а
        переименовать файл с активным mmap Windows не даёт (KAFKA-1194, открыт с 2014 и не
        исправлен). Каталог логов помечается failed, брокер гаснет и оставляет за собой
        недоделанные .cleaned/.swap, на которых спотыкается уже следующий запуск.

    Снимаем ReadOnly и убираем незавершённый мусор: первую грабли это лечит полностью,
    вторую понижает с «снести хранилище и переформатировать» до «просто перезапустить».
    Вызывать только когда брокер не работает — у живого cleaner .cleaned забирать нельзя.
#>
function Clear-KafkaLogDir([string]$LogDir) {
    if (-not $LogDir -or -not (Test-Path $LogDir)) { return }
    $unlocked = 0
    Get-ChildItem $LogDir -Recurse -Force -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Attributes -band [IO.FileAttributes]::ReadOnly } |
        ForEach-Object {
            $_.Attributes = $_.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly)
            $unlocked++
        }
    $stale = @(Get-ChildItem $LogDir -Recurse -Force -File -Include '*.deleted', '*.cleaned', '*.swap' -ErrorAction SilentlyContinue)
    if ($stale.Count) { $stale | Remove-Item -Force -ErrorAction SilentlyContinue }
    if ($unlocked -or $stale.Count) {
        Info "Санация хранилища Kafka: снят ReadOnly с $unlocked файл(ов), удалено незавершённых $($stale.Count)"
    }
}

function Test-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return $false }
    docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    return $?
}

<#
    Работает ли контейнер с таким именем. Вызывать только когда Docker уже проверен:
    сам по себе docker inspect при мёртвом демоне висит на таймауте.
#>
function Test-ContainerRunning([string]$Name) {
    $state = docker inspect -f '{{.State.Running}}' $Name 2>$null
    return ($LASTEXITCODE -eq 0 -and "$state".Trim() -eq 'true')
}

<#
    Адрес, который OPC UA сервер симулятора анонсирует клиентам.

    Milo (клиент шлюза) после discovery идёт не по адресу, куда подключался, а по тому,
    который сервер анонсировал в EndpointDescription. Значит адрес обязан резолвиться
    СО СТОРОНЫ ШЛЮЗА, и правильный ответ зависит от того, где шлюз работает:
      * шлюз на хосте        -> localhost:4840 (порт симулятора проброшен наружу);
      * шлюз в контейнере    -> scada-simulator:4840 (имя сервиса в сети compose).

    Ошибиться здесь особенно дорого, потому что отказ тихий: connect падает внутри
    супервизора, в лог идёт только «переподключаю ... (client=false, stale=true)», и
    ни одной ошибки. При этом Modbus discovery не делает и ходит прямо на SIM_HOST —
    он продолжает работать как ни в чём не бывало. Наружу это выглядит не как «шлюз
    сломан», а как «часть тегов пропала»: у BN1_MCA1 по OPC UA идут все 594 канала
    V_*/ST/M, то есть ровно те, что стоят на мнемосхемах.
#>
function Resolve-SimEndpoint([bool]$GatewayInContainer) {
    if ($GatewayInContainer) { return 'opc.tcp://scada-simulator:4840' }
    return 'opc.tcp://localhost:4840'
}

<#
    JDK 21 для сборки шлюза. Наши модули собираются под 17, а pom шлюза требует 21,
    и mvnw берёт версию из JAVA_HOME. Если в PATH стоит другая (часто 19), сборка
    падает на «release version 21 not supported» — поэтому ищем 21-ю отдельно и
    подставляем только на время вызова mvnw.
#>
function Resolve-Jdk21 {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $v = & (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Select-Object -First 1
        if ("$v" -match 'version "21') { return $env:JAVA_HOME }
    }
    $roots = @(
        (Join-Path $env:USERPROFILE '.jdks'),
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Java',
        'C:\Program Files\Microsoft'
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $hit = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -match '21' } |
               Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
               Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

<#
    БД шлюза не даёт ему стартовать на существующем томе: поле TagEntity.recordDevice
    примитивное, а колонка record_device добавляется ddl-auto как nullable — у строк,
    записанных прежней сборкой, там NULL, и Hibernate падает ещё до подъёма контекста
    («Null value was assigned to a property of primitive type»), контейнер уходит в
    цикл перезапуска. Бэкфилл идемпотентен и историю телеметрии не трогает.
    Дефект шлюза; повторится на любой новой колонке примитивного типа.
#>
function Repair-GatewayDb {
    $sql = 'UPDATE tags SET record_device = false WHERE record_device IS NULL'
    $out = docker exec scada-postgres psql -U scada_user -d scada_db -tAc $sql 2>$null
    if ($LASTEXITCODE -eq 0 -and "$out" -match 'UPDATE\s+([1-9]\d*)') {
        Ok "БД шлюза: заполнено record_device у $($Matches[1]) строк (иначе шлюз не стартует)"
    }
}

# ============================== -Status / -Stop ==============================

if ($Status) {
    Info 'Состояние стенда:'
    $checks = @(
        @{ N = 'PostgreSQL (хост)';   P = 5432 },
        @{ N = 'Redis';               P = 6379 },
        @{ N = 'Kafka';               P = 9092 },
        @{ N = 'scada-postgres';      P = 5433 },
        @{ N = 'PLC-симулятор OPCUA'; P = 4840 },
        @{ N = 'scada-gateway';       P = 8888 },
        @{ N = 'gateway (вход)';      P = 8080 },
        @{ N = 'auth';                P = 8081 },
        @{ N = 'channel';             P = 8082 },
        @{ N = 'editor';              P = 8083 },
        @{ N = 'runtime';             P = 8085 },
        @{ N = 'frontend';            P = $FrontendPort }
    )
    foreach ($c in $checks) {
        if (Test-Port $c.P) { Ok "$($c.N) : $($c.P)" } else { Warn "$($c.N) : $($c.P) — не слушает" }
    }
    if (Test-Docker) {
        Write-Host ''
        Info 'Контейнеры:'
        # Out-Host — иначе вывод docker уходит в pipeline и печатается уже после
        # следующих Write-Host, перемешивая порядок строк в отчёте.
        docker ps --format '  {{.Names}}  {{.Status}}' | Out-Host

        # Рассинхрон анонса OPC UA виден только так: в логах шлюза он не ошибка, а
        # бесконечное «переподключаю ... (client=false)», Modbus при этом идёт как ни в
        # чём не бывало, и снаружи пропажа 594 opcua-каналов выглядит как «часть тегов
        # не приходит». Сверяем, что симулятор анонсирует адрес, резолвимый для шлюза.
        if (Test-ContainerRunning 'scada-simulator') {
            Write-Host ''
            $announced = (docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' scada-simulator 2>$null |
                          Select-String -Pattern '^OPCUA_ENDPOINT=(.*)$').Matches.Groups[1].Value
            $inContainer = Test-ContainerRunning 'scada-gateway'
            $expected = Resolve-SimEndpoint $inContainer
            $where = if ($inContainer) { 'в контейнере' } else { 'на хосте (или не запущен)' }
            Info "Анонс OPC UA симулятора: $announced   (шлюз $where, ожидается $expected)"
            if ($announced -and $announced -ne $expected) {
                Err 'Анонс не резолвится со стороны шлюза — все теги OPC UA молча не идут.'
                Err "Лечится: `$env:SCADA_SIM_ENDPOINT='$expected'; docker compose -f docker-compose.gateway.yml up -d scada-simulator"
            }
        }
    }
    return
}

if ($Stop) {
    if (Test-Docker) {
        Info 'Гашу контейнеры ...'
        Push-Location $ProjectRoot
        docker compose -f docker-compose.yml -f docker-compose.gateway.yml down
        Pop-Location
        Ok 'Контейнеры остановлены (тома с данными сохранены)'
    }
    Warn 'Процессы host-режима живут в своих окнах PowerShell — закрой их вручную.'
    Warn 'Инфраструктура хоста (PostgreSQL/Redis/Kafka) намеренно не трогается.'
    return
}

Info "Проект: $ProjectRoot"
Info "Режим : $Mode"

# ============================== DOCKER-режим ==============================

if ($Mode -eq 'docker') {
    if (-not (Test-Docker)) {
        Err 'Docker не запущен. Запусти Docker Desktop и повтори.'
        return
    }

    # Ключевой шаг. Dockerfile каждого сервиса делает COPY build/libs/*.jar, то есть
    # берёт ГОТОВЫЙ jar с хоста. Без этой сборки `up --build` пересоберёт образ вокруг
    # прошлого jar и поднимет старый код — молча, без единой ошибки.
    Info 'Собираю jar-ы сервисов (иначе в образы уедет прошлая сборка) ...'
    Push-Location $ProjectRoot
    $tasks = ($Services | ForEach-Object { ":$($_.Name):bootJar" }) -join ' '
    & .\gradlew.bat --console=plain $tasks.Split(' ')
    $built = $?
    Pop-Location
    if (-not $built) { Err 'Сборка jar-ов не прошла — смотри вывод gradle выше'; return }
    Ok 'jar-ы собраны'

    $composeArgs = @('-f', 'docker-compose.yml')
    if (-not $NoSim -and -not $Sim) {
        $env:SCADA_GATEWAY_PATH = $GatewayDir -replace '\\', '/'
        # Задаём явно, а не полагаемся на дефолт compose: в этой же сессии PowerShell
        # переменная могла остаться от прогона в host-режиме (там анонс — localhost),
        # и симулятор поднялся бы с адресом, который из контейнера шлюза не резолвится.
        $env:SCADA_SIM_ENDPOINT = Resolve-SimEndpoint $true
        $composeArgs += @('-f', 'docker-compose.gateway.yml')

        # Образ шлюза тоже копирует готовый jar — собираем его тем же правилом.
        $jar = Get-ChildItem -Path (Join-Path $GatewayDir 'SCADA-gateway\target') -Filter 'SCADA-gateway-*.jar' `
                   -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        if (-not $jar) {
            $jdk = Resolve-Jdk21
            if (-not $jdk) {
                Err 'Не нашёл JDK 21 — шлюз собрать нечем. Поставь JDK 21 или задай JAVA_HOME.'
                return
            }
            Info "Собираю jar шлюза (JDK 21: $jdk) ..."
            $saved = $env:JAVA_HOME
            $env:JAVA_HOME = $jdk
            Push-Location (Join-Path $GatewayDir 'SCADA-gateway')
            & .\mvnw.cmd -q -DskipTests package
            Pop-Location
            $env:JAVA_HOME = $saved
        }
    }

    Push-Location $ProjectRoot
    Info 'Поднимаю инфраструктуру ...'
    & docker compose @composeArgs up -d --build postgres redis kafka

    if (-not $NoSim -and -not $Sim) {
        Info 'Поднимаю БД шлюза и PLC-симулятор ...'
        & docker compose @composeArgs up -d scada-postgres scada-simulator
        Repair-GatewayDb
        Info 'Поднимаю scada-gateway ...'
        & docker compose @composeArgs up -d --build scada-gateway
    }

    Info 'Поднимаю сервисы ...'
    & docker compose @composeArgs up -d --build auth channel editor runtime gateway
    Pop-Location

    Wait-Port 8080 'gateway (вход снаружи)' 120 | Out-Null
}

# ============================== HOST-режим ==============================

if ($Mode -eq 'host') {

    # ---------- 1..3. Инфраструктура на хосте ----------
    if (-not $ServicesOnly) {
        $pg = Get-Service -Name $PgService -ErrorAction SilentlyContinue
        if ($pg) {
            if ($pg.Status -ne 'Running') {
                Info "Стартую службу $PgService ..."
                try { Start-Service $PgService -ErrorAction Stop; Ok 'PostgreSQL запущен' }
                catch { Warn "Не смог стартовать $PgService (нужен запуск от администратора)." }
            } else { Ok 'PostgreSQL уже работает' }
        } else {
            Warn "Служба $PgService не найдена — проверь Get-Service *postgres* или задай -PgService."
        }

        if (Test-Port 6379) {
            Ok 'Redis уже работает (6379)'
        } else {
            $svc = Get-Service -Name 'Memurai', 'Redis' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($svc) {
                Info "Стартую службу Redis/Memurai ($($svc.Name)) ..."
                try { Start-Service $svc.Name -ErrorAction Stop } catch { Warn "Не смог стартовать $($svc.Name)." }
            } else {
                $redisExe = Join-Path $RedisHome 'redis-server.exe'
                if (Test-Path $redisExe) {
                    Info "Запускаю Redis из $RedisHome ..."
                    Start-InWindow 'redis' $RedisHome '.\redis-server.exe'
                } else {
                    Warn "Redis не найден. Портативный: github.com/tporadowski/redis в $RedisHome, либо -RedisHome."
                }
            }
        }

        if (Test-Port 9092) {
            Ok 'Kafka уже работает (9092)'
        } else {
            $cfg      = Join-Path $KafkaHome 'config\server.properties'
            $fmtBat   = Join-Path $KafkaHome 'bin\windows\kafka-storage.bat'
            if (-not (Test-Path $cfg)) {
                Err "Не найден $cfg — проверь -KafkaHome (для Kafka 4.x это config\server.properties)."
            } else {
                $logLine = (Select-String -Path $cfg -Pattern '^\s*log\.dirs\s*=' | Select-Object -First 1).Line
                $logDir = $null
                if ($logLine) {
                    $raw = (($logLine -split '=', 2)[1] -split ',')[0].Trim() -replace '/', '\'
                    if ($raw -match '^\\') { $logDir = (Split-Path $KafkaHome -Qualifier) + $raw } else { $logDir = $raw }
                }
                if ($logDir -and -not (Test-Path (Join-Path $logDir 'meta.properties'))) {
                    Info "Первый запуск Kafka — форматирую хранилище ($logDir) ..."
                    $id = (& $fmtBat random-uuid | Where-Object { $_ -notmatch 'ERROR|WARN|INFO' } | Select-Object -Last 1).Trim()
                    Info "Cluster ID = $id"
                    & $fmtBat format --standalone -t $id -c $cfg
                }
                Clear-KafkaLogDir $logDir
                Info 'Запускаю Kafka ...'
                Start-InWindow 'kafka' $KafkaHome '.\bin\windows\kafka-server-start.bat .\config\server.properties'
            }
        }

        Info 'Жду готовности инфраструктуры ...'
        Wait-Port 5432 'PostgreSQL' 30 | Out-Null
        Wait-Port 6379 'Redis'      30 | Out-Null
        Wait-Port 9092 'Kafka'      90 | Out-Null
    }

    # ---------- 4. Источник телеметрии ----------
    if (-not $NoSim) {
        if (-not (Test-Port 9092)) {
            Warn 'Kafka (9092) не слушает — источник телеметрии пропущен'
        }
        elseif ($Sim) {
            $simDir = Join-Path $ProjectRoot 'tools\kafka-sim'
            if (-not (Test-Path (Join-Path $simDir 'sim.mjs'))) {
                Warn "Симулятор не найден в $simDir — пропускаю"
            } else {
                if (-not (Test-Path (Join-Path $simDir 'node_modules'))) {
                    Info 'Первый запуск симулятора — ставлю зависимости ...'
                    Push-Location $simDir
                    & npm install --no-audit --no-fund | Out-Null
                    Pop-Location
                }
                Info 'Запускаю лёгкий Kafka-симулятор ...'
                Start-InWindow 'kafka-sim' $simDir 'node sim.mjs'
            }
        }
        elseif (-not (Test-Path (Join-Path $GatewayDir 'SCADA-gateway\pom.xml'))) {
            Warn "scada-gateway не найден в $GatewayDir — укажи -GatewayDir или запусти с -Sim"
        }
        elseif (-not (Test-Docker)) {
            Err 'Docker не запущен — БД шлюза и PLC-симулятор не поднять. Запусти Docker Desktop или используй -Sim.'
        }
        else {
            # Где окажется шлюз, решает не режим запуска, а факт: контейнер scada-gateway
            # объявлен с restart: unless-stopped, поэтому однажды поднятый в docker-режиме
            # он переживает перезагрузку и держит порт 8888 на host-стенде тоже. Раньше
            # host-ветка безусловно анонсировала симулятору localhost, считая шлюз хостовым,
            # и при живом контейнере весь OPC UA молча отваливался (см. Resolve-SimEndpoint).
            $gatewayInContainer = Test-ContainerRunning 'scada-gateway'
            $env:SCADA_SIM_ENDPOINT = Resolve-SimEndpoint $gatewayInContainer
            $env:SCADA_GATEWAY_PATH = $GatewayDir -replace '\\', '/'

            if ($gatewayInContainer) {
                Warn 'Шлюз работает в контейнере (scada-gateway), хотя режим host.'
                Warn "Подстраиваю анонс симулятора под него: $env:SCADA_SIM_ENDPOINT"
                Warn 'Нужен нативный шлюз — сначала: docker rm -f scada-gateway'
            }

            Info 'Поднимаю БД шлюза и PLC-симулятор (docker compose) ...'
            Push-Location $ProjectRoot
            & docker compose -f docker-compose.gateway.yml up -d scada-postgres scada-simulator
            Pop-Location

            # Порт 8888 держат оба варианта шлюза, поэтому одной проверки порта мало:
            # контейнер отличаем по имени. Заодно это экономит минуту на сборке jar,
            # который при живом контейнере всё равно не понадобится.
            if ($gatewayInContainer) {
                Ok 'scada-gateway работает в контейнере (8888) — нативный не запускаю'
                Info 'Если симулятор пересоздавался, OPC UA у шлюза переподключится сам (до ~1 минуты)'
            }
            elseif (Test-Port 8888) {
                Ok 'scada-gateway уже работает на хосте (8888)'
            }
            else {
                $jar = Get-ChildItem -Path (Join-Path $GatewayDir 'SCADA-gateway\target') -Filter 'SCADA-gateway-*.jar' `
                           -ErrorAction SilentlyContinue |
                       Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
                if (-not $jar) {
                    $jdk = Resolve-Jdk21
                    if (-not $jdk) {
                        Err 'Не нашёл JDK 21 — шлюз требует именно её (наши модули под 17). Поставь JDK 21 или задай JAVA_HOME.'
                    } else {
                        Info "Jar шлюза не найден — собираю (JDK 21: $jdk), это займёт минуту ..."
                        $saved = $env:JAVA_HOME
                        $env:JAVA_HOME = $jdk
                        Push-Location (Join-Path $GatewayDir 'SCADA-gateway')
                        & .\mvnw.cmd -q -DskipTests package
                        Pop-Location
                        $env:JAVA_HOME = $saved
                        $jar = Get-ChildItem -Path (Join-Path $GatewayDir 'SCADA-gateway\target') -Filter 'SCADA-gateway-*.jar' `
                                   -ErrorAction SilentlyContinue |
                               Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
                    }
                }

                if (-not $jar) {
                    Err 'Сборка шлюза не дала jar — смотри вывод maven выше'
                } else {
                    Info 'Жду БД шлюза (5433) и OPC UA симулятора (4840) ...'
                    Wait-Port 5433 'scada-postgres' 60 | Out-Null
                    Wait-Port 4840 'scada-simulator (OPC UA)' 90 | Out-Null
                    Repair-GatewayDb

                    # Переопределяем только то, что в application.yaml прибито к их стенду:
                    # адрес БД (у нас 5433 на хосте) и топик телеметрии (наш scada.tags).
                    # SIM_HOST/modbus.host уже дефолтятся в 127.0.0.1 — это и есть хост.
                    #
                    # send-bad-frames у шлюза выключен по умолчанию: кадр с value=null опасен
                    # для потребителя, который передаёт значение прямо в скрипт (null в JS —
                    # falsy, и клапан нарисовался бы ЗАКРЫТЫМ вместо «нет данных»). У нас
                    # недостоверное значение до скриптов не доходит, а фронт рисует по
                    # quality, поэтому включаем: иначе тег на обрыве замирает на последнем
                    # значении и выглядит живым.
                    $gwArgs = @(
                        '-jar', "`"$($jar.FullName)`"",
                        '--spring.datasource.url=jdbc:postgresql://localhost:5433/scada_db',
                        '--spring.kafka.bootstrap-servers=localhost:9092',
                        '--kafka.topics.telemetry=scada.tags',
                        '--gateway.send-bad-frames=true'
                    ) -join ' '
                    Info 'Запускаю scada-gateway (BN1_MCA1 -> scada.tags, порт 8888) ...'
                    Start-InWindow 'scada-gateway' (Join-Path $GatewayDir 'SCADA-gateway') "java $gwArgs"
                }
            }
        }
    }

    # ---------- 5. Микросервисы ----------
    Info 'Запускаю микросервисы ...'
    foreach ($s in $Services) {
        if (Test-Port $s.Port) {
            Warn "$($s.Name): порт $($s.Port) уже занят — пропускаю"
            continue
        }
        Info "  -> $($s.Name) (порт $($s.Port))"
        Start-InWindow $s.Name $ProjectRoot ".\gradlew :$($s.Name):bootRun"
        Start-Sleep -Seconds 2
    }
}

# ============================== Фронтенд ==============================

if (-not $NoFrontend) {
    if (Test-Port $FrontendPort) {
        Ok "Фронтенд уже работает ($FrontendPort)"
    } elseif (Test-Path $FrontendDir) {
        if (-not (Test-Path (Join-Path $FrontendDir 'node_modules'))) {
            Info 'Первый запуск фронта — ставлю зависимости (npm install), это надолго ...'
            Push-Location $FrontendDir
            & npm install --no-audit --no-fund | Out-Null
            Pop-Location
        }
        Info "Запускаю фронтенд (npm run dev, порт $FrontendPort) ..."
        Start-InWindow 'frontend' $FrontendDir 'npm run dev'
    } else {
        Warn "Каталог фронтенда не найден: $FrontendDir — укажи -FrontendDir."
    }
}

# ============================== Итог ==============================

Write-Host ''
Info 'Жду, пока поднимется вход снаружи (gateway:8080) ...'
$gwUp = Wait-Port 8080 'gateway' 240

Write-Host ''
if ($gwUp) {
    Ok 'Стенд поднят.'
} else {
    Warn 'Стенд поднят частично — смотри окна сервисов, там причина.'
}
Write-Host '  Вход снаружи : http://localhost:8080'
Write-Host '  Фронтенд     : http://localhost:' -NoNewline; Write-Host $FrontendPort
Write-Host '  Шлюз ПЛК     : http://localhost:8888/actuator/health'
if ($Mode -eq 'host') {
    Write-Host '  Swagger      : auth :8081 / channel :8082 / editor :8083  (runtime :8085)'
} else {
    Write-Host '  Порты сервисов наружу не публикуются — только через gateway:8080'
}
Write-Host ''
Write-Host '  .\start-all.ps1 -Status   — что сейчас поднято'
Write-Host '  .\start-all.ps1 -Stop     — погасить контейнеры'
Write-Host ''
Warn 'Первые значения тегов появятся не сразу: шлюз обходит 2471 канал ~90 секунд,'
Warn 'а consumer читает топик с конца. До первого значения теги идут как «нет данных».'
