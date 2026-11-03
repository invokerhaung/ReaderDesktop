/* 设计原型假数据：仅用于界面演示，不对应真实书源或内容。 */

const BOOKS = [
  {
    id: 'changye',
    title: '长夜行',
    author: '沈砚',
    cover: 'assets/changye.png',
    group: '在读',
    progress: 42,
    chapter: '第 37 章 风雪夜归人',
    latest: '第 92 章 灯下故人',
    updated: true,
    cached: 12,
    words: '86 万字',
    status: '连载',
    intro: '一封迟到三十年的信，把两个本该死去的人重新带回青石镇。雪夜里，旧刀、孤灯与未竟的约定一起醒来。'
  },
  {
    id: 'xinghai',
    title: '星海拾遗',
    author: '林渡',
    cover: 'assets/xinghai.png',
    group: '在读',
    progress: 18,
    chapter: '第 12 章 静默轨道',
    latest: '第 64 章 远日点',
    updated: false,
    cached: 3,
    words: '52 万字',
    status: '连载',
    intro: '在废弃的深空中继站，一名拾荒者收到了一段来自百年前的求救信号。'
  },
  {
    id: 'wuhai',
    title: '雾海纪事',
    author: '周迟',
    cover: 'assets/wuhai.png',
    group: '未分组',
    progress: 0,
    chapter: '尚未开始',
    latest: '第 28 章 渡口',
    updated: true,
    cached: 0,
    words: '34 万字',
    status: '连载',
    intro: '雾起时，渡口的灯会亮三次。镇上的人说，那是给回不来的人照路。'
  },
  {
    id: 'guanxing',
    title: '观星者',
    author: '顾南枝',
    cover: 'assets/guanxing.png',
    group: '在读',
    progress: 67,
    chapter: '第 48 章 浑天',
    latest: '第 71 章 岁差',
    updated: false,
    cached: 28,
    words: '61 万字',
    status: '连载',
    intro: '钦天监最年轻的监正，在星图上发现了一颗不该存在的星。'
  },
  {
    id: 'jianyudeng',
    title: '剑与灯',
    author: '陆青崖',
    cover: 'assets/jianyudeng.png',
    group: '已完本',
    progress: 100,
    chapter: '番外 · 灯下',
    latest: '全书完',
    updated: false,
    cached: 42,
    words: '48 万字',
    status: '完本',
    intro: '一个不许拔剑的剑客，和一盏不许熄灭的灯，走完了整条长街。'
  },
  {
    id: 'shanhe',
    title: '山河故人',
    author: '许砚秋',
    cover: null,
    tone: 'linear-gradient(150deg,#3a2f26,#191614)',
    glyph: '山',
    group: '未分组',
    progress: 5,
    chapter: '第 3 章 旧驿',
    latest: '第 40 章 归途',
    updated: true,
    cached: 0,
    words: '29 万字',
    status: '连载',
    intro: '故人自北而来，带着一整个冬天的雪和一个不能说的名字。'
  },
  {
    id: 'xueluo',
    title: '雪落长安',
    author: '温故',
    cover: null,
    tone: 'linear-gradient(150deg,#26313b,#12161a)',
    glyph: '雪',
    group: '在读',
    progress: 31,
    chapter: '第 22 章 夜禁',
    latest: '第 55 章 春信',
    updated: false,
    cached: 8,
    words: '44 万字',
    status: '连载',
    intro: '长安的雪落在朱雀大街上，也落在每一个不肯回家的人肩上。'
  },
  {
    id: 'wuming',
    title: '无名之辈',
    author: '陈默',
    cover: null,
    tone: 'linear-gradient(150deg,#332b33,#151218)',
    glyph: '无',
    group: '已完本',
    progress: 100,
    chapter: '后记',
    latest: '全书完',
    updated: false,
    cached: 36,
    words: '71 万字',
    status: '完本',
    intro: '没有名字的人，做了一件不需要名字的事。'
  },
  {
    id: 'beijing',
    title: '北境来信',
    author: '沈砚',
    cover: null,
    tone: 'linear-gradient(150deg,#2a3330,#121715)',
    glyph: '北',
    group: '未分组',
    progress: 0,
    chapter: '尚未开始',
    latest: '第 19 章 风雪',
    updated: true,
    cached: 0,
    words: '21 万字',
    status: '连载',
    intro: '每周一封信，从北境寄来。信里从不提战事，只提雪。'
  },
  {
    id: 'dengxia',
    title: '灯下故人',
    author: '周迟',
    cover: null,
    tone: 'linear-gradient(150deg,#3a2c25,#171211)',
    glyph: '灯',
    group: '已完本',
    progress: 100,
    chapter: '终章',
    latest: '全书完',
    updated: false,
    cached: 29,
    words: '38 万字',
    status: '完本',
    intro: '灯亮着，人就还在。灯灭了，故事才真正开始。'
  }
]

const SOURCES = [
  { key: 'moxiang', name: '墨香居', url: 'https://moxiang.example.com', group: '主力', enabled: true, status: 'ok', latency: 182, type: '文本', note: '稳定，正文干净' },
  { key: 'yunyue', name: '云阅书城', url: 'https://yunyue.example.com', group: '主力', enabled: true, status: 'ok', latency: 240, type: '文本', note: '搜索较快' },
  { key: 'qingjian', name: '青简阁', url: 'https://qingjian.example.com', group: '主力', enabled: true, status: 'ok', latency: 168, type: '文本', note: '目录规则需注意分卷' },
  { key: 'shiguang', name: '拾光书屋', url: 'https://shiguang.example.com', group: '备用', enabled: true, status: 'ok', latency: 412, type: '文本', note: '偶发 429' },
  { key: 'yehang', name: '夜航船', url: 'https://yehang.example.com', group: '备用', enabled: true, status: 'warn', latency: 620, type: '文本', note: '需要登录 Cookie' },
  { key: 'jiushu', name: '旧书铺', url: 'https://jiushu.example.com', group: '备用', enabled: true, status: 'ok', latency: 355, type: '文本', note: '老站，编码 GBK' },
  { key: 'xinghuo', name: '星火文学', url: 'https://xinghuo.example.com', group: '正版', enabled: false, status: 'off', latency: 0, type: '文本', note: '已停用' },
  { key: 'shanhai', name: '山海经阁', url: 'https://shanhai.example.com', group: '正版', enabled: true, status: 'warn', latency: 890, type: '文本', note: 'Cloudflare 验证' }
]

const SEARCH_RESULTS = [
  {
    bookId: 'changye', title: '长夜行', author: '沈砚', cover: 'assets/changye.png',
    latest: '第 92 章 灯下故人', words: '86 万字', intro: '雪夜、旧刀与一封迟到三十年的信。',
    sources: [{ name: '墨香居', latency: 182 }, { name: '云阅书城', latency: 240 }, { name: '青简阁', latency: 168 }]
  },
  {
    bookId: 'changye', title: '长夜行（精校版）', author: '沈砚', cover: 'assets/changye.png',
    latest: '第 90 章 归途', words: '85 万字', intro: '精校排版，段落完整。',
    sources: [{ name: '拾光书屋', latency: 412 }, { name: '旧书铺', latency: 355 }]
  },
  {
    bookId: 'xinghai', title: '星海拾遗', author: '林渡', cover: 'assets/xinghai.png',
    latest: '第 64 章 远日点', words: '52 万字', intro: '深空中继站与百年前的求救信号。',
    sources: [{ name: '云阅书城', latency: 240 }, { name: '青简阁', latency: 168 }]
  },
  {
    bookId: 'guanxing', title: '观星者', author: '顾南枝', cover: 'assets/guanxing.png',
    latest: '第 71 章 岁差', words: '61 万字', intro: '星图上出现了一颗不该存在的星。',
    sources: [{ name: '墨香居', latency: 182 }, { name: '夜航船', latency: 620 }]
  },
  {
    bookId: 'wuhai', title: '雾海纪事', author: '周迟', cover: 'assets/wuhai.png',
    latest: '第 28 章 渡口', words: '34 万字', intro: '雾起时，渡口的灯会亮三次。',
    sources: [{ name: '旧书铺', latency: 355 }]
  },
  {
    bookId: 'jianyudeng', title: '剑与灯', author: '陆青崖', cover: 'assets/jianyudeng.png',
    latest: '全书完', words: '48 万字', intro: '不许拔剑的剑客与不许熄灭的灯。',
    sources: [{ name: '墨香居', latency: 182 }, { name: '拾光书屋', latency: 412 }, { name: '山海经阁', latency: 890 }]
  }
]

const CHAPTERS = [
  '第 31 章 旧驿', '第 32 章 夜行', '第 33 章 刀痕', '第 34 章 故人',
  '第 35 章 灯下', '第 36 章 雪意', '第 37 章 风雪夜归人', '第 38 章 一壶酒',
  '第 39 章 磨刀', '第 40 章 镇口', '第 41 章 来客', '第 42 章 旧事',
  '第 43 章 北边', '第 44 章 信', '第 45 章 长街', '第 46 章 更鼓',
  '第 47 章 未竟', '第 48 章 灯灭', '第 49 章 雪停', '第 50 章 归途'
]

const CHAPTER_TEXT = [
  '雪落了一夜，青石镇的屋檐都压低了三分。',
  '沈砚推门进来时，肩上积着薄薄一层白。他没有抖，只把门轻轻掩上，像是怕惊动屋里那盏将熄未熄的灯。',
  '灯下坐着一个人，背对着门，手里握着一卷旧书。书页翻得很慢，慢到能听见纸边擦过指腹的声音。',
  '“你迟了。”那人说。',
  '“路上遇见一匹马。”沈砚解下佩刀，放在桌角，“马上没有人，鞍下压着一封信。”',
  '屋里静了一瞬。风从窗缝里钻进来，灯焰向一侧偏去，又慢慢立直。',
  '那人终于回头。他的眉眼很淡，像被许多年的风雪磨过，只剩下一点温和的轮廓。',
  '“信上写了什么？”',
  '“只有八个字。”沈砚从怀里取出那张被体温焐软的纸，“长夜将尽，故人当归。”',
  '灯火忽然亮了一亮。',
  '老人看着那八个字，很久没有言语。窗外雪声细密，远处传来更鼓，一下，又一下，敲在空荡荡的长街上。',
  '“三十年了。”他终于开口，声音低得像是说给自己听，“我以为他早就死在北边。”',
  '沈砚没有接话。他知道有些名字一旦说出口，屋里的灯就会灭。',
  '老人把信折好，放进书页之间，动作很慢，却比方才稳。',
  '“明日卯时，替我备一壶酒。”他站起身，走向里屋，“再把这把刀磨一磨。”',
  '沈砚应了一声。他低头看了看桌上的刀，刀鞘上有一道旧痕，像一条极细的河。',
  '雪还在下。镇口那盏孤灯被风吹得摇晃，照着一条没有人走过的路。',
  '而路的尽头，似乎有脚步声，正一步一步，向这里来。'
]

/* 书源编辑器：分区 + 双列布局。wide 字段占满两列。 */
const EDITOR_SCHEMA = {
  overview: [
    { section: '基础信息', note: '显示在书架、搜索与书源列表' },
    { label: '书源名称', value: s => s.name, required: true },
    { label: '分组', value: s => s.group, hint: '逗号分隔多个分组' },
    { label: '书源 URL', value: s => s.url, mono: true, wide: true, required: true, hint: 'http/https 地址' },
    { label: '类型', value: s => s.type },
    { label: '并发率', value: () => '1000', hint: '毫秒，0 表示不限' },
    { section: '请求配置', note: '所有规则共用' },
    { label: '请求头', value: () => '{\n  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"\n}', mono: true, area: true, wide: true, hint: 'JSON；支持 {{变量}}' },
    { label: '登录地址', value: () => '', wide: true, hint: '留空表示无需登录' },
    { label: '响应超时', value: () => '180000', hint: '毫秒' },
    { section: '备注' },
    { label: '注释', value: s => s.note, area: true, wide: true }
  ],
  search: [
    { section: '搜索入口' },
    { label: '搜索地址', value: s => `${s.url}/search?q={{key}}&page={{page}}`, mono: true, area: true, wide: true, required: true, hint: '支持 {{key}} 与 {{page}}' },
    { label: '页码规则', value: () => '', mono: true, hint: '留空按地址模板翻页' },
    { section: '列表与字段' },
    { label: '列表规则', value: () => 'class.book-list@tag.li', mono: true, wide: true, required: true, hint: '支持 class./tag./id. 与 @ 链式' },
    { label: '书名规则', value: () => 'tag.h3@text', mono: true },
    { label: '作者规则', value: () => 'class.author@text', mono: true },
    { label: '封面规则', value: () => 'tag.img@data-src', mono: true },
    { label: '详情链接', value: () => 'tag.a@href', mono: true },
    { label: '分组规则', value: () => 'class.tag@text', mono: true }
  ],
  explore: [
    { section: '发现入口' },
    { label: '发现地址', value: () => '@js:java.getExploreUrls()', mono: true, area: true, wide: true, hint: '可填 URL 或 @js 脚本' },
    { label: '分类规则', value: () => 'class.nav@tag.a', mono: true },
    { section: '列表与字段' },
    { label: '列表规则', value: () => 'class.item', mono: true, wide: true },
    { label: '书名规则', value: () => 'tag.h3@text', mono: true },
    { label: '作者规则', value: () => 'class.author@text', mono: true },
    { label: '封面规则', value: () => 'tag.img@src', mono: true },
    { label: '详情链接', value: () => 'tag.a@href', mono: true },
    { label: '下一页', value: () => 'class.next@href', mono: true }
  ],
  detail: [
    { section: '基础字段' },
    { label: '书名规则', value: () => 'tag.h1@text', mono: true },
    { label: '作者规则', value: () => 'class.author@text', mono: true },
    { label: '种类规则', value: () => 'class.kind@text', mono: true },
    { label: '字数规则', value: () => 'class.count@text', mono: true },
    { section: '展示' },
    { label: '封面规则', value: () => 'tag.img@src', mono: true, wide: true },
    { label: '简介规则', value: () => 'class.intro@html', mono: true, area: true, wide: true },
    { label: '目录链接', value: () => 'class.catalog@href', mono: true, wide: true }
  ],
  toc: [
    { section: '目录入口' },
    { label: '目录地址', value: () => '', mono: true, wide: true, hint: '留空使用详情页地址' },
    { label: '下一页规则', value: () => 'class.next@href', mono: true },
    { section: '章节' },
    { label: '列表规则', value: () => 'class.chapter-list@tag.a', mono: true, wide: true, required: true },
    { label: '章节名', value: () => 'text', mono: true },
    { label: '章节链接', value: () => 'href', mono: true },
    { label: '是否收费', value: () => 'class.vip', mono: true }
  ],
  content: [
    { section: '正文' },
    { label: '正文规则', value: () => 'class.read-content@html', mono: true, area: true, wide: true, required: true },
    { label: '翻页规则', value: () => 'class.next@href', mono: true },
    { label: '下一页文案', value: () => '下一章', mono: true },
    { section: '净化' },
    { label: '净化规则', value: () => '##广告|本章未完.*##', mono: true, wide: true, hint: '##匹配##替换 语法' },
    { label: '替换规则', value: () => '', mono: true, area: true, wide: true }
  ],
  other: [
    { section: '脚本' },
    { label: '登录检查 JS', value: () => 'cookie.getKey("token") != null', mono: true, area: true, wide: true },
    { label: '封面解密 JS', value: () => '', mono: true, area: true, wide: true },
    { section: '变量' },
    { label: '自定义变量', value: () => '{}', mono: true, area: true, wide: true, hint: 'JSON；可在规则中用 {{key}} 引用' }
  ]
}

const COMMANDS = [
  { id: 'shelf', label: '打开书架', hint: '视图', icon: 'i-book' },
  { id: 'search', label: '搜索书籍', hint: 'Ctrl K', icon: 'i-search' },
  { id: 'sources', label: '管理书源', hint: '视图', icon: 'i-database' },
  { id: 'reader', label: '继续阅读《长夜行》', hint: '阅读', icon: 'i-arrow-right' },
  { id: 'theme', label: '切换阅读主题', hint: '外观', icon: 'i-moon' },
  { id: 'import', label: '导入本地书籍', hint: 'TXT / EPUB / PDF', icon: 'i-upload' },
  { id: 'settings', label: '打开设置', hint: '视图', icon: 'i-sliders' },
  { id: 'importSources', label: '导入书源', hint: '书源', icon: 'i-upload' },
  { id: 'backup', label: '备份与恢复', hint: '数据', icon: 'i-download' },
  { id: 'welcome', label: '首次启动向导', hint: '帮助', icon: 'i-compass' },
  { id: 'update', label: '检查更新', hint: '版本', icon: 'i-refresh' },
  { id: 'about', label: '关于墨读', hint: '信息', icon: 'i-book' }
]

const LOCAL_BOOKS = [
  { id: 'guwen', title: '古文观止', author: '吴楚材', format: 'EPUB', size: '2.4 MB', meta: '12 卷 · 222 篇', last: '今天 14:20', progress: 37, tone: 'linear-gradient(150deg,#3a2f26,#191614)', glyph: '古' },
  { id: 'beijing-note', title: '北境手记', author: '佚名', format: 'TXT', size: '860 KB', meta: '未分章 · 12.6 万字', last: '昨天 22:08', progress: 68, tone: 'linear-gradient(150deg,#26313b,#12161a)', glyph: '北' },
  { id: 'shanhai-pdf', title: '山海图志', author: '郭璞', format: 'PDF', size: '18.2 MB', meta: '扫描版 · 236 页', last: '10 月 28 日', progress: 12, tone: 'linear-gradient(150deg,#2a3330,#121715)', glyph: '山' },
  { id: 'changye-clean', title: '长夜行 · 精校', author: '沈砚', format: 'EPUB', size: '3.1 MB', meta: '92 章 · 86 万字', last: '10 月 26 日', progress: 100, tone: 'linear-gradient(150deg,#3a2c25,#171211)', glyph: '夜' },
  { id: 'shenglv', title: '声律启蒙', author: '车万育', format: 'TXT', size: '420 KB', meta: '30 章 · 3.2 万字', last: '10 月 20 日', progress: 0, tone: 'linear-gradient(150deg,#332b33,#151218)', glyph: '声' },
  { id: 'wuhai-pdf', title: '雾海纪事', author: '周迟', format: 'PDF', size: '8.6 MB', meta: '插图版 · 128 页', last: '10 月 18 日', progress: 45, tone: 'linear-gradient(150deg,#2a2f2a,#121714)', glyph: '雾' }
]

const STATS = [42, 68, 35, 90, 55, 120, 76]
const STAT_LABELS = ['周四', '周五', '周六', '周日', '周一', '周二', '周三']

const SOURCE_MATCHES = [
  { name: '墨香居', latest: '第 92 章 灯下故人', words: '86 万字', latency: 182, score: 98, current: true },
  { name: '云阅书城', latest: '第 92 章 灯下故人', words: '86 万字', latency: 240, score: 96 },
  { name: '青简阁', latest: '第 90 章 归途', words: '85 万字', latency: 168, score: 91 },
  { name: '旧书铺', latest: '第 88 章 雪停', words: '84 万字', latency: 355, score: 84 }
]

const IMPORT_RESULTS = [
  { name: '墨香居', url: 'https://moxiang.example.com', group: '主力', status: 'new' },
  { name: '云阅书城', url: 'https://yunyue.example.com', group: '主力', status: 'dup' },
  { name: '青简阁', url: 'https://qingjian.example.com', group: '主力', status: 'new' },
  { name: '山海经阁', url: 'https://shanhai.example.com', group: '正版', status: 'error' }
]

const BACKUPS = [
  { name: 'backup_2026-10-30.zip', time: '今天 09:12', size: '3.8 MB', type: '自动', counts: { books: 24, sources: 8, bookmarks: 36, rules: 12 } },
  { name: 'backup_2026-10-23.zip', time: '10 月 23 日 09:12', size: '3.6 MB', type: '自动', counts: { books: 23, sources: 8, bookmarks: 34, rules: 12 } },
  { name: 'backup_2026-10-16.zip', time: '10 月 16 日 09:12', size: '3.5 MB', type: '手动', counts: { books: 23, sources: 7, bookmarks: 31, rules: 11 } }
]

const REMOTE_BACKUPS = [
  { name: 'backup_2026-10-30.zip', time: '今天 09:12', size: '3.8 MB' },
  { name: 'backup_2026-10-23.zip', time: '10 月 23 日 09:12', size: '3.6 MB' }
]

const CACHE_BOOKS = [
  { id: 'changye', title: '长夜行', cover: 'assets/changye.png', cached: 37, total: 92, size: '312 MB', status: '缓存中', progress: 40 },
  { id: 'guanxing', title: '观星者', cover: 'assets/guanxing.png', cached: 48, total: 71, size: '286 MB', status: '已缓存', progress: 68 },
  { id: 'xinghai', title: '星海拾遗', cover: 'assets/xinghai.png', cached: 12, total: 64, size: '64 MB', status: '已缓存', progress: 19 },
  { id: 'jianyudeng', title: '剑与灯', cover: 'assets/jianyudeng.png', cached: 42, total: 42, size: '208 MB', status: '已完本', progress: 100 }
]

const TASKS = [
  { id: 'cache', title: '缓存《长夜行》', detail: '37 / 92 章 · 312 MB', progress: 40, status: 'running', icon: 'i-download' },
  { id: 'backup', title: '上传备份到 WebDAV', detail: 'backup_2026-10-30.zip', progress: 72, status: 'running', icon: 'i-upload' },
  { id: 'verify', title: '验证书源', detail: '3 / 8 个书源', progress: 38, status: 'running', icon: 'i-globe' },
  { id: 'done', title: '更新目录《观星者》', detail: '71 章 · 刚刚完成', progress: 100, status: 'done', icon: 'i-check' }
]

const BOOKMARKS = [
  { chapter: 6, title: '第 37 章 风雪夜归人', excerpt: '雪落了一夜，青石镇的屋檐都压低了三分。', time: '今天 23:41' },
  { chapter: 4, title: '第 35 章 灯下', excerpt: '灯下坐着一个人，背对着门，手里握着一卷旧书。', time: '昨天 22:08' },
  { chapter: 1, title: '第 32 章 夜行', excerpt: '而路的尽头，似乎有脚步声，正一步一步，向这里来。', time: '10 月 28 日' }
]

const UPDATE_INFO = {
  current: '0.1.0',
  latest: '0.2.0',
  date: '2026-10-30',
  notes: [
    '新增书源导入向导与批量校验',
    '阅读器支持封面/滑动翻页与书签面板',
    '缓存管理与后台任务中心',
    '修复 Windows 高 DPI 下正文行距偏差'
  ]
}

const WELCOME_CARDS = [
  { id: 'sources', icon: 'i-database', title: '导入书源', desc: '支持 JSON 文件、URL 订阅与 legado:// 一键导入。' },
  { id: 'local', icon: 'i-folder', title: '导入本地书', desc: 'TXT、EPUB、PDF 直接拖进来，离线也能读。' },
  { id: 'backup', icon: 'i-download', title: '从备份恢复', desc: '读取 Legado 的 ZIP 备份，书架和进度一起带过来。' }
]
