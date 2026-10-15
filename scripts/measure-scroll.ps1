param([Parameter(Mandatory)][string]$Serial, [int]$Swipes = 20)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^[A-Za-z0-9._:-]+$' -or $Swipes -lt 1) { throw 'Provide an explicit adb serial and a positive swipe count.' }
function Device {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Arguments)
    $result = & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'The explicit-device adb command failed.' }
    $result
}
$size = Device shell wm size
$match = [regex]::Match(($size -join "`n"), '(\d+)x(\d+)')
if (!$match.Success) { throw 'Cannot read device screen dimensions.' }
$height = [int]$match.Groups[2].Value
Device shell input keyevent KEYCODE_WAKEUP | Out-Null
Device @('shell','am','start','-W','-n','com.pickaudio/.MainActivity') | Out-Null
Start-Sleep -Seconds 2
$dumpPath = '/sdcard/pickaudio-scroll-metrics.xml'
try {
    Device shell uiautomator dump $dumpPath | Out-Null
    [xml]$ui = (Device shell cat $dumpPath) -join "`n"
    if (!$ui.SelectSingleNode('//node[@package="com.pickaudio"]')) { throw 'PickAudio must be visible and the specified device unlocked.' }
    $areas = @($ui.SelectNodes('//node[@scrollable="true"]') | ForEach-Object {
        $bounds = @([regex]::Matches($_.bounds, '\d+') | ForEach-Object { [int]$_.Value })
        if ($bounds.Count -eq 4 -and ($bounds[3]-$bounds[1]) -gt $height/4) {
            [pscustomobject]@{left=$bounds[0]; top=$bounds[1]; right=$bounds[2]; bottom=$bounds[3];
                area=($bounds[2]-$bounds[0])*($bounds[3]-$bounds[1])}
        }
    })
    $scroll = $areas | Sort-Object area -Descending | Select-Object -First 1
    if (!$scroll) { throw 'No visible vertical scrolling area; open the populated library before measuring.' }
} finally { Device shell rm -f $dumpPath | Out-Null }
$x = [int](($scroll.left+$scroll.right)/2)
$startY = [int]($scroll.top+($scroll.bottom-$scroll.top)*0.8)
$endY = [int]($scroll.top+($scroll.bottom-$scroll.top)*0.2)
Write-Output "Observed list gesture: ($x,$startY) -> ($x,$endY), $Swipes swipes."
Device shell dumpsys gfxinfo com.pickaudio reset | Out-Null
for ($i=0; $i -lt $Swipes; $i++) {
    Device shell input swipe $x $startY $x $endY 300 | Out-Null
    Start-Sleep -Milliseconds 100
}
$result = Device shell dumpsys gfxinfo com.pickaudio framestats
$frames = [regex]::Match(($result -join "`n"), 'Total frames rendered:\s*(\d+)')
if (!$frames.Success -or [int]$frames.Groups[1].Value -le 0) { throw 'No rendered frames collected; this is not a valid scrolling sample.' }
$result
