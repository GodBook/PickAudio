param(
    [Parameter(Mandatory)][string]$SigningConfigPath,
    [Parameter(Mandatory)][string]$InputApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [string]$SdkPath = $env:ANDROID_SDK_ROOT
)
$ErrorActionPreference = 'Stop'
if (!$IsWindows) { throw 'This signing configuration uses Windows DPAPI.' }
$taskConfig = Get-Content -LiteralPath $SigningConfigPath -Raw | ConvertFrom-Json
if ($taskConfig.passwordProtection -ne 'WindowsCurrentUserDPAPI') { throw 'Unsupported password protection.' }
if ($taskConfig.certificateSha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Expected signing certificate is missing.' }
$taskInput = (Resolve-Path -LiteralPath $InputApk).Path
$taskOutput = [System.IO.Path]::GetFullPath($OutputApk)
if (Test-Path -LiteralPath $taskOutput) { throw 'Output APK already exists; choose another path.' }
$taskParent = Split-Path -Parent $taskOutput
if (!(Test-Path -LiteralPath $taskParent -PathType Container)) { throw 'Output directory does not exist.' }
if (!$SdkPath) { throw 'Provide -SdkPath or ANDROID_SDK_ROOT.' }
$taskBuildTools = Get-ChildItem -LiteralPath (Join-Path $SdkPath 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$taskBuildTools) { throw 'Android build-tools are required.' }
$taskSigner = Join-Path $taskBuildTools.FullName 'lib/apksigner.jar'
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
$taskPending = Join-Path $taskParent ('.pickaudio-signing-' + [guid]::NewGuid().ToString('N') + '.apk')
$taskPriorPassword = [Environment]::GetEnvironmentVariable('PICKAUDIO_PRIVATE_SIGNING_PASSWORD', 'Process')
$taskSecret = $null
$taskSecure = $null
try {
    $taskSecure = ConvertTo-SecureString (Get-Content -LiteralPath $taskConfig.passwordFile -Raw).Trim()
    $taskSecret = [System.Net.NetworkCredential]::new('', $taskSecure).Password
    [Environment]::SetEnvironmentVariable('PICKAUDIO_PRIVATE_SIGNING_PASSWORD', $taskSecret, 'Process')
    & $taskJava -jar $taskSigner sign --ks $taskConfig.keystore --ks-key-alias $taskConfig.keyAlias `
        --ks-pass env:PICKAUDIO_PRIVATE_SIGNING_PASSWORD --key-pass env:PICKAUDIO_PRIVATE_SIGNING_PASSWORD `
        --v4-signing-enabled false --out $taskPending $taskInput
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    $taskSignature = & $taskJava -jar $taskSigner verify --verbose --print-certs $taskPending
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
    $taskSigners = [regex]::Matches(($taskSignature -join "`n"), 'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)')
    if ($taskSigners.Count -ne 1 -or $taskSigners[0].Groups[1].Value.ToLowerInvariant() -ne $taskConfig.certificateSha256.ToLowerInvariant()) {
        throw 'Signed APK certificate differs from the saved configuration.'
    }
    Move-Item -LiteralPath $taskPending -Destination $taskOutput
    [pscustomobject]@{
        apk = $taskOutput
        certificateSha256 = $taskConfig.certificateSha256.ToLowerInvariant()
        sha256 = (Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant()
        size = (Get-Item -LiteralPath $taskOutput).Length
    }
} finally {
    [Environment]::SetEnvironmentVariable('PICKAUDIO_PRIVATE_SIGNING_PASSWORD', $taskPriorPassword, 'Process')
    $taskSecret = $null
    if ($taskSecure) { $taskSecure.Dispose() }
    if (Test-Path -LiteralPath $taskPending) { Remove-Item -LiteralPath $taskPending }
}
