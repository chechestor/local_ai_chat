# Local AI Chat: переустановка приложения на подключённый Android-телефон (F002).
#
# Запуск (Windows PowerShell 5.1 или PowerShell 7):
#   правый клик по файлу -> «Выполнить с помощью PowerShell»
#   или в консоли:  powershell -ExecutionPolicy Bypass -File install.ps1
#
# Скрипт лежит в одной папке с APK (архив артефакта сборки GitHub Actions).
# Порядок: найти adb -> найти APK -> проверить телефон -> удалить старую версию -> установить новую.
# История диалогов при удалении приложения теряется. Скачанные модели в Download/LocalAIChat остаются.

param(
    # Для автоматических проверок: не ждать нажатия клавиши в конце.
    [switch]$NoWait
)

# Папка с adb.exe (Android Platform Tools). Используется, если adb нет в системной переменной PATH.
# Поправьте путь, если Platform Tools лежат в другом месте.
$AdbExtraDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools'

# Идентификатор приложения (applicationId из app/build.gradle.kts).
$PackageName = 'com.chechestor.localaichat'

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Write-Step([string]$text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "   $text" -ForegroundColor Green }
function Write-Info([string]$text) { Write-Host "   $text" }
function Write-Fail([string]$text) { Write-Host "   ОШИБКА: $text" -ForegroundColor Red }

# Запуск adb: вывод (stdout и stderr) строками и код возврата.
function Invoke-Adb([string[]]$AdbArgs) {
    $output = & $script:Adb @AdbArgs 2>&1 | ForEach-Object { "$_" }
    return [pscustomobject]@{ Output = @($output); Code = $LASTEXITCODE }
}

function Find-Adb {
    $fromPath = Get-Command 'adb' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($fromPath) {
        return [pscustomobject]@{ Path = $fromPath.Source; Origin = 'системная переменная PATH' }
    }
    $candidate = Join-Path $AdbExtraDir 'adb.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        return [pscustomobject]@{ Path = $candidate; Origin = 'переменная $AdbExtraDir в начале скрипта' }
    }
    return $null
}

function Get-InstallErrorHint([string]$text) {
    if ($text -match 'INSUFFICIENT_STORAGE') { return 'На телефоне не хватает места. Освободите память и запустите скрипт снова.' }
    if ($text -match 'USER_RESTRICTED') { return 'Телефон запретил установку по USB. Включите в настройках разработчика «Установка через USB» и подтвердите установку на экране телефона.' }
    if ($text -match 'OLDER_SDK|NO_MATCHING_ABIS') { return 'Телефон не подходит: нужен Android 11 или новее, процессор ARM64.' }
    if ($text -match 'UPDATE_INCOMPATIBLE') { return 'На телефоне осталась версия с другой подписью. Удалите приложение вручную и запустите скрипт снова.' }
    return $null
}

function Invoke-Main {
    Write-Host 'Local AI Chat: переустановка приложения на телефон' -ForegroundColor White
    $scriptDir = $PSScriptRoot
    if (-not $scriptDir) { $scriptDir = (Get-Location).Path }

    # 1. adb
    Write-Step 'Поиск adb'
    $found = Find-Adb
    if (-not $found) {
        Write-Fail 'adb не найден.'
        Write-Info 'Где искали:'
        Write-Info '  - системная переменная PATH;'
        Write-Info "  - папка из переменной `$AdbExtraDir в начале скрипта: $AdbExtraDir"
        Write-Info 'Что сделать: установите Android SDK Platform Tools'
        Write-Info '  (https://developer.android.com/tools/releases/platform-tools)'
        Write-Info '  и добавьте папку platform-tools в PATH или укажите её в $AdbExtraDir.'
        return 2
    }
    $script:Adb = $found.Path
    Write-Ok "Используется adb: $($found.Path)"
    Write-Info "Откуда: $($found.Origin)"

    # 2. APK
    Write-Step 'Поиск APK'
    $apks = @(Get-ChildItem -LiteralPath $scriptDir -Filter '*.apk' -File -ErrorAction SilentlyContinue)
    if ($apks.Count -eq 0) {
        Write-Fail "В папке скрипта нет APK-файла: $scriptDir"
        Write-Info 'Распакуйте архив сборки целиком и запустите скрипт из той же папки.'
        return 3
    }
    if ($apks.Count -gt 1) {
        Write-Fail "В папке скрипта несколько APK-файлов ($($apks.Count)). Оставьте один:"
        $apks | ForEach-Object { Write-Info "  - $($_.Name)" }
        return 3
    }
    $apk = $apks[0]
    Write-Ok "APK: $($apk.Name) ($([math]::Round($apk.Length / 1MB, 1)) МБ)"

    # 3. Устройство
    Write-Step 'Поиск телефона'
    $res = Invoke-Adb @('devices')
    if ($res.Code -ne 0) {
        Write-Fail 'adb devices завершился с ошибкой:'
        $res.Output | ForEach-Object { Write-Info "  $_" }
        return 4
    }
    $devices = @()
    foreach ($line in $res.Output) {
        if ($line -match '^(\S+)\t(\S+)') { $devices += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] } }
    }
    if ($devices.Count -eq 0) {
        Write-Fail 'Телефон не найден.'
        Write-Info 'Подключите телефон по USB, включите на нём «Отладку по USB» (настройки разработчика)'
        Write-Info 'и разрешите отладку для этого компьютера в окне на экране телефона.'
        return 4
    }
    if ($devices.Count -gt 1) {
        Write-Fail "Подключено несколько устройств ($($devices.Count)). Оставьте одно:"
        $devices | ForEach-Object { Write-Info "  - $($_.Serial) ($($_.State))" }
        return 4
    }
    $device = $devices[0]
    if ($device.State -eq 'unauthorized') {
        Write-Fail "Телефон $($device.Serial) не разрешил отладку с этого компьютера."
        Write-Info 'Разблокируйте телефон и нажмите «Разрешить» в окне «Разрешить отладку по USB?», затем запустите скрипт снова.'
        return 4
    }
    if ($device.State -ne 'device') {
        Write-Fail "Телефон $($device.Serial) в состоянии «$($device.State)», работать с ним нельзя."
        Write-Info 'Переподключите кабель и запустите скрипт снова.'
        return 4
    }
    Write-Ok "Телефон: $($device.Serial)"
    $serial = $device.Serial

    # 4. Удаление старой версии
    Write-Step 'Поиск установленной версии'
    $res = Invoke-Adb @('-s', $serial, 'shell', 'pm', 'list', 'packages', $PackageName)
    if ($res.Code -ne 0) {
        Write-Fail 'Не удалось получить список приложений на телефоне:'
        $res.Output | ForEach-Object { Write-Info "  $_" }
        return 5
    }
    $installed = @($res.Output | Where-Object { $_.Trim() -eq "package:$PackageName" }).Count -gt 0
    if ($installed) {
        Write-Info "Найдена установленная версия ($PackageName). Удаление..."
        $res = Invoke-Adb @('-s', $serial, 'uninstall', $PackageName)
        $text = ($res.Output -join ' ')
        if ($res.Code -ne 0 -or $text -notmatch 'Success') {
            Write-Fail 'Не удалось удалить старую версию:'
            $res.Output | ForEach-Object { Write-Info "  $_" }
            return 5
        }
        Write-Ok 'Старая версия удалена.'
    } else {
        Write-Ok 'Приложение не установлено, шаг удаления пропущен.'
    }

    # 5. Установка
    Write-Step 'Установка APK'
    Write-Info 'Идёт установка, это может занять до минуты...'
    $res = Invoke-Adb @('-s', $serial, 'install', $apk.FullName)
    $text = ($res.Output -join ' ')
    if ($res.Code -ne 0 -or $text -notmatch '(^|\s)Success(\s|$)') {
        Write-Fail 'Установка не удалась:'
        $res.Output | ForEach-Object { Write-Info "  $_" }
        $hint = Get-InstallErrorHint $text
        if ($hint) { Write-Info $hint }
        return 6
    }
    Write-Ok 'APK установлен.'

    Write-Host ''
    Write-Host 'Установка завершена успешно' -ForegroundColor Green
    return 0
}

$exitCode = 1
try {
    $exitCode = Invoke-Main
} catch {
    Write-Fail "Непредвиденная ошибка: $($_.Exception.Message)"
    $exitCode = 1
}

if ($exitCode -ne 0) {
    Write-Host ''
    Write-Host "Установка не выполнена (код $exitCode)." -ForegroundColor Red
}

if (-not $NoWait) {
    Write-Host ''
    Write-Host 'Нажмите любую клавишу, чтобы закрыть окно...'
    try {
        [void]$Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
    } catch {
        [void](Read-Host 'Нажмите Enter')
    }
}

exit $exitCode
