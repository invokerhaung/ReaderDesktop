# Legado Desktop 编译与使用指南

本文面向 Windows 10/11 x64，覆盖从零准备环境、开发运行、打包安装到日常使用的完整流程。

## 1. 程序结构

Legado Desktop 由三部分组成：

| 组件 | 位置 | 作用 |
| --- | --- | --- |
| 核心引擎 | `core/` | 纯 JVM：书源规则解析、搜索、目录、正文、Rhino JS |
| 本地服务 | `server/` | NanoHTTPD API、SQLite、静态资源、鉴权、图片与章节缓存 |
| 桌面界面 | `frontend/` + `desktop/` | Vue 3 页面，Tauri v2 负责启动和回收 JVM |

Tauri 启动时会在本机回环地址上启动 JVM 服务，随机分配端口和 token，然后打开窗口。
不需要单独部署后端，也不依赖外网服务器；书源、书架、设置和缓存都保存在本机。

## 2. 环境准备

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | 必须设置 `JAVA_HOME`；打包时还需要其中的 `jlink.exe` |
| Node.js | 20+ | 构建前端、运行 Tauri CLI |
| Rust | 1.90+ | Tauri 2.12 依赖需要 edition2024；1.77 会报错 |
| Visual Studio Build Tools | 2022 | 安装 “使用 C++ 的桌面开发”，提供 MSVC 链接器 |
| WebView2 | Evergreen | Windows 10/11 通常已内置；缺失时安装微软运行时 |
| PowerShell | 5.1+ | 执行项目脚本 |
| Gradle | 8.13 | 仓库自带 wrapper，无需单独安装 |

检查环境：

```powershell
java -version
node -v
rustup toolchain list
```

当前机器默认 rustup 工具链是 1.77.0，不能直接用于 Tauri。安装 Rust 1.90：

```powershell
$env:RUSTUP_DIST_SERVER = "https://rsproxy.cn"
rustup toolchain install 1.90.0 --profile minimal
```

项目脚本会通过 `rustup run 1.90.0` 和 `RUSTUP_TOOLCHAIN=1.90.0` 使用该工具链。

## 3. 首次编译

在仓库根目录执行（PowerShell）：

```powershell
.\gradlew.bat :core:test :server:test
.\gradlew.bat :server:installDist

cd frontend
npm install
npm run build
cd ..
```

产物：

- 服务端发行版：`server/build/install/server/`（`lib/*.jar`、`bin/server.bat`）
- 前端静态资源：`frontend/dist/`
- 核心与服务端测试结果：`core/build/reports/tests/`、`server/build/reports/tests/`

说明：

- 每次修改 `core/` 或 `server/` 后，需要重新执行 `:server:installDist`。
- 每次修改 `frontend/` 后，需要重新执行 `npm run build`。
- Tauri debug 模式现在直接读取仓库的 `server/build/install/server` 和 `frontend/dist`，不会再用 `target/debug` 里的旧副本。

## 4. 开发运行

### 4.1 浏览器模式（前端热更新）

适合调界面。终端 1 启动后端：

```powershell
.\gradlew.bat :server:run --args="--port 1122 --dev-no-auth"
```

终端 2 启动 Vite：

```powershell
cd frontend
npm run dev
```

打开 <http://127.0.0.1:5173>。Vite 已把 `/api`、`/img`、`/healthz` 代理到 `127.0.0.1:1122`，所以后端端口必须是 1122，除非同时修改 `frontend/vite.config.ts`。

`--dev-no-auth` 会跳过 Cookie 鉴权，只用于本机开发，不要暴露到局域网。

想模拟正式鉴权时：

```powershell
.\gradlew.bat :server:run --args="--port 1122 --token dev --static-dir frontend/dist"
```

然后先访问 <http://127.0.0.1:1122/bootstrap?token=dev>，让浏览器写入 token Cookie。

### 4.2 桌面模式（Tauri dev）

首次运行前确保已执行第 3 节的 `:server:installDist` 和 `npm run build`，然后：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev-tauri.ps1
```

脚本会：

1. 从 Visual Studio 安装目录加载 MSVC 环境（`vcvars64.bat`）；
2. 检查 Rust 1.90.0；
3. 首次运行时在 `desktop/` 执行 `npm install`；
4. 执行 `npm run tauri dev`，编译并启动桌面窗口。

手动启动的等价方式：

```powershell
cd desktop/src-tauri
rustup override set 1.90.0
cd ..
# 必须在 “x64 Native Tools Command Prompt for VS 2022” 中执行
npm run tauri dev
```

注意：

- 直接执行 `npm run tauri dev` 时，若仍使用默认 Rust 1.77，会报 `feature edition2024 is required`。
- 桌面 dev 使用 `frontend/dist` 静态产物，不接 Vite 热更新；改前端后需要 `npm run build` 再重启 Tauri。
- 改变 Rust 代码或 Tauri 配置后，dev 会自动增量编译，首次可能耗时数分钟。

## 5. 打包发布

完整打包（前端 + 服务端 + jlink 运行时 + NSIS 安装包）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/package-tauri.ps1
```

可选参数：

```powershell
# 只准备资源，不执行 Tauri/NSIS 构建
powershell -ExecutionPolicy Bypass -File scripts/package-tauri.ps1 -NoBuild

# GitHub 下载 NSIS 超时时使用镜像
powershell -ExecutionPolicy Bypass -File scripts/package-tauri.ps1 `
  -GithubMirror "https://ghfast.top/https://github.com"
```

脚本流程：

1. `npm run build` 构建前端；
2. `gradlew :server:installDist`；
3. 清空并复制 `server/build/install/server`、`frontend/dist` 到 `desktop/src-tauri/server`、`desktop/src-tauri/frontend`；
4. 用 `jlink` 生成精简 JRE 到 `desktop/src-tauri/runtime`；
5. 调用 Tauri CLI 生成 NSIS 安装包。

输出：

- 安装包：`desktop/src-tauri/target/release/bundle/nsis/Legado Desktop_0.1.0_x64-setup.exe`
- 免安装可执行文件：`desktop/src-tauri/target/release/legado-desktop-shell.exe`

注意：

- 安装包是构建时的快照。修改后端或前端后必须重新打包，只重启已安装的程序不会更新内置 jar 和页面。
- release 可执行文件依赖同目录的 `runtime/`、`server/`、`frontend/`，不能只单独复制 exe。
- `jlink` 从 `JAVA_HOME` 查找；未设置或指向 JRE 时会报 `jlink not found`。
- 打包前先在 `frontend/` 执行过一次 `npm install`。

## 6. 日常使用

### 6.1 启动

安装后从开始菜单或桌面快捷方式启动 “Legado Desktop”。程序会自动：

- 在 `%APPDATA%\io.xiatou.legado.desktop` 创建数据目录；
- 启动本地 JVM 服务并选择空闲端口；
- 打开阅读界面。

单实例生效：重复启动只会聚焦已有窗口。

### 6.2 导入书源

进入左侧 “书源” 页面：

- 粘贴书源订阅 URL 或 shuyuan-api 导入链接，点击 “导入”；
- 或点击 “导入文件”，选择 Legado 格式的 JSON 书源文件。

导入后会出现在书源列表，可启用/停用或删除。规则字段兼容 Legado 的常见写法：

- 规则为对象时正常解析；
- 规则为空字符串、`null`、空数组时按“无此规则”处理；
- 规则为字符串化 JSON 时尝试再次解析。

如果导入失败，界面会显示具体原因（例如缺少 `bookSourceUrl` 或 JSON 解析错误），不再只提示“没有可导入的书源”。

### 6.3 搜索与书架

- “搜索” 页输入关键词，结果按书源聚合；
- 点击结果查看详情抽屉，可切换书源、加入书架；
- “书架” 页管理已收藏书籍和阅读进度。

### 6.4 阅读器

打开章节后支持：

- 横向分页与滚动两种模式；
- 正文图片（经本地代理加载，带书源请求头和缓存）；
- 目录、书签、章内搜索三个侧栏；
- 目录顶部可手动刷新：目录会缓存到本地用于离线阅读，不会每次自动跟随站点更新；
- 字号、行距、页边距、主题、字体等排版设置；
- 阅读进度自动保存；章节正文可离线缓存。

### 6.5 替换净化

“替换净化” 页面用于修改正文/标题内容：

- 支持普通文本和正则；
- 可限定书名/书源作用域，并设置排除范围；
- 规则有顺序，按顺序执行；
- 可新建、编辑、启停、删除、导入规则，并可在线测试。

设置页有全局替换开关。

### 6.6 设置与缓存

“设置” 页可调整阅读默认值、替换开关，并查看数据统计：

- 书籍、书源、规则、书签数量；
- 封面缓存与章节缓存占用；
- 可分别清理封面缓存、章节缓存或全部缓存；
- 可设置章节缓存有效期。

缓存策略：

- 封面与正文图片：7 天磁盘缓存，失败时回退旧缓存；
- 章节正文：按设置的有效期保存到 SQLite，书源暂时不可用时回退旧缓存；
- 内存缓存会在服务重启后清空。

### 6.7 数据、日志与备份

默认数据目录：

```
%APPDATA%\io.xiatou.legado.desktop\
  legado.db          书架、书源、书签、替换规则、设置、章节缓存
  cache\covers\      封面与正文图片缓存
  logs\server.log    本地服务日志
```

备份：关闭程序后复制整个目录即可。恢复时关闭程序，用备份覆盖后重新启动。

卸载：运行安装包自带的卸载程序。若要彻底清理，卸载后手动删除上述数据目录。

## 7. 常见问题

### `feature edition2024 is required`

Rust 工具链太旧。确认安装了 1.90.0，并使用：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev-tauri.ps1
```

### 找不到 `link.exe` 或 MSVC 链接失败

未加载 Visual Studio C++ 环境。使用 `scripts/dev-tauri.ps1`，或在 “x64 Native Tools Command Prompt for VS 2022” 中构建。确认已安装 “使用 C++ 的桌面开发” 和 Windows SDK。

### 下载 NSIS 或 Rust 依赖超时

```powershell
# Rust 工具链 / crates
$env:RUSTUP_DIST_SERVER = "https://rsproxy.cn"
# NSIS
powershell -ExecutionPolicy Bypass -File scripts/package-tauri.ps1 `
  -GithubMirror "https://ghfast.top/https://github.com"
```

### 导入书源提示失败

1. 浏览器直接打开导入链接，确认返回的是 JSON 书源数组或对象；
2. 查看界面里的具体错误信息；
3. 确认运行的是最新构建：开发模式检查 `server/build/install/server/lib/core-*.jar` 时间；安装版需要重新打包；
4. 检查 `%APPDATA%\io.xiatou.legado.desktop\logs\server.log`。

### 页面空白或 API 返回 401

服务端带 Cookie 鉴权的模式下，必须先访问 `/bootstrap?token=...`。桌面应用会自动完成；浏览器手动启动时使用启动日志中打印的 `open:` 地址。

### 端口被占用

- 桌面应用会自动选择空闲端口；
- 浏览器模式默认 1122，可通过 `--port` 修改，同时要修改 `frontend/vite.config.ts` 的代理地址。

### 手机上目录比桌面多

桌面端目录会缓存在本地（用于离线阅读），不会每次打开书都重新抓取站点目录。站点后来新增的章节不会自动出现在旧缓存里。

打开阅读器 → 左侧目录 → 顶部刷新图标，重新抓取全部分页目录。抓取时间取决于目录页数（例如 6 页、2600 章约需十几秒）。刷新后会保留当前正在阅读的章节。

### 阅读时正文不更新

- 检查 “设置” 里的缓存开关与章节缓存有效期；
- 在 “设置 → 缓存” 清理章节缓存后重试；
- 若书源规则已修改，需要刷新目录或重新打开章节。

## 8. 常用命令速查

| 目的 | 命令 |
| --- | --- |
| 运行全部 JVM 测试 | `.\gradlew.bat :core:test :server:test` |
| 构建服务端发行版 | `.\gradlew.bat :server:installDist` |
| 构建前端 | `cd frontend; npm run build` |
| 浏览器模式后端 | `.\gradlew.bat :server:run --args="--port 1122 --dev-no-auth"` |
| 浏览器模式前端 | `cd frontend; npm run dev` |
| 桌面开发 | `powershell -ExecutionPolicy Bypass -File scripts/dev-tauri.ps1` |
| 完整打包 | `powershell -ExecutionPolicy Bypass -File scripts/package-tauri.ps1` |
| 只准备打包资源 | `powershell -ExecutionPolicy Bypass -File scripts/package-tauri.ps1 -NoBuild` |
| 引擎 CLI | `.\gradlew.bat :cli:installDist`，再参考 `README.md` |
