[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [switch]$SkipBuild,
    [switch]$SkipPrompts,
    [switch]$SkipInstall,
    [switch]$RunBenchmark
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'com.ab.assistant'
$failures = 0
$pending = 0
$gateResults = New-Object System.Collections.Generic.List[object]

function Invoke-ExternalChecked {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$Description
    )
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
}

function Ask-Acceptance {
    param([string]$Description)
    $answer = (Read-Host "$Description [pass/fail/skip]").ToLowerInvariant()
    if ($answer -eq 'fail') { $script:failures++ }
    if ($answer -eq 'skip') { $script:pending++ }
}

function Register-Gate {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Evidence
    )
    $script:gateResults.Add([pscustomobject]@{
        Gate = $Name
        Status = if ($Passed) { 'PASS' } else { 'PENDING' }
        Evidence = $Evidence
    })
    if (-not $Passed) { $script:pending++ }
}

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        Invoke-ExternalChecked '.\gradlew.bat' @('--no-daemon', '--console=plain', 'test', 'assembleDebug') 'Gradle acceptance build'
    }

    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adbCommand) {
        $sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
        if (Test-Path $sdkAdb) { $adbCommand = Get-Item $sdkAdb }
    }

    if (-not $adbCommand) {
        Write-Warning 'ADB was not found; POCO/device gates are pending.'
        $pending++
    } else {
        $adbPath = if ($adbCommand -is [System.IO.FileInfo]) { $adbCommand.FullName } else { $adbCommand.Source }
        $deviceLines = & $adbPath devices
        if (-not $DeviceSerial) {
            $DeviceSerial = $deviceLines |
                Where-Object { $_ -match '^\S+\s+device$' } |
                Select-Object -First 1 |
                ForEach-Object { ($_ -split '\s+')[0] }
        }

        if (-not $DeviceSerial) {
            Write-Warning 'No connected ADB device; POCO install, smoke, and benchmark gates are pending.'
            $pending++
        } else {
            $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
            if (-not $SkipInstall) {
                Invoke-ExternalChecked $adbPath @('-s', $DeviceSerial, 'install', '-r', $apk) 'ADB APK install'
            }
            # Force-stopping an app that owns an enabled AccessibilityService makes Android/MIUI
            # revoke that service.  Keep the user's enabled state intact while performing the
            # launch smoke check below.
            Invoke-ExternalChecked $adbPath @('-s', $DeviceSerial, 'shell', 'monkey', '-p', $packageName, '1') 'ADB app launch'
            Write-Host 'Installed and launched A.B on the selected device.'
            Write-Host 'Accessibility setting:'
            & $adbPath -s $DeviceSerial shell settings get secure enabled_accessibility_services
            Write-Host 'Selected voice interaction service:'
            $voiceInteractionService = ((& $adbPath -s $DeviceSerial shell settings get secure voice_interaction_service) -join '').Trim()
            Write-Host $voiceInteractionService
            $accessibilityServices = ((& $adbPath -s $DeviceSerial shell settings get secure enabled_accessibility_services) -join '').Trim()
            $expectedAccessibility = "$packageName/$packageName.accessibility.AbAccessibilityService"
            $expectedAssistant = "$packageName/$packageName.voice.AbVoiceInteractionService"
            $accessibilityReady = @($accessibilityServices -split ':' | Where-Object { $_ -eq $expectedAccessibility }).Count -gt 0
            $assistantReady = $voiceInteractionService -eq $expectedAssistant
            Register-Gate 'Accessibility service connected' $accessibilityReady $(if ($accessibilityReady) { $accessibilityServices } else { "enabled_accessibility_services=$accessibilityServices" })
            Register-Gate 'A.B selected as system assistant' $assistantReady $(if ($assistantReady) { $voiceInteractionService } else { "voice_interaction_service=$voiceInteractionService" })
            $voiceInteractionDump = (& $adbPath -s $DeviceSerial shell dumpsys voiceinteraction) -join "`n"
            $supportsAssist = $voiceInteractionDump -match [regex]::Escape('Supports assist=true')
            Register-Gate 'A.B system-assist entrypoint enabled' $supportsAssist $(if ($supportsAssist) { 'VoiceInteractionService metadata supportsAssist=true' } else { 'VoiceInteractionService does not advertise supportsAssist=true' })
            $packageDump = (& $adbPath -s $DeviceSerial shell dumpsys package $packageName) -join "`n"
            $expectedSessionAction = 'android.service.voice.VoiceInteractionSessionService'
            $sessionRegistered = $packageDump -match [regex]::Escape('AbVoiceInteractionSessionService') -and
                $packageDump -match [regex]::Escape($expectedSessionAction)
            Register-Gate 'Voice session service registered' $sessionRegistered $(if ($sessionRegistered) { $expectedSessionAction } else { 'AbVoiceInteractionSessionService action not resolved in package dump' })
            Write-Host 'Recent inference metrics:'
            & $adbPath -s $DeviceSerial logcat -d -s MnnModelRuntime:I '*:S'
            if ($RunBenchmark) {
                $benchmarkScript = Join-Path $PSScriptRoot 'benchmark-poco.ps1'
                $benchmarkArguments = @('-DeviceSerial', $DeviceSerial)
                if ($SkipInstall) { $benchmarkArguments += '-SkipInstall' }
                Invoke-ExternalChecked $benchmarkScript $benchmarkArguments 'POCO benchmark'
            }
        }
    }

    if (-not $SkipPrompts) {
        Write-Host 'Deterministic device acceptance'
        Ask-Acceptance 'Flashlight on/off and restore flashlight off'
        Ask-Acceptance 'Open app, volume/media, timer/alarm, battery/device state'
        Ask-Acceptance 'Notification lookup, contact resolution, bounded web search'
        Ask-Acceptance 'Accessibility semantic snapshot and verified postcondition'
        Ask-Acceptance 'PTT voice: Vietnamese transcript -> router/tool -> spoken result'
        Ask-Acceptance 'SMS/call: preview, confirmation/deny, immutable payload'
        Ask-Acceptance 'Repeated successful trajectory becomes DRAFT, replay then explicit approval'
        Ask-Acceptance 'Cancellation and stuck/recovery path return truthful final state'
        Ask-Acceptance 'Warm/cold model metrics and Tier-0 latency recorded from POCO'
        Ask-Acceptance 'A.B selected as the system VoiceInteractionService assistant'
        Ask-Acceptance 'Wake word with screen off, spoken result, return to low-power idle'
    } else {
        Write-Warning 'Manual acceptance prompts were skipped; release acceptance remains pending.'
        $pending++
        $gateResults.Add([pscustomobject]@{
            Gate = 'Manual acceptance corpus'
            Status = 'PENDING'
            Evidence = 'Prompts skipped; no manual evidence supplied.'
        })
    }
} catch {
    Write-Error $_
    $failures++
} finally {
    Pop-Location
}

if ($gateResults.Count -gt 0) {
    Write-Host 'GATE EVIDENCE'
    $gateResults | Format-Table -AutoSize
}

if ($failures -gt 0) {
    Write-Error "Upgrade v2 acceptance failed: $failures failure(s), $pending pending gate(s)."
    exit 1
}
if ($pending -gt 0) {
    Write-Warning "Upgrade v2 acceptance is pending: $pending gate(s) need device/manual evidence."
    exit 2
}
Write-Host 'Upgrade v2 acceptance completed.'
