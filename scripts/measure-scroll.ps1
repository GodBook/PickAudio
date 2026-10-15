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
$width = [int]$match.Groups[1].Value
$height = [int]$match.Groups[2].Value
Device shell input keyevent KEYCODE_WAKEUP | Out-Null
Device @('shell','am','start','-W','-n','com.pickaudio/.MainActivity') | Out-Null
Start-Sleep -Seconds 2
Device shell dumpsys gfxinfo com.pickaudio reset | Out-Null
for ($i=0; $i -lt $Swipes; $i++) {
    Device shell input swipe ([int]($width/2)) ([int]($height*0.76)) ([int]($width/2)) ([int]($height*0.32)) 300 | Out-Null
    Start-Sleep -Milliseconds 100
}
Device shell dumpsys gfxinfo com.pickaudio framestats
