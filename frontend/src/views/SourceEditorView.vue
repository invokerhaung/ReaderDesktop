<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api, type SearchBook } from '../api/client'

const route = useRoute()
const key = computed(() => String(route.query.key ?? ''))

const json = ref('')
const name = ref('')
const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const error = ref('')
const message = ref('')
const testKeyword = ref('我的')
const testResult = ref<{ ok: boolean; elapsedMs: number; count?: number; error?: string; first?: SearchBook | null } | null>(null)

async function load() {
  if (!key.value) {
    error.value = '缺少书源 key'
    loading.value = false
    return
  }
  loading.value = true
  try {
    const detail = await api.sourceDetail(key.value)
    const parsed = JSON.parse(detail.json)
    name.value = parsed.bookSourceName ?? key.value
    json.value = JSON.stringify(parsed, null, 2)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const parsed = JSON.parse(json.value)
    const result = await api.saveSource(JSON.stringify(parsed))
    message.value = `已保存：${result.saved}`
    name.value = parsed.bookSourceName ?? name.value
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  error.value = ''
  message.value = ''
  testResult.value = null
  try {
    const parsed = JSON.parse(json.value)
    // 先保存再测试，保证测的是编辑后的内容
    await api.saveSource(JSON.stringify(parsed))
    testResult.value = await api.testSource(parsed.bookSourceUrl, testKeyword.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h2>编辑书源</h2>
        <p>{{ name || key || '未选择书源' }}</p>
      </div>
      <RouterLink to="/sources" class="btn ghost">返回列表</RouterLink>
    </header>

    <p v-if="loading" class="muted">正在加载…</p>
    <template v-else>
      <p v-if="error" class="notice error">{{ error }}</p>
      <p v-if="message" class="notice">{{ message }}</p>

      <textarea v-model="json" class="source-editor" spellcheck="false" />

      <div class="editor-actions">
        <button class="btn primary" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
        <input v-model="testKeyword" class="test-input" placeholder="测试关键词" />
        <button class="btn ghost" :disabled="testing" @click="test">
          {{ testing ? '测试中…' : '保存并测试' }}
        </button>
      </div>

      <div v-if="testResult" class="notice" :class="{ error: !testResult.ok }">
        <template v-if="testResult.ok">
          测试通过：搜索到 {{ testResult.count }} 条（{{ testResult.elapsedMs }} ms）
          <span v-if="testResult.first">首个结果：{{ testResult.first.name }} · {{ testResult.first.author || '未知作者' }}</span>
        </template>
        <template v-else>测试失败（{{ testResult.elapsedMs }} ms）：{{ testResult.error }}</template>
      </div>
    </template>
  </section>
</template>

<style scoped>
.source-editor {
  width: 100%;
  min-height: 480px;
  resize: vertical;
  font-family: var(--font-mono, Consolas, monospace);
  font-size: 13px;
  line-height: 1.5;
  padding: 12px;
  border-radius: 10px;
  border: 1px solid var(--border, #3a3a44);
  background: var(--panel, transparent);
  color: inherit;
}

.editor-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 12px;
}

.test-input {
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--border, #3a3a44);
  background: transparent;
  color: inherit;
  width: 160px;
}
</style>
