# Собирает архив демо редактора для Linux-сервера: dist/scada-demo.tar.gz.
# Внутри — три jar (auth, editor, gateway), собранный фронт и конфиги из server/.
#
# Запуск из корня репозитория бэкенда:
#   .\demo\build-package.ps1
#   .\demo\build-package.ps1 -Frontend D:\path\to\scada-editor-frontend
#
# Docker нужен только здесь, на машине сборки: в нём собирается фронт, чтобы не трогать
# .next рабочей копии, с которой может работать dev-сервер. На сервере Docker не нужен.
param(
    [string]$Frontend = (Join-Path $PSScriptRoot '..\..\scada-editor-frontend')
)
# Не Stop: Windows PowerShell 5.1 превращает любую строку в stderr нативной команды
# (предупреждения javac, прогресс docker) в ошибку. Нативные команды проверяются по
# $LASTEXITCODE, командлеты — своим -ErrorAction Stop.
$ErrorActionPreference = 'Continue'
$PSDefaultParameterValues['*:ErrorAction'] = 'Stop'

$repo = Resolve-Path (Join-Path $PSScriptRoot '..')
$Frontend = Resolve-Path $Frontend
$dist = Join-Path $PSScriptRoot 'dist'
$pkg = Join-Path $dist 'scada-demo'

Write-Host "== Бэкенд: bootJar auth, editor, gateway ($repo)"
Push-Location $repo
try {
    & .\gradlew.bat :auth:bootJar :editor:bootJar :gateway:bootJar -q
    if ($LASTEXITCODE -ne 0) { throw 'gradle bootJar завершился с ошибкой' }
} finally { Pop-Location }

if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force $pkg | Out-Null

foreach ($svc in 'auth', 'editor', 'gateway') {
    $jar = Get-ChildItem (Join-Path $repo "$svc\build\libs\*.jar") | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
    Copy-Item $jar.FullName (Join-Path $pkg "$svc.jar")
}

Write-Host "== Фронт: next build в Docker ($Frontend)"
docker build -t scada-demo-frontend-build $Frontend
if ($LASTEXITCODE -ne 0) { throw 'docker build фронта завершился с ошибкой' }
$cid = (docker create scada-demo-frontend-build).Trim()
try {
    docker cp "${cid}:/app" (Join-Path $pkg 'frontend')
    if ($LASTEXITCODE -ne 0) { throw 'docker cp фронта завершился с ошибкой' }
} finally { docker rm $cid | Out-Null }

Copy-Item -Recurse (Join-Path $PSScriptRoot 'server') (Join-Path $pkg 'server')
Copy-Item (Join-Path $PSScriptRoot 'README.md') $pkg

Write-Host '== Архив'
tar -czf (Join-Path $dist 'scada-demo.tar.gz') -C $pkg .
if ($LASTEXITCODE -ne 0) { throw 'tar завершился с ошибкой' }

$size = [math]::Round((Get-Item (Join-Path $dist 'scada-demo.tar.gz')).Length / 1MB)
Write-Host "Готово: $dist\scada-demo.tar.gz ($size МБ)"
