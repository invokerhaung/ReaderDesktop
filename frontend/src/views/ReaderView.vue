<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  api,
  type Book,
  type Bookmark,
  type Chapter,
  type ChapterCacheJob,
  type Content,
} from '../api/client'
import { imageSrc } from '../api/cover'
import {
  chapterListKey,
  FLOAT_SYNC_CHANNEL,
  type FloatProgressMessage,
  type FloatSyncMessage,
} from '../api/floatReader'
import AppIcon from '../components/AppIcon.vue'

interface Block {
  type: 'h1' | 'p' | 'img'
  text?: string
  src?: string
  alt?: string
  height?: number
  index?: number
}

interface ReaderBlock {
  type: 'text' | 'image'
  text?: string
  src?: string
  alt?: string
  style?: string | null
}

interface ReaderSettings {
  theme: 'ink' | 'paper' | 'eye' | 'black'
  fontFamily: 'serif' | 'sans' | 'kai'
  fontSize: number
  lineHeight: number
  paraGap: number
  pageWidth: number
  mode: 'paged' | 'scroll'
}

const SETTINGS_KEY = 'legado.reader.settings'
const DEFAULTS: ReaderSettings = {
  theme: 'ink',
  fontFamily: 'serif',
  fontSize: 19,
  lineHeight: 1.9,
  paraGap: 0.9,
  pageWidth: 720,
  mode: 'paged',
}
const THEMES = [
  { id: 'ink', name: '墨夜', swatch: '#0f1013' },
  { id: 'paper', name: '纸白', swatch: '#f4efe4' },
  { id: 'eye', name: '护眼', swatch: '#e9e2cf' },
  { id: 'black', name: '纯黑', swatch: '#000000' },
] as const

const route = useRoute()
const router = useRouter()
const bookUrl = computed(() => {
  const raw = route.query.bookUrl
  return Array.isArray(raw) ? String(raw[0] ?? '') : String(raw ?? '')
})
const previewId = computed(() => {
  const raw = route.query.preview
  return Array.isArray(raw) ? String(raw[0] ?? '') : String(raw ?? '')
})
const isPreview = computed(() => !!previewId.value)

let syncChannel: BroadcastChannel | null = null
let applyingRemote = false
let lastLocalProgressAt = 0
let lastRemoteApplyAt = 0
let scrollSaveTimer: number | undefined
let loadSeq = 0
let pendingRemote: FloatProgressMessage | null = null
let suppressRemoteUntil = 0
/** 主窗口最近一次本地切章时刻；浮窗发出的进度若早于它，说明浮窗还没看到新章节。 */
let lastLocalChapterChangeAt = 0
/** 当前正文实际对应的章节，加载失败时用它回退目录高亮。 */
const displayedIndex = ref(0)
const tocListRef = ref<HTMLElement | null>(null)

function saveProgress(index: number, position?: number) {
  if (isPreview.value) return
  const chapterUrl = chapters.value[index]?.url
  void api.saveProgress(bookUrl.value, index, position, chapterUrl).catch(() => undefined)
}

function scrollPercent(): number {
  const element = scrollRef.value
  if (!element || element.scrollHeight <= element.clientHeight) return 0
  return Math.max(0, Math.min(1, element.scrollTop / (element.scrollHeight - element.clientHeight)))
}

function pagePercent(): number {
  if (pages.value.length <= 1) return 0
  return Math.max(0, Math.min(1, pageIndex.value / (pages.value.length - 1)))
}

function notifyProgress(chapterStart = false) {
  if (isPreview.value || !book.value) return
  const at = Date.now()
  lastLocalProgressAt = at
  const message: FloatProgressMessage = {
    type: 'progress',
    source: 'reader',
    bookUrl: bookUrl.value,
    index: currentIndex.value,
    page: settings.mode === 'paged' ? pageIndex.value : null,
    percent: settings.mode === 'paged' ? pagePercent() : scrollPercent(),
    chapterTitle: chapterTitle.value,
    at,
    chapterStart: chapterStart || undefined,
    chapterUrl: chapters.value[currentIndex.value]?.url,
    tocKey: tocKey.value,
  }
  syncChannel?.postMessage(message)
}

/** 本地切章一开始就通知浮窗，避免它继续停留在旧章节并回写旧进度。 */
function broadcastChapterStart(index: number, at: number) {
  if (isPreview.value || !book.value) return
  lastLocalProgressAt = at
  syncChannel?.postMessage({
    type: 'progress',
    source: 'reader',
    bookUrl: bookUrl.value,
    index,
    page: 0,
    percent: 0,
    chapterTitle: chapters.value[index]?.title ?? book.value.name,
    at,
    chapterStart: true,
    chapterUrl: chapters.value[index]?.url,
    tocKey: tocKey.value,
  } satisfies FloatProgressMessage)
}

function saveReadingPosition() {
  if (isPreview.value) return
  // 章内千分比：主窗口与浮窗宽度/分页不同，页码不能直接互用
  const position =
    settings.mode === 'paged' ? Math.round(pagePercent() * 1000) : undefined
  saveProgress(currentIndex.value, position)
  notifyProgress()
}

/** 另一端目录版本不同：重新拉取目录，并按 URL 重新映射当前章节。 */
async function reloadTocForSync(): Promise<boolean> {
  if (tocReloading) return false
  tocReloading = true
  try {
    const displayedUrl = chapters.value[displayedIndex.value]?.url
    const currentUrl = chapters.value[currentIndex.value]?.url
    const list = await api.chapters(bookUrl.value)
    if (!list.length) return false
    chapters.value = list
    if (book.value) book.value.totalChapterNum = list.length
    const remap = (url?: string) => (url ? list.findIndex((chapter) => chapter.url === url) : -1)
    const displayed = remap(displayedUrl)
    const current = remap(currentUrl)
    if (displayed >= 0) displayedIndex.value = displayed
    if (current >= 0) currentIndex.value = current
    return true
  } catch {
    return false
  } finally {
    tocReloading = false
  }
}

async function applyRemoteProgress(message: FloatProgressMessage) {
  if (isPreview.value || message.bookUrl !== bookUrl.value) return
  // 浮窗还没收到主窗口最近的切章通知，它发来的章节已过时，忽略（即使加载很慢也不会被拉回）
  if (
    typeof message.mainChangeAt === 'number' &&
    message.mainChangeAt < lastLocalChapterChangeAt
  ) {
    // 浮窗还在旧章节，回播一次主窗口状态，让它刷新目录并按 URL 追上
    notifyProgress(true)
    return
  }
  // 正在处理上一条远端进度时，后来的消息也要排队（很可能才是最新位置），不能丢
  if (applyingRemote) {
    if (!pendingRemote || (message.at ?? 0) >= (pendingRemote.at ?? 0)) pendingRemote = message
    return
  }
  // 本地刚切完章，浮窗可能还在回写旧章节进度；直接丢弃，避免目录高亮被拉回
  if (Date.now() < suppressRemoteUntil) {
    pendingRemote = null
    return
  }
  // 本地正在切章/排版时不能直接应用，先记下来，等加载完再处理
  if (loadingContent.value) {
    if (!pendingRemote || (message.at ?? 0) >= (pendingRemote.at ?? 0)) pendingRemote = message
    return
  }
  // 目录版本不同（新增/插入了章节）时，先按 URL 刷新目录再同步，避免用旧下标切到错误章节
  if (message.tocKey && message.tocKey !== tocKey.value) {
    const reloaded = await reloadTocForSync()
    if (!reloaded || (message.tocKey && message.tocKey !== tocKey.value)) {
      // 对方目录是旧版本，把主窗口的新目录状态发过去让它重载
      notifyProgress(true)
      return
    }
  }
  const targetIndex =
    message.chapterUrl && chapters.value.length
      ? chapters.value.findIndex((chapter) => chapter.url === message.chapterUrl)
      : message.index
  if (targetIndex < 0 || targetIndex >= chapters.value.length) return
  // 迟到的旧进度不覆盖本地刚翻到的位置
  if (message.at && message.at < lastLocalProgressAt) return
  applyingRemote = true
  try {
    if (targetIndex !== currentIndex.value) {
      await selectChapter(targetIndex, false)
      // 切章失败会回退到当前正文所在章节，此时不能再用远端百分比定位旧正文
      if (currentIndex.value !== targetIndex) return
    }
    if (settings.mode === 'paged' && typeof message.percent === 'number') {
      pageIndex.value = Math.min(
        Math.max(Math.round(message.percent * Math.max(0, pages.value.length - 1)), 0),
        pages.value.length - 1,
      )
    } else if (settings.mode === 'scroll' && typeof message.percent === 'number') {
      await nextTick()
      const element = scrollRef.value
      if (element) {
        const max = Math.max(0, element.scrollHeight - element.clientHeight)
        element.scrollTo({ top: message.percent * max })
      }
    }
    // 只保存远端位置，不再回播。否则两端分页宽度不同，回传的百分比会把
    // 刚翻到的页映射回上一页，表现为“翻页后马上回滚”。
    const position =
      typeof message.percent === 'number'
        ? Math.round(message.percent * 1000)
        : settings.mode === 'paged'
          ? Math.round(pagePercent() * 1000)
          : undefined
    saveProgress(currentIndex.value, position)
  } finally {
    lastRemoteApplyAt = Date.now()
    applyingRemote = false
    flushPendingRemote()
  }
}

function flushPendingRemote() {
  const next = pendingRemote
  if (!next) return
  if (Date.now() < suppressRemoteUntil) {
    pendingRemote = null
    return
  }
  if (applyingRemote || loadingContent.value) return
  pendingRemote = null
  if (next.at && next.at < lastLocalProgressAt) return
  void applyRemoteProgress(next)
}

async function manualVerify() {
  if (verifying.value) return
  const url = currentChapter.value?.url || bookUrl.value
  if (!url) {
    showToast('当前没有可验证的章节地址')
    return
  }
  verifying.value = true
  error.value = ''
  showToast('请在验证窗口中完成验证，然后关闭窗口')
  try {
    await api.verifySource(url, chapterTitle.value || book.value?.name || '网页验证')
    showToast('验证完成，正在重新加载章节')
    if (chapters.value.length) {
      await selectChapter(currentIndex.value, false)
    } else {
      await load()
    }
  } catch (e) {
    const text = e instanceof Error ? e.message : String(e)
    error.value = text
    showToast(text)
  } finally {
    verifying.value = false
  }
}

async function openFloatWindow() {
  if (isPreview.value) {
    showToast('试看模式不支持浮窗，请先加入书架')
    return
  }
  try {
    await api.floatCommand('open', {
      bookUrl: bookUrl.value,
      index: currentIndex.value,
      percent: settings.mode === 'paged' ? pagePercent() : scrollPercent(),
      chapterTitle: chapterTitle.value,
    })
    showToast('已打开浮窗')
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

const book = ref<Book | null>(null)
const chapters = ref<Chapter[]>([])
const tocKey = computed(() => chapterListKey(chapters.value))
let tocReloading = false
const currentIndex = ref(0)
const content = ref('')
const contentBlocks = ref<ReaderBlock[]>([])
const loading = ref(true)
const loadingContent = ref(false)
const refreshingToc = ref(false)
const refreshingChapter = ref(false)
const error = ref('')
const verifying = ref(false)
const showCacheDialog = ref(false)
const cacheStart = ref(1)
const cacheEnd = ref(1)
const cacheJob = ref<ChapterCacheJob | null>(null)
const cacheStarting = ref(false)
let cachePollTimer: number | undefined

const settings = reactive<ReaderSettings>(loadSettings())
const showToc = ref(false)
const showSettings = ref(false)
const drawerTab = ref<'toc' | 'bookmarks' | 'search'>('toc')
const bookmarks = ref<Bookmark[]>([])
const searchQuery = ref('')
const searchInput = ref<HTMLInputElement | null>(null)
const toast = ref('')
const chromeVisible = ref(true)
const pages = ref<Block[][]>([])
const pageIndex = ref(0)
const viewportRef = ref<HTMLElement | null>(null)
const measureRef = ref<HTMLElement | null>(null)
const scrollRef = ref<HTMLElement | null>(null)
const windowWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)

const currentChapter = computed(() => chapters.value[currentIndex.value])
const chapterTitle = computed(() => currentChapter.value?.title ?? book.value?.name ?? '')

const effectivePageWidth = computed(() =>
  Math.max(320, Math.min(settings.pageWidth, windowWidth.value - 160)),
)

const readerStyle = computed(() => ({
  '--r-size': `${settings.fontSize}px`,
  '--r-lh': String(settings.lineHeight),
  '--r-para': `${settings.paraGap}em`,
  '--r-width': `${effectivePageWidth.value}px`,
}))

const trackStyle = computed(() => ({
  transform: `translateX(-${pageIndex.value * (effectivePageWidth.value + 64)}px)`,
}))

const pageLabel = computed(() => {
  if (settings.mode === 'scroll') return `${currentIndex.value + 1} / ${chapters.value.length}`
  return `${pageIndex.value + 1} / ${Math.max(1, pages.value.length)}`
})

const progressPercent = computed(() => {
  if (settings.mode === 'scroll') {
    return chapters.value.length ? ((currentIndex.value + 1) / chapters.value.length) * 100 : 0
  }
  return pages.value.length ? ((pageIndex.value + 1) / pages.value.length) * 100 : 0
})

interface SearchHit {
  blockIndex: number
  pageIndex: number
  before: string
  match: string
  after: string
}

const searchHits = computed<SearchHit[]>(() => {
  const query = searchQuery.value.trim()
  if (!query) return []
  const needle = query.toLowerCase()
  const hits: SearchHit[] = []
  contentBlocks.value.forEach((block, blockIndex) => {
    if (block.type !== 'text' || !block.text) return
    const text = block.text
    const lower = text.toLowerCase()
    let from = 0
    let count = 0
    while (from < lower.length && count < 5 && hits.length < 60) {
      const at = lower.indexOf(needle, from)
      if (at < 0) break
      const start = Math.max(0, at - 18)
      const end = Math.min(text.length, at + query.length + 28)
      const pageIndex = pages.value.findIndex((page) =>
        page.some((item) => item.index === blockIndex),
      )
      hits.push({
        blockIndex,
        pageIndex,
        before: text.slice(start, at),
        match: text.slice(at, at + query.length),
        after: text.slice(at + query.length, end),
      })
      from = at + Math.max(1, query.length)
      count++
    }
  })
  return hits
})

function loadSettings(): ReaderSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY)
    if (!raw) return { ...DEFAULTS }
    return { ...DEFAULTS, ...(JSON.parse(raw) as Partial<ReaderSettings>) }
  } catch {
    return { ...DEFAULTS }
  }
}

watch(
  settings,
  (value) => localStorage.setItem(SETTINGS_KEY, JSON.stringify(value)),
  { deep: true },
)

watch(
  () => [settings.fontSize, settings.lineHeight, settings.paraGap, settings.pageWidth, settings.fontFamily, settings.mode],
  async () => {
    await nextTick()
    if (settings.mode === 'paged') await paginate()
    else scrollRef.value?.scrollTo({ top: 0 })
  },
)

watch([showToc, showSettings], () => showChrome())

watch(drawerTab, (tab) => {
  if (tab === 'toc' && showToc.value) scrollTocToCurrent()
})

let chromeTimer: number | undefined
function showChrome() {
  chromeVisible.value = true
  window.clearTimeout(chromeTimer)
  chromeTimer = window.setTimeout(() => {
    if (!showToc.value && !showSettings.value) chromeVisible.value = false
  }, 2600)
}

function toggleToc() {
  if (showToc.value && drawerTab.value === 'toc') {
    showToc.value = false
  } else {
    openDrawer('toc')
  }
}

function openDrawer(tab: 'toc' | 'bookmarks' | 'search') {
  drawerTab.value = tab
  showToc.value = true
  showSettings.value = false
  if (tab === 'toc') {
    scrollTocToCurrent()
    void loadCachedFlags().then(() => scrollTocToCurrent())
  }
  if (tab === 'bookmarks') void loadBookmarks()
  if (tab === 'search') void nextTick(() => searchInput.value?.focus())
}

/** 打开目录时直接把当前章滚到可视区中间（不用 scrollIntoView，避免被其它滚动容器带偏）。 */
function scrollTocToCurrent() {
  void nextTick(() => {
    const list = tocListRef.value
    const active = list?.querySelector<HTMLElement>('.reader-toc-item.active')
    if (!list || !active) return
    const listRect = list.getBoundingClientRect()
    const activeRect = active.getBoundingClientRect()
    const top = activeRect.top - listRect.top + list.scrollTop
    list.scrollTop = Math.max(0, top - (list.clientHeight - activeRect.height) / 2)
  })
}

async function loadCachedFlags() {
  if (isPreview.value || !bookUrl.value || !chapters.value.length) return
  try {
    const result = await api.cachedChapters(bookUrl.value)
    const cached = new Set(result.cached)
    chapters.value = chapters.value.map((chapter) => {
      const value = cached.has(chapter.index)
      return chapter.cached === value ? chapter : { ...chapter, cached: value }
    })
  } catch {
    // 目录缓存状态失败不影响阅读
  }
}

function openCacheDialog() {
  if (isPreview.value) {
    showToast('试看模式不支持缓存章节')
    return
  }
  if (!chapters.value.length) return
  showCacheDialog.value = true
  if (cacheJob.value && (cacheJob.value.state === 'running' || cacheJob.value.state === 'cancelling')) {
    void pollCache()
    return
  }
  cacheJob.value = null
  cacheStart.value = currentIndex.value + 1
  cacheEnd.value = chapters.value.length
}

function closeCacheDialog() {
  showCacheDialog.value = false
  window.clearTimeout(cachePollTimer)
}

async function startCache() {
  if (cacheStarting.value || !chapters.value.length) return
  const start = Math.min(
    Math.max(1, Math.round(Number(cacheStart.value) || 1)),
    chapters.value.length,
  )
  const end = Math.min(
    Math.max(1, Math.round(Number(cacheEnd.value) || chapters.value.length)),
    chapters.value.length,
  )
  if (start > end) {
    showToast('缓存范围无效')
    return
  }
  cacheStart.value = start
  cacheEnd.value = end
  cacheStarting.value = true
  try {
    cacheJob.value = await api.startChapterCache(bookUrl.value, start - 1, end - 1)
    void pollCache()
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  } finally {
    cacheStarting.value = false
  }
}

async function pollCache() {
  window.clearTimeout(cachePollTimer)
  const job = cacheJob.value
  if (!job) return
  try {
    const status = await api.chapterCacheStatus(job.id)
    cacheJob.value = status
    void loadCachedFlags()
    if (status.state === 'running' || status.state === 'cancelling') {
      cachePollTimer = window.setTimeout(() => void pollCache(), 1000)
    } else if (status.state === 'done') {
      showToast('章节缓存完成')
    } else if (status.state === 'cancelled') {
      showToast('章节缓存已停止')
    } else if (status.state === 'error') {
      showToast(status.error || '章节缓存失败')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

async function stopCache() {
  const job = cacheJob.value
  if (!job) return
  try {
    cacheJob.value = await api.stopChapterCache(job.id)
    void pollCache()
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

const cacheProgress = computed(() => {
  const job = cacheJob.value
  if (!job || !job.total) return 0
  return Math.min(100, Math.round(((job.done + job.failed) / job.total) * 100))
})

const cacheStateLabel = computed(() => {
  const job = cacheJob.value
  if (!job) return ''
  if (job.state === 'running') return '缓存中'
  if (job.state === 'cancelling') return '正在停止'
  if (job.state === 'done') return '缓存完成'
  if (job.state === 'cancelled') return '已停止'
  return '缓存失败'
})

function toggleSettings() {
  showSettings.value = !showSettings.value
  if (showSettings.value) showToc.value = false
}

let toastTimer: number | undefined
function showToast(text: string) {
  toast.value = text
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
  }, 2200)
}

async function loadBookmarks() {
  if (isPreview.value) {
    bookmarks.value = []
    return
  }
  try {
    bookmarks.value = await api.bookmarks(bookUrl.value)
  } catch {
    bookmarks.value = []
  }
}

function nearestChapterIndex(list: Chapter[], url: string, around: number): number {
  let best = -1
  let bestDistance = Number.MAX_SAFE_INTEGER
  list.forEach((item, index) => {
    if (item.url !== url) return
    const distance = Math.abs(index - around)
    if (distance < bestDistance) {
      best = index
      bestDistance = distance
    }
  })
  return best
}

async function refreshToc() {
  if (refreshingToc.value) return
  if (isPreview.value) {
    showToast('试看模式不支持刷新目录')
    return
  }
  const oldIndex = displayedIndex.value
  const oldChapterUrl = chapters.value[oldIndex]?.url
  refreshingToc.value = true
  try {
    const list = await api.refreshChapters(bookUrl.value)
    chapters.value = list
    if (book.value) book.value.totalChapterNum = list.length
    const matched = oldChapterUrl ? nearestChapterIndex(list, oldChapterUrl, oldIndex) : -1
    if (matched >= 0) {
      // 同一个 URL 说明正文仍是这一章，只同步新目录里的位置，不重新抓正文
      currentIndex.value = matched
      displayedIndex.value = matched
      saveProgress(matched)
      // 目录版本已变，通知浮窗刷新目录并按 URL 对齐
      notifyProgress(true)
      scrollTocToCurrent()
    } else {
      await selectChapter(Math.min(oldIndex, list.length - 1))
    }
    showToast(`目录已更新，共 ${list.length} 章`)
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  } finally {
    refreshingToc.value = false
  }
}

async function refreshCurrentChapter() {
  if (refreshingChapter.value || loadingContent.value || !currentChapter.value) return
  const index = currentIndex.value
  const anchor = settings.mode === 'paged' ? pagePercent() : scrollPercent()
  refreshingChapter.value = true
  loadingContent.value = true
  error.value = ''
  try {
    const data = isPreview.value
      ? await api.previewContent(previewId.value, index, { refresh: true })
      : await api.content(bookUrl.value, index, {
          refresh: true,
          chapterUrl: chapters.value[index]?.url,
        })
    content.value = data.content
    contentBlocks.value = toReaderBlocks(data)
    await nextTick()
    if (settings.mode === 'paged') {
      await paginate(false)
      pageIndex.value = Math.min(
        Math.max(Math.round(anchor * Math.max(0, pages.value.length - 1)), 0),
        pages.value.length - 1,
      )
    } else {
      await nextTick()
      const element = scrollRef.value
      if (element) {
        const max = Math.max(0, element.scrollHeight - element.clientHeight)
        element.scrollTo({ top: anchor * max })
      }
    }
    saveReadingPosition()
    showToast('本章已刷新')
  } catch (e) {
    // 刷新失败时保留当前正文，只提示错误
    showToast(e instanceof Error ? e.message : String(e))
  } finally {
    refreshingChapter.value = false
    loadingContent.value = false
  }
}

function currentExcerpt(): string {
  if (settings.mode === 'paged') {
    const page = pages.value[pageIndex.value] ?? []
    return page
      .filter((block) => block.type === 'p' && block.text)
      .map((block) => (block.text ?? '').trim())
      .join(' ')
      .slice(0, 80)
  }
  const block = contentBlocks.value.find((item) => item.type === 'text' && item.text?.trim())
  return (block?.text ?? '').trim().slice(0, 80)
}

async function addBookmark() {
  if (!currentChapter.value) return
  if (isPreview.value) {
    showToast('试看模式不支持书签，请先加入书架')
    return
  }
  const position = settings.mode === 'paged'
    ? pageIndex.value
    : Math.round(scrollRef.value?.scrollTop ?? 0)
  const duplicate = bookmarks.value.some(
    (item) =>
      item.chapterIndex === currentIndex.value &&
      item.mode === settings.mode &&
      item.position === position,
  )
  if (duplicate) {
    showToast('当前页已有书签')
    return
  }
  try {
    const saved = await api.addBookmark({
      bookUrl: bookUrl.value,
      chapterIndex: currentIndex.value,
      chapterTitle: chapterTitle.value,
      mode: settings.mode,
      position,
      excerpt: currentExcerpt(),
    })
    bookmarks.value = [saved, ...bookmarks.value]
    drawerTab.value = 'bookmarks'
    showToast('已添加书签')
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

async function removeBookmark(bookmark: Bookmark) {
  try {
    await api.deleteBookmark(bookmark.id)
    bookmarks.value = bookmarks.value.filter((item) => item.id !== bookmark.id)
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

function bookmarkPositionLabel(bookmark: Bookmark): string {
  if (bookmark.mode === 'paged') return `第 ${bookmark.position + 1} 页`
  return `滚动 ${bookmark.position} px`
}

async function jumpBookmark(bookmark: Bookmark) {
  if (bookmark.chapterIndex < 0 || bookmark.chapterIndex >= chapters.value.length) return
  showToc.value = false
  await selectChapter(bookmark.chapterIndex, true, 'first', {
    mode: bookmark.mode === 'paged' ? 'paged' : 'scroll',
    position: bookmark.position,
  })
}

function jumpSearchHit(hit: SearchHit) {
  showToc.value = false
  if (settings.mode === 'paged' && hit.pageIndex >= 0) {
    pageIndex.value = hit.pageIndex
    showChrome()
    return
  }
  void nextTick(() => {
    const target = scrollRef.value?.querySelector<HTMLElement>(
      `[data-block-index="${hit.blockIndex}"]`,
    )
    target?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  })
}

function cycleTheme() {
  const index = THEMES.findIndex((theme) => theme.id === settings.theme)
  settings.theme = THEMES[(index + 1) % THEMES.length].id
}

function resetSettings() {
  Object.assign(settings, DEFAULTS)
}

function breakPosition(text: string, max: number): number {
  const punctuation = /[，。！？；：、,.!?;:》」』”’）]/
  const floor = Math.max(1, max - 30)
  for (let index = max - 1; index >= floor; index--) {
    if (punctuation.test(text[index])) return index + 1
  }
  return Math.max(1, max)
}

function toReaderBlocks(data: Content): ReaderBlock[] {
  if (data.blocks?.length) {
    return data.blocks
      .filter((block) => (block.type === 'image' ? Boolean(block.src) : Boolean(block.text?.trim())))
      .map((block) => ({
        type: block.type,
        text: block.text,
        src: block.src,
        alt: block.alt ?? undefined,
        style: block.style,
      }))
  }
  return data.content
    .split(/\n+/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((text) => ({ type: 'text' as const, text }))
}

function contentImageSrc(src?: string): string {
  if (!src) return ''
  return imageSrc(src, { source: book.value?.origin, bookUrl: book.value?.bookUrl })
}

function hideContentImage(event: Event) {
  const image = event.target as HTMLImageElement | null
  if (image) image.style.display = 'none'
}

function imageHeight(style: string | null | undefined, viewportHeight: number): number {
  const upper = style?.toUpperCase()
  const max = Math.max(160, viewportHeight - 24)
  if (upper === 'SINGLE') return Math.min(max, Math.max(220, viewportHeight - 80))
  if (upper === 'FULL') return Math.min(max, Math.round(viewportHeight * 0.58))
  return Math.min(max, Math.max(160, Math.round(viewportHeight * 0.48)))
}async function paginate(toLast = false) {
  await nextTick()
  const viewport = viewportRef.value
  const measure = measureRef.value
  if (!viewport || !measure) return
  const height = viewport.clientHeight || window.innerHeight - 120
  const blocks: Block[] = [
    { type: 'h1', text: chapterTitle.value },
    ...contentBlocks.value.map((block, index): Block => {
      if (block.type === 'image' && block.src) {
        return {
          type: 'img',
          src: block.src,
          alt: block.alt,
          height: imageHeight(block.style, height),
          index,
        }
      }
      return { type: 'p', text: block.text ?? '', index }
    }),
  ]
  measure.style.width = `${effectivePageWidth.value}px`
  measure.innerHTML = ''
  const result: Block[][] = []
  let page: Block[] = []

  const pushPage = () => {
    if (page.length) {
      result.push(page)
      page = []
      measure.innerHTML = ''
    }
  }

  const addHeading = (text: string) => {
    const node = document.createElement('h1')
    node.textContent = text
    measure.appendChild(node)
    if (measure.scrollHeight > height && page.length > 0) {
      measure.removeChild(node)
      pushPage()
      measure.appendChild(node)
    }
    page.push({ type: 'h1', text })
  }

  const addImage = (block: Block) => {
    const node = document.createElement('figure')
    node.className = 'reader-image paged'
    node.style.height = `${block.height}px`
    measure.appendChild(node)
    if (measure.scrollHeight > height && page.length > 0) {
      measure.removeChild(node)
      pushPage()
      measure.appendChild(node)
    }
    page.push(block)
  }

  const addParagraph = (text: string, blockIndex: number) => {
    let remaining = text
    while (remaining.length) {
      const node = document.createElement('p')
      node.textContent = remaining
      measure.appendChild(node)
      if (measure.scrollHeight <= height) {
        page.push({ type: 'p', text: remaining, index: blockIndex })
        return
      }

      let low = 1
      let high = remaining.length
      let best = 0
      while (low <= high) {
        const mid = Math.floor((low + high) / 2)
        node.textContent = remaining.slice(0, mid)
        if (measure.scrollHeight <= height) {
          best = mid
          low = mid + 1
        } else {
          high = mid - 1
        }
      }

      if (best === 0) {
        measure.removeChild(node)
        if (page.length) {
          pushPage()
          continue
        }
        best = 1
        node.textContent = remaining.slice(0, best)
        measure.appendChild(node)
      }

      const cut = breakPosition(remaining, best)
      node.textContent = remaining.slice(0, cut)
      page.push({ type: 'p', text: remaining.slice(0, cut), index: blockIndex })
      pushPage()
      remaining = remaining.slice(cut)
    }
  }

  for (const block of blocks) {
    if (block.type === 'h1') addHeading(block.text ?? '')
    else if (block.type === 'img') addImage(block)
    else addParagraph(block.text ?? '', block.index ?? 0)
  }
  if (page.length) result.push(page)

  pages.value = result.length ? result : [[]]
  pageIndex.value = toLast
    ? pages.value.length - 1
    : Math.min(pageIndex.value, pages.value.length - 1)
}

async function selectChapter(
  index: number,
  save = true,
  position: 'first' | 'last' = 'first',
  target?: { mode: 'paged' | 'scroll'; position: number },
) {
  if (index < 0 || index >= chapters.value.length) return
  const seq = ++loadSeq
  const previousIndex = displayedIndex.value
  currentIndex.value = index
  loadingContent.value = true
  error.value = ''
  // save=true 表示用户主动切章；立即通知浮窗，并在短时间内拒绝它的旧章节进度
  if (save) {
    const at = Date.now()
    lastLocalChapterChangeAt = at
    suppressRemoteUntil = at + 1200
    broadcastChapterStart(index, at)
  }
  try {
    const data = isPreview.value
      ? await api.previewContent(previewId.value, index)
      : await api.content(bookUrl.value, index, { chapterUrl: chapters.value[index]?.url })
    if (seq !== loadSeq) return
    content.value = data.content
    contentBlocks.value = toReaderBlocks(data)
    await nextTick()
    if (seq !== loadSeq) return
    if (settings.mode === 'paged') {
      pageIndex.value = 0
      await paginate(position === 'last')
      if (seq !== loadSeq) return
      if (target?.mode === 'paged') {
        pageIndex.value = Math.min(Math.max(target.position, 0), pages.value.length - 1)
      }
    } else {
      pageIndex.value = 0
      await nextTick()
      if (seq !== loadSeq) return
      const top = target?.mode === 'scroll' ? Math.max(0, target.position) : 0
      scrollRef.value?.scrollTo({ top })
    }
    displayedIndex.value = index
    if (save) saveReadingPosition()
    if (!isPreview.value) {
      void api
        .prefetchChapters(bookUrl.value, index, chapters.value[index]?.url)
        .catch(() => undefined)
    }
  } catch (e) {
    if (seq !== loadSeq) return
    error.value = e instanceof Error ? e.message : String(e)
    // 正文没有换成目标章节时，目录高亮必须跟正文保持一致
    if (currentIndex.value !== previousIndex && content.value) {
      currentIndex.value = previousIndex
      if (save) notifyProgress()
    }
  } finally {
    if (seq === loadSeq) {
      loadingContent.value = false
      flushPendingRemote()
    }
  }
}

function turnPage(delta: number) {
  if (loadingContent.value || applyingRemote) return
  if (settings.mode === 'scroll') {
    scrollRef.value?.scrollBy({ top: delta * window.innerHeight * 0.85, behavior: 'smooth' })
    return
  }
  const next = pageIndex.value + delta
  if (next < 0) {
    if (currentIndex.value > 0) void selectChapter(currentIndex.value - 1, true, 'last')
    return
  }
  if (next >= pages.value.length) {
    if (currentIndex.value < chapters.value.length - 1) {
      void selectChapter(currentIndex.value + 1, true, 'first')
    }
    return
  }
  pageIndex.value = next
  saveReadingPosition()
}

function onScroll() {
  // 接收远端进度时程序会 scrollTo；不要把这个滚动再回播成自己的进度
  if (applyingRemote || Date.now() - lastRemoteApplyAt < 800) return
  window.clearTimeout(scrollSaveTimer)
  scrollSaveTimer = window.setTimeout(() => {
    notifyProgress()
    if (!isPreview.value) saveProgress(currentIndex.value)
  }, 500)
}

function goBack() {
  if (router.options.history.state.back) router.back()
  else router.push('/')
}

function onKeydown(event: KeyboardEvent) {
  const target = document.activeElement as HTMLElement | null
  if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
  if (event.key === 'Escape') {
    if (showCacheDialog.value) {
      closeCacheDialog()
      event.preventDefault()
      return
    }
    if (showToc.value || showSettings.value) {
      showToc.value = false
      showSettings.value = false
      event.preventDefault()
    }
    return
  }
  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    turnPage(-1)
  } else if (event.key === 'ArrowRight' || event.key === ' ') {
    event.preventDefault()
    turnPage(1)
  } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 't') {
    event.preventDefault()
    toggleToc()
  }
}

let resizeTimer: number | undefined
function onResize() {
  windowWidth.value = window.innerWidth
  window.clearTimeout(resizeTimer)
  resizeTimer = window.setTimeout(() => {
    if (settings.mode === 'paged') void paginate()
  }, 150)
}

function applyQuerySettings() {
  const query = route.query
  if (typeof query.theme === 'string' && THEMES.some((theme) => theme.id === query.theme)) {
    settings.theme = query.theme as ReaderSettings['theme']
  }
  if (query.mode === 'paged' || query.mode === 'scroll') {
    settings.mode = query.mode
  }
  if (typeof query.fontSize === 'string') {
    const size = Number(query.fontSize)
    if (size >= 16 && size <= 30) settings.fontSize = size
  }
}

async function applyServerDefaults() {
  if (localStorage.getItem(SETTINGS_KEY)) return
  try {
    const remote = await api.settings()
    if (THEMES.some((theme) => theme.id === remote.theme)) {
      settings.theme = remote.theme as ReaderSettings['theme']
    }
    if (remote.fontFamily === 'serif' || remote.fontFamily === 'sans' || remote.fontFamily === 'kai') {
      settings.fontFamily = remote.fontFamily
    }
    if (remote.mode === 'paged' || remote.mode === 'scroll') settings.mode = remote.mode
    if (typeof remote.fontSize === 'number' && remote.fontSize >= 16 && remote.fontSize <= 30) {
      settings.fontSize = remote.fontSize
    }
    if (typeof remote.lineHeight === 'number' && remote.lineHeight >= 1.6 && remote.lineHeight <= 2.2) {
      settings.lineHeight = remote.lineHeight
    }
    if (typeof remote.paraGap === 'number' && remote.paraGap >= 0.4 && remote.paraGap <= 1.6) {
      settings.paraGap = remote.paraGap
    }
    if (typeof remote.pageWidth === 'number' && remote.pageWidth >= 620 && remote.pageWidth <= 880) {
      settings.pageWidth = remote.pageWidth
    }
  } catch {
    // 后端不可用时沿用内置默认值
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (isPreview.value) {
      const session = await api.previewSession(previewId.value)
      book.value = session.book
      chapters.value = session.chapters
    } else {
      const shelf = await api.books()
      book.value = shelf.find((item) => item.bookUrl === bookUrl.value) ?? null
      if (!book.value) throw new Error('这本书不在书架，请先从发现页加入')
      chapters.value = await api.chapters(bookUrl.value)
    }
    if (!chapters.value.length) throw new Error('目录为空')
    await loadBookmarks()
    const start = Math.min(Math.max(book.value?.durChapterIndex ?? 0, 0), chapters.value.length - 1)
    await selectChapter(start, false)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await applyServerDefaults()
  applyQuerySettings()
  showChrome()
  if (typeof BroadcastChannel !== 'undefined') {
    syncChannel = new BroadcastChannel(FLOAT_SYNC_CHANNEL)
    syncChannel.onmessage = (event: MessageEvent<FloatSyncMessage>) => {
      const message = event.data
      if (!message || message.source !== 'float') return
      if (message.type === 'sync-request') {
        if (isPreview.value || !book.value) return
        if (message.bookUrl && message.bookUrl !== bookUrl.value) return
        // 浮窗重新获得焦点时回播一次当前进度，修复它隐藏/加载期间漏收的消息
        notifyProgress(true)
        return
      }
      if (message.type === 'progress') void applyRemoteProgress(message)
    }
  }
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onResize)
  await load()
  if (
    settings.mode === 'paged' &&
    typeof route.query.index !== 'string' &&
    (book.value?.durChapterPos ?? 0) > 0
  ) {
    const percent = Math.min(1, Math.max(0, (book.value?.durChapterPos ?? 0) / 1000))
    pageIndex.value = Math.min(
      Math.round(percent * Math.max(0, pages.value.length - 1)),
      pages.value.length - 1,
    )
  }
  if (typeof route.query.index === 'string') {
    const index = Number(route.query.index)
    if (Number.isInteger(index) && index >= 0 && index < chapters.value.length) {
      await selectChapter(index)
    }
  }
  if (route.query.panel === 'settings') showSettings.value = true
  else if (
    route.query.panel === 'toc' ||
    route.query.panel === 'bookmarks' ||
    route.query.panel === 'search'
  ) {
    openDrawer(route.query.panel)
  }
  if (typeof route.query.q === 'string') {
    searchQuery.value = route.query.q
    openDrawer('search')
  }
  if (settings.mode === 'paged' && typeof route.query.page === 'string') {
    const page = Number(route.query.page)
    if (Number.isFinite(page) && page > 0) {
      pageIndex.value = Math.min(page - 1, pages.value.length - 1)
    }
  }
  showChrome()
})

onBeforeUnmount(() => {
  window.clearTimeout(chromeTimer)
  window.clearTimeout(resizeTimer)
  window.clearTimeout(toastTimer)
  window.clearTimeout(scrollSaveTimer)
  window.clearTimeout(cachePollTimer)
  syncChannel?.close()
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onResize)
  if (isPreview.value) void api.closePreview(previewId.value).catch(() => undefined)
})
</script>

<template>
  <div
    class="reader-shell"
    :data-theme="settings.theme"
    :data-font="settings.fontFamily"
    :class="{ 'chrome-hidden': !chromeVisible }"
    :style="readerStyle"
    @mousemove="showChrome"
  >
    <header class="reader-top">
      <div class="reader-left">
        <button class="reader-icon" title="返回上一层" @click="goBack">
          <AppIcon name="arrow-right" class="icon-flip" />
        </button>
        <div class="reader-title">
          <strong>{{ book?.name }}</strong>
          <span>{{ currentChapter?.title }}</span>
        </div>
      </div>
      <div class="reader-actions">
        <button class="reader-icon" title="搜索本章" @click="openDrawer('search')">
          <AppIcon name="search" />
        </button>
        <button class="reader-icon" title="书签" @click="openDrawer('bookmarks')">
          <AppIcon name="bookmark" />
        </button>
        <button class="reader-icon" title="目录" @click="toggleToc">
          <AppIcon name="list" />
        </button>
        <button
          class="reader-icon"
          :class="{ spinning: refreshingChapter }"
          :disabled="refreshingChapter"
          title="刷新本章（重新抓取正文）"
          @click="refreshCurrentChapter"
        >
          <AppIcon name="refresh" />
        </button>
        <button class="reader-icon" title="浮窗阅读" @click="openFloatWindow">
          <AppIcon name="float" />
        </button>
        <button class="reader-icon" title="切换主题" @click="cycleTheme">
          <AppIcon name="moon" />
        </button>
        <button class="reader-icon" title="排版" @click="toggleSettings">
          <AppIcon name="type" />
        </button>
      </div>
    </header>

    <div v-if="loading && !content" class="reader-loading">正在加载…</div>
    <div v-else-if="error && !content" class="reader-error reader-error-block">
      <p>{{ error }}</p>
      <button class="reader-verify" :disabled="verifying" @click="manualVerify">
        {{ verifying ? '验证中…' : '网页验证' }}
      </button>
    </div>

    <template v-else>
      <div
        v-if="settings.mode === 'paged'"
        ref="viewportRef"
        class="paged-viewport"
      >
        <button class="page-zone prev" aria-label="上一页" @click.stop="turnPage(-1)" />
        <div class="paged-window">
          <div class="paged-track" :style="trackStyle">
            <section v-for="(page, pageNo) in pages" :key="pageNo" class="reader-page">
              <template v-for="(block, blockNo) in page" :key="blockNo">
                <h1 v-if="block.type === 'h1'">{{ block.text }}</h1>
                <figure
                  v-else-if="block.type === 'img'"
                  class="reader-image paged"
                  :style="{ height: `${block.height}px` }"
                >
                  <span class="reader-image-alt">{{ block.alt || '图片' }}</span>
                  <img
                    v-if="block.src"
                    :src="contentImageSrc(block.src)"
                    :alt="block.alt || ''"
                    loading="lazy"
                    decoding="async"
                    @error="hideContentImage"
                  />
                </figure>
                <p v-else :data-block-index="block.index">{{ block.text }}</p>
              </template>
            </section>
          </div>
        </div>
        <button class="page-zone next" aria-label="下一页" @click.stop="turnPage(1)" />
      </div>

      <div v-else ref="scrollRef" class="scroll-viewport" @scroll.passive="onScroll">
        <article class="reader-article">
          <h1>{{ chapterTitle }}</h1>
          <template v-for="(block, blockIndex) in contentBlocks" :key="blockIndex">
            <figure
              v-if="block.type === 'image' && block.src"
              class="reader-image"
              :data-block-index="blockIndex"
            >
              <span class="reader-image-alt">{{ block.alt || '图片' }}</span>
              <img
                :src="contentImageSrc(block.src)"
                :alt="block.alt || ''"
                loading="lazy"
                decoding="async"
                @error="hideContentImage"
              />
            </figure>
            <p v-else :data-block-index="blockIndex">{{ block.text }}</p>
          </template>
        </article>
      </div>
    </template>

    <div v-if="error && content" class="reader-error toast reader-error-block">
      <p>{{ error }}</p>
      <button class="reader-verify" :disabled="verifying" @click="manualVerify">
        {{ verifying ? '验证中…' : '网页验证' }}
      </button>
    </div>
    <p v-if="toast" class="reader-toast">{{ toast }}</p>

    <footer class="reader-bottom">
      <span class="reader-chapter-name" :title="chapterTitle">{{ chapterTitle }}</span>
      <div class="reader-progress"><i :style="{ width: `${progressPercent}%` }" /></div>
      <span>{{ pageLabel }}</span>
    </footer>

    <aside class="reader-drawer right" :class="{ open: showSettings }">
      <header class="reader-drawer-head">
        <strong>排版</strong>
        <button class="reader-icon" @click="showSettings = false"><AppIcon name="x" /></button>
      </header>
      <div class="reader-drawer-body">
        <div class="setting-block">
          <span class="setting-label">主题</span>
          <div class="theme-chips">
            <button
              v-for="theme in THEMES"
              :key="theme.id"
              class="theme-chip"
              :class="{ active: settings.theme === theme.id }"
              @click="settings.theme = theme.id"
            >
              <i :style="{ background: theme.swatch }" />{{ theme.name }}
            </button>
          </div>
        </div>
        <div class="setting-block">
          <span class="setting-label">字体</span>
          <select v-model="settings.fontFamily" class="reader-select">
            <option value="serif">宋体</option>
            <option value="kai">楷体</option>
            <option value="sans">黑体</option>
          </select>
        </div>
        <div class="setting-block">
          <div class="setting-line"><span>字号</span><b>{{ settings.fontSize }} px</b></div>
          <input v-model.number="settings.fontSize" type="range" min="16" max="30" />
        </div>
        <div class="setting-block">
          <div class="setting-line"><span>行高</span><b>{{ settings.lineHeight.toFixed(1) }}</b></div>
          <input v-model.number="settings.lineHeight" type="range" min="1.6" max="2.2" step="0.1" />
        </div>
        <div class="setting-block">
          <div class="setting-line"><span>段距</span><b>{{ settings.paraGap.toFixed(1) }} em</b></div>
          <input v-model.number="settings.paraGap" type="range" min="0.4" max="1.6" step="0.1" />
        </div>
        <div class="setting-block">
          <div class="setting-line"><span>页宽</span><b>{{ settings.pageWidth }} px</b></div>
          <input v-model.number="settings.pageWidth" type="range" min="620" max="880" step="20" />
        </div>
        <div class="setting-block">
          <span class="setting-label">翻页</span>
          <div class="reader-segmented">
            <button :class="{ active: settings.mode === 'paged' }" @click="settings.mode = 'paged'">横向</button>
            <button :class="{ active: settings.mode === 'scroll' }" @click="settings.mode = 'scroll'">滚动</button>
          </div>
        </div>
        <button class="reader-reset" @click="resetSettings">恢复默认</button>
      </div>
    </aside>

    <aside class="reader-drawer left" :class="{ open: showToc }">
      <header class="reader-drawer-head">
        <strong>阅读导航</strong>
        <div class="reader-drawer-actions">
          <button
            v-if="drawerTab === 'toc'"
            class="reader-icon"
            title="缓存章节"
            @click="openCacheDialog"
          >
            <AppIcon name="cloud-download" :size="16" />
          </button>
          <button
            v-if="drawerTab === 'toc'"
            class="reader-icon"
            :class="{ spinning: refreshingToc }"
            :disabled="refreshingToc"
            title="刷新目录"
            @click="refreshToc"
          >
            <AppIcon name="refresh" :size="16" />
          </button>
          <button class="reader-icon" @click="showToc = false"><AppIcon name="x" /></button>
        </div>
      </header>
      <div class="reader-tabs">
        <button :class="{ active: drawerTab === 'toc' }" @click="drawerTab = 'toc'; scrollTocToCurrent()">目录</button>
        <button :class="{ active: drawerTab === 'bookmarks' }" @click="drawerTab = 'bookmarks'; loadBookmarks()">
          书签<span v-if="bookmarks.length">{{ bookmarks.length }}</span>
        </button>
        <button :class="{ active: drawerTab === 'search' }" @click="drawerTab = 'search'; nextTick(() => searchInput?.focus())">
          搜索
        </button>
      </div>

      <div v-if="drawerTab === 'toc'" ref="tocListRef" class="reader-toc-list">
        <button
          v-for="chapter in chapters"
          :key="chapter.index"
          class="reader-toc-item"
          :class="{ active: chapter.index === currentIndex }"
          @click="selectChapter(chapter.index); showToc = false"
        >
          <span class="reader-toc-title">{{ chapter.title }}</span>
          <span
            v-if="!isPreview"
            class="reader-toc-cache"
            :class="{ cached: chapter.cached }"
            :title="chapter.cached ? '已缓存' : '未缓存'"
          >
            <AppIcon :name="chapter.cached ? 'check' : 'cloud'" :size="12" />
          </span>
        </button>
      </div>

      <div v-else-if="drawerTab === 'bookmarks'" class="reader-nav-panel">
        <button class="reader-bookmark-add" @click="addBookmark">
          <AppIcon name="bookmark" :size="14" /> 添加当前页
        </button>
        <p v-if="!bookmarks.length" class="reader-panel-empty">还没有书签。翻到想记住的页面，点击上方按钮。</p>
        <div v-else class="reader-bookmark-list">
          <article v-for="bookmark in bookmarks" :key="bookmark.id" class="reader-bookmark-card">
            <button class="reader-bookmark-main" @click="jumpBookmark(bookmark)">
              <div class="reader-bookmark-head">
                <strong>{{ bookmark.chapterTitle || '未命名章节' }}</strong>
                <span>{{ bookmarkPositionLabel(bookmark) }}</span>
              </div>
              <p v-if="bookmark.excerpt">{{ bookmark.excerpt }}</p>
            </button>
            <button class="reader-icon danger" title="删除书签" @click.stop="removeBookmark(bookmark)">
              <AppIcon name="trash" :size="15" />
            </button>
          </article>
        </div>
      </div>

      <div v-else class="reader-nav-panel">
        <div class="reader-search-box">
          <AppIcon name="search" :size="15" />
          <input
            ref="searchInput"
            v-model="searchQuery"
            placeholder="搜索当前章节"
            spellcheck="false"
            @keydown.esc="showToc = false"
            @keydown.enter.prevent="searchHits[0] && jumpSearchHit(searchHits[0])"
          />
          <button v-if="searchQuery" class="reader-icon" @click="searchQuery = ''">
            <AppIcon name="x" :size="14" />
          </button>
        </div>
        <p v-if="!searchQuery.trim()" class="reader-panel-empty">
          输入关键词，搜索范围仅限当前章节。
        </p>
        <p v-else-if="!searchHits.length" class="reader-panel-empty">没有找到匹配内容。</p>
        <div v-else class="reader-search-results">
          <button
            v-for="(hit, hitIndex) in searchHits"
            :key="hitIndex"
            class="reader-search-hit"
            @click="jumpSearchHit(hit)"
          >
            <span class="reader-search-page">
              {{ settings.mode === 'paged' && hit.pageIndex >= 0 ? `第 ${hit.pageIndex + 1} 页` : '滚动位置' }}
            </span>
            <span class="reader-search-text">
              {{ hit.before }}<mark>{{ hit.match }}</mark>{{ hit.after }}
            </span>
          </button>
        </div>
      </div>
    </aside>

    <div v-if="showCacheDialog" class="reader-cache-mask" @click.self="closeCacheDialog">
      <section class="reader-cache-dialog">
        <header class="reader-cache-head">
          <strong>离线缓存</strong>
          <button class="reader-icon" title="关闭" @click="closeCacheDialog">
            <AppIcon name="x" :size="16" />
          </button>
        </header>
        <p class="reader-cache-label">缓存范围（章号）</p>
        <div class="reader-cache-range">
          <input v-model.number="cacheStart" type="number" min="1" :max="chapters.length" />
          <span>至</span>
          <input v-model.number="cacheEnd" type="number" min="1" :max="chapters.length" />
        </div>
        <p class="reader-cache-hint">
          默认从当前章缓存到最后一章；已缓存章节会自动跳过。
          如书源需要人机验证，缓存过程中可能弹出验证窗口。
        </p>
        <div v-if="cacheJob" class="reader-cache-progress">
          <div class="reader-cache-progress-head">
            <span>{{ cacheStateLabel }}</span>
            <b>{{ cacheJob.done + cacheJob.failed }} / {{ cacheJob.total }}</b>
          </div>
          <div class="reader-cache-bar">
            <i :style="{ width: `${cacheProgress}%` }" />
          </div>
          <p v-if="cacheJob.currentTitle">正在缓存：{{ cacheJob.currentTitle }}</p>
          <p v-if="cacheJob.failed" class="reader-cache-error">失败 {{ cacheJob.failed }} 章</p>
          <p v-if="cacheJob.error" class="reader-cache-error">{{ cacheJob.error }}</p>
        </div>
        <footer class="reader-cache-actions">
          <button
            v-if="cacheJob && (cacheJob.state === 'running' || cacheJob.state === 'cancelling')"
            class="btn ghost"
            :disabled="cacheJob.state === 'cancelling'"
            @click="stopCache"
          >
            停止
          </button>
          <button
            v-else
            class="btn primary"
            :disabled="cacheStarting"
            @click="startCache"
          >
            {{ cacheStarting ? '开始中…' : '开始缓存' }}
          </button>
          <button class="btn ghost" @click="closeCacheDialog">关闭</button>
        </footer>
      </section>
    </div>

    <div ref="measureRef" class="measure-page" aria-hidden="true" />
  </div>
</template>
