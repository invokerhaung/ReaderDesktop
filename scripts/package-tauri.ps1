#requires -Version 5.1
param(
    [switch]$NoBuild,
    [string]$GithubMirror = ""
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\vs-env.ps1"

$Root = Split-Path -Parent $PSScriptRoot
$Desktop = Join-Path $Root "desktop"
$SrcTauri = Join-Path $Desktop "src-tauri"
$Runtime = Join-Path $SrcTauri "runtime"
$Server = Join-Path $SrcTauri "server"
$Frontend = Join-Path $SrcTauri "frontend"

Write-Host "==> build frontend"
Push-Location (Join-Path $Root "frontend")
try {
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "frontend build failed" }
} finally {
    Pop-Location
}

Write-Host "==> build server distribution"
Push-Location $Root
try {
    & (Join-Path $Root "gradlew.bat") ":server:installDist"
    if ($LASTEXITCODE -ne 0) { throw "gradle installDist failed" }
} finally {
    Pop-Location
}

Write-Host "==> prepare Tauri resources"
foreach ($dir in @($Server, $Frontend)) {
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
if (Test-Path $Runtime) { Remove-Item -Recurse -Force $Runtime }
Copy-Item (Join-Path $Root "server\build\install\server\*") $Server -Recurse -Force
Copy-Item (Join-Path $Root "frontend\dist\*") $Frontend -Recurse -Force

$javaHome = $env:JAVA_HOME
if ($javaHome) {
    $jlink = Join-Path $javaHome "bin\jlink.exe"
} else {
    $jlinkCommand = Get-Command jlink -ErrorAction SilentlyContinue
    $jlink = if ($jlinkCommand) { $jlinkCommand.Source } else { $null }
}
if (-not $jlink -or -not (Test-Path $jlink)) {
    throw "jlink not found. Set JAVA_HOME to a JDK 17 installation."
}

$modules = "java.base,java.logging,java.sql,java.naming,java.desktop,java.xml,java.management,jdk.crypto.ec,jdk.unsupported"
Write-Host "==> jlink runtime"
& $jlink --add-modules $modules --strip-debug --no-man-pages --no-header-files --compress=2 --output $Runtime
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE" }

if ($NoBuild) {
    Write-Host "Resources prepared. Tauri build skipped (-NoBuild)."
    exit 0
}

Write-Host "==> tauri build (NSIS)"
if ($GithubMirror) {
    $env:TAURI_BUNDLER_TOOLS_GITHUB_MIRROR = $GithubMirror
    Write-Host "using GitHub mirror: $GithubMirror"
}
Import-VcEnvironment
if (Get-Command rustup -ErrorAction SilentlyContinue) {
    & rustup run 1.90.0 rustc --version *> $null
    if ($LASTEXITCODE -eq 0) {
        $env:RUSTUP_TOOLCHAIN = "1.90.0"
        Write-Host "using Rust 1.90.0"
    }
}
Push-Location $Desktop
try {
    $cargoTauri = Get-Command cargo-tauri -ErrorAction SilentlyContinue
    if ($cargoTauri) {
        & cargo tauri build --bundles nsis
    } else {
        & npx --yes "@tauri-apps/cli@2" build --bundles nsis
    }
    if ($LASTEXITCODE -ne 0) { throw "tauri build failed" }
} finally {
    Pop-Location
}

Write-Host "Done. See desktop/src-tauri/target/release/bundle/nsis/"
