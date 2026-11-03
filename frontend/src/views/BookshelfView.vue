<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, SHELF_CHANGED_EVENT, type Book, type BookSource } from '../api/client'
import { coverSrc, hideBrokenCover } from '../api/cover'
import { bookRouteName, bookTypeLabel, isImageBook, isVideoBook } from '../api/bookType'
import {
  loadRatio,
  loadShelfLayout,
  saveRatio,
  saveShelfLayout,
  type CoverRatio,
  type ShelfLayout,
} from '../api/viewPrefs'
import AppIcon from '../components/AppIcon.vue'
import BookDetailDrawer from '../components/BookDetailDrawer.vue'
import SwitchSourceDialog from '../components/SwitchSourceDialog.vue'

const router = useRouter()
const route = useRoute()
const books = ref<Book[]>([])
const loading = ref(true)
const error = ref('')
const detailBook = ref<Book | null>(null)
const switchTarget = ref<Book | null>(null)
const filter = ref<'all' | 'text' | 'image' | 'video'>('all')
const shelfLayout = ref<ShelfLayout>(loadShelfLayout())
const ratio = ref<CoverRatio>(loadRatio())

function setShelfLayout(value: ShelfLayout) {
  shelfLayout.value = value
  saveShelfLayout(value)
}

function setRatio(value: CoverRatio) {
  ratio.value = value
  saveRatio(value)
}

const showAddUrl = ref(false)
const addUrlText = ref('')
const addUrlBusy = ref(false)
const addUrlError = ref('')
const addUrlSummary = ref('')
const addUrlResults = ref<{ url: string; ok: boolean; error?: string }[]>([])
const addUrlSourceKey = ref('')
const addUrlSources = ref<BookSource[]>([])
const addUrlInput = ref<HTMLTextAreaElement | null>(null)

const addUrlLineCount = computed(
  () => addUrlText.value.split('\n').map((line) => line.trim()).filter(Boolean).length,
)
const addUrlHasFailures = computed(() => addUrlResults.value.some((item) => !item.ok))

function openAddUrl() {
  showAddUrl.value = true
  addUrlText.value = ''
  addUrlError.value = ''
  addUrlSummary.value = ''
  addUrlResults.value = []
  addUrlSourceKey.value = ''
  void loadAddUrlSources()
  void nextTick(() => addUrlInput.value?.focus())
}

async function loadAddUrlSources() {
  try {
    addUrlSources.value = (await api.sources()).filter((source) => source.enabled)
  } catch {
    addUrlSources.value = []
  }
}

async function runAddUrls(urls: string[]) {
  if (!urls.length) return
  addUrlBusy.value = true
  addUrlError.value = ''
  try {
    const result = await api.addBooksByUrl(
      urls.join('\n'),
      addUrlSourceKey.value || undefined,
    )
    const pending = new Set(urls)
    const others = addUrlResults.value.filter((item) => !pending.has(item.url))
    addUrlResults.value = [
      ...others,
      ...result.results.map(({ url, ok, error }) => ({ url, ok, error })),
    ]
    const okCount = addUrlResults.value.filter((item) => item.ok).length
    addUrlSummary.value = `成功 ${okCount}/${addUrlResults.value.length}`
    // 即使全部失败（例如“已在书架”），也刷新书架，避免界面停留在旧数据
    if (result.total > 0) {
      filter.value = 'all'
      await load(false)
    }
  } catch (e) {
    addUrlError.value = e instanceof Error ? e.message : String(e)
  } finally {
    addUrlBusy.value = false
  }
}

function submitAddUrl() {
  const urls = addUrlText.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
  addUrlResults.value = []
  addUrlSummary.value = ''
  void runAddUrls(Array.from(new Set(urls)))
}

function retryFailedUrls() {
  const failed = addUrlResults.value.filter((item) => !item.ok).map((item) => item.url)
  void runAddUrls(failed)
}

const filteredBooks = computed(() => {
  if (filter.value === 'all') return books.value
  if (filter.value === 'image') return books.value.filter((book) => isImageBook(book.type))
  if (filter.value === 'video') return books.value.filter((book) => isVideoBook(book.type))
  return books.value.filter(
    (book) => !isImageBook(book.type) && !isVideoBook(book.type),
  )
})

const continueBook = computed(
  () => books.value.find((book) => book.durChapterIndex > 0) ?? books.value[0],
)

function progress(book: Book): number {
  if (!book.totalChapterNum || book.durChapterIndex < 0) return 0
  return Math.min(100, Math.round(((book.durChapterIndex + 1) / book.totalChapterNum) * 100))
}

function openBook(book: Book) {
  router.push({ name: bookRouteName(book.type), query: { bookUrl: book.bookUrl } })
}

function openDetail(book: Book) {
  detailBook.value = book
}

function readFromDetail() {
  const book = detailBook.value
  detailBook.value = null
  if (book) openBook(book)
}

function jumpChapter(index: number) {
  const book = detailBook.value
  detailBook.value = null
  if (book) {
    router.push({
      name: bookRouteName(book.type),
      query: { bookUrl: book.bookUrl, index: String(index) },
    })
  }
}

function openSwitchSource() {
  switchTarget.value = detailBook.value
  detailBook.value = null
}

async function onSwitched(book: Book) {
  switchTarget.value = null
  await load()
  openBook(book)
}

async function onRemoved() {
  detailBook.value = null
  await load()
}

async function load(showLoading = true) {
  if (showLoading) loading.value = true
  error.value = ''
  try {
    books.value = await api.books()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    if (showLoading) loading.value = false
  }
}

function onShelfChanged() {
  void load(false)
}

onMounted(async () => {
  window.addEventListener(SHELF_CHANGED_EVENT, onShelfChanged)
  await load()
  const detailUrl = route.query.detail
  if (typeof detailUrl === 'string') {
    const book = books.value.find((item) => item.bookUrl === detailUrl)
    if (book) {
      if (route.query.switch === '1') switchTarget.value = book
      else detailBook.value = book
    }
  }
})

onUnmounted(() => {
  window.removeEventListener(SHELF_CHANGED_EVENT, onShelfChanged)
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h2>书架</h2>
        <p>{{ books.length }} 本 · 进度保存在本机</p>
      </div>
      <div class="page-head-actions">
        <button class="btn ghost" @click="openAddUrl">
          <AppIcon name="plus" /> 添加网址
        </button>
        <RouterLink to="/search" class="btn primary">
          <AppIcon name="plus" /> 找书
        </RouterLink>
      </div>
    </header>

    <p v-if="error" class="notice error">{{ error }}</p>
    <p v-if="loading" class="muted">正在加载书架…</p>

    <template v-else-if="books.length">
      <div class="result-view-bar shelf-view-bar">
        <div class="shelf-tabs">
          <button
            v-for="tab in [
              { key: 'all', label: '全部' },
              { key: 'text', label: '小说' },
              { key: 'image', label: '漫画' },
              { key: 'video', label: '视频' },
            ]"
            :key="tab.key"
            class="tag kind-chip"
            :class="{ active: filter === tab.key }"
            @click="filter = tab.key as typeof filter"
          >
            {{ tab.label }}
          </button>
        </div>
        <div class="result-view-actions">
          <button
            class="tag kind-chip"
            :class="{ active: shelfLayout === 'wall' }"
            @click="setShelfLayout('wall')"
          >
            封面墙
          </button>
          <button
            class="tag kind-chip"
            :class="{ active: shelfLayout === 'list' }"
            @click="setShelfLayout('list')"
          >
            列表
          </button>
          <button
            class="tag kind-chip"
            :class="{ active: shelfLayout === 'grid' }"
            @click="setShelfLayout('grid')"
          >
            横屏
          </button>
          <select
            v-if="shelfLayout === 'grid'"
            class="ratio-select"
            :value="ratio"
            @change="setRatio(($event.target as HTMLSelectElement).value as CoverRatio)"
          >
            <option value="4:3">封面 4:3</option>
            <option value="16:9">封面 16:9</option>
          </select>
        </div>
      </div>

      <article v-if="continueBook && filter === 'all'" class="continue-card" @click="openBook(continueBook)">
        <div>
          <span class="eyebrow">继续阅读</span>
          <h3>{{ continueBook.name }}</h3>
          <p>{{ continueBook.author }} · {{ continueBook.latestChapterTitle || '暂无章节' }}</p>
          <div class="progress-track">
            <i :style="{ width: `${progress(continueBook)}%` }" />
          </div>
          <span class="muted">已读 {{ progress(continueBook) }}%</span>
        </div>
        <span class="btn ghost">继续阅读 <AppIcon name="arrow-right" /></span>
      </article>

      <div v-if="shelfLayout === 'wall'" class="book-grid">
        <article
          v-for="book in filteredBooks"
          :key="book.bookUrl"
          class="book-card"
          @click="openBook(book)"
        >
          <div class="book-cover">
            <span class="cover-fallback">{{ book.name.slice(0, 1) }}</span>
            <span v-if="bookTypeLabel(book.type) !== '小说'" class="book-type-badge">
              {{ bookTypeLabel(book.type) }}
            </span>
            <img
              v-if="book.coverUrl"
              :src="coverSrc(book.coverUrl, { source: book.origin, bookUrl: book.bookUrl })"
              :alt="book.name"
              loading="lazy"
              decoding="async"
              @error="hideBrokenCover"
            />
          </div>
          <div class="book-name">{{ book.name }}</div>
          <div class="book-meta">{{ book.author || '未知作者' }}</div>
          <div v-if="book.intro" class="result-intro book-intro">{{ book.intro }}</div>
          <div class="progress-track small">
            <i :style="{ width: `${progress(book)}%` }" />
          </div>
          <button
            class="card-more"
            title="书籍详情"
            @click.stop="openDetail(book)"
          >
            <AppIcon name="more" :size="16" />
          </button>
        </article>
      </div>

      <div v-else-if="shelfLayout === 'list'" class="result-list">
        <article
          v-for="book in filteredBooks"
          :key="book.bookUrl"
          class="result-row clickable"
          @click="openBook(book)"
        >
          <div class="result-cover">
            <span class="cover-fallback">{{ book.name.slice(0, 1) }}</span>
            <img
              v-if="book.coverUrl"
              :src="coverSrc(book.coverUrl, { source: book.origin, bookUrl: book.bookUrl })"
              :alt="book.name"
              loading="lazy"
              decoding="async"
              @error="hideBrokenCover"
            />
          </div>
          <div class="result-main">
            <strong>{{ book.name }}</strong>
            <span>
              {{ book.author || '未知作者' }} · {{ book.originName }}
              <em class="type-badge">{{ bookTypeLabel(book.type) }}</em>
            </span>
            <small>{{ book.latestChapterTitle || '暂无章节' }}</small>
            <small v-if="book.intro" class="result-intro">{{ book.intro }}</small>
            <div class="progress-track small">
              <i :style="{ width: `${progress(book)}%` }" />
            </div>
          </div>
          <button class="icon-btn" title="书籍详情" @click.stop="openDetail(book)">
            <AppIcon name="more" :size="16" />
          </button>
        </article>
      </div>

      <div v-else class="result-grid">
        <article
          v-for="book in filteredBooks"
          :key="book.bookUrl"
          class="result-card clickable"
          @click="openBook(book)"
        >
          <div
            class="result-card-cover"
            :style="{ aspectRatio: ratio === '4:3' ? '4 / 3' : '16 / 9' }"
          >
            <span class="cover-fallback">{{ book.name.slice(0, 1) }}</span>
            <img
              v-if="book.coverUrl"
              :src="coverSrc(book.coverUrl, { source: book.origin, bookUrl: book.bookUrl })"
              :alt="book.name"
              loading="lazy"
              decoding="async"
              @error="hideBrokenCover"
            />
            <span class="book-type-badge">{{ bookTypeLabel(book.type) }}</span>
          </div>
          <div class="result-card-main">
            <strong>{{ book.name }}</strong>
            <span>{{ book.author || '未知作者' }} · {{ book.originName }}</span>
            <p v-if="book.intro" class="result-intro">{{ book.intro }}</p>
            <div class="progress-track small">
              <i :style="{ width: `${progress(book)}%` }" />
            </div>
          </div>
          <div class="shelf-card-actions">
            <span class="btn ghost small">继续阅读</span>
            <button class="icon-btn" title="书籍详情" @click.stop="openDetail(book)">
              <AppIcon name="more" :size="16" />
            </button>
          </div>
        </article>
      </div>

      <div v-if="!filteredBooks.length" class="empty-state">
        <AppIcon name="book" :size="30" />
        <h3>该分类下没有书</h3>
        <p>切换回“全部”看看。</p>
      </div>
    </template>

    <div v-else class="empty-state">
      <AppIcon name="book" :size="34" />
      <h3>书架还是空的</h3>
      <p>先去发现页搜索，或者导入一个书源。</p>
      <div class="empty-actions">
        <RouterLink to="/search" class="btn primary">去发现</RouterLink>
        <RouterLink to="/sources" class="btn ghost">导入书源</RouterLink>
      </div>
    </div>

    <BookDetailDrawer
      v-if="detailBook"
      :book="detailBook"
      @close="detailBook = null"
      @read="readFromDetail"
      @jump="jumpChapter"
      @switch="openSwitchSource"
      @removed="onRemoved"
    />
    <SwitchSourceDialog
      v-if="switchTarget"
      :book="switchTarget"
      @close="switchTarget = null"
      @switched="onSwitched"
    />

    <Teleport to="body">
      <div v-if="showAddUrl" class="panel-scrim dialog" @click="showAddUrl = false" />
      <div v-if="showAddUrl" class="switch-dialog add-url-dialog" role="dialog" aria-modal="true">
        <header class="switch-head add-url-head">
          <div>
            <h3>添加书籍网址</h3>
            <p>自动按「origin 选项 → 书源地址前缀 → bookUrlPattern」匹配书源，也可以手动指定。</p>
          </div>
          <button class="icon-btn" @click="showAddUrl = false"><AppIcon name="x" /></button>
        </header>
        <div class="add-url-body">
          <div class="add-url-label">
            <span>书籍网址</span>
            <span class="muted">{{ addUrlLineCount }} 条</span>
          </div>
          <textarea
            ref="addUrlInput"
            v-model="addUrlText"
            class="add-url-textarea"
            spellcheck="false"
            placeholder="每行一个链接，例如：&#10;https://example.com/book/123.html&#10;https://site.com/novel/1,{&quot;origin&quot;:&quot;https://site.com&quot;}"
          />
          <div class="add-url-source-row">
            <span class="add-url-label-text">书源</span>
            <select v-model="addUrlSourceKey" class="add-url-select">
              <option value="">自动匹配（推荐）</option>
              <option
                v-for="source in addUrlSources"
                :key="source.bookSourceUrl"
                :value="source.bookSourceUrl"
              >
                {{ source.bookSourceName }}
              </option>
            </select>
            <span class="muted">镜像域名自动匹配不到时，指定该站点的书源</span>
          </div>
          <p v-if="addUrlError" class="notice error">{{ addUrlError }}</p>
          <section v-if="addUrlResults.length" class="add-url-report">
            <header class="add-url-report-head">
              <strong>{{ addUrlSummary }}</strong>
              <button
                v-if="addUrlHasFailures"
                class="btn ghost small"
                :disabled="addUrlBusy"
                @click="retryFailedUrls"
              >
                重试失败项
              </button>
            </header>
            <div class="add-url-results">
              <div
                v-for="item in addUrlResults"
                :key="item.url"
                class="add-url-result"
                :class="{ failed: !item.ok }"
              >
                <span class="add-url-dot" />
                <span class="add-url-link" :title="item.url">{{ item.url }}</span>
                <span v-if="item.error" class="add-url-error">{{ item.error }}</span>
                <span v-else class="add-url-ok">已加入</span>
              </div>
            </div>
          </section>
        </div>
        <footer class="add-url-foot">
          <span class="muted">只匹配已启用的书源</span>
          <div class="add-url-foot-actions">
            <button class="btn ghost small" @click="showAddUrl = false">关闭</button>
            <button
              class="btn primary small"
              :disabled="addUrlBusy || addUrlLineCount === 0"
              @click="submitAddUrl"
            >
              {{ addUrlBusy ? '添加中…' : '添加' }}
            </button>
          </div>
        </footer>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.shelf-view-bar {
  margin-bottom: 16px;
}

.shelf-view-bar .shelf-tabs {
  margin: 0;
}

.book-intro {
  margin-top: 4px;
}

.shelf-card-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.page-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.add-url-dialog {
  width: min(680px, 94vw);
  max-height: 82vh;
}

.add-url-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 20px 4px;
  overflow: auto;
}

.add-url-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-3);
}

.add-url-textarea {
  min-height: 140px;
  max-height: 240px;
  resize: vertical;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--surface, #16181d);
  color: inherit;
  font-family: var(--font-mono, Consolas, monospace);
  font-size: 12.5px;
  line-height: 1.65;
  transition: border-color 150ms ease, box-shadow 150ms ease;
}

.add-url-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px rgb(217 162 94 / 15%);
}

.add-url-source-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
}

.add-url-label-text {
  color: var(--text-3);
}

.add-url-select {
  max-width: 260px;
  padding: 7px 10px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--surface, #16181d);
  color: var(--text);
  font-size: 12.5px;
}

.add-url-report {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface, #16181d);
}

.add-url-report-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}

.add-url-results {
  display: flex;
  flex-direction: column;
  max-height: 190px;
  overflow: auto;
}

.add-url-result {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border-bottom: 1px solid rgb(255 255 255 / 4%);
  font-size: 12px;
}

.add-url-result:last-child {
  border-bottom: none;
}

.add-url-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
}

.add-url-result.failed .add-url-dot {
  background: #e08a8a;
}

.add-url-link {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-3);
}

.add-url-error {
  flex: none;
  max-width: 46%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #e08a8a;
}

.add-url-ok {
  flex: none;
  color: var(--accent-strong);
}

.add-url-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  padding: 12px 20px 16px;
  border-top: 1px solid var(--border);
}

.add-url-foot-actions {
  display: flex;
  gap: 10px;
}
</style>
