<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, type ReplaceRule } from '../api/client'
import AppIcon from '../components/AppIcon.vue'

const rules = ref<ReplaceRule[]>([])
const keyword = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const message = ref('')
const replaceEnabled = ref(true)
const globalBusy = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const showUrlImport = ref(false)
const importUrl = ref('')

const dialogOpen = ref(false)
const pendingDelete = ref<number | null>(null)
let pendingTimer: number | undefined
const formError = ref('')
const testText = ref('第 1 章 雪夜\n这是一段包含广告和多余空格的正文。')
const testResult = ref('')
const testChanged = ref(false)
const testBusy = ref(false)

const form = reactive<ReplaceRule>(emptyRule())

const enabledCount = computed(() => rules.value.filter((rule) => rule.isEnabled).length)
const filtered = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return rules.value
  return rules.value.filter((rule) =>
    [rule.name, rule.group, rule.pattern, rule.replacement, rule.scope, rule.excludeScope]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(key)),
  )
})

function emptyRule(): ReplaceRule {
  const nextOrder = rules.value.length
    ? Math.max(...rules.value.map((rule) => rule.sortOrder)) + 1
    : 0
  return {
    id: 0,
    name: '',
    group: '',
    pattern: '',
    replacement: '',
    scope: '',
    scopeTitle: false,
    scopeContent: true,
    excludeScope: '',
    isEnabled: true,
    isRegex: true,
    timeoutMillisecond: 3000,
    sortOrder: nextOrder,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [list, settings] = await Promise.all([api.replaceRules(), api.settings()])
    rules.value = list
    replaceEnabled.value = settings.replaceEnabled !== false
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function openNew() {
  Object.assign(form, emptyRule())
  formError.value = ''
  testResult.value = ''
  testChanged.value = false
  dialogOpen.value = true
}

function openEdit(rule: ReplaceRule) {
  Object.assign(form, rule)
  formError.value = ''
  testResult.value = ''
  testChanged.value = false
  dialogOpen.value = true
}

function closeDialog() {
  dialogOpen.value = false
}

async function save() {
  formError.value = ''
  if (!form.pattern.trim()) {
    formError.value = '请填写要匹配的内容'
    return
  }
  if (!form.scopeContent && !form.scopeTitle) {
    formError.value = '至少选择作用于正文或章节标题'
    return
  }
  busy.value = true
  try {
    const saved = await api.saveReplaceRule({ ...form })
    message.value = form.id ? `已更新：${saved.name}` : `已创建：${saved.name}`
    dialogOpen.value = false
    await load()
  } catch (e) {
    formError.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

function askRemove(rule: ReplaceRule) {
  window.clearTimeout(pendingTimer)
  if (pendingDelete.value === rule.id) {
    void remove(rule)
    return
  }
  pendingDelete.value = rule.id
  pendingTimer = window.setTimeout(() => {
    pendingDelete.value = null
  }, 4000)
}

async function remove(rule: ReplaceRule) {
  window.clearTimeout(pendingTimer)
  pendingDelete.value = null
  try {
    await api.deleteReplaceRule(rule.id)
    message.value = `已删除：${rule.name}`
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function toggle(rule: ReplaceRule, event: Event) {
  const input = event.target as HTMLInputElement
  const previous = rule.isEnabled
  rule.isEnabled = input.checked
  try {
    await api.setReplaceRuleEnabled(rule.id, input.checked)
  } catch (e) {
    rule.isEnabled = previous
    input.checked = previous
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function toggleGlobal(event: Event) {
  const input = event.target as HTMLInputElement
  const previous = replaceEnabled.value
  replaceEnabled.value = input.checked
  globalBusy.value = true
  try {
    await api.saveSettings({ replaceEnabled: input.checked })
    message.value = input.checked ? '已开启全局替换净化' : '已暂停所有替换规则'
  } catch (e) {
    replaceEnabled.value = previous
    input.checked = previous
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    globalBusy.value = false
  }
}

async function importFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const json = JSON.parse(await file.text())
    const result = await api.importReplaceRules(json)
    message.value = `导入 ${result.imported} 条规则${result.skipped ? `，跳过 ${result.skipped} 条` : ''}`
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
    input.value = ''
  }
}

async function importFromUrl() {
  const url = importUrl.value.trim()
  if (!url) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await api.importReplaceRulesUrl(url)
    message.value = `导入 ${result.imported} 条规则${result.skipped ? `，跳过 ${result.skipped} 条` : ''}`
    importUrl.value = ''
    showUrlImport.value = false
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function runTest() {
  testBusy.value = true
  formError.value = ''
  try {
    const result = await api.testReplaceRule({
      text: testText.value,
      pattern: form.pattern,
      replacement: form.replacement,
      isRegex: form.isRegex,
      timeoutMillisecond: form.timeoutMillisecond,
    })
    testResult.value = result.result
    testChanged.value = result.changed
  } catch (e) {
    formError.value = e instanceof Error ? e.message : String(e)
  } finally {
    testBusy.value = false
  }
}

function scopeLabel(rule: ReplaceRule): string {
  if (rule.scopeTitle && rule.scopeContent) return '标题 + 正文'
  if (rule.scopeTitle) return '仅标题'
  return '仅正文'
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && dialogOpen.value) closeDialog()
}

onMounted(() => {
  void load()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.clearTimeout(pendingTimer)
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h2>替换净化</h2>
        <p>{{ enabledCount }} / {{ rules.length }} 条启用 · 按排序依次作用于章节标题与正文</p>
      </div>
      <div class="head-actions">
        <div class="global-replace">
          <label class="switch" :title="replaceEnabled ? '暂停全部规则' : '启用全部规则'">
            <input
              type="checkbox"
              :checked="replaceEnabled"
              :disabled="globalBusy"
              @change="toggleGlobal"
            />
            <span />
          </label>
          <span>{{ replaceEnabled ? '全局净化已开启' : '全局净化已暂停' }}</span>
        </div>
        <button class="btn ghost" :disabled="busy" @click="fileInput?.click()">
          <AppIcon name="plus" /> 导入文件
        </button>
        <button class="btn ghost" :disabled="busy" @click="showUrlImport = !showUrlImport">
          链接导入
        </button>
        <button class="btn primary" @click="openNew">
          <AppIcon name="plus" /> 新建规则
        </button>
      </div>
      <input ref="fileInput" type="file" accept=".json,application/json" hidden @change="importFile" />
    </header>

    <form v-if="showUrlImport" class="search-bar" @submit.prevent="importFromUrl">
      <AppIcon name="filter" />
      <input v-model="importUrl" placeholder="粘贴替换规则 JSON 的订阅链接" />
      <button class="btn primary" type="submit" :disabled="busy">导入</button>
    </form>

    <p v-if="error" class="notice error">{{ error }}</p>
    <p v-if="message" class="notice">{{ message }}</p>

    <div class="manage-toolbar">
      <div class="search-bar compact">
        <AppIcon name="search" />
        <input v-model="keyword" placeholder="搜索名称、分组、匹配内容或作用范围" />
      </div>
      <span class="muted">共 {{ filtered.length }} 条</span>
    </div>

    <p v-if="loading" class="muted">正在加载替换规则…</p>

    <div v-else-if="filtered.length" class="replace-list">
      <article
        v-for="rule in filtered"
        :key="rule.id"
        class="replace-row"
        :class="{ disabled: !rule.isEnabled }"
      >
        <label class="switch" :title="rule.isEnabled ? '停用' : '启用'">
          <input type="checkbox" :checked="rule.isEnabled" @change="toggle(rule, $event)" />
          <span />
        </label>
        <div class="replace-main">
          <div class="replace-title">
            <strong>{{ rule.name }}</strong>
            <span v-if="rule.group" class="tag">{{ rule.group }}</span>
            <span class="tag">{{ rule.isRegex ? '正则' : '文本' }}</span>
            <span class="tag">{{ scopeLabel(rule) }}</span>
          </div>
          <code class="replace-expr">
            <span>{{ rule.pattern }}</span>
            <i>→</i>
            <span class="replacement">{{ rule.replacement || '（删除匹配内容）' }}</span>
          </code>
          <p v-if="rule.scope || rule.excludeScope" class="replace-scope">
            <span v-if="rule.scope">范围：{{ rule.scope }}</span>
            <span v-if="rule.excludeScope">排除：{{ rule.excludeScope }}</span>
          </p>
        </div>
        <div class="replace-actions">
          <template v-if="pendingDelete === rule.id">
            <span class="delete-hint">确认删除？</span>
            <button class="btn small danger-solid" @click="remove(rule)">删除</button>
            <button class="btn small ghost" @click="pendingDelete = null">取消</button>
          </template>
          <template v-else>
            <button class="btn small ghost" @click="openEdit(rule)">编辑</button>
            <button class="icon-btn danger" title="删除" @click="askRemove(rule)">
              <AppIcon name="trash" />
            </button>
          </template>
        </div>
      </article>
    </div>

    <div v-else class="empty-state">
      <AppIcon name="filter" :size="34" />
      <h3>{{ keyword ? '没有匹配的规则' : '还没有替换规则' }}</h3>
      <p>{{ keyword ? '换个关键词试试。' : '可以新建规则，或导入 Legado 的替换规则 JSON。' }}</p>
      <div v-if="!keyword" class="empty-actions">
        <button class="btn primary" @click="openNew">新建规则</button>
      </div>
    </div>

    <Teleport to="body">
      <div v-if="dialogOpen" class="panel-scrim dialog" @click.self="closeDialog" />
      <div v-if="dialogOpen" class="rule-dialog" role="dialog" aria-modal="true">
        <header class="switch-head">
          <div>
            <h3>{{ form.id ? '编辑替换规则' : '新建替换规则' }}</h3>
            <p>正则使用 Java 语法，替换内容中可用 $1 引用捕获组。</p>
          </div>
          <button class="icon-btn" @click="closeDialog"><AppIcon name="x" /></button>
        </header>

        <div class="rule-form">
          <div class="form-grid">
            <label class="field">
              <span>名称</span>
              <input v-model="form.name" placeholder="例如：去除广告尾巴" />
            </label>
            <label class="field">
              <span>分组</span>
              <input v-model="form.group" placeholder="可选" />
            </label>
          </div>

          <label class="field">
            <span>匹配内容 <b v-if="form.isRegex">（正则）</b></span>
            <textarea v-model="form.pattern" rows="3" spellcheck="false" placeholder="要匹配的文字或正则表达式" />
          </label>

          <label class="field">
            <span>替换为</span>
            <textarea v-model="form.replacement" rows="2" spellcheck="false" placeholder="留空表示删除匹配内容" />
          </label>

          <div class="form-grid">
            <label class="field">
              <span>作用书籍 / 书源</span>
              <input v-model="form.scope" placeholder="书名或书源 URL，多个用逗号分隔；留空为全部" />
            </label>
            <label class="field">
              <span>排除书籍 / 书源</span>
              <input v-model="form.excludeScope" placeholder="命中这里的不应用该规则" />
            </label>
          </div>

          <div class="check-row">
            <label class="check"><input v-model="form.isEnabled" type="checkbox" /><span>启用</span></label>
            <label class="check"><input v-model="form.isRegex" type="checkbox" /><span>使用正则</span></label>
            <label class="check"><input v-model="form.scopeContent" type="checkbox" /><span>作用于正文</span></label>
            <label class="check"><input v-model="form.scopeTitle" type="checkbox" /><span>作用于标题</span></label>
          </div>

          <details class="rule-advanced">
            <summary>排序与超时</summary>
            <div class="form-grid">
              <label class="field">
                <span>排序（越小越先执行）</span>
                <input v-model.number="form.sortOrder" type="number" step="1" />
              </label>
              <label class="field">
                <span>超时（毫秒）</span>
                <input v-model.number="form.timeoutMillisecond" type="number" min="100" step="100" />
              </label>
            </div>
          </details>

          <section class="test-box">
            <div class="test-head">
              <strong>测试替换</strong>
              <button class="btn small ghost" :disabled="testBusy || !form.pattern" @click="runTest">
                {{ testBusy ? '测试中…' : '运行测试' }}
              </button>
            </div>
            <textarea v-model="testText" rows="3" spellcheck="false" placeholder="输入一段测试文本" />
            <pre v-if="testResult" class="test-result" :class="{ changed: testChanged }">{{ testResult }}</pre>
            <p v-else class="muted">运行后会显示替换结果；无变化时提示未命中。</p>
            <p v-if="testResult && !testChanged" class="muted">结果与原文相同，规则未命中。</p>
          </section>

          <p v-if="formError" class="notice error">{{ formError }}</p>
        </div>

        <footer class="rule-foot">
          <button class="btn ghost" @click="closeDialog">取消</button>
          <button class="btn primary" :disabled="busy" @click="save">保存规则</button>
        </footer>
      </div>
    </Teleport>
  </section>
</template>
