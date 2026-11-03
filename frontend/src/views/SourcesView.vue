<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type BookSource } from '../api/client'
import AppIcon from '../components/AppIcon.vue'

const sources = ref<BookSource[]>([])
const groups = ref<{ name: string; count: number }[]>([])
const ungrouped = ref(0)
const loading = ref(true)
const error = ref('')
const message = ref('')
const url = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const busy = ref(false)
const groupFilter = ref('all')

const assigning = ref<BookSource | null>(null)
const assignSelected = ref<string[]>([])
const assignNew = ref('')
const showGroupManager = ref(false)
const newGroupName = ref('')
const assignUngrouped = ref(true)
const renaming = ref('')
const renameValue = ref('')

const typeNames: Record<number, string> = {
  0: '文本',
  1: '音频',
  2: '图片',
  3: '文件',
  4: '视频',
  5: '订阅',
}

function splitGroups(value?: string | null): string[] {
  return (value ?? '')
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

const filteredSources = computed(() => {
  if (groupFilter.value === 'all') return sources.value
  if (groupFilter.value === 'ungrouped') {
    return sources.value.filter((source) => splitGroups(source.bookSourceGroup).length === 0)
  }
  return sources.value.filter((source) =>
    splitGroups(source.bookSourceGroup).includes(groupFilter.value),
  )
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [sourceList, groupInfo] = await Promise.all([api.sources(), api.sourceGroups()])
    sources.value = sourceList
    groups.value = groupInfo.groups
    ungrouped.value = groupInfo.ungrouped
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function refreshGroups() {
  try {
    const groupInfo = await api.sourceGroups()
    groups.value = groupInfo.groups
    ungrouped.value = groupInfo.ungrouped
  } catch {
    // 分组刷新失败不影响列表
  }
}

async function importUrl() {
  const value = url.value.trim()
  if (!value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await api.importSourceUrl(value)
    message.value = `已导入 ${result.imported} 个书源`
    url.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
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
    const text = await file.text()
    const json = JSON.parse(text)
    const result = await api.importSourceJson(json)
    message.value = `已导入 ${result.imported} 个书源`
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
    input.value = ''
  }
}

async function toggleEnabled(source: BookSource, event: Event) {
  const input = event.target as HTMLInputElement
  const previous = source.enabled
  source.enabled = input.checked
  try {
    await api.setSourceEnabled(source.bookSourceUrl, input.checked)
    message.value = `${source.bookSourceName} 已${input.checked ? '启用' : '停用'}`
  } catch (e) {
    source.enabled = previous
    input.checked = previous
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function remove(source: BookSource) {
  if (!window.confirm(`删除书源「${source.bookSourceName}」？`)) return
  try {
    await api.deleteSource(source.bookSourceUrl)
    message.value = `已删除：${source.bookSourceName}`
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

const logging = ref('')

async function login(source: BookSource) {
  logging.value = source.bookSourceUrl
  error.value = ''
  message.value = ''
  try {
    const result = await api.loginSource(source.bookSourceUrl)
    message.value = `${source.bookSourceName} 登录成功，已保存 ${result.cookies} 条 Cookie`
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    logging.value = ''
  }
}

async function clearCookies(source: BookSource) {
  error.value = ''
  message.value = ''
  try {
    await api.clearSourceCookies(source.bookSourceUrl)
    message.value = `已清除 ${source.bookSourceName} 的 Cookie`
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

function openAssign(source: BookSource) {
  assigning.value = source
  assignSelected.value = splitGroups(source.bookSourceGroup)
  assignNew.value = ''
}

async function saveAssign() {
  const source = assigning.value
  if (!source) return
  error.value = ''
  message.value = ''
  const selected = assignSelected.value.filter(Boolean)
  const added = assignNew.value.trim()
  if (added && !selected.includes(added)) selected.push(added)
  try {
    await api.setSourceGroups(source.bookSourceUrl, selected)
    message.value = `已更新「${source.bookSourceName}」的分组`
    assigning.value = null
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function createGroup() {
  const name = newGroupName.value.trim()
  if (!name) return
  error.value = ''
  message.value = ''
  try {
    await api.createSourceGroup(name, assignUngrouped.value)
    message.value = `已创建分组：${name}`
    newGroupName.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

function startRename(name: string) {
  renaming.value = name
  renameValue.value = name
}

async function confirmRename() {
  const from = renaming.value
  const to = renameValue.value.trim()
  if (!from || !to || from === to) {
    renaming.value = ''
    return
  }
  try {
    await api.renameSourceGroup(from, to)
    message.value = `分组「${from}」已重命名为「${to}」`
    if (groupFilter.value === from) groupFilter.value = to
    renaming.value = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function removeGroup(name: string) {
  if (!window.confirm(`删除分组「${name}」？组内书源会保留。`)) return
  try {
    await api.deleteSourceGroup(name)
    message.value = `已删除分组：${name}`
    if (groupFilter.value === name) groupFilter.value = 'all'
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h2>书源</h2>
        <p>{{ sources.length }} 个 · 支持分组管理、JSON 文件与订阅 URL</p>
      </div>
      <button class="btn ghost" :disabled="busy" @click="showGroupManager = true">
        <AppIcon name="filter" /> 管理分组
      </button>
      <button class="btn ghost" :disabled="busy" @click="fileInput?.click()">
        <AppIcon name="plus" /> 导入文件
      </button>
      <input ref="fileInput" type="file" accept=".json,application/json" hidden @change="importFile" />
    </header>

    <form class="search-bar" @submit.prevent="importUrl">
      <AppIcon name="database" />
      <input v-model="url" placeholder="粘贴书源订阅 URL 或 shuyuan-api 导入链接" />
      <button class="btn primary" type="submit" :disabled="busy">导入</button>
    </form>

    <p v-if="error" class="notice error">{{ error }}</p>
    <p v-if="message" class="notice">{{ message }}</p>

    <div v-if="sources.length" class="kind-chips group-filter">
      <button
        class="tag kind-chip"
        :class="{ active: groupFilter === 'all' }"
        @click="groupFilter = 'all'"
      >
        全部 {{ sources.length }}
      </button>
      <button
        class="tag kind-chip"
        :class="{ active: groupFilter === 'ungrouped' }"
        @click="groupFilter = 'ungrouped'"
      >
        未分组 {{ ungrouped }}
      </button>
      <button
        v-for="group in groups"
        :key="group.name"
        class="tag kind-chip"
        :class="{ active: groupFilter === group.name }"
        @click="groupFilter = group.name"
      >
        {{ group.name }} {{ group.count }}
      </button>
    </div>

    <p v-if="loading" class="muted">正在加载书源…</p>

    <div v-else-if="filteredSources.length" class="source-list">
      <article v-for="source in filteredSources" :key="source.bookSourceUrl" class="source-row">
        <span class="status-dot" :class="{ online: source.enabled }" />
        <div class="source-main">
          <strong>{{ source.bookSourceName }}</strong>
          <span>{{ source.bookSourceUrl }}</span>
        </div>
        <span class="tag">{{ typeNames[source.bookSourceType] ?? '文本' }}</span>
        <span
          v-for="group in splitGroups(source.bookSourceGroup)"
          :key="group"
          class="tag group-tag"
        >
          {{ group }}
        </span>
        <span v-if="!splitGroups(source.bookSourceGroup).length" class="tag">未分组</span>
        <label class="switch" :title="source.enabled ? '停用' : '启用'">
          <input
            type="checkbox"
            :checked="source.enabled"
            @change="toggleEnabled(source, $event)"
          />
          <span />
        </label>
        <button class="btn ghost small" @click="openAssign(source)">分组</button>
        <RouterLink
          class="btn ghost small"
          :to="`/sources/edit?key=${encodeURIComponent(source.bookSourceUrl)}`"
        >
          编辑
        </RouterLink>
        <button
          class="btn ghost small"
          :disabled="logging === source.bookSourceUrl"
          title="打开网页完成登录，登录后关闭窗口"
          @click="login(source)"
        >
          {{ logging === source.bookSourceUrl ? '等待登录…' : '登录' }}
        </button>
        <button class="btn ghost small" title="清除该站点 Cookie" @click="clearCookies(source)">
          清Cookie
        </button>
        <button class="icon-btn danger" title="删除" @click="remove(source)">
          <AppIcon name="trash" />
        </button>
      </article>
    </div>

    <div v-else-if="!loading && sources.length" class="empty-state">
      <AppIcon name="database" :size="34" />
      <h3>该分组下没有书源</h3>
      <p>切换到「全部」，或给书源设置这个分组。</p>
    </div>

    <div v-else-if="!loading" class="empty-state">
      <AppIcon name="database" :size="34" />
      <h3>还没有书源</h3>
      <p>粘贴订阅 URL，或导入一个 JSON 书源文件。</p>
    </div>

    <Teleport to="body">
      <div v-if="assigning" class="panel-scrim dialog" @click="assigning = null" />
      <div v-if="assigning" class="switch-dialog group-dialog" role="dialog" aria-modal="true">
        <header class="switch-head">
          <div>
            <h3>设置分组 · {{ assigning.bookSourceName }}</h3>
            <p>可多选；分组会写回书源 JSON，和 Android 备份格式一致。</p>
          </div>
          <button class="icon-btn" @click="assigning = null"><AppIcon name="x" /></button>
        </header>
        <div class="group-dialog-body">
          <label v-for="group in groups" :key="group.name" class="group-check">
            <input v-model="assignSelected" type="checkbox" :value="group.name" />
            <span>{{ group.name }}</span>
            <span class="muted">{{ group.count }}</span>
          </label>
          <p v-if="!groups.length" class="muted">还没有分组，在下面输入即可新建。</p>
          <input v-model="assignNew" class="group-input" placeholder="新分组名（留空则不加）" />
          <div class="group-dialog-actions">
            <button class="btn primary small" @click="saveAssign">保存</button>
            <button class="btn ghost small" @click="assigning = null">取消</button>
          </div>
        </div>
      </div>

      <div v-if="showGroupManager" class="panel-scrim dialog" @click="showGroupManager = false" />
      <div v-if="showGroupManager" class="switch-dialog group-dialog" role="dialog" aria-modal="true">
        <header class="switch-head">
          <div>
            <h3>管理分组</h3>
            <p>重命名或删除会影响所有带该分组的书源；书源本身不会被删除。</p>
          </div>
          <button class="icon-btn" @click="showGroupManager = false"><AppIcon name="x" /></button>
        </header>
        <div class="group-dialog-body">
          <div v-for="group in groups" :key="group.name" class="group-manage-row">
            <template v-if="renaming === group.name">
              <input v-model="renameValue" class="group-input" @keydown.enter="confirmRename" />
              <button class="btn primary small" @click="confirmRename">保存</button>
              <button class="btn ghost small" @click="renaming = ''">取消</button>
            </template>
            <template v-else>
              <span class="group-name">{{ group.name }}</span>
              <span class="muted">{{ group.count }} 个书源</span>
              <button class="btn ghost small" @click="startRename(group.name)">重命名</button>
              <button class="btn ghost small" @click="removeGroup(group.name)">删除</button>
            </template>
          </div>
          <p v-if="!groups.length" class="muted">还没有分组。</p>
          <div class="group-create">
            <input v-model="newGroupName" class="group-input" placeholder="新分组名" @keydown.enter="createGroup" />
            <label class="group-check inline">
              <input v-model="assignUngrouped" type="checkbox" />
              <span>把未分组书源加入</span>
            </label>
            <button class="btn primary small" @click="createGroup">新建</button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.group-filter {
  margin-bottom: 16px;
}

.group-tag {
  color: var(--accent-strong);
  border-color: rgb(217 162 94 / 35%);
}

.group-dialog {
  width: min(560px, 92vw);
  max-height: 76vh;
}

.group-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 20px 20px;
  overflow: auto;
}

.group-check {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}

.group-check.inline {
  border: none;
  padding: 0;
  gap: 6px;
  font-size: 12px;
  color: var(--text-3);
}

.group-check span:first-of-type {
  flex: 1;
}

.group-input {
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: transparent;
  color: inherit;
  font-size: 13px;
}

.group-dialog-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  margin-top: 4px;
}

.group-manage-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--border);
}

.group-name {
  flex: 1;
  font-size: 13px;
}

.group-create {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.group-create .group-input {
  flex: 1;
  min-width: 160px;
}
</style>
