<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api, type Book, type Chapter } from '../api/client'
import { coverSrc, hideBrokenCover } from '../api/cover'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ book: Book }>()
const emit = defineEmits<{
  close: []
  read: []
  jump: [index: number]
  switch: []
  removed: []
}>()

const chapters = ref<Chapter[]>([])
const loading = ref(false)
const error = ref('')
const removing = ref(false)

function progress(): number {
  if (!props.book.totalChapterNum || props.book.durChapterIndex < 0) return 0
  return Math.min(
    100,
    Math.round(((props.book.durChapterIndex + 1) / props.book.totalChapterNum) * 100),
  )
}

async function loadChapters() {
  loading.value = true
  error.value = ''
  try {
    chapters.value = await api.chapters(props.book.bookUrl)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function removeBook() {
  if (!window.confirm(`把《${props.book.name}》移出书架？章节缓存也会一起删除。`)) return
  removing.value = true
  try {
    await api.deleteBook(props.book.bookUrl)
    emit('removed')
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    removing.value = false
  }
}

watch(() => props.book.bookUrl, loadChapters)
onMounted(loadChapters)
</script>

<template>
  <Teleport to="body">
    <div class="panel-scrim" @click="emit('close')" />
    <aside class="detail-drawer">
      <header class="detail-head">
        <strong>书籍详情</strong>
        <button class="icon-btn" @click="emit('close')"><AppIcon name="x" /></button>
      </header>
      <div class="detail-body">
        <div class="detail-hero">
          <div class="detail-cover">
            <span class="cover-fallback">{{ book.name.slice(0, 1) }}</span>
            <img
              v-if="book.coverUrl"
              :src="coverSrc(book.coverUrl, { source: book.origin, bookUrl: book.bookUrl })"
              :alt="book.name"
              decoding="async"
              @error="hideBrokenCover"
            />
          </div>
          <div class="detail-info">
            <h3>{{ book.name }}</h3>
            <p>{{ book.author || '未知作者' }} · {{ book.originName }}</p>
            <div class="detail-tags">
              <span class="tag">{{ book.kind || '未分类' }}</span>
              <span class="tag">{{ book.totalChapterNum }} 章</span>
              <span class="tag">已读 {{ progress() }}%</span>
            </div>
          </div>
        </div>

        <div class="detail-actions">
          <button class="btn primary" @click="emit('read')">
            <AppIcon name="arrow-right" /> 继续阅读
          </button>
          <button class="btn ghost" @click="emit('switch')">
            <AppIcon name="refresh" /> 换源
          </button>
          <button class="btn ghost danger" :disabled="removing" @click="removeBook">
            <AppIcon name="trash" /> 移出书架
          </button>
        </div>

        <p class="detail-intro">{{ book.intro || '暂无简介' }}</p>
        <p class="detail-latest">
          最新：{{ book.latestChapterTitle || '暂无章节' }}
        </p>

        <p v-if="error" class="notice error">{{ error }}</p>
        <div class="detail-chapters">
          <div class="detail-chapters-head">
            <strong>目录</strong>
            <span>{{ loading ? '加载中…' : `共 ${chapters.length} 章` }}</span>
          </div>
          <button
            v-for="chapter in chapters.slice(0, 12)"
            :key="chapter.index"
            class="detail-chapter"
            @click="emit('jump', chapter.index)"
          >
            {{ chapter.title }}
          </button>
        </div>
      </div>
    </aside>
  </Teleport>
</template>
