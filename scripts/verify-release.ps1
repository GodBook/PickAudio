param(
    [Parameter(Mandatory)][string]$ApkPath,
    [Parameter(Mandatory)][int]$PreviousVersionCode,
    [string]$ExpectedCertificateSha256,
    [string]$SdkPath = $env:ANDROID_SDK_ROOT,
    [string]$ChangelogPath,
    [string]$OutputDirectory,
    [switch]$AllowUnsigned
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$stagedDirectory = $null
if ($IsWindows -and $apk.ToCharArray().Where({ [int]$_ -gt 127 }).Count -gt 0) {
    $stagedDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("pickaudio-release-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stagedDirectory | Out-Null
    $stagedApk = Join-Path $stagedDirectory 'candidate.apk'
    Copy-Item -LiteralPath $apk -Destination $stagedApk
    $apk = $stagedApk
}
try {
if (!$SdkPath) { throw 'Provide -SdkPath or ANDROID_SDK_ROOT.' }
$sdk = (Resolve-Path -LiteralPath $SdkPath).Path
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Android build-tools are required.' }
$exe = if ($IsWindows) { '.exe' } else { '' }
$aapt = Join-Path $buildTools.FullName "aapt$exe"
$badging = & $aapt dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'APK manifest cannot be read.' }
$package = [regex]::Match(($badging -join "`n"), "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
if (!$package.Success -or $package.Groups[1].Value -ne 'com.pickaudio') { throw 'Unexpected APK application ID.' }
$code = [int]$package.Groups[2].Value
$name = $package.Groups[3].Value
if ($code -le $PreviousVersionCode) { throw "versionCode $code must exceed $PreviousVersionCode before release." }
$properties = ConvertFrom-StringData (Get-Content -Raw (Join-Path $projectRoot 'version.properties'))
if ($code -ne [int]$properties.versionCode -or $name -ne $properties.versionName) { throw 'APK does not match version.properties.' }
& (Join-Path $buildTools.FullName "zipalign$exe") -c -P 16 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'APK 16 KiB ZIP alignment failed.' }
$certificate = $null
if (!$AllowUnsigned) {
    if ($ExpectedCertificateSha256 -notmatch '^[a-fA-F0-9:]{64,95}$') { throw 'Expected production certificate SHA-256 is required.' }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java$exe" } else { "java$exe" }
    $signature = & $java -jar (Join-Path $buildTools.FullName 'lib/apksigner.jar') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $signers = [regex]::Matches(($signature -join "`n"), 'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)')
    $expected = $ExpectedCertificateSha256.Replace(':', '').ToLowerInvariant()
    if ($signers.Count -ne 1 -or $signers[0].Groups[1].Value.ToLowerInvariant() -ne $expected) { throw 'Unexpected production signer.' }
    $certificate = $expected
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
try {
    foreach ($abi in @('arm64-v8a', 'x86_64')) {
        $entry = $zip.GetEntry("lib/$abi/libquickjs_bridge.so")
        if (!$entry) { throw "Missing native library for $abi." }
        $stream = $entry.Open()
        $buffer = [System.IO.MemoryStream]::new()
        try { $stream.CopyTo($buffer); $bytes = $buffer.ToArray() } finally { $stream.Dispose(); $buffer.Dispose() }
        if ($bytes[4] -ne 2 -or $bytes[5] -ne 1) { throw 'Expected little-endian ELF64 library.' }
        $offset = [BitConverter]::ToUInt64($bytes, 32)
        $stride = [BitConverter]::ToUInt16($bytes, 54)
        $count = [BitConverter]::ToUInt16($bytes, 56)
        for ($i = 0; $i -lt $count; $i++) {
            $start = [int]($offset + $i * $stride)
            if ([BitConverter]::ToUInt32($bytes, $start) -eq 1 -and [BitConverter]::ToUInt64($bytes, $start + 48) -lt 16384) { throw "ELF PT_LOAD alignment below 16 KiB: $abi" }
        }
    }
    $dexBytes = ($zip.Entries | Where-Object FullName -Match '^classes\d*\.dex$' | Measure-Object Length -Sum).Sum
} finally { $zip.Dispose() }
$changelog = if ($ChangelogPath) { Get-Content -Raw -LiteralPath $ChangelogPath } else { 'See release notes.' }
$metadata = [ordered]@{
    versionCode = $code; versionName = $name; changelog = $changelog.Trim()
    downloadUrl = "https://github.com/GodBook/PickAudio/releases/download/v$name/PickAudio-v$name-release.apk"
    releasePageUrl = "https://github.com/GodBook/PickAudio/releases/tag/v$name"
    apkSize = (Get-Item -LiteralPath $apk).Length; sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    publishTime = (Get-Date).ToUniversalTime().ToString('o'); signerSha256 = $certificate
    publishable = !$AllowUnsigned; dexBytes = $dexBytes
}
if ($OutputDirectory) {
    # Generated build metadata only. Never overwrite the historical root version.json.
    $output = [System.IO.Path]::GetFullPath($OutputDirectory)
    $buildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'app/build'))
    if (!$output.StartsWith($buildRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'OutputDirectory must be inside app/build.' }
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'version.json') -Encoding utf8
}
$metadata | ConvertTo-Json
} finally {
    if ($stagedDirectory) {
        Remove-Item -LiteralPath (Join-Path $stagedDirectory 'candidate.apk') -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stagedDirectory -ErrorAction SilentlyContinue
    }
}
