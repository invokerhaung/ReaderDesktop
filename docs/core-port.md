# Core 落地状态（Phase 0.1）

## 已完成

| 能力 | 实现 |
| --- | --- |
| 规则引擎 | 上游 `AnalyzeRule`、`AnalyzeByJSoup`、`AnalyzeByJsonPath`、`AnalyzeByXPath`、`AnalyzeByRegex`、`RuleAnalyzer/RuleCombiner` 已复制并去 Android 化 |
| JS 引擎 | `:rhino` 模块（Rhino + 协程），移除 Android 类与 LruCache 依赖 |
| URL 解析 | `AnalyzeUrl` 支持 `@js:`/`<js>`、`{{key}}`/`{{page}}`、URL 选项 JSON（method/headers/body/charset/retry/webView/js） |
| HTTP | OkHttp 5，CookieStore 内存实现，TLS 宽松校验，GET/POST 表单与 JSON，charset 解码 |
| 实体 | `BookSource` / `Book` / `BookChapter` / `SearchBook` + 规则实体，去 Room/Parcelable |
| 编排 | `SourceEngine`：search / bookInfo / chapters / content；目录支持 `nextTocUrl` 多页；正文输出文本 + 文本/图片块（`contentDocument`） |
| 真实源验证 | 笔趣阁 byqg.cc：搜索 12/12、目录 1421/1421、正文去空白完全一致，详见 [golden-diff-byqg.md](golden-diff-byqg.md) |
| CLI | `search` / `info` / `chapters` / `content` / `json-search` |
| 测试 | JDK HttpServer fixture，覆盖搜索、详情、目录、正文、URL 相对路径、关键词编码、Rhino `java.md5Encode` |

## 运行

```bash
./gradlew :cli:installDist
./gradlew :server:run --args="--port 8099 --token dev --static-dir examples/fixture-site"

java -cp "cli/build/install/cli/lib/*" io.legado.desktop.cli.MainKt \
  search examples/fixture-source.json 长夜
```

## 已知限制

1. **WebView 请求未启用**：`AnalyzeUrl` 遇到 `webView: true` 会抛异常；后续接 Tauri WebView2 / JCEF，或桌面端 sidecar。
2. **JS 扩展只覆盖常用子集**：`java.ajax/connect/base64/md5/encodeURI/htmlFormat/timeFormat` 等；文件、加密、字体、登录 UI 等尚未移植。
3. **jsLib 未共享 scope**：`getShareScope` 返回 null，每个脚本独立执行；依赖 jsLib 全局变量的源可能失败。
4. **发现页未实现**：只有 search；`exploreUrl` 与筛选控件待 Phase 2。
5. **目录已支持 `nextTocUrl` 多页**；正文翻页是简单串行循环，搜索分页/并发解析未移植。站点不稳定时依赖 HTTP/1.1 + 默认重试 2 次 + 400ms 页间延迟。
6. **字符集**：支持 URL 选项与响应头 charset；尚未解析 HTML `<meta charset>`，部分 GBK 站点可能乱码。
7. **Cookie**：仅内存；未接数据库、内置浏览器同步、`loginUrl` / `loginCheckJs`。
8. **Cronet / 代理 / TLS 指纹**：未移植；目前使用 OkHttp 默认栈 + 宽松证书校验。
9. **包名**：复制代码暂时保留 `io.legado.app.*`，模块隔离；后续可统一迁移到 `io.legado.desktop.core.*`。
10. **单测覆盖**：fixture 覆盖 CSS 规则、一个 JS 规则与正文图片块；真实书源的索引语法、`@` 链式、`##` 替换、GBK 表单、图片/JSON 规则需要下一轮补测。
11. **正文图片排版**：只读取 `src/data-src/...` 并解析绝对地址，尚未处理 `imageStyle` 的 TEXT 内联排版与图片尺寸探测；分页端按比例预留高度。

## 下一步

- Phase 0.1 收尾：补 `##匹配##替换`、索引语法、`@` 链式、GBK meta、POST 表单编码测试。
- Phase 0.2：Vue DOM 分页原型（已有设计稿）。
- Phase 0.3：CF 请求头保真实验（OkHttp / Apache HttpClient 5 / java.net.http）。
- Phase 1：文件书、缓存、配置、Room→sqlite-jdbc、HTTP 服务 API。
