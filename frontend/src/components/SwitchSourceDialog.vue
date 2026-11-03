<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type Book, type SearchBook } from '../api/client'
import { coverSrc, hideBrokenCover } from '../api/cover'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ book: Book }>()
const emit = defineEmits<{
  close: []
  switched: [book: Book]
}>()

const candidates = ref<SearchBook[]>([])
const loading = ref(true)
const error = ref('')
const switching = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const results = await api.search(props.book.name)
    const keyword = props.book.name.replace(/\s/g, '')
    candidates.value = results.filter(
      (item) =>
        item.bookUrl !== props.book.bookUrl &&
        item.name.replace(/\s/g, '').includes(keyword.slice(0, 8)),
    )
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function switchTo(candidate: SearchBook) {
  switching.value = candidate.bookUrl
  error.value = ''
  try {
    const book = await api.switchBook(
      props.book.bookUrl,
      candidate.bookUrl,
      candidate.origin ?? undefined,
    )
    emit('switched', book)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    switching.value = ''
  }
}

onMounted(load)
</script>

<template>
  <Teleport to="body">
    <div class="panel-scrim dialog" @click="emit('close')" />
    <div class="switch-dialog" role="dialog" aria-modal="true">
      <header class="switch-head">
        <div>
          <h3>换源 · {{ book.name }}</h3>
          <p>从已启用书源中查找同名书籍，切换后保留阅读进度。</p>
        </div>
        <button class="icon-btn" @click="emit('close')"><AppIcon name="x" /></button>
      </header>

      <p v-if="loading" class="muted switch-empty">正在搜索其他书源…</p>
      <p v-else-if="error" class="notice error switch-empty">{{ error }}</p>
      <p v-else-if="!candidates.length" class="muted switch-empty">
        没有找到可替换的来源。
      </p>

      <div v-else class="switch-list">
        <article v-for="candidate in candidates" :key="candidate.bookUrl" class="switch-row">
          <div class="switch-cover">
            <span class="cover-fallback">{{ candidate.name.slice(0, 1) }}</span>
            <img
              v-if="candidate.coverUrl"
              :src="coverSrc(candidate.coverUrl, { source: candidate.origin, bookUrl: candidate.bookUrl })"
              :alt="candidate.name"
              decoding="async"
              @error="hideBrokenCover"
            />
          </div>
          <div class="switch-main">
            <strong>{{ candidate.name }}</strong>
            <span>{{ candidate.author || '未知作者' }} · {{ candidate.originName }}</span>
            <small>{{ candidate.lastChapter || candidate.wordCount || '' }}</small>
          </div>
          <button
            class="btn primary small"
            :disabled="!!switching"
            @click="switchTo(candidate)"
          >
            {{ switching === candidate.bookUrl ? '切换中…' : '切换' }}
          </button>
        </article>
      </div>
    </div>
  </Teleport>
</template>
