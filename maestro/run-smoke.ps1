param(
    [string]$Maestro = "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$flows = @(
    "maestro\00-login-hydrate-existing-profile.yaml",
    "maestro\01-home-history-smoke.yaml",
    "maestro\02-farmer-profile-parcel-geometry.yaml",
    "maestro\03-start-crop-eligible-parcel.yaml",
    "maestro\05-completed-cycle-view-only.yaml"
)

if (-not (Test-Path $Maestro)) {
    throw "Maestro executable not found at '$Maestro'. Pass -Maestro with the correct path."
}

foreach ($flow in $flows) {
    Write-Host ""
    Write-Host "==> Running $flow" -ForegroundColor Cyan
    & $Maestro test $flow
}

Write-Host ""
Write-Host "All Agri-OS Maestro smoke flows passed." -ForegroundColor Green

