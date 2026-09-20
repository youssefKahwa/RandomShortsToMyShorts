# Installs faster-whisper (speech-to-text) and Argos Translate (machine translation) in an
# isolated virtual environment, and downloads an Arabic -> English translation package.
# Both run fully offline, no account or API key needed.
#
# Usage: .\scripts\setup-auto-script.ps1 [-FromLang xx] [-ToLang yy]

param(
    [string]$FromLang = "ar",
    [string]$ToLang = "en"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$venvDir = Join-Path $projectRoot ".venv-ml"

Write-Host "=== 1/3 Virtual environment ===" -ForegroundColor Cyan
if (-not (Test-Path $venvDir)) {
    python -m venv $venvDir
}
$venvPython = Join-Path $venvDir "Scripts\python.exe"
Write-Host "  $venvPython" -ForegroundColor Green

Write-Host ""
Write-Host "=== 2/3 Installing faster-whisper and argostranslate ===" -ForegroundColor Cyan
& $venvPython -m pip install --upgrade pip --quiet
& $venvPython -m pip install faster-whisper argostranslate --quiet
if ($LASTEXITCODE -ne 0) { Write-Host "  Install failed." -ForegroundColor Red; exit 1 }
Write-Host "  Installed." -ForegroundColor Green

Write-Host ""
Write-Host "=== 3/3 Downloading the $FromLang -> $ToLang translation package ===" -ForegroundColor Cyan
$installScript = @"
import argostranslate.package
argostranslate.package.update_package_index()
available = argostranslate.package.get_available_packages()
pkg = next((p for p in available if p.from_code == '$FromLang' and p.to_code == '$ToLang'), None)
if pkg is None:
    raise SystemExit('No package found for $FromLang -> $ToLang. See https://www.argosopentech.com/argospm/index/')
argostranslate.package.install_from_path(pkg.download())
print('Installed $FromLang -> $ToLang translation package.')
"@
& $venvPython -c $installScript

Write-Host ""
Write-Host "Done. The Whisper model itself (small, ~250 MB) downloads automatically on first use." -ForegroundColor Yellow
Write-Host "Point application.yml at this venv's python:" -ForegroundColor Yellow
Write-Host "  app.auto-script.python-binary: $($venvPython -replace '\\','/')"
Write-Host ""
Write-Host "For another language pair, re-run with e.g.:" -ForegroundColor Cyan
Write-Host "  .\scripts\setup-auto-script.ps1 -FromLang fr -ToLang en"
Write-Host "Full list of available pairs: https://www.argosopentech.com/argospm/index/"
