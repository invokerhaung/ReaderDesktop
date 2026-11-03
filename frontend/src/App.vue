<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { api, getHealth } from './api/client'
import { applyAppTheme, normalizeAppTheme } from './api/appTheme'
import AppIcon from './components/AppIcon.vue'

const route = useRoute()
const online = ref(false)
const version = ref('unknown')
const floatHint = ref('')

async function openFloatWindow() {
  try {
    await api.floatCommand('open')
    floatHint.value = ''
  } catch (e) {
    floatHint.value = e instanceof Error ? e.message : String(e)
    window.setTimeout(() => {
      floatHint.value = ''
    }, 3200)
  }
}

const title = computed(() => (route.meta.title as string | undefined) ?? '墨读')
const immersive = computed(() => route.meta.immersive === true)
const floatWindow = computed(() => route.meta.floatWindow === true)

onMounted(async () => {
  try {
    const health = await getHealth()
    version.value = health.version
    online.value = true
  } catch {
    online.value = false
  }
  if (floatWindow.value) return
  try {
    const settings = await api.settings()
    applyAppTheme(normalizeAppTheme(settings.appTheme))
  } catch {
    // 设置不可用时沿用本地缓存的主题
  }
})
</script>

<template>
  <RouterView v-if="immersive" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">墨</span>
        <span class="brand-name">墨读</span>
      </div>
      <nav class="nav">
        <RouterLink to="/" class="nav-item">
          <AppIcon name="book" /><span>书架</span>
        </RouterLink>
        <RouterLink to="/search" class="nav-item">
          <AppIcon name="search" /><span>搜索</span>
        </RouterLink>
        <RouterLink to="/explore" class="nav-item">
          <AppIcon name="compass" /><span>发现</span>
        </RouterLink>
        <RouterLink to="/sources" class="nav-item">
          <AppIcon name="database" /><span>书源</span>
        </RouterLink>
        <RouterLink to="/replace" class="nav-item">
          <AppIcon name="filter" /><span>净化</span>
        </RouterLink>
        <RouterLink to="/settings" class="nav-item">
          <AppIcon name="settings" /><span>设置</span>
        </RouterLink>
      </nav>
      <div class="sidebar-foot">
        <span class="status-dot" :class="{ online }" />
        <span>{{ online ? `已连接 · ${version}` : '服务未连接' }}</span>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <h1>{{ title }}</h1>
        <div class="topbar-actions">
          <button
            class="topbar-link topbar-float"
            :title="floatHint || '打开浮窗阅读'"
            @click="openFloatWindow"
          >
            浮窗
          </button>
          <RouterLink to="/search" class="topbar-link">搜索书籍</RouterLink>
        </div>
      </header>
      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.topbar-float {
  border: 0;
  background: none;
  font: inherit;
  cursor: pointer;
}
</style>
