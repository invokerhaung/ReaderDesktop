<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, addSearchBookToShelf, type BookSource, type SearchBook } from '../api/client'
import {
  loadLayout,
  loadRatio,
  saveLayout,
  saveRatio,
  type CoverRatio,
  type ResultLayout,
} from '../api/viewPrefs'
import AppIcon from '../components/AppIcon.vue'
import BookResultList from '../components/BookResultList.vue'
import BookPreviewDrawer from '../components/BookPreviewDrawer.vue'

const sources = ref<BookSource[]>([])
const selectedKey = ref('')
const kinds = ref<{ title: string; url: string }[]>([])
const activeKind = ref('')
const results = ref<SearchBook[]>([])
const loadingKinds = ref(false)
const loadingBooks = ref(false)
const error = ref('')
const message = ref('')
const added = ref<Record<string, boolean>>({})
const adding = ref<Record<string, boolean>>({})
const page = ref(1)
const layout = ref<ResultLayout>(loadLayout())
const ratio = ref<CoverRatio>(loadRatio())
const preview = ref<SearchBook | null>(null)

function setLayout(value: ResultLayout) {
  layout.value = value
  saveLayout(value)
}

function setRatio(value: CoverRatio) {
  ratio.value = value
  saveRatio(value)
}

const enabledSources = () => sources.value.filter((s) => s.enabled)

async function loadSources() {
  try {
    sources.value = await api.sources()
  } catch {
    sources.value = []
  }
}

async function selectSource(key: string) {
  selectedKey.value = key
  kinds.value = []
  activeKind.value = ''
  results.value = []
  error.value = ''
  message.value = ''
  if (!key) return
  loadingKinds.value = true
  try {
    const detail = await api.exploreKinds(key)
    kinds.value = detail.kinds
    if (!detail.kinds.length) message.value = '该书源未配置发现栏目。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loadingKinds.value = false
  }
}

async function loadKind(kind: { title: string; url: string }, nextPage = 1) {
  activeKind.value = kind.title
  loadingBooks.value = true
  error.value = ''
  message.value = ''
  if (nextPage === 1) results.value = []
  page.value = nextPage
  try {
    const books = await api.exploreBooks(selectedKey.value, kind.url, nextPage)
    if (nextPage === 1) results.value = books
    else results.value.push(...books)
    void markShelfItems()
    if (!results.value.length) message.value = '这个栏目没有返回内容。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loadingBooks.value = false
  }
}

async function addToShelf(book: SearchBook) {
  if (added.value[book.bookUrl] || adding.value[book.bookUrl]) return
  adding.value[book.bookUrl] = true
  try {
    await addSearchBookToShelf(book)
    added.value[book.bookUrl] = true
    message.value = `已加入书架：${book.name}`
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    adding.value[book.bookUrl] = false
  }
}

async function markShelfItems() {
  try {
    const shelf = await api.books()
    const urls = new Set(shelf.map((book) => book.bookUrl))
    for (const book of results.value) {
      if (urls.has(book.bookUrl)) added.value[book.bookUrl] = true
    }
  } catch {
    // 标记失败不影响浏览
  }
}

onMounted(loadSources)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h2>发现</h2>
        <p>按书源浏览各站点的分类与榜单</p>
      </div>
      <RouterLink to="/search" class="btn ghost">关键字搜索</RouterLink>
    </header>

    <p v-if="!enabledSources().length" class="notice">
      还没有可用的书源。<RouterLink to="/sources">先去导入一个书源</RouterLink>。
    </p>

    <template v-else>
      <div v-if="enabledSources().length" class="source-picker">
        <button
          v-for="source in enabledSources()"
          :key="source.bookSourceUrl"
          class="tag kind-chip source-chip"
          :class="{ active: selectedKey === source.bookSourceUrl }"
          :title="source.bookSourceUrl"
          @click="selectSource(source.bookSourceUrl)"
        >
          {{ source.bookSourceName }}
        </button>
      </div>

      <p v-if="loadingKinds" class="muted">正在加载栏目…</p>
      <div v-else-if="kinds.length" class="kind-chips">
        <button
          v-for="kind in kinds"
          :key="kind.url"
          class="tag kind-chip"
          :class="{ active: activeKind === kind.title }"
          @click="loadKind(kind)"
        >
          {{ kind.title }}
        </button>
      </div>

      <p v-if="error" class="notice error">{{ error }}</p>
      <p v-if="message" class="notice">{{ message }}</p>
      <p v-if="loadingBooks" class="muted">正在加载内容…</p>

      <div v-if="results.length" class="result-view-bar">
        <span class="muted">{{ results.length }} 本</span>
        <div class="result-view-actions">
          <button
            class="tag kind-chip"
            :class="{ active: layout === 'list' }"
            @click="setLayout('list')"
          >
            列表
          </button>
          <button
            class="tag kind-chip"
            :class="{ active: layout === 'grid' }"
            @click="setLayout('grid')"
          >
            横屏
          </button>
          <select
            v-if="layout === 'grid'"
            class="ratio-select"
            :value="ratio"
            @change="setRatio(($event.target as HTMLSelectElement).value as CoverRatio)"
          >
            <option value="4:3">封面 4:3</option>
            <option value="16:9">封面 16:9</option>
          </select>
        </div>
      </div>

      <BookResultList
        v-if="results.length"
        :books="results"
        :layout="layout"
        :ratio="ratio"
        :added="added"
        :adding="adding"
        @add="addToShelf"
        @open="preview = $event"
      />

      <div v-if="results.length && activeKind && !loadingBooks" class="load-more">
        <button class="btn ghost" @click="loadKind({ title: activeKind, url: kinds.find(k => k.title === activeKind)!.url }, page + 1)">
          加载更多
        </button>
      </div>

      <div v-else-if="!results.length && !loadingBooks && !loadingKinds && selectedKey && kinds.length" class="empty-state">
        <AppIcon name="compass" :size="34" />
        <h3>选择一个栏目</h3>
        <p>点击上方栏目开始浏览。</p>
      </div>
    </template>

    <BookPreviewDrawer
      v-if="preview"
      :book="preview"
      :added="!!added[preview.bookUrl]"
      :adding="!!adding[preview.bookUrl]"
      @close="preview = null"
      @add="addToShelf"
      @added="added[$event.bookUrl] = true"
    />
  </section>
</template>

<style scoped>
.source-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 4px 0 18px;
}

.source-chip {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
