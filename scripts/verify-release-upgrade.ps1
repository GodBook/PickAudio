param(
    [Parameter(Mandatory)][ValidateSet('seed', 'verify', 'cleanup')][string]$Mode,
    [string]$Serial = 'emulator-5560',
    [string]$SdkPath = 'D:/dev/android-sdk',
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
$deviceRoot = '/data/user/0/com.pickaudio'
$database = "$deviceRoot/databases/pickaudio.db"
$wave = "$deviceRoot/files/release-upgrade.wav"
$partial = "$deviceRoot/files/download_parts/temp_release_upgrade_download.part"
$preferences = "$deviceRoot/files/datastore/pickaudio_settings.preferences_pb"
if (!$OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'app/build/release-1.3.0' }
$output = [System.IO.Path]::GetFullPath($OutputDirectory)
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'app/build'))
if (!$output.StartsWith($buildRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Verification evidence must remain inside app/build.'
}
New-Item -ItemType Directory -Path $output -Force | Out-Null

function Invoke-Device {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Arguments)
    $result = (& $adb -s $Serial @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Dedicated-device command failed: $result" }
    return $result
}
function Quote-Shell([string]$Value) { return "'" + $Value.Replace("'", "'" + '"' + "'" + '"' + "'") + "'" }
function Read-Sql([string]$Statement) { Invoke-Device shell ('sqlite3 ' + (Quote-Shell $database) + ' ' + (Quote-Shell $Statement)) }
function Require-Value([string]$Actual, [string]$Expected, [string]$Label) {
    if ($Actual -ne $Expected) { throw "$Label expected '$Expected', got '$Actual'." }
}
function Preferences-Hash {
    $value = Invoke-Device shell ("if [ -f '$preferences' ]; then sha256sum '$preferences'; fi")
    if (!$value) { return $null }
    return ($value -split '\s+')[0]
}

Require-Value $Serial 'emulator-5560' 'Dedicated serial'
Require-Value (Invoke-Device shell getprop ro.boot.qemu.avd_name) 'PickAudio_Optimization_API36' 'Dedicated AVD'
Require-Value (Invoke-Device shell getprop ro.build.type) 'userdebug' 'Disposable emulator build'
if ((Invoke-Device shell id) -notmatch '^uid=0\(root\)') { throw 'Start adbd as root on the dedicated emulator before this check.' }
Invoke-Device shell am force-stop com.pickaudio | Out-Null
$package = Invoke-Device shell dumpsys package com.pickaudio

if ($Mode -eq 'seed') {
    if ($package -notmatch 'versionCode=9\b' -or $package -notmatch 'versionName=1\.2\.0\b') { throw 'Seed requires the original v1.2.0 installation.' }
    Require-Value (Read-Sql 'PRAGMA user_version;') '2' 'Original Room version'
    Require-Value (Read-Sql "SELECT COUNT(*) FROM tracks WHERE id LIKE 'release_upgrade_%';") '0' 'Fresh fixture IDs'
    $sourceWave = Join-Path $projectRoot 'app/build/ux-verification/upgrade.wav'
    if (!(Test-Path -LiteralPath $sourceWave)) { throw 'The retained 20-second WAV fixture is required.' }
    Invoke-Device push $sourceWave $wave | Out-Null
    $deviceUid = Invoke-Device shell stat -c %u $deviceRoot
    if ($deviceUid -notmatch '^\d+$' -or [int]$deviceUid -lt 10000) { throw 'Unexpected target app UID.' }
    Invoke-Device shell "mkdir -p '$deviceRoot/files/download_parts'" | Out-Null
    Invoke-Device shell "dd if=/dev/zero of='$partial' bs=1024 count=1" | Out-Null
    Invoke-Device shell "chown ${deviceUid}:${deviceUid} '$wave' '$partial' '$deviceRoot/files/download_parts'" | Out-Null
    $waveSize = (Get-Item -LiteralPath $sourceWave).Length
    $preferencesHash = Preferences-Hash
    if ($preferencesHash -notmatch '^[a-f0-9]{64}$') { throw 'Persist a baseline setting before seeding the upgrade fixture.' }
    $scriptContent = 'lx.send(lx.EVENT_NAMES.inited, {status:true,sources:{wy:{name:"升级源",actions:["musicUrl"],qualitys:["128k"]}}});'
    $scriptHash = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData([System.Text.Encoding]::UTF8.GetBytes($scriptContent))).ToLowerInvariant()
    $statement = @"
PRAGMA foreign_keys=ON;
BEGIN;
INSERT INTO tracks(id,title,artist,album,durationMs,coverUri,trackNumber,createdAt) VALUES('release_upgrade_track','正式覆盖升级验证','拾音验证','1.3升级',20000,NULL,1,123);
INSERT INTO local_assets(trackId,uri,sourceType,fileSize,mimeType,format,isAvailable,fileHash,folderName) VALUES('release_upgrade_track','file://$wave','SAF_FILE',$waveSize,'audio/wav','wav',1,NULL,'升级文件夹');
INSERT INTO online_refs(trackId,platform,platformSongId,platformMetadataJson) VALUES('release_upgrade_track','wy','release_upgrade_no_fetch','{}');
INSERT INTO playlists(id,name,coverUri,sortOrder,isSystem,createdAt,updatedAt) VALUES('release_upgrade_playlist','正式升级保留歌单',NULL,1,0,123,123);
INSERT INTO playlist_tracks(playlistId,trackId,sortOrder,addedAt) VALUES('release_upgrade_playlist','release_upgrade_track',0,123);
INSERT INTO favorites(trackId,addedAt) VALUES('release_upgrade_track',123);
INSERT INTO queue_entries(id,trackId,queueOrder) VALUES(701,'release_upgrade_track',0),(702,'release_upgrade_track',1);
INSERT OR REPLACE INTO playback_snapshot(id,currentTrackId,progressMs,playbackMode,shuffleOrderJson,shuffleHistoryJson,updatedAt) VALUES(1,'release_upgrade_track',3500,'LIST_LOOP','[1]','[0]',123);
INSERT INTO lyric_records(trackId,sourceType,content,offsetMs,updatedAt) VALUES('release_upgrade_track','MANUAL_LRC','[00:00.00]升级歌词保留
[00:05.00]每首歌的校准仍为300毫秒',300,123);
INSERT INTO source_scripts(id,name,version,author,description,homepage,scriptHash,scriptContent,capabilitiesJson,isEnabled,createdAt,updatedAt) VALUES('release_upgrade_source','升级保留音源','1','验证','','','$scriptHash','$scriptContent','{}',1,123,123);
UPDATE platform_source_selection SET sourceId='release_upgrade_source' WHERE platform='wy';
INSERT INTO download_tasks(id,trackId,title,artist,album,coverUri,platform,platformSongId,targetQuality,status,downloadedBytes,totalBytes,tempFilePath,targetUri,errorMessage,createdAt,updatedAt,actualQuality,bytesPerSecond,etaSeconds,resourceEtag,durationMs) VALUES('release_upgrade_download','release_upgrade_track','升级前暂停下载','拾音验证','1.3升级',NULL,'wy','release_upgrade_no_fetch','320k','PAUSED',1024,4096,'$partial',NULL,NULL,123,123,NULL,0,NULL,NULL,20000);
COMMIT;
"@
    Read-Sql $statement | Out-Null
    Require-Value (Read-Sql 'PRAGMA integrity_check;') 'ok' 'Baseline database integrity'
    $seed = [ordered]@{ from='1.2.0'; code=9; schema=2; uid=$deviceUid; preferencesSha256=$preferencesHash; waveBytes=$waveSize; partialBytes=1024 }
    $seed | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'upgrade-seed.json') -Encoding utf8
    $seed | ConvertTo-Json
}
elseif ($Mode -eq 'verify') {
    if ($package -notmatch 'versionCode=10\b' -or $package -notmatch 'versionName=1\.3\.0\b') { throw 'Verify requires the official v1.3.0 installation.' }
    $seed = Get-Content -Raw -LiteralPath (Join-Path $output 'upgrade-seed.json') | ConvertFrom-Json
    Require-Value (Read-Sql 'PRAGMA user_version;') '4' 'Migrated Room version'
    Require-Value (Invoke-Device shell stat -c %u $deviceRoot) $seed.uid 'UID retained by cover installation'
    Require-Value (Read-Sql 'PRAGMA integrity_check;') 'ok' 'Migrated database integrity'
    Require-Value (Read-Sql 'PRAGMA foreign_key_check;') '' 'Migrated foreign keys'
    $checks = [ordered]@{
        tracks="SELECT COUNT(*) FROM tracks WHERE id='release_upgrade_track' AND title='正式覆盖升级验证';"
        assets="SELECT COUNT(*) FROM local_assets WHERE trackId='release_upgrade_track' AND folderName='升级文件夹' AND uri='file://$wave' AND isAvailable=1;"
        onlineReferences="SELECT COUNT(*) FROM online_refs WHERE trackId='release_upgrade_track' AND platformSongId='release_upgrade_no_fetch';"
        playlists="SELECT COUNT(*) FROM playlists WHERE id='release_upgrade_playlist';"
        members="SELECT COUNT(*) FROM playlist_tracks WHERE playlistId='release_upgrade_playlist' AND trackId='release_upgrade_track' AND sortOrder=0;"
        favorites="SELECT COUNT(*) FROM favorites WHERE trackId='release_upgrade_track' AND addedAt=123 AND sortOrder=123;"
        lyrics="SELECT COUNT(*) FROM lyric_records WHERE trackId='release_upgrade_track' AND sourceType='MANUAL_LRC' AND offsetMs=300 AND content LIKE '%升级歌词保留%';"
        sources="SELECT COUNT(*) FROM source_scripts WHERE id='release_upgrade_source' AND scriptContent LIKE '%lx.send%' AND isEnabled=1;"
        selection="SELECT COUNT(*) FROM platform_source_selection WHERE platform='wy' AND sourceId='release_upgrade_source';"
        pausedDownloads="SELECT COUNT(*) FROM download_tasks WHERE id='release_upgrade_download' AND status='PAUSED' AND downloadedBytes=1024 AND tempFilePath='$partial';"
        snapshot="SELECT COUNT(*) FROM playback_snapshot WHERE id=1 AND currentEntryId=701 AND progressMs=3500 AND playbackMode='LIST_LOOP' AND shuffleOrderJson='[702]' AND shuffleHistoryJson='[701]';"
    }
    foreach ($check in $checks.GetEnumerator()) { Require-Value (Read-Sql $check.Value) '1' $check.Key }
    Require-Value (Read-Sql "SELECT COUNT(*) FROM queue_entries WHERE trackId='release_upgrade_track' AND id IN (701,702);") '2' 'Duplicate queue occurrences'
    Require-Value (Invoke-Device shell stat -c %s $wave) "$($seed.waveBytes)" 'Local audio bytes'
    Require-Value (Invoke-Device shell stat -c %s $partial) '1024' 'Paused download bytes'
    Require-Value (Preferences-Hash) $seed.preferencesSha256 'Persisted preferences bytes'
    $report = [ordered]@{ from='1.2.0'; to='1.3.0'; versionCode=10; schema=4; uidPreserved=$true; dataPreserved=$true; duplicateEntries=2; lyricOffsetMs=300; pausedBytes=1024; preferencesPreserved=$true; integrity='ok'; device=$Serial }
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'upgrade-verification.json') -Encoding utf8
    $report | ConvertTo-Json
}
else {
    Read-Sql @"
PRAGMA foreign_keys=ON;
BEGIN;
DELETE FROM queue_entries WHERE trackId='release_upgrade_track';
DELETE FROM playback_snapshot WHERE currentTrackId='release_upgrade_track';
DELETE FROM download_tasks WHERE id='release_upgrade_download';
UPDATE platform_source_selection SET sourceId='builtin_aggregate' WHERE sourceId='release_upgrade_source';
DELETE FROM source_scripts WHERE id='release_upgrade_source';
DELETE FROM playlists WHERE id='release_upgrade_playlist';
DELETE FROM tracks WHERE id='release_upgrade_track';
COMMIT;
"@ | Out-Null
    # Exact files created by seed on this isolated device; no recursive deletion.
    Invoke-Device shell "rm -f '$wave' '$partial'" | Out-Null
    Require-Value (Read-Sql "SELECT COUNT(*) FROM tracks WHERE id='release_upgrade_track';") '0' 'Upgrade fixture cleanup'
    'Dedicated upgrade fixture cleaned; archived prior debug data remains in app/build.'
}
