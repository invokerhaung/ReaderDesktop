<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type SearchBook } from '../api/client'
import { coverSrc, hideBrokenCover } from '../api/cover'
import { bookRouteName, bookTypeLabel } from '../api/bookType'
import AppIcon from './AppIcon.vue'

const props = defineProps<{
  book: SearchBook
  added: boolean
  adding: boolean
}>()

const emit = defineEmits<{
  close: []
  add: [book: SearchBook]
  added: [book: SearchBook]
}>()

const router = useRouter()
const starting = ref(false)
const error = ref('')

async function startNow() {
  if (starting.value) return
  starting.value = true
  error.value = ''
  try {
    if (props.added) {
      await router.push({
        name: bookRouteName(props.book.type),
        query: { bookUrl: props.book.bookUrl },
      })
      return
    }
    const session = await api.openPreview(props.book)
    await router.push({
      name: bookRouteName(session.book.type),
      query: { preview: session.id },
    })
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="panel-scrim" @click="emit('close')" />
    <aside class="detail-drawer">
      <header class="detail-head">
        <strong>作品详情</strong>
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
              <span class="tag">{{ bookTypeLabel(book.type) }}</span>
              <span v-if="book.kind" class="tag">{{ book.kind }}</span>
              <span v-if="book.wordCount" class="tag">{{ book.wordCount }}</span>
            </div>
          </div>
        </div>

        <div class="detail-actions">
          <button class="btn primary" :disabled="starting" @click="startNow">
            <AppIcon name="arrow-right" />
            {{ starting ? '打开中…' : bookTypeLabel(book.type) === '视频' ? '直接观看' : '直接阅读' }}
          </button>
          <button
            class="btn ghost"
            :disabled="added || adding"
            @click="emit('add', book)"
          >
            <AppIcon name="plus" />
            {{ adding ? '加入中…' : added ? '已在书架' : '加入书架' }}
          </button>
        </div>

        <p v-if="error" class="notice error">{{ error }}</p>
        <p class="detail-intro">{{ book.intro || '暂无简介' }}</p>
        <p v-if="book.lastChapter" class="detail-latest">最新：{{ book.lastChapter }}</p>
        <p class="muted">直接打开为临时试看，不写入书架；需要保存进度和书签再点「加入书架」。</p>
      </div>
    </aside>
  </Teleport>
</template>
