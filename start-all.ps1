<#
    start-all.ps1 — единый запуск SCADA-бэкенда без Docker.

    Что делает:
      1. Проверяет/поднимает PostgreSQL (служба postgresql-x64-17)
      2. Поднимает Redis   (порт 6379)
      3. Поднимает Kafka   (порт 9092, KRaft, конфиг config\server.properties)
      4. Ждёт готовности инфраструктуры по портам
      5. Поднимает источник телеметрии — по умолчанию РЕАЛЬНЫЙ шлюз scada-gateway:
         его postgres и PLC-симулятор в Docker, сам шлюз — на хосте своим окном.
         Флаг -Sim переключает обратно на лёгкий tools\kafka-sim.
      6. Запускает 5 сервисов через gradlew bootRun, каждый в своём окне:
         auth:8081  channel:8082  editor:8083  runtime:8085  gateway:8080
      7. Запускает фронтенд (npm run dev) в своём окне (порт 5173)

    Повторный запуск безопасен: то, что уже слушает свой порт, пропускается.

    Примеры:
      .\start-all.ps1
      .\start-all.ps1 -RedisHome C:\redis -KafkaHome C:\kafka_2.13-4.3.1
      .\start-all.ps1 -ServicesOnly      # только сервисы, инфраструктуру не трогать
      .\start-all.ps1 -Sim               # лёгкий kafka-sim вместо реального шлюза
      .\start-all.ps1 -NoSim             # вообще без источника телеметрии
#>

param(
    [string]$ProjectRoot = $PSScriptRoot,
    [string]$KafkaHome   = 'C:\kafka_2.13-4.3.1',
    [string]$RedisHome   = 'C:\redis',
    [string]$PgService   = 'postgresql-x64-17',
    [string]$FrontendDir = 'Z:\Project Java\scada-editor-frontend',
    [string]$GatewayDir  = 'Z:\Project Java\scada-gateway',
    [switch]$ServicesOnly,
    [switch]$Sim,
    [switch]$NoSim
)

if (-not $ProjectRoot) { $ProjectRoot = 'Z:\GitKraken\scada-editor-backend' }
$ErrorActionPreference = 'Continue'

# ---------- helpers ----------
function Info($m) { Write-Host "[*] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[OK] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[!] $m" -ForegroundColor Yellow }
function Err($m)  { Write-Host "[X] $m" -ForegroundColor Red }

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

# Запуск команды в отдельном окне PowerShell с заголовком
function Start-InWindow([string]$Title, [string]$WorkDir, [string]$Command) {
    $inner = "`$host.ui.RawUI.WindowTitle='$Title'; Set-Location '$WorkDir'; $Command"
    Start-Process -FilePath 'powershell.exe' `
        -ArgumentList '-NoExit', '-Command', $inner -WindowStyle Normal | Out-Null
}

Info "Проект: $ProjectRoot"

# ---------- 1. PostgreSQL ----------
if (-not $ServicesOnly) {
    $pg = Get-Service -Name $PgService -ErrorAction SilentlyContinue
    if ($pg) {
        if ($pg.Status -ne 'Running') {
            Info "Стартую службу $PgService ..."
            try { Start-Service $PgService -ErrorAction Stop; Ok "PostgreSQL запущен" }
            catch { Warn "Не смог стартовать $PgService (нужен запуск от администратора). Запусти службу вручную." }
        } else { Ok "PostgreSQL уже работает" }
    } else {
        Warn "Служба $PgService не найдена — проверь имя (Get-Service *postgres*) или задай -PgService."
    }

    # ---------- 2. Redis ----------
    if (Test-Port 6379) {
        Ok "Redis уже работает (6379)"
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
                Warn "Redis не найден. Скачай портативный (github.com/tporadowski/redis) в $RedisHome, поставь Memurai, или укажи -RedisHome."
            }
        }
    }

    # ---------- 3. Kafka ----------
    if (Test-Port 9092) {
        Ok "Kafka уже работает (9092)"
    } else {
        $cfg     = Join-Path $KafkaHome 'config\server.properties'
        $fmtBat  = Join-Path $KafkaHome 'bin\windows\kafka-storage.bat'
        $startBat = Join-Path $KafkaHome 'bin\windows\kafka-server-start.bat'
        if (-not (Test-Path $cfg)) {
            Err "Не найден $cfg — проверь -KafkaHome (для Kafka 4.x это config\server.properties)."
        } else {
            # определить каталог логов из log.dirs, чтобы понять, форматировали ли хранилище
            $logLine = (Select-String -Path $cfg -Pattern '^\s*log\.dirs\s*=' | Select-Object -First 1).Line
            $logDir = $null
            if ($logLine) {
                $raw = (($logLine -split '=', 2)[1] -split ',')[0].Trim() -replace '/', '\'
                if ($raw -match '^\\') { $logDir = (Split-Path $KafkaHome -Qualifier) + $raw } else { $logDir = $raw }
            }
            $needFormat = $logDir -and -not (Test-Path (Join-Path $logDir 'meta.properties'))
            if ($needFormat) {
                Info "Первый запуск Kafka — форматирую хранилище ($logDir) ..."
                $id = (& $fmtBat random-uuid | Where-Object { $_ -notmatch 'ERROR|WARN|INFO' } | Select-Object -Last 1).Trim()
                Info "Cluster ID = $id"
                & $fmtBat format --standalone -t $id -c $cfg
            }
            Info "Запускаю Kafka ..."
            Start-InWindow 'kafka' $KafkaHome '.\bin\windows\kafka-server-start.bat .\config\server.properties'
        }
    }

    # ---------- 4. Ждём инфраструктуру ----------
    Info "Жду готовности инфраструктуры ..."
    Wait-Port 5432 'PostgreSQL' 30 | Out-Null
    Wait-Port 6379 'Redis'      30 | Out-Null
    Wait-Port 9092 'Kafka'      90 | Out-Null
}

# ---------- 4.5. Источник телеметрии ----------
# По умолчанию — реальный шлюз scada-gateway: его БД и PLC-симулятор поднимаются
# в Docker (порты 5433 / 4840 / 5020 на хост), а сам шлюз запускается на хосте.
# Так он ходит в наш брокер по localhost:9092 и не упирается в advertised.listeners
# хостовой Kafka, который из контейнера не резолвится.
# -Sim возвращает лёгкий tools\kafka-sim (3 тега сцены, без Docker и OPC UA).
if (-not $NoSim) {
    if (-not (Test-Port 9092)) {
        Warn "Kafka (9092) не слушает — источник телеметрии пропущен (подними Kafka или запусти без -ServicesOnly)"
    }
    elseif ($Sim) {
        $simDir = Join-Path $ProjectRoot 'tools\kafka-sim'
        if (-not (Test-Path (Join-Path $simDir 'sim.mjs'))) {
            Warn "Симулятор не найден в $simDir — пропускаю"
        } else {
            if (-not (Test-Path (Join-Path $simDir 'node_modules'))) {
                Info "Первый запуск симулятора — ставлю зависимости (npm install) ..."
                Push-Location $simDir
                & npm install --no-audit --no-fund | Out-Null
                Pop-Location
            }
            Info "Запускаю лёгкий Kafka-симулятор (телеметрия сцены + выполнение команд) ..."
            Start-InWindow 'kafka-sim' $simDir 'node sim.mjs'
        }
    }
    elseif (-not (Test-Path (Join-Path $GatewayDir 'SCADA-gateway\pom.xml'))) {
        Warn "scada-gateway не найден в $GatewayDir — источник телеметрии пропущен (укажи -GatewayDir или запусти с -Sim)"
    }
    elseif (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Warn "docker не найден в PATH — БД шлюза и PLC-симулятор не поднять (запусти с -Sim)"
    }
    else {
        # Симулятор анонсирует свой endpoint клиентам; шлюз идёт по нему с хоста,
        # поэтому имя сервиса compose здесь не годится — только localhost.
        $env:SCADA_SIM_ENDPOINT = 'opc.tcp://localhost:4840'
        $env:SCADA_GATEWAY_PATH = $GatewayDir -replace '\\', '/'

        Info "Поднимаю БД шлюза и PLC-симулятор (docker compose) ..."
        Push-Location $ProjectRoot
        & docker compose -f docker-compose.gateway.yml up -d scada-postgres scada-simulator
        Pop-Location

        $jar = Get-ChildItem -Path (Join-Path $GatewayDir 'SCADA-gateway\target') -Filter 'SCADA-gateway-*.jar' `
                   -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        if (-not $jar) {
            Info "Jar шлюза не найден — собираю (mvnw -DskipTests package), это займёт минуту ..."
            Push-Location (Join-Path $GatewayDir 'SCADA-gateway')
            & .\mvnw.cmd -q -DskipTests package
            Pop-Location
            $jar = Get-ChildItem -Path (Join-Path $GatewayDir 'SCADA-gateway\target') -Filter 'SCADA-gateway-*.jar' `
                       -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        }

        if (-not $jar) {
            Err "Сборка шлюза не дала jar — посмотри вывод maven выше"
        } elseif (Test-Port 8888) {
            # У лёгкого симулятора порта нет, и повторный прогон скрипта плодил вторую
            # копию (см. STAND_SETUP «Известные грабли»). У шлюза порт есть — пользуемся.
            Ok "scada-gateway уже работает (8888)"
        } else {
            Info "Жду БД шлюза (5433) и OPC UA симулятора (4840) ..."
            Wait-Port 5433 'scada-postgres' 60 | Out-Null
            Wait-Port 4840 'scada-simulator (OPC UA)' 90 | Out-Null

            # Переопределяем только то, что в application.yaml прибито к их стенду:
            # адрес БД (у нас 5433 на хосте) и топик телеметрии (наш scada.tags).
            # SIM_HOST/modbus.host уже дефолтятся в 127.0.0.1 — это и есть хост.
            $gwArgs = @(
                '-jar', "`"$($jar.FullName)`"",
                '--spring.datasource.url=jdbc:postgresql://localhost:5433/scada_db',
                '--spring.kafka.bootstrap-servers=localhost:9092',
                '--kafka.topics.telemetry=scada.tags'
            ) -join ' '
            Info "Запускаю scada-gateway (телеметрия BN1_MCA1 -> scada.tags, порт 8888) ..."
            Start-InWindow 'scada-gateway' (Join-Path $GatewayDir 'SCADA-gateway') "java $gwArgs"
        }
    }
}

# ---------- 5. Микросервисы ----------
$services = @(
    @{ Name = 'auth';    Port = 8081 },
    @{ Name = 'channel'; Port = 8082 },
    @{ Name = 'editor';  Port = 8083 },
    @{ Name = 'runtime'; Port = 8085 },   # нужна Kafka
    @{ Name = 'gateway'; Port = 8080 }    # запускается последним
)

Info "Запускаю микросервисы ..."
foreach ($s in $services) {
    if (Test-Port $s.Port) {
        Warn "$($s.Name): порт $($s.Port) уже занят — пропускаю"
        continue
    }
    Info "  -> $($s.Name) (порт $($s.Port))"
    Start-InWindow $s.Name $ProjectRoot ".\gradlew :$($s.Name):bootRun"
    Start-Sleep -Seconds 2
}

# ---------- 6. Фронтенд ----------
if (Test-Port 5173) {
    Ok "Фронтенд уже работает (5173)"
} elseif (Test-Path $FrontendDir) {
    Info "Запускаю фронтенд (npm run dev) ..."
    Start-InWindow 'frontend' $FrontendDir 'npm run dev'
} else {
    Warn "Каталог фронтенда не найден: $FrontendDir — укажи -FrontendDir."
}

Write-Host ""
Ok "Готово. Каждый компонент — в своём окне. Первый gradle-запуск компилирует модули, подожди пару минут."
Write-Host "  Gateway : http://localhost:8080"
Write-Host "  auth    : http://localhost:8081/swagger-ui.html"
Write-Host "  channel : http://localhost:8082/swagger-ui.html"
Write-Host "  editor  : http://localhost:8083/swagger-ui.html"
Write-Host "  runtime : http://localhost:8085"
Write-Host "  frontend: http://localhost:5173"
