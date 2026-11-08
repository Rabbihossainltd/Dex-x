# Dex-X — Windows build script
#
#   Right-click PowerShell → "Run as administrator", then:
#     cd C:\path\to\Dex-X
#     powershell -ExecutionPolicy Bypass -File tools\build-windows.ps1
#
# What it does, in order — each step is skipped if it is already done:
#   1. Rust (rustup)          winget  Rustlang.Rustup
#   2. Node.js LTS            winget  OpenJS.NodeJS.LTS
#   3. MSVC C++ build tools   winget  Microsoft.VisualStudio.2022.BuildTools
#   4. JDK 17                 winget  EclipseAdoptium.Temurin.17.JDK
#   5. Android SDK            the official cmdline-tools zip, into %LOCALAPPDATA%\Android\Sdk
#   6. npm ci, then `npx tauri build`, which also builds the launcher APK,
#      the wmd daemon and scrcpy's patched server, and bundles all three.
#
# The first run takes 30–60 minutes, almost all of it Rust compiling ~500
# crates. Later runs reuse that and take a couple of minutes.
#
# Output:
#   open-android-dex-tauri\src-tauri\target\release\open-android-dex-tauri.exe
#   open-android-dex-tauri\src-tauri\target\release\bundle\nsis\*-setup.exe

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root
Write-Host "Dex-X build — $root" -ForegroundColor Cyan

function Have($cmd) { $null -ne (Get-Command $cmd -ErrorAction SilentlyContinue) }

function Ensure-Winget($id, $name, $extra) {
    Write-Host "`n== $name ==" -ForegroundColor Yellow
    if ($extra) {
        winget install --id $id --accept-source-agreements --accept-package-agreements --silent @extra
    } else {
        winget install --id $id --accept-source-agreements --accept-package-agreements --silent
    }
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) {
        Write-Host "  winget could not install $name (exit $LASTEXITCODE) — install it by hand, then run this script again." -ForegroundColor Red
    }
}

if (-not (Have winget)) {
    throw "winget is missing. Install 'App Installer' from the Microsoft Store and run this again."
}

# ── 1..4: the toolchain ────────────────────────────────────────────────────
if (-not (Have rustc)) { Ensure-Winget 'Rustlang.Rustup' 'Rust' }
if (-not (Have node))  { Ensure-Winget 'OpenJS.NodeJS.LTS' 'Node.js LTS' }

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$haveMsvc = $false
if (Test-Path $vswhere) {
    $found = & $vswhere -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    $haveMsvc = -not [string]::IsNullOrWhiteSpace($found)
}
if (-not $haveMsvc) {
    Write-Host "`n== MSVC C++ build tools (this is the big download, ~2 GB) ==" -ForegroundColor Yellow
    winget install --id Microsoft.VisualStudio.2022.BuildTools --accept-source-agreements --accept-package-agreements --silent `
        --override "--wait --quiet --nocache --add Microsoft.VisualStudio.Workload.VCTools --add Microsoft.VisualStudio.Component.Windows11SDK.22000 --includeRecommended"
}

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    Ensure-Winget 'EclipseAdoptium.Temurin.17.JDK' 'JDK 17'
    $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Filter 'jdk-17*' -Directory -ErrorAction SilentlyContinue |
           Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Filter 'jdk-17*' -Directory -ErrorAction SilentlyContinue |
           Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if ($env:JAVA_HOME) { Write-Host "JAVA_HOME = $env:JAVA_HOME" }

# ── 5: Android SDK (cmdline-tools only — no Android Studio) ────────────────
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    Write-Host "`n== Android SDK ==" -ForegroundColor Yellow
    $zip = Join-Path $env:TEMP 'cmdline-tools.zip'
    Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -OutFile $zip
    $tmp = Join-Path $env:TEMP 'cmdline-tools-x'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Path (Join-Path $sdk 'cmdline-tools') -Force | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $sdk 'cmdline-tools\latest') -Force
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Write-Host "ANDROID_HOME = $sdk"

Write-Host "`n== SDK packages ==" -ForegroundColor Yellow
& $sdkmanager --sdk_root=$sdk 'platform-tools' 'platforms;android-35' 'platforms;android-36' 'build-tools;35.0.0' 'build-tools;36.0.0' 2>&1 | Select-Object -Last 4

# ── 6: build ──────────────────────────────────────────────────────────────
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
Write-Host "`n== npm ci ==" -ForegroundColor Yellow
Set-Location (Join-Path $root 'open-android-dex-tauri')
npm ci
if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }

Write-Host "`n== tauri build (this is the long part) ==" -ForegroundColor Yellow
npx tauri build
if ($LASTEXITCODE -ne 0) { throw "tauri build failed" }

Write-Host "`n== done ==" -ForegroundColor Green
$rel = Join-Path $root 'open-android-dex-tauri\src-tauri\target\release'
Write-Host "exe:        $rel\open-android-dex-tauri.exe"
Get-ChildItem (Join-Path $rel 'bundle\nsis') -Filter '*.exe' -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "installer:  $($_.FullName)" }
