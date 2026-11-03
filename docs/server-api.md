# 本地服务 API（Phase 2 切片）

服务由 `:server` 提供，NanoHTTPD，默认 `127.0.0.1:1122`。前端通过 `/bootstrap?token=...` 换取 `sid` HttpOnly Cookie 后访问 `/api/*`。

## 数据

SQLite（`data/legado.db`，sqlite-jdbc）：

| 表 | 字段 |
| --- | --- |
| `book_sources` | `source_key` PK、`name`、`group_name`、`enabled`、`json`、`updated_at` |
| `books` | `book_url` PK、`name`、`author`、`origin`、`origin_name`、`cover_url`、`intro`、`kind`、`latest_chapter`、`toc_url`、`total_chapters`、`dur_chapter_index`、`dur_chapter_pos`（视频毫秒/漫画页序）、`type`（位标志：8 文本 / 64 漫画 / 2048 视频）、`updated_at` |
| `chapters` | `(book_url, idx)` PK、`title`、`url`、`is_vip`、`resource_url`（视频取流结果缓存） |
| `replace_rules` | `id` PK、`name`、`group_name`、`pattern`、`replacement`、`scope`、`scope_title`、`scope_content`、`exclude_scope`、`is_enabled`、`is_regex`、`timeout_ms`、`sort_order` |
| `bookmarks` | `id` PK、`book_url`、`chapter_index`、`chapter_title`、`mode`、`position`、`excerpt`、`created_at` |
| `chapter_cache` | `(book_url, idx)` PK、`version`、`text`、`blocks`、`updated_at`（正文离线缓存） |
| `settings` | `key` PK、`value`（JSON 文本） |

书源整段存 JSON；书籍按字段存储；章节独立成行，便于书架与目录查询。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/healthz` | 健康检查 |
| GET | `/bootstrap?token=` | 换 Cookie 并 303 到 `/` |
| GET | `/img?url=&source=&book=` | 封面代理：按书源请求头/Referer 拉取，支持 `coverDecodeJs`，磁盘缓存 |
| GET | `/media?url=&source=&book=` | 媒体代理：支持 `Range`/206 流式透传，自动补齐书源请求头；m3u8 会把子列表、分片、`EXT-X-KEY` URI 重写到本代理 |
| GET | `/api/sources/login` | POST body `{"key":"...","url":"可选"}`；打开 WebView 登录窗口，关闭后保存 Cookie |
| POST | `/api/sources/verify` | body `{"url":"...","title":"可选"}`；打开置顶的可见 WebView 完成 CF/网页验证，关闭后保存 Cookie 并标记该 host 走 WebView |
| GET | `/api/ping` | `{pong:true}` |
| GET | `/api/about` | 版本与数据目录 |
| GET | `/api/sources` | 全部书源（完整 JSON） |
| POST | `/api/sources/import` | body 为书源对象、数组，或 `{"url":"..."}` 远程订阅 |
| DELETE | `/api/sources?key=` | 删除书源 |
| POST | `/api/sources/delete` | body `{"key":"..."}` |
| POST | `/api/sources/enabled` | body `{"key":"...","enabled":true}` |
| GET | `/api/source-groups` | 分组列表 `{groups:[{name,count}], ungrouped:N}`；分组来自 `bookSourceGroup` 的逗号分隔值 |
| POST | `/api/sources/groups` | body `{"key":"...","groups":["小说","精选"]}`；写回 `bookSourceGroup` |
| POST | `/api/source-groups/rename` | body `{"from":"旧","to":"新"}`；更新所有引用该书源的 `bookSourceGroup` |
| POST | `/api/source-groups/delete` | body `{"name":"..."}`；从所有书源移除该分组，不删除书源 |
| POST | `/api/source-groups/create` | body `{"name":"...","assignUngrouped":true}`；可选把未分组书源归入（与上游 `addGroup` 一致） |
| GET | `/api/explore?key=` | 发现栏目：`exploreUrl` 支持 `@js:`/`<js>`（Rhino 执行后返回 JSON 数组或 `标题::URL` 行）、JSON 数组、`标题::URL` 行（`&&`/换行分隔），空 URL 的分组标题会被过滤 |
| POST | `/api/explore` | body `{"key":"...","url":"...","page":1}`；按 `exploreRule` 解析发现列表 |
| POST | `/api/search` | body `{"keyword":"..."}`，可选 `sourceKey`；默认搜索全部启用书源 |
| GET | `/api/books` | 书架 |
| POST | `/api/books` | body `{"bookUrl":"..."}`，可选 `sourceKey` 与 `name/author/coverUrl/tocUrl/lastChapter/type` 等搜索元数据；抓详情页后入库，目录懒加载 |
| POST | `/api/books/by-url` | body `{"urls":"一行一个网址"}`；按 `origin 选项 → 启用书源 bookSourceUrl 前缀（最长）→ bookUrlPattern 正则` 匹配书源，抓详情+目录后直接入库；返回 `{total, success, results:[{url, ok, book?, error?}]}`，同 URL 已在书架会报“已在书架”，同名同作者会按章节标题迁移进度 |
| DELETE | `/api/books?url=` | 删除书籍与章节 |
| GET | `/api/books/chapters?url=` | 目录；优先读库，缺失时抓取并写入 |
| POST | `/api/books/chapters/refresh` | body `{"url":"..."}`；重新抓取全部分页目录并替换缓存，保留当前阅读章节 |
| GET | `/api/books/content?url=&index=&refresh=1` | 正文；内存缓存，返回 `content` 文本与 `blocks` 结构（文本/图片），并应用替换规则。`refresh=1` 跳过内存/磁盘缓存强制重新抓取，成功后更新缓存，失败时保留旧正文。漫画书源的 `blocks` 即章节图片列表 |
| GET | `/api/books/video?url=&index=&resolution=&refresh=1` | 视频取流：解析 `contentRule`（单 URL / `名称::URL` / JSON resolutions），返回 `kind`（`hls`/`file`/`drm`）、`url`、`resolutions`、`defaultIndex`、`drm` |
| POST | `/api/preview/open` | 临时试看（不写书架）：body 为搜索结果元数据（`bookUrl/sourceKey/name/...`），返回 `{id, book, chapters}`；会话仅驻内存，TTL 30 分钟 |
| GET | `/api/preview/session?id=` | 取试看会话的书籍与目录 |
| GET | `/api/preview/content?id=&index=&refresh=1` | 试看正文/图片，行为与 `/api/books/content` 一致但不落正文缓存；`refresh=1` 强制重新抓取。 |
| GET | `/api/preview/video?id=&index=&resolution=&refresh=1` | 试看视频取流，结果缓存在会话内 |
| POST | `/api/preview/close` | body `{"id":"..."}`，释放试看会话 |
| PUT | `/api/books/progress` | body `{"url":"...","index":N,"position":可选}`；文字书 `position` 为章内千分比（0–1000，主窗口/浮窗通用），视频为毫秒，漫画为页序 |
| POST | `/api/books/switch` | body `{"fromUrl":"...","toUrl":"...","sourceKey":"可选"}`；抓取新源详情/目录，保留进度，删除旧书 |
| GET | `/api/bookmarks?url=` | 某本书的书签，按创建时间倒序 |
| POST | `/api/bookmarks` | body `{"bookUrl","chapterIndex","chapterTitle","mode","position","excerpt"}` |
| DELETE | `/api/bookmarks?id=` | 删除书签 |
| GET | `/api/replace-rules` | 全部替换规则，按 `sortOrder`/id 排序 |
| POST | `/api/replace-rules` | 新建或更新规则（带 `id` 即更新；正则无效返回错误） |
| DELETE | `/api/replace-rules?id=` | 删除规则 |
| POST | `/api/replace-rules/enabled` | body `{"id":N,"enabled":true}` |
| POST | `/api/replace-rules/import` | 规则对象/数组、`{"replaceRules":[...]}` 或 `{"url":"..."}`；兼容 `regex/replaceSummary/useTo/enable/serialNumber` 旧字段 |
| POST | `/api/replace-rules/test` | body `{"text":"...","pattern":"...","replacement":"...","isRegex":true,"timeoutMillisecond":3000}`，返回替换结果 |
| GET | `/api/settings` | 设置对象；未设置时 `replaceEnabled=true` |
| PUT | `/api/settings` | 合并写入设置对象，值为 JSON（字符串/数字/布尔） |
| POST | `/api/float/<action>` | 桌面壳浮窗控制（open/toggle/show/hide/back/settings/top/prefs/hotkey/drag/resize/state/reset），需通过桌面壳启动 |
| GET | `/api/stats` | 书籍、书源、规则、书签数量，数据库大小与数据目录 |
| GET | `/api/cache` | 封面/正文缓存统计 |
| DELETE | `/api/cache?type=cover\|chapter\|all` | 清理封面/正文/全部缓存，返回最新统计 |

响应统一为：

```json
{ "isSuccess": true, "errorMsg": null, "data": {} }
```

## 封面代理

`GET /img?url=<coverUrl>&source=<bookSourceUrl>&book=<bookUrl>`：

- `source`/`book` 可选，但建议都带：`source` 用于取书源请求头、Cookie 与解密规则；`book` 作为 Referer 回退值。
- `kind=cover`（默认）使用 `coverDecodeJs`；`kind=content` 使用 `ruleContent.imageDecode` 处理正文插图。
- 请求头优先使用书源 `header`；未配置 `Referer` 时使用书籍 URL，未配置 UA 时使用桌面端默认 UA。
- 响应必须是图片（按 `Content-Type` 与文件头校验）；`text/html` 等防盗链页面直接返回 404，前端显示书名首字占位。
- 结果缓存在 `data/cache/covers/<sha256>.img` + `.type`，有效期 7 天；下载失败时回退到旧缓存。
- 单文件上限 8 MB；支持 PNG/JPEG/GIF/WebP/BMP/ICO/SVG 与 `data:image/...`。
- 解密 JS 以 `src`（图片地址）、`result`（原始字节）执行，返回 `ByteArray` 或 `InputStream`。

## 媒体代理

`GET /media?url=<mediaUrl>&source=<bookSourceUrl>&book=<bookUrl>`：

- 用于漫画图片与视频流：透传 `Range` 并返回 206（含 `Content-Range`），未知长度时用 chunked。
- 自动补齐书源请求头、UA、Cookie，未配置 `Referer` 时回退到书源地址。
- 命中 m3u8（扩展名或 `Content-Type`）时抓取清单并重写：变体列表、分片、`#EXT-X-KEY` / `#EXT-X-MAP` 的 `URI` 全部改写到 `/media?url=...`，保证 key 与分片也带请求头；AES-128 解密由前端 hls.js 完成。
- 只接受 `http`/`https`；`/media` 需要 `sid` 登录态。

## 正文结构

`GET /api/books/content` 同时返回：

```json
{
  "index": 0,
  "title": "第1回 雪夜",
  "content": "段落文本…",
  "blocks": [
    { "type": "text", "text": "段落文本…" },
    { "type": "image", "src": "https://…/pic.png", "alt": "插图", "style": "FULL" }
  ]
}
```

- 图片地址已按正文页 URL 解析为绝对地址；前端用 `/img?kind=content` 代理加载。
- `style` 来自书源 `ruleContent.imageStyle`（DEFAULT/FULL/TEXT/SINGLE），用于阅读器分页时预留高度。
- 章节只有图片没有文字时 `content` 可为空，`blocks` 仍有图片块。

## 替换净化

- 规则按 `sortOrder` 升序依次执行，支持正则与纯文本替换。
- `scopeContent` 作用于正文，`scopeTitle` 作用于章节标题（目录接口与正文标题）。
- `scope` / `excludeScope` 为书名或书源 URL 的子串匹配，留空表示全部；与上游 `replace_rules` 语义一致。
- `settings.replaceEnabled=false` 时暂停全部规则；规则改动实时生效，正文缓存保存原始文本。
- 正则替换有超时保护：`timeoutMillisecond` 到期返回原文，避免灾难性回溯阻塞服务线程。

## 设置

目前使用以下键，前端与阅读器共享：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `replaceEnabled` | boolean | 全局净化开关，默认 `true` |
| `theme` | string | 默认阅读主题 `ink/paper/eye/black` |
| `fontFamily` | string | `serif/kai/sans` |
| `fontSize` | number | 16–30 |
| `lineHeight` | number | 1.6–2.2 |
| `paraGap` | number | 0.4–1.6 |
| `pageWidth` | number | 620–880 |
| `mode` | string | `paged/scroll` |
| `cacheEnabled` | boolean | 是否缓存正文到 SQLite，默认 `true` |
| `cacheChapterDays` | number | 正文缓存保留天数，默认 30，范围 1–3650 |
| `appTheme` | string | 应用整体主题 `ink/slate/black/paper/eye` |
| `chineseScript` | string | 正文/章节标题简繁转换：`off`（默认）/`simplified`/`traditional`；只影响显示，不修改正文缓存 |
| `float` | object | 浮窗阅读配置，见下表 |

浮窗配置 `float`：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `preset` | string | `clear/dark/light/custom` 背景预设 |
| `bgColor` / `bgOpacity` | string / number | 背景色与不透明度（0–1） |
| `textColor` | string | 正文颜色 |
| `fontFamily` / `fontSize` / `lineHeight` | string / number / number | 字体、字号（12–30）、行高（1.4–2.4） |
| `autoFontSize` | boolean | 字号随浮窗宽度等比缩放 |
| `outline` | boolean | 文字描边 / 阴影 |
| `alwaysOnTop` | boolean | 浮窗置顶 |
| `emergencyHide` | boolean | 老板键隐藏浮窗时同时隐藏主窗口 |
| `hotkey` | string | 全局老板键，默认 `Ctrl+Alt+X` |
| `wheel` / `clickZones` | boolean | 滚轮翻页 / 点击左右区域翻页 |
| `progress` | string | 进度显示位置 `bottom/top/hidden` |
| `width` / `height` | number | 浮窗默认尺寸，最小 300×200 |

`PUT /api/settings` 只合并传入的键，不会清空其他设置。

## CF 网页验证

- 直连 OkHttp 超时为 connect 10s / read 30s / call 30s；返回 403/412/503 或超时后自动回退 WebView。
- 先由隐藏 WebView 自动过挑战（20s fallback，最多两次）；失败后复用同一个 WebView 会话并**真正显示**「完成安全验证」窗口（置顶），由用户点一次验证。
- 阅读页加载失败时显示「网页验证」按钮，调用 `/api/sources/verify`：打开置顶的可见 WebView，完成后关闭窗口收集 Cookie，并把 host 标记为 WebView 抓取，后续章节复用该会话，不再先等 OkHttp。
- 简繁转换由 opencc4j（OpenCC 词表）完成，默认 `off`；转换只发生在 API 返回时，原始章节缓存不变。`java.t2s()/s2t()` 也已接入同一转换器。

## 浮窗阅读

浮窗是 Tauri 壳创建的独立原生窗口（label `float`），不是浏览器弹窗：

- 无边框、透明背景、置顶（可关）、跳过任务栏并设置 `WS_EX_TOOLWINDOW`，隐藏后不进入 Alt+Tab。
- 内容通过 `/bootstrap?token=...&redirect=/float?...` 加载，与主窗口共用 JVM 服务、Cookie 与数据库。
- 拖动/缩放由页面上的 8 个热区调用壳内 `start_dragging` / `start_resize_dragging`，最小尺寸 300×200；位置与大小写入 `<app_data>/float-window.json`，下次打开恢复。
- 老板键默认 `Ctrl+Alt+X`，可在设置页录入并实时重新注册；注册失败时设置页显示错误。
- 浮窗与主阅读器通过 `BroadcastChannel('legado:float-sync')` 双向同步章节与页码，进度同时写入 `/api/books/progress`。
- `/api/float/*` 由 Kotlin 服务端代理到桌面壳的桥（`X-Bridge-Token`），仅桌面端可用；浏览器调试时返回“浮窗功能需要通过桌面端启动”。

## 缓存

- 正文首次获取后写入 `chapter_cache`（按 `version` 兼容解析结构），阅读时优先内存、再磁盘。
- 网络失败时回退到过期正文缓存，支持离线阅读；`cacheEnabled=false` 时不读写磁盘缓存。
- 正文缓存保存原始文本/块，替换规则仍在返回时实时应用。
- 封面/插图缓存位于 `data/cache/covers`，统计与清理走 `/api/cache`。
- 删除书籍或换源会清理旧书的章节缓存与书签。

## 示例

```bash
# 启动（fixture 站点）
./gradlew :server:run --args="--port 8099 --token dev --static-dir examples/fixture-site"

# 导入书源（Cookie 由 bootstrap 获取后带上）
curl -b "sid=dev" -H "Content-Type: application/json" \
  -d @examples/fixture-source.json http://127.0.0.1:8099/api/sources/import

# 搜索
curl -b "sid=dev" -H "Content-Type: application/json" \
  -d '{"keyword":"长夜"}' http://127.0.0.1:8099/api/search

# 加书架（会抓目录）
curl -b "sid=dev" -H "Content-Type: application/json" \
  -d '{"bookUrl":"http://127.0.0.1:8099/book1.html"}' http://127.0.0.1:8099/api/books

# 目录 / 正文
curl -b "sid=dev" "http://127.0.0.1:8099/api/books/chapters?url=http://127.0.0.1:8099/book1.html"
curl -b "sid=dev" "http://127.0.0.1:8099/api/books/content?url=http://127.0.0.1:8099/book1.html&index=0"
```

## 测试

`server/src/test/kotlin/io/legado/desktop/server/ApiIntegrationTest.kt`：

- 启动临时 SQLite + NanoHTTPD + JDK fixture 站点
- 导入书源 → 搜索 → 加书架 → 目录 → 正文 → 保存进度
- 导入替换规则 → 断言标题/正文替换、全局开关、设置读写与统计
- 封面/正文图片：fixture 校验 UA/Referer，`coverDecodeJs` 与 `imageDecode` 返回新图，断言代理字节与缓存目录
- 书签：新增 → 列表 → 删除
- 缓存：正文缓存写入/读取/清理、封面缓存清理、`cacheEnabled=false` 不落盘
- 正文 `blocks`：断言文本块与图片块（绝对地址、alt）
- 断言响应码、数量、书名、章节数与正文内容

## 已知限制

1. 搜索在请求线程内 `runBlocking`，所有启用源并发；没有 SSE/流式结果。
2. 正文内存缓存 + SQLite 磁盘缓存；尚未实现章节缓存容量上限，清理需要手动触发。
3. 未实现备份导入导出、WebDAV、PDF/EPUB 资源代理。
4. 加书架时按 `bookSourceUrl` 前缀匹配书源；同源多书源时取第一个。
5. 删除书源不级联删除书籍；后续补一致性策略。
6. 替换规则的 `replacement` 暂不支持上游的 `@js:` 动态替换。
7. 图片缓存没有容量上限，需手动清理。
8. 正文图片在分页模式按固定比例预留高度，极长竖图会按比例缩放而不是按真实宽高排版。
