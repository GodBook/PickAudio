param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$schemaRoot = Join-Path $ProjectRoot 'app/schemas/com.pickaudio.data.db.PickAudioDatabase'
$source = Get-Content -Raw (Join-Path $ProjectRoot 'app/src/main/java/com/pickaudio/data/db/PickAudioDatabase.kt')
$currentVersion = [int][regex]::Match($source, 'version\s*=\s*(\d+)').Groups[1].Value
foreach ($number in 2..$currentVersion) {
    $schemaPath = Join-Path $schemaRoot "$number.json"
    if (!(Test-Path -LiteralPath $schemaPath)) { throw "Missing Room schema: $number" }
    $schema = Get-Content -Raw -LiteralPath $schemaPath | ConvertFrom-Json
    if ($schema.database.version -ne $number -or $schema.database.identityHash.Length -ne 32) { throw "Invalid Room schema: $number" }
    if ($schema.database.entities.Count -lt 13) { throw "Incomplete schema: $number" }
}
Write-Output "Room schemas 2..$currentVersion verified. Device migration tests remain required."
