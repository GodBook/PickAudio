param(
    [Parameter(Mandatory)][string]$Serial,
    [string]$Dataset = 'empty library',
    [int]$ColdSamples = 10,
    [int]$WarmSamples = 20
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^[A-Za-z0-9._:-]+$') { throw 'An explicit adb device serial is required.' }
if ($ColdSamples -lt 1 -or $WarmSamples -lt 1) { throw 'Sample counts must be positive.' }
$package = 'com.pickaudio'
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Arguments)
    $result = & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'The explicit-device adb command failed.' }
    $result
}
if (!(Invoke-Adb @('shell','pm','path',$package))) { throw 'PickAudio is not installed on the specified device.' }
function Launch-Sample {
    $result = Invoke-Adb @('shell','am','start','-W','-n',"$package/.MainActivity")
    $match = [regex]::Match(($result -join "`n"), '(?m)^TotalTime:\s*(\d+)')
    if (!$match.Success) { throw 'Activity TotalTime was not reported.' }
    [int]$match.Groups[1].Value
}
function Summary([int[]]$Samples) {
    $sorted = @($Samples | Sort-Object)
    [ordered]@{ samplesMs=$Samples; p50Ms=$sorted[[math]::Ceiling($sorted.Count * 0.50)-1]; p95Ms=$sorted[[math]::Ceiling($sorted.Count * 0.95)-1] }
}
$cold = @()
for ($i=0; $i -lt $ColdSamples; $i++) {
    Invoke-Adb @('shell','am','force-stop',$package) | Out-Null
    $cold += Launch-Sample
    Start-Sleep -Milliseconds 300
}
$warm = @()
for ($i=0; $i -lt $WarmSamples; $i++) {
    Invoke-Adb @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
    Start-Sleep -Milliseconds 300
    $warm += Launch-Sample
}
[ordered]@{
    device=$Serial
    model=(Invoke-Adb @('shell','getprop','ro.product.model') | Out-String).Trim()
    sdk=(Invoke-Adb @('shell','getprop','ro.build.version.sdk') | Out-String).Trim()
    dataset=$Dataset
    definition='am start -W TotalTime; Activity display time, not full content readiness or audio output'
    cold=(Summary $cold)
    warm=(Summary $warm)
    package=(Invoke-Adb @('shell','dumpsys','package',$package) | Select-String 'versionCode=|versionName=') -join "`n"
    memory=(Invoke-Adb @('shell','dumpsys','meminfo',$package)) -join "`n"
} | ConvertTo-Json -Depth 5
