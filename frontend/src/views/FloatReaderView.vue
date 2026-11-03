<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, type Book, type Chapter, type Content } from '../api/client'
import { isImageBook, isVideoBook } from '../api/bookType'
import {
  FLOAT_DEFAULTS,
  FLOAT_PREFS_CHANNEL,
  FLOAT_SYNC_CHANNEL,
  floatFontStack,
  floatPageStyle,
  normalizeFloatPrefs,
  type FloatPrefs,
  type FloatPrefsMessage,
  type FloatProgressMessage,
} from '../api/floatReader'

interface FloatSegment {
  type: 'title' | 'text'
  text: string
  block: number
  start: number
}

type FloatPage = FloatSegment[]

interface Anchor {
  block: number
  offset: number
}

const RESIZE_DIRS = ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'] as const

const route = useRoute()

const prefs = reactive<FloatPrefs>({ ...FLOAT_DEFAULTS })
const book = ref<Book | null>(null)
const chapters = ref<Chapter[]>([])
const chapterIndex = ref(0)
const contentBlocks = ref<string[]>([])
const pages = ref<FloatPage[]>([[]])
const pageIndex = ref(0)
const loading = ref(true)
const loadingContent = ref(false)
const loadError = ref('')
const toast = ref('')
const currentTop = ref(true)
const viewportWidth = ref(typeof window === 'undefined' ? 460 : window.innerWidth)

const bodyRef = ref<HTMLElement | null>(null)
const measureRef = ref<HTMLElement | null>(null)

const menu = reactive({ open: false, x: 0, y: 0 })

let syncChannel: BroadcastChannel | null = null
let prefsChannel: BroadcastChannel | null = null
let resizeObserver: ResizeObserver | null = null
let saveTimer: number | undefined
let toastTimer: number | undefined
let repaginateTimer: number | undefined
let lastTurnAt = 0
let remoteBusy = false
let dragging = false

const chapterTitle = computed(() => chapters.value[chapterIndex.value]?.title ?? book.value?.name ?? '')

const effectiveFontSize = computed(() => {
  if (!prefs.autoFontSize) return prefs.fontSize
  const width = Math.max(220, viewportWidth.value - 32)
  return Math.max(12, Math.min(30, Math.round(width / 22)))
})

const shellStyle = computed(() => floatPageStyle(prefs, effectiveFontSize.value))

const currentPage = computed<FloatPage>(() => pages.value[pageIndex.value] ?? [])

const progressPercent = computed(() => {
  if (!chapters.value.length) return 0
  const within = pages.value.length ? (pageIndex.value + 1) / pages.value.length : 0
  return Math.min(100, Math.max(0, ((chapterIndex.value + within) / chapters.value.length) * 100))
})

/** 章内位置（千分比），与主窗口宽度/分页无关。 */
const chapterPermille = computed(() => {
  if (pages.value.length <= 1) return 0
  return Math.round((pageIndex.value / (pages.value.length - 1)) * 1000)
})

function showToast(text: string) {
  toast.value = text
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
  }, 2600)
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function queryNumber(key: string): number | null {
  const raw = route.query[key]
  const value = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(value) ? value : null
}

function textBlocks(data: Content): string[] {
  if (data.blocks?.length) {
    return data.blocks
      .filter((block) => block.type === 'text' && Boolean(block.text?.trim()))
      .map((block) => (block.text ?? '').trim())
      .filter(Boolean)
  }
  return data.content
    .split(/\n+/)
    .map((line) => line.trim())
    .filter(Boolean)
}

function breakPosition(text: string, max: number): number {
  const punctuation = /[，。！？；：、,.!?;:》」』”’）)]/
  const floor = Math.max(1, max - 30)
  for (let index = max - 1; index >= floor; index--) {
    if (punctuation.test(text[index])) return index + 1
  }
  return Math.max(1, max)
}

function applyMeasureStyle(measure: HTMLElement) {
  measure.style.fontFamily = floatFontStack(prefs.fontFamily)
  measure.style.fontSize = `${effectiveFontSize.value}px`
  measure.style.lineHeight = String(prefs.lineHeight)
}

async function paginate(anchor: Anchor | null = null) {
  await nextTick()
  const body = bodyRef.value
  const measure = measureRef.value
  if (!body || !measure) return
  const width = body.clientWidth
  const height = body.clientHeight
  if (width < 60 || height < 60) return

  applyMeasureStyle(measure)
  measure.style.width = `${width}px`
  measure.innerHTML = ''

  const result: FloatPage[] = []
  let page: FloatPage = []

  const fits = () => measure.scrollHeight <= height - 2
  const reset = () => {
    measure.innerHTML = ''
  }
  const pushPage = () => {
    if (page.length) {
      result.push(page)
      page = []
      reset()
    }
  }
  const addTitle = (text: string) => {
    const node = document.createElement('h1')
    node.textContent = text
    measure.appendChild(node)
    if (!fits() && page.length) {
      measure.removeChild(node)
      pushPage()
      measure.appendChild(node)
    }
    page.push({ type: 'title', text, block: -1, start: 0 })
  }
  const addText = (text: string, block: number) => {
    let remaining = text
    let offset = 0
    while (remaining.length) {
      const node = document.createElement('p')
      node.textContent = remaining
      node.style.textIndent = offset === 0 ? '2em' : '0'
      measure.appendChild(node)
      if (fits()) {
        page.push({ type: 'text', text: remaining, block, start: offset })
        return
      }

      let low = 1
      let high = remaining.length
      let best = 0
      while (low <= high) {
        const mid = (low + high) >> 1
        node.textContent = remaining.slice(0, mid)
        if (fits()) {
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
        node.textContent = remaining.slice(0, 1)
        measure.appendChild(node)
      }

      const cut = breakPosition(remaining, best)
      node.textContent = remaining.slice(0, cut)
      page.push({ type: 'text', text: remaining.slice(0, cut), block, start: offset })
      pushPage()
      offset += cut
      remaining = remaining.slice(cut)
    }
  }

  addTitle(chapterTitle.value)
  contentBlocks.value.forEach((text, index) => addText(text, index))
  if (page.length) result.push(page)

  pages.value = result.length ? result : [[]]
  if (anchor) restoreAnchor(anchor)
  else pageIndex.value = clamp(pageIndex.value, 0, pages.value.length - 1)
}

function captureAnchor(): Anchor | null {
  const segment = pages.value[pageIndex.value]?.[0]
  if (!segment) return null
  return { block: segment.block, offset: segment.start }
}

function restoreAnchor(anchor: Anchor) {
  if (anchor.block < 0) {
    pageIndex.value = 0
    return
  }
  const exact = pages.value.findIndex((page) =>
    page.some(
      (segment) =>
        segment.block === anchor.block &&
        anchor.offset >= segment.start &&
        anchor.offset < segment.start + segment.text.length,
    ),
  )
  if (exact >= 0) {
    pageIndex.value = exact
    return
  }
  const fallback = pages.value.findIndex((page) =>
    page.some((segment) => segment.block >= anchor.block),
  )
  pageIndex.value =
    fallback >= 0 ? fallback : clamp(pageIndex.value, 0, pages.value.length - 1)
}

async function loadChapter(
  index: number,
  options: { page?: number | null; percent?: number | null; notify?: boolean } = {},
) {
  if (!book.value || index < 0 || index >= chapters.value.length) return
  chapterIndex.value = index
  loadingContent.value = true
  loadError.value = ''
  try {
    const data = await api.content(book.value.bookUrl, index)
    contentBlocks.value = textBlocks(data)
    pageIndex.value = 0
    await nextTick()
    await paginate(null)
    if (typeof options.percent === 'number' && Number.isFinite(options.percent)) {
      pageIndex.value = clamp(
        Math.round(options.percent * Math.max(0, pages.value.length - 1)),
        0,
        pages.value.length - 1,
      )
    } else if (typeof options.page === 'number' && Number.isFinite(options.page)) {
      pageIndex.value = clamp(Math.round(options.page), 0, pages.value.length - 1)
    }
    if (options.notify !== false) afterChange()
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  } finally {
    loadingContent.value = false
  }
}

async function init() {
  loading.value = true
  loadError.value = ''
  try {
    const shelf = await api.books()
    const wanted = typeof route.query.bookUrl === 'string' ? route.query.bookUrl : ''
    const readable = shelf.filter((item) => !isImageBook(item.type) && !isVideoBook(item.type))
    let target: Book
    if (wanted) {
      const found = shelf.find((item) => item.bookUrl === wanted)
      if (!found) throw new Error('这本书不在书架，请先从发现页加入')
      if (isImageBook(found.type) || isVideoBook(found.type)) {
        throw new Error('浮窗仅支持文字书籍')
      }
      target = found
    } else {
      const latest = readable[0]
      if (!latest) throw new Error('书架还没有可阅读的书籍')
      target = latest
    }
    book.value = target
    chapters.value = await api.chapters(target.bookUrl)
    if (!chapters.value.length) throw new Error('目录为空')

    const wantedIndex = queryNumber('index')
    const index =
      wantedIndex != null && wantedIndex >= 0
        ? Math.min(Math.round(wantedIndex), chapters.value.length - 1)
        : clamp(target.durChapterIndex, 0, chapters.value.length - 1)
    const page = queryNumber('page')
    const percent = queryNumber('percent')
    const storedPermille = target.durChapterPos ?? 0
    const options: { page?: number; percent?: number } = {}
    if (percent != null) {
      options.percent = clamp(percent, 0, 1)
    } else if (page != null && page >= 0) {
      options.page = page
    } else if (storedPermille > 0) {
      options.percent = clamp(storedPermille / 1000, 0, 1)
    } else {
      options.page = 0
    }
    await loadChapter(index, { ...options, notify: false })
    currentTop.value = prefs.alwaysOnTop
    await refreshWindowState()
    afterChange()
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function refreshWindowState() {
  try {
    const state = await api.floatCommand<{ alwaysOnTop?: boolean; hotkey?: string | null }>('state')
    if (typeof state.alwaysOnTop === 'boolean') currentTop.value = state.alwaysOnTop
  } catch {
    // 非桌面壳环境（例如浏览器调试）忽略
  }
}

function flushProgress() {
  if (!book.value) return
  const body = JSON.stringify({
    url: book.value.bookUrl,
    index: chapterIndex.value,
    position: chapterPermille.value,
  })
  try {
    void fetch('/api/books/progress', {
      method: 'PUT',
      keepalive: true,
      headers: { 'Content-Type': 'application/json' },
      body,
    })
  } catch {
    // ignore
  }
}

function saveProgressSoon() {
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => {
    if (!book.value) return
    void api
      .saveProgress(book.value.bookUrl, chapterIndex.value, chapterPermille.value)
      .catch(() => undefined)
  }, 260)
}

function broadcastProgress() {
  if (!book.value) return
  const message: FloatProgressMessage = {
    type: 'progress',
    source: 'float',
    bookUrl: book.value.bookUrl,
    index: chapterIndex.value,
    page: pageIndex.value,
    percent:
      pages.value.length > 1
        ? pageIndex.value / (pages.value.length - 1)
        : 0,
    chapterTitle: chapterTitle.value,
  }
  syncChannel?.postMessage(message)
}

function afterChange() {
  saveProgressSoon()
  broadcastProgress()
}

function turnPage(delta: number) {
  if (loading.value || loadingContent.value || remoteBusy) return
  const now = performance.now()
  if (now - lastTurnAt < 120) return
  lastTurnAt = now
  menu.open = false
  const next = pageIndex.value + delta
  if (next < 0) {
    if (chapterIndex.value > 0) {
      void loadChapter(chapterIndex.value - 1, { page: Number.MAX_SAFE_INTEGER })
    }
    return
  }
  if (next >= pages.value.length) {
    if (chapterIndex.value < chapters.value.length - 1) {
      void loadChapter(chapterIndex.value + 1, { page: 0 })
    }
    return
  }
  pageIndex.value = next
  afterChange()
}

async function onRemoteProgress(message: FloatProgressMessage) {
  if (!book.value || message.bookUrl !== book.value.bookUrl) return
  if (remoteBusy) return
  remoteBusy = true
  try {
    if (message.index !== chapterIndex.value) {
      await loadChapter(message.index, {
        page: typeof message.percent === 'number' ? undefined : message.page ?? undefined,
        percent: typeof message.percent === 'number' ? message.percent : undefined,
        notify: false,
      })
    } else if (typeof message.percent === 'number' && pages.value.length) {
      pageIndex.value = clamp(
        Math.round(message.percent * Math.max(0, pages.value.length - 1)),
        0,
        pages.value.length - 1,
      )
    } else if (typeof message.page === 'number') {
      pageIndex.value = clamp(message.page, 0, pages.value.length - 1)
    }
  } finally {
    remoteBusy = false
  }
}

async function toggleTop() {
  menu.open = false
  const next = !currentTop.value
  try {
    await api.floatCommand('top', { top: next })
    currentTop.value = next
    prefs.alwaysOnTop = next
    await api.saveSettings({ float: { ...prefs } })
    prefsChannel?.postMessage({ type: 'prefs', prefs: { ...prefs } } satisfies FloatPrefsMessage)
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

async function backToMain() {
  menu.open = false
  window.clearTimeout(saveTimer)
  if (book.value) {
    await api.saveProgress(book.value.bookUrl, chapterIndex.value, chapterPermille.value).catch(() => undefined)
  }
  broadcastProgress()
  try {
    await api.floatCommand('back')
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

async function openSettings() {
  menu.open = false
  window.clearTimeout(saveTimer)
  if (book.value) {
    await api.saveProgress(book.value.bookUrl, chapterIndex.value, chapterPermille.value).catch(() => undefined)
  }
  broadcastProgress()
  try {
    await api.floatCommand('settings')
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

async function hideWindow() {
  menu.open = false
  window.clearTimeout(saveTimer)
  if (book.value) {
    await api.saveProgress(book.value.bookUrl, chapterIndex.value, chapterPermille.value).catch(() => undefined)
  }
  broadcastProgress()
  try {
    await api.floatCommand('hide')
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

function openMenu(event: MouseEvent) {
  event.preventDefault()
  menu.x = Math.min(event.clientX, Math.max(8, window.innerWidth - 168))
  menu.y = Math.min(event.clientY, Math.max(8, window.innerHeight - 176))
  menu.open = true
}

let pointerStart: { x: number; y: number } | null = null

function onPointerDown(event: PointerEvent) {
  if (menu.open) {
    menu.open = false
    return
  }
  if (event.button !== 0) return
  pointerStart = { x: event.clientX, y: event.clientY }
  dragging = false
}

function onPointerMove(event: PointerEvent) {
  if (!pointerStart || dragging) return
  if (Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y) > 5) {
    dragging = true
    void api.floatCommand('drag').catch(() => undefined)
  }
}

function onPointerUp(event: PointerEvent) {
  if (!pointerStart) return
  const wasDragging = dragging
  pointerStart = null
  dragging = false
  if (wasDragging || event.button !== 0) return
  if (!prefs.clickZones) return
  const width = window.innerWidth
  if (event.clientX < width * 0.35) turnPage(-1)
  else if (event.clientX > width * 0.65) turnPage(1)
}

function beginResize(event: PointerEvent, dir: string) {
  event.preventDefault()
  event.stopPropagation()
  menu.open = false
  void api.floatCommand('resize', { dir }).catch((e) => {
    showToast(e instanceof Error ? e.message : String(e))
  })
}

function onWheel(event: WheelEvent) {
  if (!prefs.wheel) return
  event.preventDefault()
  if (Math.abs(event.deltaY) < 1) return
  turnPage(event.deltaY > 0 ? 1 : -1)
}

function onKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null
  if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
  if (event.key === 'Escape') {
    menu.open = false
    return
  }
  if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
    event.preventDefault()
    turnPage(-1)
  } else if (event.key === 'ArrowRight' || event.key === 'PageDown' || event.key === ' ') {
    event.preventDefault()
    turnPage(1)
  }
}

function scheduleRepaginate() {
  window.clearTimeout(repaginateTimer)
  repaginateTimer = window.setTimeout(() => {
    viewportWidth.value = Math.max(220, window.innerWidth)
    void paginate(captureAnchor())
  }, 140)
}

async function loadPrefs() {
  try {
    const settings = await api.settings()
    Object.assign(prefs, normalizeFloatPrefs(settings.float))
    currentTop.value = prefs.alwaysOnTop
  } catch {
    // 使用默认值
  }
}

function setupChannels() {
  if (typeof BroadcastChannel === 'undefined') return
  syncChannel = new BroadcastChannel(FLOAT_SYNC_CHANNEL)
  syncChannel.onmessage = (event: MessageEvent<FloatProgressMessage>) => {
    const message = event.data
    if (!message || message.type !== 'progress' || message.source !== 'reader') return
    void onRemoteProgress(message)
  }
  prefsChannel = new BroadcastChannel(FLOAT_PREFS_CHANNEL)
  prefsChannel.onmessage = (event: MessageEvent<FloatPrefsMessage>) => {
    const message = event.data
    if (!message || message.type !== 'prefs') return
    Object.assign(prefs, normalizeFloatPrefs(message.prefs))
    currentTop.value = prefs.alwaysOnTop
    scheduleRepaginate()
  }
}

watch(
  () => [effectiveFontSize.value, prefs.lineHeight, prefs.fontFamily],
  scheduleRepaginate,
)

onMounted(async () => {
  document.documentElement.setAttribute('data-float-window', 'true')
  document.title = '墨读浮窗'
  setupChannels()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('wheel', onWheel, { passive: false })
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('beforeunload', flushProgress)
  window.addEventListener('pagehide', flushProgress)
  resizeObserver = new ResizeObserver(() => scheduleRepaginate())
  if (bodyRef.value) resizeObserver.observe(bodyRef.value)
  await loadPrefs()
  await init()
  viewportWidth.value = Math.max(220, window.innerWidth)
})

onBeforeUnmount(() => {
  window.clearTimeout(saveTimer)
  window.clearTimeout(toastTimer)
  window.clearTimeout(repaginateTimer)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('wheel', onWheel)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('beforeunload', flushProgress)
  window.removeEventListener('pagehide', flushProgress)
  resizeObserver?.disconnect()
  syncChannel?.close()
  prefsChannel?.close()
  document.documentElement.removeAttribute('data-float-window')
})
</script>

<template>
  <div
    class="float-shell"
    :style="shellStyle"
    @contextmenu="openMenu"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
  >
    <div
      v-for="dir in RESIZE_DIRS"
      :key="dir"
      class="float-resize"
      :class="`rz-${dir}`"
      @pointerdown="beginResize($event, dir)"
      @contextmenu.prevent
    />

    <div ref="bodyRef" class="float-body">
      <article v-if="pages.length && currentPage.length" class="float-page">
        <template v-for="(segment, index) in currentPage" :key="index">
          <h1 v-if="segment.type === 'title'">{{ segment.text }}</h1>
          <p v-else :class="{ cont: segment.start > 0 }">{{ segment.text }}</p>
        </template>
      </article>
      <p v-if="loading || loadingContent" class="float-status">正在加载…</p>
      <p v-else-if="loadError" class="float-status clickable" @click="init">{{ loadError }}</p>
      <p v-else-if="!currentPage.length" class="float-status">本章暂无内容</p>
    </div>

    <div v-if="prefs.progress !== 'hidden'" class="float-progress" :class="prefs.progress">
      <i><b :style="{ width: `${progressPercent}%` }" /></i>
      <span>{{ Math.round(progressPercent) }}%</span>
    </div>

    <div
      v-if="menu.open"
      class="float-menu"
      :style="{ left: `${menu.x}px`, top: `${menu.y}px` }"
      @pointerdown.stop
      @contextmenu.prevent
    >
      <button @click="backToMain">返回主窗口</button>
      <button @click="openSettings">打开设置</button>
      <button @click="toggleTop">{{ currentTop ? '取消置顶' : '窗口置顶' }}</button>
      <button @click="hideWindow">隐藏浮窗</button>
    </div>

    <p v-if="toast" class="float-toast">{{ toast }}</p>

    <div ref="measureRef" class="float-measure" aria-hidden="true" />
  </div>
</template>
