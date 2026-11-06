#requires -Version 5.1
$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $repo "desktop\src-tauri\target\debug\legado-desktop-shell.exe"
$dist = Join-Path $repo "frontend\dist\index.html"
$serverLib = Join-Path $repo "server\build\install\server\lib"

if (-not (Test-Path $serverLib)) {
    throw "缺少服务端构建产物，请先执行：.\gradlew :server:installDist"
}
if (-not (Test-Path $dist)) {
    throw "缺少前端构建产物，请先执行：cd frontend; npm run build"
}
if (-not (Test-Path $exe)) {
    throw "预览壳未构建。请在导入 MSVC 环境后执行：cd desktop/src-tauri; cargo build"
}

$running = Get-Process legado-desktop-shell -ErrorAction SilentlyContinue
if ($running) {
    throw "请先退出正在运行的墨读（包括托盘里的），再运行预览；预览会复用同一份数据目录。"
}

Write-Host "启动预览版：$exe"
Start-Process -FilePath $exe -WorkingDirectory (Split-Path -Parent $exe)
