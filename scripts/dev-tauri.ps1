#requires -Version 5.1
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\vs-env.ps1"
Import-VcEnvironment

if (Get-Command rustup -ErrorAction SilentlyContinue) {
    & rustup run 1.90.0 rustc --version *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Rust 1.90.0 is not installed. Run: rustup toolchain install 1.90.0 --profile minimal"
    }
    $env:RUSTUP_TOOLCHAIN = "1.90.0"
} else {
    throw "rustup not found."
}

$Desktop = Join-Path (Split-Path -Parent $PSScriptRoot) "desktop"
Push-Location $Desktop
try {
    if (-not (Test-Path "node_modules")) {
        npm install
        if ($LASTEXITCODE -ne 0) { throw "npm install failed" }
    }
    npm run tauri dev
} finally {
    Pop-Location
}
