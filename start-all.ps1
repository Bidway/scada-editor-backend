<#
    start-all.ps1 — единый запуск стенда SCADA целиком, одной командой.

    Два режима:

      -Mode host   (по умолчанию) — быстрый цикл разработки.
                   Инфраструктура на хосте (PostgreSQL/Redis/Kafka), БД шлюза и
                   PLC-симулятор — тоже без Docker (см. -DockerGateway ниже),
                   всё остальное — своим окном на хосте через gradlew bootRun.
                   Пересборка модуля = перезапуск окна.

      -Mode docker — всё в контейнерах, ближе к бою. Перед сборкой образов
                   ОБЯЗАТЕЛЬНО собирает jar'ы: наши Dockerfile'ы копируют готовый
                   build/libs/*.jar с хоста, поэтому `docker compose up --build`
                   сам по себе НЕ пересобирает Java-код и молча поднимает
                   прошлую сборку.

    Источник телеметрии (оба режима):
      по умолчанию (режим host) — реальный scada-gateway БЕЗ Docker: БД шлюза —
                   в уже поднятом хостовом PostgreSQL (scada_db на 5432), сам
                   шлюз и PLC-симулятор — процессами на хосте, симулятор по
                   127.0.0.1. Опрашивает симулятор по OPC UA и Modbus и
                   публикует все 2471 канал BN1_MCA1 в scada.tags. Заведено
                   25.08.2026 (scada-rmu): у Docker Desktop на этой машине
                   систематически рвётся проброс порта 4840 (vpnkit/gvisor) —
                   соединение принимается и тут же обрывается, порт 5020 рядом
                   при этом жив. Не чинится ни docker restart, ни
                   force-recreate контейнера, ни полным перезапуском Docker
                   Desktop. Требует один раз: venv в plc-simulator\.venv312
                   (Python 3.12/3.13 — pip install -r requirements.txt) — на
                   3.14 у asyncua 1.1.8 известный баг;
      -NoSim         — без источника вовсе;
      -DockerGateway — прежнее поведение: БД шлюза и PLC-симулятор в Docker
                   (только режим host; в -Mode docker и так всё в контейнерах).
                   Если контейнер scada-gateway уже поднят с прошлого прогона
                   (restart: unless-stopped переживает перезагрузку), он
                   используется автоматически и без этого флага.

    Примеры:
      .\start-all.ps1                     # host-режим, реальный шлюз без Docker, фронт
      .\start-all.ps1 -Mode docker        # весь стенд в контейнерах
      .\start-all.ps1 -DockerGateway      # БД шлюза и PLC-симулятор в Docker
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
    [switch]$NoSim,
    # Прежнее поведение (до 26.08.2026): БД шлюза (scada_db) и PLC-симулятор в
    # Docker. Отключено по умолчанию — докеровский NAT-проброс порта 4840 у
    # Docker Desktop на этой машине систематически рвёт соединение сразу после
    # handshake (порт 5020 рядом при этом жив), не чинится ни docker restart,
    # ни force-recreate контейнера, ни полным перезапуском Docker Desktop
    # (scada-rmu). По умолчанию БД — в уже поднятом хостовом PostgreSQL (5432),
    # симулятор — питоновский процесс в своём окне на 127.0.0.1, шлюз ходит к
    # нему напрямую, без прослойки vpnkit/gvisor. Требует заранее: venv в
    # plc-simulator\.venv312 (Python 3.12/3.13 — на 3.11 у asyncua 1.1.8 нет
    # известного бага, на 3.14 есть; см. plc-simulator/Dockerfile) и роль/базу
    # scada_user/scada_db в хостовом PostgreSQL.
    [switch]$DockerGateway,
    [switch]$NoFrontend,
    [switch]$Status,
    [switch]$Stop
)

if (-not $ProjectRoot) { $ProjectRoot = 'Z:\Claude\Projects\scada-editor-backend' }
$ErrorActionPreference = 'Continue'

# true, только если этот прогон сам поднимал Kafka с нуля (порт не слушал на
# входе) — сигнал для Start-GatewayNative, что уже запущенный шлюз мог зависнуть
# на мёртвом соединении и его надо перезапустить, а не считать рабочим по факту
# открытого порта 8888.
$KafkaJustStarted = $false

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

<#
    Вторую половину той же грабли (KAFKA-1194) Clear-KafkaLogDir не лечит — она
    убирает уже накопившийся мусор, но LogCleaner тут же попробует создать новый:
    компактит __consumer_offsets через .cleaned -> .swap, переименовать файл с
    активным mmap Windows не даёт, каталог логов помечается failed, брокер гасит
    сам себя через несколько минут после старта. Выключаем компакцию совсем —
    плата за это только рост __consumer_offsets, для dev-стенда не критично.
    Конфиг лежит вне репозитория (в git его нет), поэтому патчим его сами при
    каждом запуске — иначе правка теряется при переустановке Kafka или на новой
    машине.
#>
function Disable-KafkaLogCleaner([string]$ConfigPath) {
    if (-not (Test-Path $ConfigPath)) { return }
    $content = Get-Content $ConfigPath -Raw
    if ($content -match '(?m)^\s*log\.cleaner\.enable\s*=\s*false\s*$') { return }
    if ($content -match '(?m)^\s*log\.cleaner\.enable\s*=') {
        $patched = $content -replace '(?m)^\s*log\.cleaner\.enable\s*=.*$', 'log.cleaner.enable=false'
    } else {
        $block = "`r`n############################# Log Cleaner #############################`r`n`r`n" +
                 "# Выключено намеренно: LogCleaner компактит __consumer_offsets через" +
                 "`r`n# .cleaned -> .swap -> рабочий файл, а переименовать файл с активным mmap" +
                 "`r`n# Windows не даёт (KAFKA-1194, открыт с 2014, не исправлен) — брокер валит" +
                 "`r`n# все log dirs и гасится через несколько минут после старта. На Linux это" +
                 "`r`n# не проблема, но брокер здесь нативный на Windows. Плата — __consumer_offsets" +
                 "`r`n# растёт, а не сжимается; для dev-стенда это не критично.`r`n" +
                 "log.cleaner.enable=false`r`n"
        $patched = $content.TrimEnd() + "`r`n" + $block
    }
    Set-Content -Path $ConfigPath -Value $patched -NoNewline
    Info 'Kafka: log.cleaner.enable=false в server.properties (KAFKA-1194 — компакция валит брокер на Windows)'
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
function Start-GatewayNative([string]$DbUrl, [switch]$ForceRestart) {
    if (Test-Port 8888) {
        if (-not $ForceRestart) {
            Ok 'scada-gateway уже работает на хосте (8888)'
            return
        }
        # Порт слушает, но Kafka мы только что подняли заново — у уже живого шлюза
        # соединение с брокером умерло вместе с ним, а сеть при этом переустановилась
        # молча: TCP до Kafka/OPC UA/БД выглядит живым, но публикация в scada.tags
        # не возобновляется сама (проверено вживую 31.08.2026). Единственное лечение —
        # перезапустить процесс целиком.
        Warn 'scada-gateway работает, но Kafka только что перезапускалась — публикация могла зависнуть, перезапускаю шлюз'
        Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -match 'SCADA-gateway' } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    }
    $jar = Get-ChildItem -Path (Join-Path $GatewayDir 'SCADA-gateway\target') -Filter 'SCADA-gateway-*.jar' `
               -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    $jdk = $null
    if (-not $jar) {
        $jdk = Resolve-Jdk21
        if (-not $jdk) {
            Err 'Не нашёл JDK 21 — шлюз требует именно её (наши модули под 17). Поставь JDK 21 или задай JAVA_HOME.'
            return
        }
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
    if (-not $jar) {
        Err 'Сборка шлюза не дала jar — смотри вывод maven выше'
        return
    }
    if (-not $jdk) { $jdk = Resolve-Jdk21 }
    if (-not $jdk) {
        Err 'Не нашёл JDK 21 — шлюз требует именно её для запуска. Поставь JDK 21 или задай JAVA_HOME.'
        return
    }
    # Переопределяем только то, что в application.yaml прибито к их стенду: адрес БД
    # и топик телеметрии (наш scada.tags). SIM_HOST/modbus.host уже дефолтятся в
    # 127.0.0.1 — это и есть хост. send-bad-frames включаем: у нас недостоверное
    # значение до скриптов не доходит, фронт рисует по quality (см. gwArgs выше по коду).
    $gwArgs = @(
        '-jar', "`"$($jar.FullName)`"",
        "--spring.datasource.url=$DbUrl",
        '--spring.kafka.bootstrap-servers=localhost:9092',
        '--kafka.topics.telemetry=scada.tags',
        '--gateway.send-bad-frames=true'
    ) -join ' '
    Info "Запускаю scada-gateway (BN1_MCA1 -> scada.tags, порт 8888, JDK 21: $jdk) ..."
    $gwJava = Join-Path $jdk 'bin\java.exe'
    Start-InWindow 'scada-gateway' (Join-Path $GatewayDir 'SCADA-gateway') "`"$gwJava`" $gwArgs"
}

function Repair-GatewayDb {
    $sql = 'UPDATE tags SET record_device = false WHERE record_device IS NULL'
    $out = docker exec scada-postgres psql -U scada_user -d scada_db -tAc $sql 2>$null
    if ($LASTEXITCODE -eq 0 -and "$out" -match 'UPDATE\s+([1-9]\d*)') {
        Ok "БД шлюза: заполнено record_device у $($Matches[1]) строк (иначе шлюз не стартует)"
    }
}

<#
    То же самое, но для нативного PostgreSQL (запуск шлюза без Docker) — без docker exec.
    Заодно один раз заводит роль/базу scada_user/scada_db, если их ещё нет
    (первый прогон на новой машине).
#>
function Repair-GatewayDb-Native {
    $env:PGPASSWORD = 'postgres'
    $hasRole = psql -h localhost -p 5432 -U postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='scada_user'" 2>$null
    if ("$hasRole".Trim() -ne '1') {
        Info 'Нативный PostgreSQL: завожу роль/базу scada_user/scada_db (первый запуск без Docker) ...'
        psql -h localhost -p 5432 -U postgres -c "CREATE ROLE scada_user LOGIN PASSWORD 'scada_password';" 2>$null | Out-Null
        psql -h localhost -p 5432 -U postgres -c "CREATE DATABASE scada_db OWNER scada_user;" 2>$null | Out-Null
    }
    $env:PGPASSWORD = 'scada_password'
    $sql = 'UPDATE tags SET record_device = false WHERE record_device IS NULL'
    $out = psql -h localhost -p 5432 -U scada_user -d scada_db -tAc $sql 2>$null
    if ($LASTEXITCODE -eq 0 -and "$out" -match 'UPDATE\s+([1-9]\d*)') {
        Ok "БД шлюза (нативная): заполнено record_device у $($Matches[1]) строк"
    }
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
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
    if (-not $NoSim) {
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

    if (-not $NoSim) {
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
                Disable-KafkaLogCleaner $cfg
                Clear-KafkaLogDir $logDir
                Info 'Запускаю Kafka ...'
                Start-InWindow 'kafka' $KafkaHome '.\bin\windows\kafka-server-start.bat .\config\server.properties'
                # Сами подняли брокер — значит, любой уже запущенный консьюмер/продюсер
                # (в первую очередь scada-gateway) мог остаться со сдохшим соединением
                # и не восстановиться сам. Дальше по скрипту это флаг «перезапускай его
                # принудительно», см. Start-GatewayNative -ForceRestart.
                $KafkaJustStarted = $true
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
        elseif (-not (Test-Path (Join-Path $GatewayDir 'SCADA-gateway\pom.xml'))) {
            Warn "scada-gateway не найден в $GatewayDir — укажи -GatewayDir или запусти с -NoSim"
        }
        elseif ($DockerGateway) {
            if (-not (Test-Docker)) {
                Err 'Docker не запущен — нужен для -DockerGateway. Запусти Docker Desktop или убери флаг (по умолчанию — без Docker).'
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
                else {
                    Info 'Жду БД шлюза (5433) и OPC UA симулятора (4840) ...'
                    Wait-Port 5433 'scada-postgres' 60 | Out-Null
                    Wait-Port 4840 'scada-simulator (OPC UA)' 90 | Out-Null
                    Repair-GatewayDb
                    Start-GatewayNative 'jdbc:postgresql://localhost:5433/scada_db' -ForceRestart:$KafkaJustStarted
                }
            }
        }
        else {
            # По умолчанию — без Docker вообще (scada-rmu, см. шапку файла). Но если
            # контейнер scada-gateway уже жив с прошлого прогона в -DockerGateway
            # (restart: unless-stopped переживает перезагрузку), используем его, а не
            # поднимаем второй нативный шлюз поверх занятого порта 8888.
            $gatewayInContainer = (Test-Docker) -and (Test-ContainerRunning 'scada-gateway')
            if ($gatewayInContainer) {
                Warn 'Шлюз уже работает в контейнере (scada-gateway) — использую его вместо нативного.'
                $env:SCADA_SIM_ENDPOINT = Resolve-SimEndpoint $true
                $env:SCADA_GATEWAY_PATH = $GatewayDir -replace '\\', '/'
                Info 'Поднимаю БД шлюза и PLC-симулятор (docker compose) — их требует контейнерный шлюз ...'
                Push-Location $ProjectRoot
                & docker compose -f docker-compose.gateway.yml up -d scada-postgres scada-simulator
                Pop-Location
                Ok 'scada-gateway работает в контейнере (8888) — нативный не запускаю'
                Info 'Если симулятор пересоздавался, OPC UA у шлюза переподключится сам (до ~1 минуты)'
            }
            else {
                $venvPy = Join-Path $GatewayDir 'plc-simulator\.venv312\Scripts\python.exe'
                if (-not (Test-Path $venvPy)) {
                    Err "Нет venv симулятора: $venvPy — один раз выполни в plc-simulator: py -3.12 -m venv .venv312; .venv312\Scripts\pip install -r requirements.txt"
                } else {
                    # Шлюз здесь всегда хостовый, поэтому анонс симулятора — всегда localhost.
                    if (Test-Port 4840) {
                        Ok 'plc-simulator (native) уже слушает 4840 — не запускаю второй'
                    } else {
                        Info 'Запускаю PLC-симулятор нативно (без Docker) ...'
                        $simInner = "`$host.ui.RawUI.WindowTitle='plc-simulator (native)'; " +
                            "Set-Location '$(Join-Path $GatewayDir 'plc-simulator')'; " +
                            "`$env:OPCUA_ENDPOINT='opc.tcp://localhost:4840'; " +
                            "& '$venvPy' simulator.py config\replay_config.yaml"
                        Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoExit', '-Command', $simInner -WindowStyle Normal | Out-Null
                    }
                    Wait-Port 4840 'plc-simulator (native, OPC UA)' 30 | Out-Null
                    Repair-GatewayDb-Native
                    Start-GatewayNative 'jdbc:postgresql://localhost:5432/scada_db' -ForceRestart:$KafkaJustStarted
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
