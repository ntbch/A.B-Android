[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [int]$DirectWaitSeconds = 6,
    [int]$ModelWaitSeconds = 40,
    [string]$ModelRequest,
    [string]$PromptCacheSecondRequest = 'nhan cho Nam ve viec den tre',
    [string[]]$OnlyLabel,
    [ValidateRange(2, 10)]
    [int]$WarmRepeatCount = 2,
    [switch]$SkipInstall,
    [switch]$WarmModel,
    [switch]$PromptCacheProbe,
    [switch]$SkipRestore,
    [ValidateSet('OPENCL', 'VULKAN', 'CPU')]
    [string]$Backend,
    [ValidateSet(0, 4, 8, 16)]
    [int]$SchemaBenchmarkCount = 0,
    [int]$BackendLoadWaitSeconds = 25,
    [int]$StartupWaitSeconds = 15,
    [ValidateRange(1, 60)]
    [int]$AdbTimeoutSeconds = 20,
    [ValidateRange(1, 20)]
    [int]$UiDumpTimeoutSeconds = 5
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$packageName = 'com.ab.assistant'
$schemaBenchmarkExtra = 'com.ab.assistant.SCHEMA_BENCHMARK_COUNT'
$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$adb = 'C:\Users\bach\AppData\Local\Android\Sdk\platform-tools\adb.exe'

if (-not (Test-Path $adb)) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $adb = $adbCommand.Source }
}
if (-not (Test-Path $adb)) {
    throw 'ADB was not found.'
}

function Invoke-Adb {
    param(
        [Parameter(Position = 0)]
        [string[]]$Arguments,
        [int]$TimeoutSeconds = $AdbTimeoutSeconds
    )
    $processInfo = New-Object System.Diagnostics.ProcessStartInfo
    $processInfo.FileName = $adb
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.Arguments = ($Arguments | ForEach-Object {
        $value = ([string]$_).Replace('\\', '\\\\').Replace('"', '\\"')
        '"' + $value + '"'
    }) -join ' '

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $processInfo
    try {
        [void]$process.Start()
        # Drain both pipes while adb is running; waiting first can deadlock on
        # dumpsys/uiautomator output that fills a redirected pipe buffer.
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch { }
            [void]$process.WaitForExit(1000)
            $global:LASTEXITCODE = 124
            Write-Warning ("ADB command timed out after {0}s: adb {1}" -f $TimeoutSeconds, ($Arguments -join ' '))
            return @()
        }
        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result
        $global:LASTEXITCODE = $process.ExitCode
        if ($process.ExitCode -ne 0 -and $stderr) {
            Write-Warning ("ADB command failed ({0}): {1}" -f $process.ExitCode, $stderr.Trim())
        }
        if ([string]::IsNullOrEmpty($stdout)) { return @() }
        return @($stdout -split "`r?`n" | Where-Object { $_ -ne '' })
    } finally {
        $process.Dispose()
    }
}

function Get-Serial {
    if ($DeviceSerial) { return $DeviceSerial }
    $line = Invoke-Adb @('devices') |
        Where-Object { $_ -match '^\S+\s+device$' } |
        Select-Object -First 1
    if (-not $line) { throw 'No connected ADB device.' }
    return ($line -split '\s+')[0]
}

function Get-DeviceSnapshot {
    $mem = (Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'meminfo', $packageName)) -join "`n"
    $thermal = (Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'thermalservice')) -join "`n"
    $battery = (Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'battery')) -join "`n"
    $processId = ((Invoke-Adb @('-s', $serial, 'shell', 'pidof', $packageName)) -join '').Trim()
    $cpuInfo = (Invoke-Adb @('-s', $serial, 'shell', 'dumpsys', 'cpuinfo')) -join "`n"
    $cpuUsage = $null
    if ($processId -and $cpuInfo -match "(?m)^\s*([0-9]+(?:\.[0-9]+)?)%\s+$([regex]::Escape($processId))/$([regex]::Escape($packageName))") {
        $cpuUsage = [double]$Matches[1]
    }
    $currentTemperatures = ''
    if ($thermal -match '(?s)Current temperatures from HAL:(.*?)(?:Current cooling devices from HAL:|Temperature static thresholds from HAL:)') {
        $currentTemperatures = $Matches[1]
    }
    $temperature = {
        param([string]$Name, [int]$Type)
        $pattern = "mValue=([0-9.]+), mType=$Type, mName=$Name"
        if ($currentTemperatures -match $pattern) { return [double]$Matches[1] }
        return $null
    }
    $batteryLevel = if ($battery -match '(?m)^\s*level:\s*(\d+)') { [int]$Matches[1] } else { $null }
    $batteryTemperature = if ($battery -match '(?m)^\s*temperature:\s*(\d+)') { [math]::Round(([double]$Matches[1] / 10), 1) } else { $null }
    $thermalStatus = if ($thermal -match '(?m)^Thermal Status:\s*(\d+)') { [int]$Matches[1] } else { $null }
    $totalPss = if ($mem -match '(?m)^\s*TOTAL PSS:\s*(\d+)') { [int64]$Matches[1] } else { $null }
    $totalSwapPss = if ($mem -match 'TOTAL SWAP PSS:\s*(\d+)') { [int64]$Matches[1] } else { $null }
    [pscustomobject]@{
        timestamp = (Get-Date).ToString('o')
        pid = $processId
        totalPssKb = $totalPss
        totalSwapPssKb = $totalSwapPss
        cpuUsagePercent = $cpuUsage
        cpuTemperatureC = & $temperature 'CPU' 0
        skinTemperatureC = & $temperature 'SKIN' 3
        batteryTemperatureC = if ($batteryTemperature -ne $null) { $batteryTemperature } else { & $temperature 'BATTERY' 2 }
        batteryLevelPercent = $batteryLevel
        thermalStatus = $thermalStatus
    }
}

function Get-UiXml {
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        Invoke-Adb -Arguments @('-s', $serial, 'shell', 'uiautomator', 'dump', '/sdcard/ab-window.xml') -TimeoutSeconds $UiDumpTimeoutSeconds | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $xml = (Invoke-Adb -Arguments @('-s', $serial, 'shell', 'cat', '/sdcard/ab-window.xml') -TimeoutSeconds $UiDumpTimeoutSeconds) -join ''
            if ($LASTEXITCODE -eq 0 -and $xml -match '<hierarchy') {
                return $xml
            }
        }
        Start-Sleep -Milliseconds 500
    }
    return ''
}

function Get-CenterFromClass {
    param([string]$Xml, [string]$ClassName)
    $pattern = '<node\b[^>]*class="' + [regex]::Escape($ClassName) + '"[^>]*enabled="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*/?>'
    $match = [regex]::Match($Xml, $pattern)
    if (-not $match.Success) { return $null }
    $left = [int]$match.Groups[1].Value
    $top = [int]$match.Groups[2].Value
    $right = [int]$match.Groups[3].Value
    $bottom = [int]$match.Groups[4].Value
    return @(
        [int](($left + $right) / 2.0),
        [int](($top + $bottom) / 2.0)
    )
}

function Test-RunButtonEnabled {
    param([string]$Xml)
    foreach ($node in [regex]::Matches($Xml, '<node\b[^>]*>')) {
        $value = $node.Value
        if ($value -match 'class="android\.widget\.Button"' -and
            $value -match 'text="CHẠY TRỢ LÝ CỤC BỘ"' -and
            $value -match 'enabled="true"') {
            return $true
        }
    }
    return $false
}

function Test-ConfirmationVisible {
    param([string]$Xml)
    $enabledButtons = [regex]::Matches(
        $Xml,
        '<node\b[^>]*class="android\.widget\.Button"[^>]*enabled="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*/?>'
    )
    return $enabledButtons.Count -ge 8
}

function Wait-ForRunResult {
    param([int]$WaitSeconds)
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $observedBusy = $false
    Start-Sleep -Milliseconds 500
    while ((Get-Date) -lt $deadline) {
        $xml = Get-UiXml
        if (Test-ConfirmationVisible $xml) {
            return $true
        }
        $runEnabled = Test-RunButtonEnabled $xml
        if (-not $runEnabled) {
            $observedBusy = $true
        } elseif ($observedBusy) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Set-PromptText {
    param([string]$Text)
    $edit = $null
    for ($attempt = 0; $attempt -lt $StartupWaitSeconds; $attempt++) {
        $xml = Get-UiXml
        $edit = Get-CenterFromClass $xml 'android.widget.EditText'
        if ($edit) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $edit) { throw 'Prompt EditText was not found in the UI hierarchy.' }
    Invoke-Adb @('-s', $serial, 'shell', 'input', 'tap', $edit[0], $edit[1]) | Out-Null
    $clearKeyEvents = @('123') + (1..128 | ForEach-Object { '67' })
    Invoke-Adb (@('-s', $serial, 'shell', 'input', 'keyevent') + $clearKeyEvents) | Out-Null
    $encoded = $Text -replace ' ', '%s'
    Invoke-Adb @('-s', $serial, 'shell', 'input', 'text', $encoded) | Out-Null
    # Hide the soft keyboard before tapping controls below the EditText.
    # Otherwise the confirmation buttons' UIAutomator bounds can overlap the
    # IME even though the app hierarchy still reports their content bounds.
    Invoke-Adb @('-s', $serial, 'shell', 'input', 'keyevent', '4') | Out-Null
}

function Cancel-PendingConfirmation {
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        $xml = Get-UiXml
        $buttons = [regex]::Matches(
            $xml,
            '<node\b[^>]*class="android\.widget\.Button"[^>]*enabled="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*/?>'
        )
        # Confirmation controls are appended after the six stable
        # application buttons. Tap only the final button when that pair is
        # present; this keeps the probe from committing outbound actions.
        if ($buttons.Count -ge 8) {
            $match = $buttons[$buttons.Count - 1]
            $left = [int]$match.Groups[1].Value
            $top = [int]$match.Groups[2].Value
            $right = [int]$match.Groups[3].Value
            $bottom = [int]$match.Groups[4].Value
            Invoke-Adb @('-s', $serial, 'shell', 'input', 'tap', [int](($left + $right) / 2.0), [int](($top + $bottom) / 2.0)) | Out-Null
            Start-Sleep -Seconds 1
            $remaining = [regex]::Matches(
                (Get-UiXml),
                '<node\b[^>]*class="android\.widget\.Button"[^>]*enabled="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*/?>'
            )
            if ($remaining.Count -lt 8) { return $true }
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Assert-ConfirmationCancelled {
    param([pscustomobject]$Result, [string]$Label)
    if ($Result.statusText -notmatch 'SMS|Gọi|call|message') { return }
    if (-not (Cancel-PendingConfirmation)) {
        throw "${Label}: outbound confirmation remained active after the cancellation attempt."
    }
    Write-Host "SAFETY ${Label}: outbound confirmation canceled; no commit."
}

function Assert-ValidModelConfirmation {
    param([pscustomobject]$Result, [string]$Label)
    # Use the stable ASCII action marker; PowerShell 5 can decode the
    # Vietnamese confirmation label differently when the script has no BOM.
    if ($Result.statusText -notmatch 'SMS') {
        throw "${Label}: model-route did not produce a valid outbound confirmation."
    }
}

function Launch-Application {
    param([switch]$DefaultBackend)
    Invoke-Adb @('-s', $serial, 'shell', 'input', 'keyevent', '4') | Out-Null
    Invoke-Adb @('-s', $serial, 'shell', 'am', 'force-stop', $packageName) | Out-Null
    if ($SchemaBenchmarkCount -gt 0) {
        Invoke-Adb @('-s', $serial, 'shell', 'am', 'start', '-n', "$packageName/.MainActivity", '--ei', $schemaBenchmarkExtra, $SchemaBenchmarkCount) | Out-Null
        Start-Sleep -Seconds 3
    } elseif ($Backend -and -not $DefaultBackend) {
        Invoke-Adb @('-s', $serial, 'shell', 'am', 'start', '-n', "$packageName/.MainActivity", '--es', 'com.ab.assistant.BACKEND_BENCHMARK', $Backend) | Out-Null
        Start-Sleep -Seconds $BackendLoadWaitSeconds
    } else {
        Invoke-Adb @('-s', $serial, 'shell', 'monkey', '-p', $packageName, '1') | Out-Null
        Start-Sleep -Seconds 3
    }
}

function Run-Request {
    param(
        [string]$Label,
        [string]$Request,
        [int]$WaitSeconds,
        [switch]$ReuseProcess,
        [switch]$DefaultBackend
    )
    Write-Host "CASE ${Label}: $Request"
    Invoke-Adb @('-s', $serial, 'logcat', '-c') | Out-Null
    if (-not $ReuseProcess) {
        Launch-Application -DefaultBackend:$DefaultBackend
    }
    Set-PromptText $Request
    $xml = Get-UiXml
    $runButton = Get-CenterFromClass $xml 'android.widget.Button'
    if (-not $runButton) { throw "Run button was not found for case $Label." }
    $started = Get-Date
    Invoke-Adb @('-s', $serial, 'shell', 'input', 'tap', $runButton[0], $runButton[1]) | Out-Null
    [void](Wait-ForRunResult $WaitSeconds)
    $elapsed = ((Get-Date) - $started).TotalMilliseconds
    $metrics = Invoke-Adb @('-s', $serial, 'logcat', '-d', '-s', 'MnnModelRuntime:I', '*:S')
    $ui = Get-UiXml
    $textNodes = [regex]::Matches($ui, '<node\b[^>]*>') | ForEach-Object {
        $node = $_.Value
        if ($node -match 'class="android\.widget\.TextView"' -and $node -match 'text="([^"]*)"') {
            $textValue = $Matches[1]
            $packageValue = if ($node -match 'package="([^"]*)"') { $Matches[1] } else { '' }
            [pscustomobject]@{
                package = $packageValue
                text = $textValue
            }
        } elseif ($node -match 'class="android\.widget\.TextView"' -and $node -match "text='([^']*)'") {
            $textValue = $Matches[1]
            $packageValue = if ($node -match 'package="([^"]*)"') { $Matches[1] } else { '' }
            [pscustomobject]@{
                package = $packageValue
                text = $textValue
            }
        }
    }
    $texts = @($textNodes | Where-Object {
        $_.text -and $_.text.Trim() -and $_.text -notmatch '^Inference metrics'
    })
    $appTexts = @($texts | Where-Object { $_.package -eq $packageName })
    $statusText = if ($appTexts.Count -gt 0) { $appTexts[-1].text } elseif ($texts.Count -gt 0) { $texts[-1].text } else { '' }
    [pscustomobject]@{
        label = $Label
        request = $Request
        elapsedMs = [math]::Round($elapsed)
        statusText = $statusText
        metrics = ($metrics -join "`n")
    }
}

$serial = Get-Serial
if (-not $SkipInstall) {
    if (-not (Test-Path $apk)) { throw "Debug APK not found: $apk" }
    Invoke-Adb @('-s', $serial, 'install', '-r', $apk) | Out-Host
}

Write-Host "DEVICE $serial"
Write-Host (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.product.model'))
Write-Host (Invoke-Adb @('-s', $serial, 'shell', 'getprop', 'ro.build.version.sdk'))
Write-Host 'SNAPSHOT BEFORE'
(Get-DeviceSnapshot | ConvertTo-Json -Compress)

$cases = @(
    @{ Label = 'flashlight-direct'; Request = 'bat den pin'; Wait = $DirectWaitSeconds },
    @{ Label = 'open-app-direct'; Request = 'mo YouTube'; Wait = $DirectWaitSeconds },
    @{ Label = 'volume-direct'; Request = 'am luong 30 phan tram'; Wait = $DirectWaitSeconds },
    @{ Label = 'timer-direct'; Request = 'dat hen gio 5 phut'; Wait = $DirectWaitSeconds },
    @{ Label = 'web-route'; Request = 'search thoi tiet Ha Noi hom nay'; Wait = $DirectWaitSeconds },
    @{ Label = 'message-direct-natural'; Request = 'nhan Nam la 10 phut nua toi'; Wait = $DirectWaitSeconds },
    @{ Label = 'message-model-route'; Request = 'nhan cho Nam ve viec den muon'; Wait = $ModelWaitSeconds }
)
if ($SchemaBenchmarkCount -gt 0) {
    $cases = @(@{
        Label = "schema-$SchemaBenchmarkCount"
        Request = if ($ModelRequest) { $ModelRequest } else { 'do prompt benchmark' }
        Wait = $ModelWaitSeconds
    })
}
if ($OnlyLabel) {
    $cases = @($cases | Where-Object { $OnlyLabel -contains $_.Label })
}
if ($ModelRequest) {
    $cases = @($cases | ForEach-Object {
        if ($_.Label -eq 'message-model-route') {
            $_.Request = $ModelRequest
        }
        $_
    })
}

try {
    if ($WarmModel -or $PromptCacheProbe) {
        $cases = @($cases | Where-Object { $_.Label -eq 'message-model-route' })
        if ($cases.Count -ne 1) { throw 'WarmModel or PromptCacheProbe requires the message-model-route corpus case.' }
        Launch-Application
        $case = $cases[0]
    }

    if ($PromptCacheProbe) {
        $first = Run-Request 'message-model-cache-1' $case.Request $case.Wait -ReuseProcess
        Assert-ConfirmationCancelled $first 'message-model-cache-1'
        $second = Run-Request 'message-model-cache-2' $PromptCacheSecondRequest $case.Wait -ReuseProcess
        Assert-ConfirmationCancelled $second 'message-model-cache-2'
        $results = @($first, $second)
    } elseif ($WarmModel) {
        $results = @()
        for ($iteration = 1; $iteration -le $WarmRepeatCount; $iteration++) {
            $label = "message-model-warm-$iteration"
            $result = Run-Request $label $case.Request $case.Wait -ReuseProcess
            Assert-ValidModelConfirmation $result $label
            Assert-ConfirmationCancelled $result $label
            $results += $result
        }
    } else {
        $results = foreach ($case in $cases) {
            $result = Run-Request $case.Label $case.Request $case.Wait
            if ($case.Label -eq 'message-model-route') {
                Assert-ValidModelConfirmation $result $case.Label
            }
            Assert-ConfirmationCancelled $result $case.Label
            $result
        }
    }

    Write-Host 'RESULTS'
    $results | Format-List
}
finally {
    # Restore the flashlight state if the direct corpus case actually turned it on.
    if (-not $SkipRestore) {
        try {
            Run-Request 'restore-flashlight-off' 'tat den pin' $DirectWaitSeconds -DefaultBackend | Format-List
        } catch {
            Write-Warning "Flashlight restore failed: $($_.Exception.Message)"
        }
    }
}

Write-Host 'SNAPSHOT AFTER'
(Get-DeviceSnapshot | ConvertTo-Json -Compress)
