<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, type Book, type Chapter } from '../api/client'
import { mediaSrc } from '../api/media'
import { bookTypeLabel } from '../api/bookType'
import AppIcon from '../components/AppIcon.vue'

interface ComicPage {
  src: string
  alt?: string | null
  loaded: boolean
  failed: boolean
}

const route = useRoute()
const router = useRouter()
const bookUrl = String(route.query.bookUrl ?? '')
const previewId = String(route.query.preview ?? '')
const isPreview = !!previewId

function saveProgress(position: number) {
  if (isPreview || index.value < 0) return
  void api.saveProgress(bookUrl, index.value, position)
}

function goBack() {
  if (router.options.history.state.back) router.back()
  else router.push('/')
}

const book = ref<Book | null>(null)
const chapters = ref<Chapter[]>([])
const index = ref(-1)
const pages = ref<ComicPage[]>([])
const loading = ref(true)
const error = ref('')
const scale = ref(1)
const showUi = ref(true)
const showToc = ref(false)
const toast = ref('')

const imageEls = new Map<number, HTMLImageElement>()
const pageRefs = ref<HTMLElement[]>([])
const stage = ref<HTMLDivElement | null>(null)
let uiTimer: number | undefined
let scrollTimer: number | undefined
let toastTimer: number | undefined
let pendingRestore = 0

const chapter = computed(() => chapters.value[index.value] ?? null)
const chapterTitle = computed(() => chapter.value?.title ?? '')
const hasPrev = computed(() => index.value > 0)
const hasNext = computed(() => index.value >= 0 && index.value < chapters.value.length - 1)

function showToast(text: string) {
  toast.value = text
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
  }, 1800)
}

function wakeUi() {
  showUi.value = true
  window.clearTimeout(uiTimer)
  uiTimer = window.setTimeout(() => {
    if (!showToc.value) showUi.value = false
  }, 3000)
}

/** 释放当前章的全部图片对象，避免跨章常驻内存。 */
function disposeChapter() {
  imageEls.forEach((img) => {
    img.onload = null
    img.onerror = null
    img.src = ''
  })
  imageEls.clear()
  pages.value = []
  pageRefs.value = []
}

function preloadPage(i: number, src: string) {
  const img = new Image()
  img.decoding = 'async'
  imageEls.set(i, img)
  img.onload = () => {
    const page = pages.value[i]
    if (page) page.loaded = true
  }
  img.onerror = () => {
    const page = pages.value[i]
    if (page) page.failed = true
  }
  img.src = src
}

async function loadChapter(target: number, restorePage = 0) {
  if (target < 0 || target >= chapters.value.length) return
  loading.value = true
  error.value = ''
  pendingRestore = restorePage
  disposeChapter()
  index.value = target
  router.replace({
    name: 'comic',
    query: isPreview
      ? { preview: previewId, index: String(target) }
      : { bookUrl, index: String(target) },
  })
  try {
    const content = isPreview
      ? await api.previewContent(previewId, target)
      : await api.content(bookUrl, target)
    const images = (content.blocks ?? []).filter(
      (block) => block.type === 'image' && !!block.src,
    )
    if (!images.length) {
      error.value = '本章没有图片内容'
      return
    }
    pages.value = images.map((block) => ({
      src: mediaSrc(block.src!, { source: book.value?.origin, bookUrl }),
      alt: block.alt,
      loaded: false,
      failed: false,
    }))
    pages.value.forEach((page, i) => preloadPage(i, page.src))
    await nextTick()
    if (pendingRestore > 0) scrollToPage(pendingRestore)
    else {
      stage.value?.scrollTo({ top: 0 })
      if (!isPreview) void api.saveProgress(bookUrl, target, 0)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function scrollToPage(pageIndex: number) {
  const el = pageRefs.value[pageIndex]
  if (el) el.scrollIntoView({ block: 'start' })
}

function currentPage(): number {
  const host = stage.value
  if (!host) return 0
  const top = host.getBoundingClientRect().top
  for (let i = 0; i < pageRefs.value.length; i++) {
    const el = pageRefs.value[i]
    if (!el) continue
    const rect = el.getBoundingClientRect()
    if (rect.bottom > top + 8) return i
  }
  return 0
}

function onScroll() {
  wakeUi()
  window.clearTimeout(scrollTimer)
  scrollTimer = window.setTimeout(() => {
    saveProgress(currentPage())
  }, 600)
}

function gotoChapter(target: number) {
  if (target < 0 || target >= chapters.value.length) return
  showToc.value = false
  void loadChapter(target)
}

function zoomBy(delta: number) {
  scale.value = Math.min(3, Math.max(0.5, Math.round((scale.value + delta) * 10) / 10))
}

function onWheel(event: WheelEvent) {
  if (!event.ctrlKey) return
  event.preventDefault()
  zoomBy(event.deltaY < 0 ? 0.1 : -0.1)
}

async function addBookmark() {
  if (index.value < 0) return
  if (isPreview) {
    showToast('试看模式不支持书签')
    return
  }
  try {
    await api.addBookmark({
      bookUrl,
      chapterIndex: index.value,
      chapterTitle: chapterTitle.value,
      mode: 'comic',
      position: currentPage(),
    })
    showToast('已添加书签')
  } catch (e) {
    showToast(e instanceof Error ? e.message : String(e))
  }
}

async function retryPage(page: ComicPage, i: number) {
  page.failed = false
  page.loaded = false
  preloadPage(i, page.src)
}

function onKeydown(event: KeyboardEvent) {
  if (event.target instanceof HTMLInputElement) return
  if (event.key === 'ArrowRight' || event.key === 'PageDown' || event.key === ' ') {
    event.preventDefault()
    if (hasNext.value) gotoChapter(index.value + 1)
  } else if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
    event.preventDefault()
    if (hasPrev.value) gotoChapter(index.value - 1)
  } else if (event.key === 'Escape') {
    if (showToc.value) showToc.value = false
    else goBack()
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  try {
    if (isPreview) {
      const session = await api.previewSession(previewId)
      book.value = session.book
      chapters.value = session.chapters
    } else {
      const books = await api.books()
      book.value = books.find((item) => item.bookUrl === bookUrl) ?? null
      if (!book.value) {
        error.value = '书籍不在书架'
        loading.value = false
        return
      }
      chapters.value = await api.chapters(bookUrl)
    }
    if (!chapters.value.length) {
      error.value = '目录为空'
      loading.value = false
      return
    }
    const queryIndex = Number(route.query.index)
    const start = Number.isFinite(queryIndex) ? queryIndex : book.value.durChapterIndex
    const target = Math.min(Math.max(start || 0, 0), chapters.value.length - 1)
    const restore = !isPreview && target === book.value.durChapterIndex
      ? book.value.durChapterPos ?? 0
      : 0
    await loadChapter(target, restore)
    wakeUi()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.clearTimeout(uiTimer)
  window.clearTimeout(scrollTimer)
  saveProgress(currentPage())
  if (isPreview) void api.closePreview(previewId).catch(() => undefined)
  disposeChapter()
})
</script>

<template>
  <div class="comic-shell" @mousemove="wakeUi" @wheel="onWheel">
    <div ref="stage" class="comic-stage" @scroll.passive="onScroll" @click="showUi = !showUi">
      <p v-if="loading" class="comic-hint">正在加载 {{ chapterTitle || '章节' }}…</p>
      <p v-else-if="error" class="comic-hint error">{{ error }}</p>
      <div v-else class="comic-pages" :style="{ width: `${Math.round(scale * 100)}%` }">
        <div
          v-for="(page, i) in pages"
          :key="`${index}-${i}`"
          :ref="(el) => (pageRefs[i] = el as HTMLElement)"
          class="comic-page"
        >
          <img v-if="!page.failed" :src="page.src" :alt="page.alt ?? `第 ${i + 1} 页`" />
          <button v-else class="btn ghost" @click.stop="retryPage(page, i)">第 {{ i + 1 }} 页加载失败，重试</button>
        </div>
      </div>
    </div>

    <header class="comic-bar top" :class="{ hidden: !showUi }">
      <button class="icon-btn" title="返回上一层" @click="goBack">
        <AppIcon name="arrow-right" class="flip" />
      </button>
      <div class="comic-title">
        <strong>{{ book?.name || '漫画' }}</strong>
        <span>{{ chapterTitle }} · {{ pages.length }} 页</span>
      </div>
      <div class="comic-actions">
        <button class="icon-btn" title="缩小" @click="zoomBy(-0.1)">－</button>
        <span class="comic-scale">{{ Math.round(scale * 100) }}%</span>
        <button class="icon-btn" title="放大" @click="zoomBy(0.1)">＋</button>
        <button class="icon-btn" title="添加书签" @click="addBookmark"><AppIcon name="bookmark" /></button>
        <button class="icon-btn" title="目录" @click="showToc = true"><AppIcon name="list" /></button>
      </div>
    </header>

    <footer class="comic-bar bottom" :class="{ hidden: !showUi }">
      <button class="btn ghost small" :disabled="!hasPrev" @click="gotoChapter(index - 1)">上一话</button>
      <span class="comic-progress">{{ index + 1 }} / {{ chapters.length }}</span>
      <button class="btn ghost small" :disabled="!hasNext" @click="gotoChapter(index + 1)">下一话</button>
    </footer>

    <div v-if="toast" class="comic-toast">{{ toast }}</div>

    <Teleport to="body">
      <div v-if="showToc" class="comic-toc-scrim" @click="showToc = false" />
      <aside v-if="showToc" class="comic-toc">
        <header>
          <strong>目录</strong>
          <span class="muted">{{ bookTypeLabel(book?.type) }} · {{ chapters.length }} 话</span>
          <button class="icon-btn" @click="showToc = false"><AppIcon name="x" /></button>
        </header>
        <ol>
          <li
            v-for="item in chapters"
            :key="item.index"
            :class="{ active: item.index === index }"
            @click="gotoChapter(item.index)"
          >
            <span>{{ item.title }}</span>
          </li>
        </ol>
      </aside>
    </Teleport>
  </div>
</template>

<style scoped>
.comic-shell {
  position: fixed;
  inset: 0;
  background: #0b0c0e;
  color: #e9e6e0;
  overflow: hidden;
}

.comic-stage {
  position: absolute;
  inset: 0;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
}

.comic-pages {
  margin: 0 auto;
  min-width: 100%;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.comic-page {
  width: 100%;
  display: flex;
  justify-content: center;
}

.comic-page img {
  display: block;
  width: 100%;
  height: auto;
  user-select: none;
}

.comic-hint {
  margin: 40vh auto 0;
  text-align: center;
  color: #8a8880;
}

.comic-hint.error {
  color: #e08a8a;
}

.comic-bar {
  position: absolute;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: linear-gradient(180deg, rgb(10 11 13 / 92%), rgb(10 11 13 / 70%));
  backdrop-filter: blur(8px);
  transition: transform 220ms ease, opacity 220ms ease;
  z-index: 5;
}

.comic-bar.top {
  top: 0;
  border-bottom: 1px solid #23252b;
}

.comic-bar.bottom {
  bottom: 0;
  justify-content: space-between;
  border-top: 1px solid #23252b;
  background: linear-gradient(0deg, rgb(10 11 13 / 92%), rgb(10 11 13 / 70%));
}

.comic-bar.hidden {
  opacity: 0;
  pointer-events: none;
}

.comic-bar.top.hidden {
  transform: translateY(-100%);
}

.comic-bar.bottom.hidden {
  transform: translateY(100%);
}

.comic-title {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}

.comic-title strong {
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.comic-title span {
  font-size: 11px;
  color: #8a8880;
}

.comic-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.comic-scale {
  font-size: 11px;
  color: #8a8880;
  min-width: 38px;
  text-align: center;
}

.flip {
  transform: rotate(180deg);
}

.comic-progress {
  font-size: 12px;
  color: #8a8880;
}

.comic-toast {
  position: absolute;
  left: 50%;
  bottom: 84px;
  transform: translateX(-50%);
  padding: 8px 16px;
  border-radius: 999px;
  background: rgb(20 22 26 / 92%);
  border: 1px solid #2c2f36;
  font-size: 12px;
  z-index: 8;
}

.comic-toc-scrim {
  position: fixed;
  inset: 0;
  background: rgb(0 0 0 / 55%);
  z-index: 20;
}

.comic-toc {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(360px, 86vw);
  background: #131519;
  border-left: 1px solid #23252b;
  display: flex;
  flex-direction: column;
  z-index: 21;
}

.comic-toc header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid #23252b;
}

.comic-toc header strong {
  flex: 1;
}

.comic-toc ol {
  margin: 0;
  padding: 8px 0;
  overflow: auto;
  list-style: none;
}

.comic-toc li {
  padding: 9px 18px;
  font-size: 13px;
  color: #b9b5ac;
  cursor: pointer;
  border-left: 2px solid transparent;
}

.comic-toc li:hover {
  background: #1a1d22;
}

.comic-toc li.active {
  color: #e5b77b;
  border-left-color: #d9a25e;
  background: #1a1d22;
}
</style>
