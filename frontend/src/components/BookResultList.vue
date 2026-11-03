<script setup lang="ts">
import { computed } from 'vue'
import type { SearchBook } from '../api/client'
import { coverSrc, hideBrokenCover } from '../api/cover'
import { bookTypeLabel } from '../api/bookType'

const props = defineProps<{
  books: SearchBook[]
  layout: 'list' | 'grid'
  ratio: '4:3' | '16:9'
  added: Record<string, boolean>
  adding: Record<string, boolean>
}>()

const emit = defineEmits<{
  add: [book: SearchBook]
  open: [book: SearchBook]
}>()

const coverStyle = computed(() => ({
  aspectRatio: props.ratio === '4:3' ? '4 / 3' : '16 / 9',
}))

function buttonLabel(book: SearchBook): string {
  if (props.adding[book.bookUrl]) return '加入中…'
  if (props.added[book.bookUrl]) return '已在书架'
  return '加入书架'
}
</script>

<template>
  <div v-if="layout === 'list'" class="result-list">
    <article
      v-for="book in books"
      :key="book.bookUrl"
      class="result-row clickable"
      @click="emit('open', book)"
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
        <small>{{ book.lastChapter || book.wordCount || '' }}</small>
        <small v-if="book.intro" class="result-intro">{{ book.intro }}</small>
      </div>
      <button
        class="btn"
        :class="added[book.bookUrl] ? 'ghost' : 'primary'"
        :disabled="added[book.bookUrl] || adding[book.bookUrl]"
        @click.stop="emit('add', book)"
      >
        {{ buttonLabel(book) }}
      </button>
    </article>
  </div>

  <div v-else class="result-grid">
    <article
      v-for="book in books"
      :key="book.bookUrl"
      class="result-card clickable"
      @click="emit('open', book)"
    >
      <div class="result-card-cover" :style="coverStyle">
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
      </div>
      <button
        class="btn small card-add"
        :class="added[book.bookUrl] ? 'ghost' : 'primary'"
        :disabled="added[book.bookUrl] || adding[book.bookUrl]"
        @click.stop="emit('add', book)"
      >
        {{ buttonLabel(book) }}
      </button>
    </article>
  </div>
</template>
