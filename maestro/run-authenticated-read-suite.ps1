[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$DeviceId,
    [Parameter(Mandatory)][guid]$DynamicFarmerId,
    [Parameter(Mandatory)][guid]$DynamicParcelId,
    [Parameter(Mandatory)][guid]$CropCycleId,
    [Parameter(Mandatory)][guid]$CostedActivityId,
    [Parameter(Mandatory)][ValidateRange(0,10000)][int]$ExpectedSoilProfileCount,
    [ValidatePattern('^[0-9]{6}$')][string]$LandPinCode = '560001',
    [string]$Maestro = 'C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat',
    [string]$Adb = 'C:\Users\SANTOSH\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [uri]$HealthUrl = 'http://localhost:8000/health',
    [string]$EvidenceRoot = (Join-Path ([IO.Path]::GetTempPath()) 'agrios-authenticated-reads'),
    [switch]$Execute
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $Execute) {
    Write-Host 'Plan only. No device, backend, build, fixture or Maestro command executed.'
    Write-Host 'Awaiting execution approval. Review docs/authenticated-read-gate1-coverage.md.'
    return
}
# -Execute is intentionally opt-in. No commands run during implementation review.

function Invoke-Checked([string]$File, [string[]]$Arguments) {
    $result = & $File @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('Command failed: ' + [IO.Path]::GetFileName($File)) }
    return $result
}
function Invoke-BackendSafe([string]$Command, [string]$Name) {
    # No output from auth/fixture processes is persisted verbatim. The supported
    # preflights retain credentials in memory and clean their temporary auth rows.
    $null = Invoke-Checked 'wsl.exe' @('-e', 'bash', '-lc', $Command)
    Add-Content -LiteralPath $record -Value "$Name exit=0"
}
$evidence = [IO.Path]::GetFullPath($EvidenceRoot)
if ($evidence -eq $repo -or $evidence.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Evidence must be outside the Android source checkout'
}
if ($HealthUrl.Host -notin @('localhost','127.0.0.1') -or $HealthUrl.UserInfo) { throw 'Use local backend health only' }
$deviceLines = @(Invoke-Checked $Adb @('devices','-l'))
$match = @($deviceLines | Where-Object { $_ -match ('^' + [regex]::Escape($DeviceId) + '\s+device(?:\s|$)') })
if ($match.Count -ne 1) { throw 'Explicit selected serial is missing, offline or ambiguous' }
$null = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 15
$run = Join-Path $evidence (Get-Date -Format 'yyyyMMdd-HHmmss')
$null = New-Item -ItemType Directory -Path $run
$record = Join-Path $run 'run-metadata.txt'
$previousSerial = $env:ANDROID_SERIAL
Push-Location $repo
try {
    $env:ANDROID_SERIAL = $DeviceId
    $androidRevision = Invoke-Checked 'git' @('rev-parse','HEAD')
    $androidDirty = @(Invoke-Checked 'git' @('status','--porcelain')).Count -gt 0
    $backendRevision = Invoke-Checked 'wsl.exe' @('-e','bash','-lc','cd ~/projects/farmint && git rev-parse HEAD')
    $backendDirty = @(Invoke-Checked 'wsl.exe' @('-e','bash','-lc','cd ~/projects/farmint && git status --porcelain')).Count -gt 0
    $null = Invoke-Checked 'wsl.exe' @('-e','bash','-lc','cd ~/projects/farmint && git merge-base --is-ancestor addedc5 HEAD')
    $version = Invoke-Checked $Maestro @('--version')
    @("device=$DeviceId", "android_revision=$androidRevision", "android_dirty=$androidDirty",
      "backend_revision=$backendRevision", "backend_dirty=$backendDirty", "maestro_version=$version",
      "dynamic_farmer=$DynamicFarmerId", "dynamic_parcel=$DynamicParcelId", "crop_cycle=$CropCycleId",
      'dynamic_tenant=android-dynamic-test', 'persona_tenant=android-persona-lifecycle-test',
      'project=0f7e0a6b-8472-5d6d-8a14-a9d000000001') | Set-Content -LiteralPath $record
    Invoke-BackendSafe 'cd ~/projects/farmint && venv/bin/python backend/scripts/seed_android_dynamic_profile_test_context.py' 'dynamic-dry-run'
    Invoke-BackendSafe 'cd ~/projects/farmint && venv/bin/python backend/scripts/seed_android_crop_cycle_test_fixture.py' 'crop-dry-run'
    # Sequential and complete BEFORE any Android OTP request.
    Invoke-BackendSafe 'cd ~/projects/farmint && venv/bin/python backend/scripts/test_android_fixture_authenticated_reads.py dynamic --exercise-otp' 'dynamic-preflight'
    Invoke-BackendSafe 'cd ~/projects/farmint && venv/bin/python backend/scripts/test_android_fixture_authenticated_reads.py crop --exercise-otp' 'crop-preflight'
    $null = Invoke-Checked '.\gradlew.bat' @(':app:assembleDebug', ':app:testDebugUnitTest', '-PotpPrivacyIsolation=false', '--console=plain')
    $apk = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
    Add-Content -LiteralPath $record -Value ('apk_sha256=' + (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)
    $null = Invoke-Checked $Adb @('-s',$DeviceId,'install','-r',$apk)
    foreach ($flow in @('67a-authenticated-active-cycle-reads','66a-authenticated-profile-land-reads','68-authenticated-persona-read-isolation')) {
        $artifacts = Join-Path $run $flow
        $null = Invoke-Checked $Maestro @('--device',$DeviceId,'test','--debug-output',$artifacts,
            '--test-output-dir',$artifacts, '-e',"DYNAMIC_FARMER_ID=$DynamicFarmerId", '-e',"DYNAMIC_PARCEL_ID=$DynamicParcelId",
            '-e',"CROP_CYCLE_ID=$CropCycleId", '-e',"COSTED_ACTIVITY_ID=$CostedActivityId",
            '-e',"EXPECTED_SOIL_PROFILE_COUNT=$ExpectedSoilProfileCount", '-e',"LAND_PIN_CODE=$LandPinCode", "maestro\$flow.yaml")
        Add-Content -LiteralPath $record -Value "$flow exit=0"
    }
    # Status checks only; no fixture writes or evidence cleanup.
    Invoke-BackendSafe 'cd ~/projects/farmint && venv/bin/python backend/scripts/test_android_fixture_authenticated_reads.py dynamic' 'dynamic-postflight'
    Invoke-BackendSafe 'cd ~/projects/farmint && venv/bin/python backend/scripts/test_android_fixture_authenticated_reads.py crop' 'crop-postflight'
} finally {
    $env:ANDROID_SERIAL = $previousSerial
    Pop-Location
}
