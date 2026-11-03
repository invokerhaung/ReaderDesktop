<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Hls from 'hls.js'
import { api, type Book, type Chapter, type VideoStream } from '../api/client'
import { mediaSrc } from '../api/media'
import { coverSrc, hideBrokenCover } from '../api/cover'
import AppIcon from '../components/AppIcon.vue'

const route = useRoute()
const router = useRouter()
const bookUrl = String(route.query.bookUrl ?? '')
const previewId = String(route.query.preview ?? '')
const isPreview = !!previewId

function saveProgress(position: number) {
  if (isPreview || index.value < 0) return
  void api.saveProgress(bookUrl, index.value, position)
}

const book = ref<Book | null>(null)
const chapters = ref<Chapter[]>([])
const index = ref(-1)
const stream = ref<VideoStream | null>(null)
const quality = ref<number | undefined>(undefined)
const loading = ref(true)
const error = ref('')
const videoEl = ref<HTMLVideoElement | null>(null)
const playerBox = ref<HTMLDivElement | null>(null)

let hls: Hls | null = null
let progressTimer: number | undefined
let resumeApplied = false
let autoplayNext = false
let toastTimer: number | undefined
const toast = ref('')

const chapter = computed(() => chapters.value[index.value] ?? null)
const hasPrev = computed(() => index.value > 0)
const hasNext = computed(() => index.value >= 0 && index.value < chapters.value.length - 1)
const canSwitchQuality = computed(() => (stream.value?.resolutions.length ?? 0) > 1)

function showToast(text: string) {
  toast.value = text
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
  }, 2200)
}

function goBack() {
  if (router.options.history.state.back) router.back()
  else router.push('/')
}

function destroyPlayer() {  window.clearInterval(progressTimer)
  progressTimer = undefined
  if (hls) {
    hls.destroy()
    hls = null
  }
  const video = videoEl.value
  if (video) {
    video.pause()
    video.removeAttribute('src')
    video.load()
  }
}

function seekResume(play: boolean) {
  const video = videoEl.value
  const current = book.value
  if (!video) return
  const saved = Number(current?.durChapterPos ?? 0)
  if (!resumeApplied && index.value === current?.durChapterIndex && saved > 1) {
    video.currentTime = saved / 1000
  }
  resumeApplied = true
  if (play) void video.play().catch(() => undefined)
}

async function loadStream(refresh = false) {
  if (index.value < 0) return
  loading.value = true
  error.value = ''
  resumeApplied = false
  destroyPlayer()
  try {
    const result = isPreview
      ? await api.previewVideo(previewId, index.value, {
          resolution: quality.value,
          refresh,
        })
      : await api.video(bookUrl, index.value, {
          resolution: quality.value,
          refresh,
        })
    stream.value = result
    if (result.kind === 'drm' || result.drm) {
      error.value = '该剧集使用 DRM 加密（Widevine/PlayReady），当前不受支持'
      return
    }
    const src = mediaSrc(result.url, { source: book.value?.origin, bookUrl })
    await nextTick()
    const video = videoEl.value
    if (!video) return
    const shouldPlay = autoplayNext
    if (result.kind === 'hls' && Hls.isSupported()) {
      hls = new Hls({
        maxBufferLength: 30,
        backBufferLength: 30,
        enableWorker: true,
      })
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (!data.fatal) return
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          hls?.startLoad()
          return
        }
        error.value = data.details ? `播放失败：${data.details}` : '播放失败，可尝试“重新解析”'
      })
      hls.on(Hls.Events.MANIFEST_PARSED, () => seekResume(shouldPlay))
      hls.loadSource(src)
      hls.attachMedia(video)
    } else {
      video.src = src
      video.addEventListener('loadedmetadata', () => seekResume(shouldPlay), { once: true })
      if (shouldPlay) void video.play().catch(() => undefined)
    }
    startProgressTimer()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
    autoplayNext = false
  }
}

function startProgressTimer() {
  window.clearInterval(progressTimer)
  progressTimer = window.setInterval(() => {
    const video = videoEl.value
    if (!video || index.value < 0) return
    if (video.currentTime > 0) {
      saveProgress(Math.floor(video.currentTime * 1000))
    }
  }, 5000)
}

function selectEpisode(target: number, play = true) {
  if (target < 0 || target >= chapters.value.length) return
  const video = videoEl.value
  if (video && index.value >= 0 && video.currentTime > 0) {
    saveProgress(Math.floor(video.currentTime * 1000))
  }
  index.value = target
  autoplayNext = play
  router.replace({
    name: 'video',
    query: isPreview
      ? { preview: previewId, index: String(target) }
      : { bookUrl, index: String(target) },
  })
  if (!isPreview) void api.saveProgress(bookUrl, target, 0)
  void loadStream()
  playerBox.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onEnded() {
  if (hasNext.value) selectEpisode(index.value + 1)
}

function onQualityChange(event: Event) {
  const video = videoEl.value
  autoplayNext = !!video && !video.paused
  quality.value = Number((event.target as HTMLSelectElement).value)
  void loadStream()
}

function toggleFullscreen() {
  const video = videoEl.value
  if (!video) return
  if (document.fullscreenElement) void document.exitFullscreen()
  else void video.requestFullscreen?.().catch(() => undefined)
}

function onKeydown(event: KeyboardEvent) {
  if (event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) return
  const video = videoEl.value
  if (!video) return
  if (event.key === ' ') {
    event.preventDefault()
    if (video.paused) void video.play()
    else video.pause()
  } else if (event.key === 'ArrowRight') {
    video.currentTime = Math.min(video.duration || Infinity, video.currentTime + 10)
  } else if (event.key === 'ArrowLeft') {
    video.currentTime = Math.max(0, video.currentTime - 10)
  } else if (event.key.toLowerCase() === 'f') {
    toggleFullscreen()
  } else if (event.key.toLowerCase() === 'm') {
    video.muted = !video.muted
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
    const hasQueryIndex = Number.isFinite(queryIndex)
    const start = hasQueryIndex ? queryIndex : (book.value.durChapterIndex ?? 0)
    index.value = Math.min(Math.max(start || 0, 0), chapters.value.length - 1)
    autoplayNext = hasQueryIndex
    await loadStream()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  const video = videoEl.value
  if (video && index.value >= 0 && video.currentTime > 0 && !isPreview) {
    void api.saveProgress(bookUrl, index.value, Math.floor(video.currentTime * 1000))
  }
  if (isPreview) void api.closePreview(previewId).catch(() => undefined)
  destroyPlayer()
})
</script>

<template>
  <section class="page video-page">
    <header class="page-head">
      <div>
        <h2>{{ book?.name || '视频详情' }}</h2>
        <p>{{ chapter?.title || '正在解析…' }} · {{ chapters.length }} 集</p>
      </div>
      <button class="btn ghost" @click="goBack">返回</button>
    </header>

    <div class="video-layout">
      <div class="video-main">
        <div ref="playerBox" class="player-box">
          <video
            ref="videoEl"
            class="player-el"
            controls
            playsinline
            preload="metadata"
            @ended="onEnded"
            @dblclick="toggleFullscreen"
          />
          <div v-if="loading" class="player-mask">正在解析视频地址…</div>
          <div v-else-if="error" class="player-mask error">
            <strong>无法播放</strong>
            <p>{{ error }}</p>
            <div class="player-mask-actions">
              <button class="btn primary small" @click="loadStream(true)">重新解析</button>
            </div>
          </div>
        </div>

        <div class="player-toolbar">
          <button class="btn ghost small" :disabled="!hasPrev" @click="selectEpisode(index - 1)">上一集</button>
          <span class="player-progress">{{ index + 1 }} / {{ chapters.length }}</span>
          <select
            v-if="canSwitchQuality"
            class="quality-select"
            :value="quality ?? stream?.defaultIndex ?? 0"
            @change="onQualityChange"
          >
            <option v-for="item in stream?.resolutions ?? []" :key="item.index" :value="item.index">
              {{ item.name || `清晰度 ${item.index + 1}` }}
            </option>
          </select>
          <button class="btn ghost small" :disabled="!hasNext" @click="selectEpisode(index + 1)">下一集</button>
        </div>

        <h3 class="episode-title">选集</h3>
        <div class="episode-grid">
          <button
            v-for="item in chapters"
            :key="item.index"
            class="episode-item"
            :class="{ active: item.index === index }"
            @click="selectEpisode(item.index)"
          >
            {{ item.title }}
          </button>
        </div>
      </div>

      <aside class="video-info">
        <div class="info-cover">
          <span class="cover-fallback">{{ (book?.name || '影').slice(0, 1) }}</span>
          <img
            v-if="book?.coverUrl"
            :src="coverSrc(book.coverUrl, { source: book?.origin, bookUrl })"
            :alt="book?.name"
            decoding="async"
            @error="hideBrokenCover"
          />
        </div>
        <h3>{{ book?.name }}</h3>
        <p class="info-meta">{{ book?.author || '未知作者' }} · {{ book?.originName }}</p>
        <p class="info-meta" v-if="book?.kind">{{ book.kind }}</p>
        <p class="info-meta" v-if="book?.latestChapterTitle">最新：{{ book.latestChapterTitle }}</p>
        <p class="info-intro">{{ book?.intro || '暂无简介' }}</p>
        <div class="info-actions">
          <RouterLink
            v-if="!isPreview"
            class="btn ghost small"
            :to="`/?detail=${encodeURIComponent(bookUrl)}`"
          >
            书籍详情
          </RouterLink>
          <span v-else class="muted trial-badge">试看模式 · 不保存进度</span>
          <button class="btn ghost small" @click="loadStream(true)">刷新地址</button>
        </div>
      </aside>
    </div>

    <div v-if="toast" class="video-toast">{{ toast }}</div>
  </section>
</template>

<style scoped>
.video-page {
  padding-bottom: 32px;
}

.video-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 22px;
  align-items: start;
}

@media (max-width: 980px) {
  .video-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.player-box {
  position: relative;
  aspect-ratio: 16 / 9;
  background: #000;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--border);
}

.player-el {
  width: 100%;
  height: 100%;
  display: block;
  background: #000;
}

.player-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgb(10 11 13 / 82%);
  color: #cfc9bf;
  text-align: center;
  padding: 20px;
}

.player-mask strong {
  font-size: 15px;
}

.player-mask p {
  margin: 0;
  font-size: 12px;
  color: #9c988f;
  word-break: break-all;
  max-width: 460px;
}

.player-mask.error strong {
  color: #e08a8a;
}

.player-mask-actions {
  display: flex;
  gap: 10px;
  margin-top: 6px;
}

.player-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 2px;
}

.player-progress {
  font-size: 12px;
  color: var(--text-3);
}

.quality-select {
  background: var(--surface, #16181d);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 12px;
}

.episode-title {
  margin: 6px 0 10px;
  font-size: 14px;
  color: var(--text-2, #b9b5ac);
}

.episode-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(92px, 1fr));
  gap: 8px;
  max-height: 320px;
  overflow: auto;
  padding-right: 4px;
}

.episode-item {
  padding: 9px 8px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--surface, #16181d);
  color: var(--text-2, #c7c2b8);
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: border-color 150ms ease, color 150ms ease, background 150ms ease;
}

.episode-item:hover {
  border-color: var(--text-3);
  color: var(--text);
}

.episode-item.active {
  border-color: var(--accent);
  color: var(--accent-strong);
  background: rgb(217 162 94 / 12%);
}

.video-info {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  background: var(--surface, #16181d);
}

.info-cover {
  position: relative;
  aspect-ratio: 3 / 4;
  width: 100%;
  overflow: hidden;
  border-radius: 9px;
  background: linear-gradient(150deg, var(--cover-a), var(--cover-b));
  margin-bottom: 12px;
}

.info-cover img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.video-info h3 {
  margin: 0 0 6px;
  font-size: 16px;
}

.info-meta {
  margin: 2px 0;
  font-size: 12px;
  color: var(--text-3);
}

.info-intro {
  margin: 12px 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text-2, #b9b5ac);
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
}

.info-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.video-toast {
  position: fixed;
  left: 50%;
  bottom: 40px;
  transform: translateX(-50%);
  padding: 8px 16px;
  border-radius: 999px;
  background: rgb(20 22 26 / 92%);
  border: 1px solid #2c2f36;
  font-size: 12px;
  z-index: 30;
}
</style>

<style scoped>
.trial-badge {
  font-size: 12px;
  align-self: center;
}
</style>
