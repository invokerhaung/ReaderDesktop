# 上游来源与同步策略

## 来源

| 项 | 值 |
| --- | --- |
| 项目 | Legado（开源阅读） |
| 本地快照 | `D:\homework\legado_self` |
| Commit | `7640c219a` |
| 许可证 | GPLv3 |
| 使用方式 | 复制源码到本仓库后适配，**不修改上游** |

## 原则

1. 只复制需要的文件，不整包搬迁。
2. 上游文件保持原样；所有改动发生在本仓库的副本中。
3. 每个批次在下方登记，便于上游升级时人工 diff。
4. 不把 Android 端改造成本仓库的依赖；两端暂时独立演进。

## 批次登记

### Batch 1（Phase 0.1，已落地 2026-10-30）

| 上游目录 | 文件数 | 目标 | 主要改动 |
| --- | --- | --- | --- |
| `modules/rhino` | 26 | `rhino/` | Android 库改为 Kotlin JVM；移除 LruCache/Context/Android 类名 |
| `model/analyzeRule` | 10 复制 + `AnalyzeUrl` 重写 | `core/.../model/analyzeRule` | TextUtils/Base64/Keep；去掉 WebView/Glide/MediaItem/Cronet |
| `utils` 子集 | ~8 | `core/.../utils` | Gson、URL、Escape、MD5、编码、日志；去 Android 网络/上下文 |
| `data/entities` | 重写 | `core/.../data/entities` | 去 Room/Parcelable/UI 方法，保留规则字段 |
| `help/http` | 重写 | `core/.../help/http` | OkHttp + 内存 CookieStore |
| `help/source` | 重写 | `core/.../help/source` | `getShareScope` 暂返回 null |
| `model/webBook` | 重写 | `core/.../model/SourceEngine.kt` | 搜索/详情/目录/正文的简化编排 |

### 明确不复制

`ui/`、`base/`、`service/`、`data/AppDatabase.kt`、`data/dao/`、`model/ReadBook.kt`、
`model/BookCover.kt`、`lib/cronet`、`lib/theme`、`lib/permission`、`help/glide`、`help/exoplayer`。

## 同步流程

1. 记录上游新 commit。
2. 对登记过的文件执行 `git diff <old> <new> -- <path>`。
3. 人工判断是否移植；移植后在批次表补充说明。
4. 不直接 merge 上游历史。
