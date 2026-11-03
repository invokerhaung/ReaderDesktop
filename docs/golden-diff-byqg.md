# Golden Diff · 笔趣阁（byqg.cc）

- 日期：2026-10-30
- 书源：`https://shuyuan-api.yiove.com/import/book-source/1d6cc87c-88ac-4aed-a711-4984ac88f7e5`
- 书源名：`笔趣阁(byqg.cc)`（`https://www.byqg.cc`）
- 测试书：`全民求生：魔法废土世界的暴徒` / 木屋键盘侠
- Android：MuMu 模拟器，Android 15，ABI `x86_64,arm64-v8a,x86`，包 `io.xiatou.reader.release`，版本 `3.26.060310`
- 桌面端：`:core` 引擎 + `:cli`（Java 17）

## 结果

| 环节 | Android | 桌面 core | 差异 |
| --- | --- | --- | --- |
| 搜索「全民求生」 | 12 条 | 12 条 | **0**（name / author / bookUrl / wordCount 全等） |
| 目录 | 1421 章 | 1421 章 | **0**（标题、URL path 全等） |
| 正文 · 第 1 章 | 2897 字（去空白） | 2897 字 | **完全一致** |

> 搜索与目录对比前会统一 URL 为 path；正文对比前去掉所有空白、全角空格与中英文引号。

## 复现方式

Android 侧：

```bash
adb connect 127.0.0.1:16384
adb forward tcp:1122 tcp:1122
adb forward tcp:1123 tcp:1123     # HTTP 端口 + 1
# 在应用「我的 -> Web 服务」中启动服务
curl "http://127.0.0.1:1122/getBookSources"
curl "http://127.0.0.1:1122/getChapterList?url=<bookUrl>"
curl "http://127.0.0.1:1122/getBookContent?url=<bookUrl>&index=0"
# 搜索：ws://127.0.0.1:1123/searchBook，消息 {"key":"全民求生"}
```

桌面侧：

```bash
./gradlew :cli:installDist
java -cp "cli/build/install/cli/lib/*" io.legado.desktop.cli.MainKt json-search  tmp/source-byqg.json 全民求生
java -cp "cli/build/install/cli/lib/*" io.legado.desktop.cli.MainKt json-chapters tmp/source-byqg.json <bookUrl>
java -cp "cli/build/install/cli/lib/*" io.legado.desktop.cli.MainKt content      tmp/source-byqg.json <chapterUrl> <bookUrl>
node scripts/golden-diff.mjs \
  --android-search tmp/android-search.json --our-search tmp/our-search.json \
  --android-chapters tmp/android-chapters.json --our-chapters tmp/our-chapters.json \
  --android-content tmp/android-content0.json --our-content tmp/our-content0.txt \
  --origin https://www.byqg.cc
```

## 发现

1. **相对 URL、`@css:` 规则、JS 翻页均正常**：书源使用 `/search/{{key}}.html`、`@css:div.bookbox`、`<js>baseUrl.replace(...)</js>` 与 `nextTocUrl` 脚本，桌面端输出与 Android 一致。
2. **两端的 author 都为空**：书源作者规则 `div.author:eq(0)@text##作者：` 在该站点结构上未命中；桌面端行为与 Android 保持了一致（这是源规则问题，不是移植偏差）。
3. **wordCount 两端都是 `作者：xxx`**：同一规则取错了 div，但双端一致。
4. **目录第 2/3 页站点不稳定**：首次请求会出现 HTTP/2 stream timeout / TLS reset。处理：
   - OkHttp 强制 **HTTP/1.1**；
   - 默认重试 2 次；
   - 目录页之间延迟 400ms。
   之后 1421 章全部取到。
5. **Android WebSocket 端口是 HTTP 端口 + 1（1122 → 1123）**，不是文档里写的 1235。

## 结论

该源在搜索、目录、正文三个环节均达到 **0 diff**。Phase 0.1 的真实书源验证完成 **1 / 20**，仍需补更多源覆盖 `##` 替换、索引语法、`@` 链式、GBK、POST 等分支。
