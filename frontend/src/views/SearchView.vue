<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, addSearchBookToShelf, type BookSource, type SearchBook } from '../api/client'
import { isImageBook, isVideoBook } from '../api/bookType'
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

const route = useRoute()
const router = useRouter()

const keyword = ref('')
const results = ref<SearchBook[]>([])
const sources = ref<BookSource[]>([])
const loading = ref(false)
const error = ref('')
const message = ref('')
const added = ref<Record<string, boolean>>({})
const adding = ref<Record<string, boolean>>({})
const progress = ref({ done: 0, total: 0, failed: 0 })
const resultFilter = ref<'all' | 'text' | 'image' | 'video'>('all')
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

const filteredResults = computed(() => {
  if (resultFilter.value === 'image') {
    return results.value.filter((book) => isImageBook(book.type))
  }
  if (resultFilter.value === 'video') {
    return results.value.filter((book) => isVideoBook(book.type))
  }
  if (resultFilter.value === 'text') {
    return results.value.filter((book) => !isImageBook(book.type) && !isVideoBook(book.type))
  }
  return results.value
})

let closeStream: (() => void) | null = null

async function loadSources() {
  try {
    sources.value = await api.sources()
  } catch {
    sources.value = []
  }
}

function runSearch() {
  const value = keyword.value.trim()
  if (!value) return
  void router.replace({ query: { ...route.query, q: value } })
  closeStream?.()
  loading.value = true
  error.value = ''
  message.value = ''
  results.value = []
  added.value = {}
  progress.value = { done: 0, total: 0, failed: 0 }
  closeStream = api.searchStream(value, {
    onStart: () => {
      progress.value.total++
    },
    onResults: (s) => {
      progress.value.done++
      results.value.push(...s.books)
    },
    onError: () => {
      progress.value.done++
      progress.value.failed++
    },
    onDone: (s) => {
      loading.value = false
      void markShelfItems()
      if (s.total < 0) {
        error.value = '搜索连接中断'
      } else if (!results.value.length && !s.error) {
        message.value = '没有找到结果，换个关键词或检查书源。'
      }
    },
  })
}

onBeforeUnmount(() => {
  closeStream?.()
  closeStream = null
})

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

async function markShelfItems() {  try {
    const shelf = await api.books()
    const urls = new Set(shelf.map((book) => book.bookUrl))
    for (const book of results.value) {
      if (urls.has(book.bookUrl)) added.value[book.bookUrl] = true
    }
  } catch {
    // 标记失败不影响搜索
  }
}

onMounted(async () => {
  await loadSources()
  const initial = route.query.q
  if (typeof initial === 'string' && initial.trim()) {
    keyword.value = initial
    await runSearch()
  }
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h2>发现</h2>
        <p>{{ sources.length }} 个书源 · 一次搜遍</p>
      </div>
      <RouterLink to="/sources" class="btn ghost">管理书源</RouterLink>
    </header>

    <form class="search-bar" @submit.prevent="runSearch">
      <AppIcon name="search" />
      <input v-model="keyword" placeholder="搜索书名或作者" autocomplete="off" />
      <button class="btn primary" type="submit" :disabled="loading">
        {{ loading ? '搜索中…' : '搜索' }}
      </button>
    </form>

    <p v-if="sources.length === 0" class="notice">
      还没有书源。<RouterLink to="/sources">先去导入一个书源</RouterLink>。
    </p>
    <p v-if="error" class="notice error">{{ error }}</p>
    <p v-if="message" class="notice">{{ message }}</p>
    <p v-if="loading" class="notice">
      正在搜索… 已完成 {{ progress.done }}/{{ progress.total || '…' }}
      <template v-if="progress.failed">（{{ progress.failed }} 个书源失败）</template>
    </p>

    <div v-if="results.length" class="result-view-bar">
      <div class="kind-chips">
        <button
          v-for="tab in [
            { key: 'all', label: '全部' },
            { key: 'text', label: '小说' },
            { key: 'image', label: '漫画' },
            { key: 'video', label: '视频' },
          ]"
          :key="tab.key"
          class="tag kind-chip"
          :class="{ active: resultFilter === tab.key }"
          @click="resultFilter = tab.key as typeof resultFilter"
        >
          {{ tab.label }}
        </button>
      </div>
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
      v-if="filteredResults.length"
      :books="filteredResults"
      :layout="layout"
      :ratio="ratio"
      :added="added"
      :adding="adding"
      @add="addToShelf"
      @open="preview = $event"
    />

    <div v-else-if="!loading && !results.length" class="empty-state">
      <AppIcon name="compass" :size="34" />
      <h3>找一本想读的书</h3>
      <p>输入书名或作者，从所有启用的书源里搜索。</p>
    </div>

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
