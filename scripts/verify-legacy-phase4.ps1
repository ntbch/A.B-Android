[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [switch]$SkipBuild,
    [switch]$SkipPrompts,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Continue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$buildExitCode = 0
$smokeFailures = 0

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat test
        $buildExitCode = $LASTEXITCODE
        if ($buildExitCode -ne 0) {
            Write-Warning "Gradle verification failed with exit code $buildExitCode."
        }
    }

    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $SkipInstall -and $DeviceSerial -and $adb -and (Test-Path $apk)) {
        & $adb.Source -s $DeviceSerial install -r $apk
        if ($LASTEXITCODE -ne 0) {
            Write-Warning 'ADB install failed.'
            $smokeFailures++
        }
    } elseif (-not $SkipInstall -and $DeviceSerial) {
        Write-Warning 'Skipping install: adb, APK, or a successful build is missing.'
    }

    if (-not $SkipPrompts) {
        $checks = @(
            'Launch A.B and confirm JNI/MNN status is shown',
            'Run: flashlight on/off (Camera permission path)',
            'Run: open an installed app',
            'Run: set volume to 30 percent and media play/pause',
            'Run: set a 5 minute timer and set an alarm',
            'Enable notification access, then read notifications',
            'Grant contacts access, then find a contact',
            'Run a web search with network available',
            'Prepare/send an SMS and verify confirmation plus recipient/message',
            'Dial a contact and verify the dialer opens without auto-calling'
        )
        foreach ($check in $checks) {
            $answer = (Read-Host "$check [pass/fail/skip]").ToLowerInvariant()
            if ($answer -eq 'fail') { $smokeFailures++ }
        }
    }
} finally {
    Pop-Location
}

if ($buildExitCode -ne 0 -or $smokeFailures -ne 0) {
    exit 1
}
Write-Host 'Legacy Phase-4 verification completed.'
