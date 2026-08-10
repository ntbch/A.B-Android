[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string[]]$Backend = @('OPENCL', 'VULKAN', 'CPU'),
    [int]$WaitSeconds = 10,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'com.ab.assistant'
$activityName = "$packageName/.MainActivity"
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$adb = 'C:\Users\bach\AppData\Local\Android\Sdk\platform-tools\adb.exe'

if (-not (Test-Path $adb)) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $adb = $adbCommand.Source }
}
if (-not (Test-Path $adb)) { throw 'ADB was not found.' }

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
}

function Get-Serial {
    if ($DeviceSerial) { return $DeviceSerial }
    $line = Invoke-Adb @('devices') |
        Where-Object { $_ -match '^\S+\s+device$' } |
        Select-Object -First 1
    if (-not $line) { throw 'No connected ADB device.' }
    return ($line -split '\s+')[0]
}

function Get-UiXml {
    Invoke-Adb @('-s', $serial, 'shell', 'uiautomator', 'dump', '/sdcard/ab-window.xml') | Out-Null
    return (Invoke-Adb @('-s', $serial, 'shell', 'cat', '/sdcard/ab-window.xml')) -join ''
}

function Get-MetricsText {
    param([string]$Xml)
    $nodes = [regex]::Matches($Xml, '<node\b[^>]*>') | ForEach-Object {
        $node = $_.Value
        if ($node -match 'package="com\.ab\.assistant"' -and
            $node -match 'class="android\.widget\.TextView"' -and
            $node -match 'text="([^"]*)"') {
            $Matches[1]
        }
    }
    return @($nodes | Where-Object { $_ -match 'Inference metrics' } | Select-Object -Last 1)
}

$serial = Get-Serial
if (-not $SkipInstall) {
    if (-not (Test-Path $apk)) { throw "Debug APK not found: $apk" }
    Invoke-Adb @('-s', $serial, 'install', '-r', $apk) | Out-Host
}

Write-Host "DEVICE $serial"
Write-Host (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.product.model'))
Write-Host (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.build.version.sdk'))

$results = foreach ($requested in $Backend) {
    $normalized = $requested.ToUpperInvariant()
    if ($normalized -notin @('OPENCL', 'VULKAN', 'CPU')) {
        throw "Unsupported benchmark backend: $requested"
    }
    Write-Host "BACKEND $normalized"
    Invoke-Adb @('-s', $serial, 'logcat', '-c') | Out-Null
    Invoke-Adb @('-s', $serial, 'shell', 'am', 'force-stop', $packageName) | Out-Null
    $started = Get-Date
    Invoke-Adb @('-s', $serial, 'shell', 'am', 'start', '-n', $activityName, '--es', 'com.ab.assistant.BACKEND_BENCHMARK', $normalized) | Out-Null
    Start-Sleep -Seconds $WaitSeconds
    $elapsed = [math]::Round(((Get-Date) - $started).TotalMilliseconds)
    $metrics = Invoke-Adb @('-s', $serial, 'logcat', '-d', '-s', 'MnnModelRuntime:I', '*:S')
    $ui = Get-UiXml
    [pscustomobject]@{
        requestedBackend = $normalized
        elapsedMs = $elapsed
        metricsText = (Get-MetricsText $ui) -join "`n"
        logcat = ($metrics -join "`n")
    }
}

Write-Host 'RESULTS'
$results | Format-List
