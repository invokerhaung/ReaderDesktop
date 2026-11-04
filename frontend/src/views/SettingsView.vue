<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api, getHealth, type CacheStats, type ReplaceRule, type Stats } from '../api/client'
import {
  APP_THEMES,
  applyAppTheme,
  loadAppTheme,
  normalizeAppTheme,
  type AppTheme,
} from '../api/appTheme'
import {
  FLOAT_DEFAULTS,
  FLOAT_DEFAULT_HOTKEY,
  FLOAT_MIN_HEIGHT,
  FLOAT_MIN_WIDTH,
  FLOAT_PREFS_CHANNEL,
  FLOAT_PRESETS,
  applyFloatPreset,
  floatPageStyle,
  hotkeyFromEvent,
  normalizeFloatPrefs,
  type FloatPrefs,
  type FloatPrefsMessage,
  type FloatPreset,
  type FloatState,
} from '../api/floatReader'
import AppIcon from '../components/AppIcon.vue'

type Theme = 'ink' | 'paper' | 'eye' | 'black'
type Font = 'serif' | 'sans' | 'kai'
type Mode = 'paged' | 'scroll'
type ChineseScript = 'off' | 'simplified' | 'traditional'

interface Prefs {
  appTheme: AppTheme
  theme: Theme
  fontFamily: Font
  fontSize: number
  lineHeight: number
  paraGap: number
  pageWidth: number
  mode: Mode
  chineseScript: ChineseScript
  replaceEnabled: boolean
  cacheEnabled: boolean
  cacheChapterDays: number
  preloadChapterCount: number
  float: FloatPrefs
}

const READER_KEY = 'legado.reader.settings'
const THEMES = [
  { id: 'ink', name: '墨夜', swatch: '#0f1013' },
  { id: 'paper', name: '纸白', swatch: '#f4efe4' },
  { id: 'eye', name: '护眼', swatch: '#e9e2cf' },
  { id: 'black', name: '纯黑', swatch: '#000000' },
] as const

const DEFAULTS: Prefs = {
  appTheme: 'ink',
  theme: 'ink',
  fontFamily: 'serif',
  fontSize: 19,
  lineHeight: 1.9,
  paraGap: 0.9,
  pageWidth: 720,
  mode: 'paged',
  chineseScript: 'off',
  replaceEnabled: true,
  cacheEnabled: true,
  cacheChapterDays: 30,
  preloadChapterCount: 7,
  float: { ...FLOAT_DEFAULTS },
}

const prefs = reactive<Prefs>({ ...DEFAULTS, appTheme: loadAppTheme() })
const appThemeName = computed(
  () => APP_THEMES.find((theme) => theme.id === prefs.appTheme)?.name ?? '墨夜',
)
const stats = ref<Stats | null>(null)
const cacheStats = ref<CacheStats | null>(null)
const rules = ref<ReplaceRule[]>([])
const version = ref('')
const loading = ref(true)
const busy = ref(false)
const cacheBusy = ref<'' | 'cover' | 'chapter' | 'all'>('')
const error = ref('')
const message = ref('')

const enabledRules = computed(() => rules.value.filter((rule) => rule.isEnabled).length)
const totalCache = computed(
  () => (cacheStats.value?.coverBytes ?? 0) + (cacheStats.value?.chapterBytes ?? 0),
)

const floatState = ref<FloatState | null>(null)
const floatHotkeyError = ref('')
const floatMessage = ref('')
const hotkeyInput = ref<HTMLInputElement | null>(null)
let floatSaveTimer: number | undefined
let prefsChannel: BroadcastChannel | null = null

const floatPreviewStyle = computed(() =>
  floatPageStyle(prefs.float, Math.min(prefs.float.fontSize, 20)),
)

const floatStatusLabel = computed(() => {
  if (!floatState.value) return '桌面端可用'
  if (!floatState.value.open) return '浮窗未打开'
  return floatState.value.visible ? '浮窗已显示' : '浮窗已隐藏'
})

const floatGeometryLabel = computed(() => {
  const state = floatState.value
  if (!state) return ''
  const position =
    state.x != null && state.y != null ? `，位置 ${state.x}, ${state.y}` : '，使用默认位置'
  return `当前 ${state.width} × ${state.height}${position}`
})

function clampValue(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function presetSwatch(id: FloatPreset): string {
  if (id === 'clear') {
    return 'repeating-conic-gradient(rgba(127,127,127,.45) 0% 25%, transparent 0% 50%) 50% / 10px 10px'
  }
  return FLOAT_PRESETS.find((preset) => preset.id === id)?.swatch ?? 'transparent'
}

function selectFloatPreset(preset: FloatPreset) {
  prefs.float = applyFloatPreset(prefs.float, preset)
  scheduleFloatSave()
}

function markFloatCustom() {
  if (prefs.float.preset !== 'custom') prefs.float.preset = 'custom'
}

function onFloatColor() {
  markFloatCustom()
  scheduleFloatSave()
}

function setFloatOpacity(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  prefs.float.bgOpacity = clampValue(value / 100, 0, 1)
  markFloatCustom()
  scheduleFloatSave()
}

function scheduleFloatSave() {
  window.clearTimeout(floatSaveTimer)
  floatSaveTimer = window.setTimeout(() => {
    void persistFloatPrefs()
  }, 300)
}

async function persistFloatPrefs() {
  try {
    await api.saveSettings({ float: { ...prefs.float } })
    prefsChannel?.postMessage({
      type: 'prefs',
      prefs: { ...prefs.float },
    } satisfies FloatPrefsMessage)
    try {
      // 不要在这里带 width/height：用户拖动/缩放后的实际尺寸由壳自己记忆，
      // 只有「默认尺寸」输入框显式修改时才通过 applySize 覆盖。
      await api.floatCommand('prefs', {
        top: prefs.float.alwaysOnTop,
        emergencyHide: prefs.float.emergencyHide,
      })
    } catch {
      // 非桌面壳环境忽略
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function applyFloatTopToggle() {
  try {
    const state = await api.floatCommand<FloatState>('top', { top: prefs.float.alwaysOnTop })
    floatState.value = state
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
  void persistFloatPrefs()
}

async function applyFloatSize() {
  prefs.float.width = Math.round(clampValue(prefs.float.width, FLOAT_MIN_WIDTH, 2000))
  prefs.float.height = Math.round(clampValue(prefs.float.height, FLOAT_MIN_HEIGHT, 3000))
  try {
    const state = await api.floatCommand<FloatState>('prefs', {
      width: prefs.float.width,
      height: prefs.float.height,
      applySize: true,
    })
    floatState.value = state
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
  void persistFloatPrefs()
}

async function resetFloatGeometry() {
  floatHotkeyError.value = ''
  try {
    const state = await api.floatCommand<FloatState>('reset')
    floatState.value = state
    prefs.float.width = state.width
    prefs.float.height = state.height
    await persistFloatPrefs()
    floatMessage.value = '浮窗位置与大小已重置'
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function openFloatNow() {
  try {
    const state = await api.floatCommand<FloatState>('open')
    floatState.value = state
    floatMessage.value = '浮窗已打开'
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function applyFloatHotkey(hotkey: string) {
  floatHotkeyError.value = ''
  floatMessage.value = ''
  try {
    const state = await api.floatCommand<FloatState>('hotkey', { hotkey })
    prefs.float.hotkey = hotkey
    floatState.value = state
    await api.saveSettings({ float: { ...prefs.float } })
    floatMessage.value = `老板键已设为 ${hotkey}`
    hotkeyInput.value?.blur()
  } catch (e) {
    floatHotkeyError.value = e instanceof Error ? e.message : String(e)
  }
}

async function captureHotkey(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    hotkeyInput.value?.blur()
    return
  }
  const hotkey = hotkeyFromEvent(event)
  if (!hotkey) return
  await applyFloatHotkey(hotkey)
}

function resetFloatHotkey() {
  void applyFloatHotkey(FLOAT_DEFAULT_HOTKEY)
}

async function refreshFloatState() {
  try {
    floatState.value = await api.floatCommand<FloatState>('state')
    if (floatState.value.hotkey) {
      prefs.float.hotkey = floatState.value.hotkey
    }
    if (floatState.value.hotkeyError) {
      floatHotkeyError.value = floatState.value.hotkeyError
    }
  } catch {
    floatState.value = null
  }
}

function isNumber(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [settings, statsData, cacheData, ruleList, health] = await Promise.all([
      api.settings(),
      api.stats(),
      api.cacheStats(),
      api.replaceRules(),
      getHealth(),
    ])
    stats.value = statsData
    cacheStats.value = cacheData
    rules.value = ruleList
    version.value = health.version

    if (THEMES.some((theme) => theme.id === settings.theme)) prefs.theme = settings.theme as Theme
    prefs.appTheme = normalizeAppTheme(settings.appTheme ?? prefs.appTheme)
    if (settings.fontFamily === 'serif' || settings.fontFamily === 'sans' || settings.fontFamily === 'kai') {
      prefs.fontFamily = settings.fontFamily
    }
    if (settings.mode === 'paged' || settings.mode === 'scroll') prefs.mode = settings.mode
    if (
      settings.chineseScript === 'off' ||
      settings.chineseScript === 'simplified' ||
      settings.chineseScript === 'traditional'
    ) {
      prefs.chineseScript = settings.chineseScript
    }
    if (isNumber(settings.fontSize, 16, 30)) prefs.fontSize = settings.fontSize
    if (isNumber(settings.lineHeight, 1.6, 2.2)) prefs.lineHeight = settings.lineHeight
    if (isNumber(settings.paraGap, 0.4, 1.6)) prefs.paraGap = settings.paraGap
    if (isNumber(settings.pageWidth, 620, 880)) prefs.pageWidth = settings.pageWidth
    if (typeof settings.replaceEnabled === 'boolean') prefs.replaceEnabled = settings.replaceEnabled
    if (typeof settings.cacheEnabled === 'boolean') prefs.cacheEnabled = settings.cacheEnabled
    if (isNumber(settings.cacheChapterDays, 1, 3650)) {
      prefs.cacheChapterDays = settings.cacheChapterDays
    }
    if (isNumber(settings.preloadChapterCount, 0, 50)) {
      prefs.preloadChapterCount = settings.preloadChapterCount
    }
    prefs.float = normalizeFloatPrefs(settings.float)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function save() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await api.saveSettings({ ...prefs })
    applyAppTheme(prefs.appTheme)
    const readerSettings = {
      theme: prefs.theme,
      fontFamily: prefs.fontFamily,
      fontSize: prefs.fontSize,
      lineHeight: prefs.lineHeight,
      paraGap: prefs.paraGap,
      pageWidth: prefs.pageWidth,
      mode: prefs.mode,
    }
    localStorage.setItem(READER_KEY, JSON.stringify(readerSettings))
    message.value = '设置已保存，阅读器将使用新的默认排版'
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

const READER_THEME_BY_APP: Record<AppTheme, Theme> = {
  ink: 'ink',
  slate: 'ink',
  black: 'black',
  paper: 'paper',
  eye: 'eye',
}

function selectChineseScript(value: ChineseScript) {
  prefs.chineseScript = value
  message.value = ''
  void api.saveSettings({ chineseScript: value })
    .then(() => {
      message.value =
        value === 'off'
          ? '正文简繁转换已关闭'
          : value === 'simplified'
            ? '正文将统一显示为简体，重新打开章节后生效'
            : '正文将统一显示为繁体，重新打开章节后生效'
    })
    .catch((e) => {
      error.value = e instanceof Error ? e.message : String(e)
    })
}

function selectAppTheme(theme: AppTheme) {
  prefs.appTheme = theme
  applyAppTheme(theme)
  // 同步阅读默认主题，避免浅色界面打开书后仍是深色排版
  prefs.theme = READER_THEME_BY_APP[theme]
  localStorage.setItem(
    READER_KEY,
    JSON.stringify({
      theme: prefs.theme,
      fontFamily: prefs.fontFamily,
      fontSize: prefs.fontSize,
      lineHeight: prefs.lineHeight,
      paraGap: prefs.paraGap,
      pageWidth: prefs.pageWidth,
      mode: prefs.mode,
    }),
  )
  void api.saveSettings({ appTheme: theme, theme: prefs.theme }).catch(() => undefined)
}

function reset() {
  Object.assign(prefs, DEFAULTS)
  prefs.float = normalizeFloatPrefs(FLOAT_DEFAULTS)
  applyAppTheme(DEFAULTS.appTheme)
  void applyFloatHotkey(FLOAT_DEFAULT_HOTKEY)
  message.value = '已恢复默认值，点击「保存设置」后生效'
}

function formatBytes(bytes: number | undefined): string {
  if (!bytes) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

async function clearCache(type: 'cover' | 'chapter' | 'all') {
  cacheBusy.value = type
  error.value = ''
  message.value = ''
  try {
    cacheStats.value = await api.clearCache(type)
    message.value =
      type === 'cover' ? '封面缓存已清理' : type === 'chapter' ? '正文缓存已清理' : '全部缓存已清理'
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    cacheBusy.value = ''
  }
}

onMounted(async () => {
  await load()
  void refreshFloatState()
  if (typeof BroadcastChannel !== 'undefined') {
    prefsChannel = new BroadcastChannel(FLOAT_PREFS_CHANNEL)
    prefsChannel.onmessage = (event: MessageEvent<FloatPrefsMessage>) => {
      const payload = event.data
      if (!payload || payload.type !== 'prefs') return
      const incoming = normalizeFloatPrefs(payload.prefs)
      prefs.float.alwaysOnTop = incoming.alwaysOnTop
      prefs.float.hotkey = incoming.hotkey
      if (floatState.value) floatState.value.alwaysOnTop = incoming.alwaysOnTop
    }
  }
})

onBeforeUnmount(() => {
  window.clearTimeout(floatSaveTimer)
  prefsChannel?.close()
})
</script>

<template>
  <section class="page">
    <header class="page-head">
      <div>
        <h2>设置</h2>
        <p>阅读默认值会同步到阅读器，也可以在阅读器内临时调整</p>
      </div>
      <div class="head-actions">
        <button class="btn ghost" @click="reset">恢复默认</button>
        <button class="btn primary" :disabled="busy || loading" @click="save">
          {{ busy ? '保存中…' : '保存设置' }}
        </button>
      </div>
    </header>

    <p v-if="error" class="notice error">{{ error }}</p>
    <p v-if="message" class="notice">{{ message }}</p>
    <p v-if="loading" class="muted">正在加载设置…</p>

    <div v-else class="settings-grid">
      <section class="pref-card settings-theme-card">
        <header class="pref-card-head">
          <div>
            <strong>更改主题</strong>
            <span>整套界面配色，标题栏、侧栏、卡片和按钮会一起切换</span>
          </div>
          <span class="tag">{{ appThemeName }}</span>
        </header>
        <div class="pref-body">
          <div class="app-theme-grid">
            <button
              v-for="theme in APP_THEMES"
              :key="theme.id"
              class="app-theme-card"
              :class="{ active: prefs.appTheme === theme.id }"
              @click="selectAppTheme(theme.id)"
            >
              <span class="app-theme-preview" :style="{ background: theme.swatch }">
                <i :style="{ background: theme.accent }" />
                <b :style="{ background: theme.accent }" />
              </span>
              <span class="app-theme-name">{{ theme.name }}</span>
              <span v-if="prefs.appTheme === theme.id" class="app-theme-check">
                <AppIcon name="check" :size="12" />
              </span>
            </button>
          </div>
          <p class="pref-note">切换立即生效并自动保存；阅读页正文主题在下方「阅读主题」中单独设置。</p>
        </div>
      </section>

      <section class="pref-card settings-float-card">
        <header class="pref-card-head">
          <div>
            <strong>浮窗阅读</strong>
            <span>无边框透明小窗，可拖动、可缩放，跟随阅读进度双向同步</span>
          </div>
          <span class="tag">{{ floatStatusLabel }}</span>
        </header>
        <div class="pref-body float-pref-body">
          <div class="float-pref-column">
            <div class="pref-block">
              <span class="pref-label">背景样式</span>
              <div class="float-preset-row">
                <button
                  v-for="preset in FLOAT_PRESETS"
                  :key="preset.id"
                  class="float-preset"
                  :class="{ active: prefs.float.preset === preset.id }"
                  @click="selectFloatPreset(preset.id)"
                >
                  <i :style="{ background: presetSwatch(preset.id) }" />
                  <span>{{ preset.name }}</span>
                </button>
              </div>
            </div>

            <div class="pref-two">
              <label class="pref-field">
                <span>背景颜色</span>
                <input class="float-color-input" type="color" v-model="prefs.float.bgColor" @input="onFloatColor" />
              </label>
              <label class="pref-field">
                <span>文字颜色</span>
                <input class="float-color-input" type="color" v-model="prefs.float.textColor" @input="onFloatColor" />
              </label>
            </div>

            <div class="pref-block">
              <div class="pref-line">
                <span>背景不透明度</span>
                <b>{{ Math.round(prefs.float.bgOpacity * 100) }}%</b>
              </div>
              <input
                type="range"
                min="0"
                max="100"
                :value="Math.round(prefs.float.bgOpacity * 100)"
                @input="setFloatOpacity"
              />
            </div>

            <div class="pref-two">
              <label class="pref-field">
                <span>正文字体</span>
                <select v-model="prefs.float.fontFamily" @change="scheduleFloatSave">
                  <option value="serif">宋体 · 衬线</option>
                  <option value="kai">楷体</option>
                  <option value="sans">黑体 · 无衬线</option>
                </select>
              </label>
              <div class="pref-field">
                <span>进度显示</span>
                <div class="pref-segmented">
                  <button
                    :class="{ active: prefs.float.progress === 'bottom' }"
                    @click="prefs.float.progress = 'bottom'; scheduleFloatSave()"
                  >
                    底部
                  </button>
                  <button
                    :class="{ active: prefs.float.progress === 'top' }"
                    @click="prefs.float.progress = 'top'; scheduleFloatSave()"
                  >
                    顶部
                  </button>
                  <button
                    :class="{ active: prefs.float.progress === 'hidden' }"
                    @click="prefs.float.progress = 'hidden'; scheduleFloatSave()"
                  >
                    隐藏
                  </button>
                </div>
              </div>
            </div>

            <div class="pref-block">
              <div class="pref-line">
                <span>字号</span>
                <b>
                  {{ prefs.float.fontSize }} px
                  <template v-if="prefs.float.autoFontSize">（按窗口宽度自适应）</template>
                </b>
              </div>
              <input
                v-model.number="prefs.float.fontSize"
                type="range"
                min="12"
                max="30"
                :disabled="prefs.float.autoFontSize"
                @input="scheduleFloatSave"
              />
              <label class="pref-line float-toggle-line">
                <span>自适应字号（随窗口宽度缩放）</span>
                <span class="switch">
                  <input v-model="prefs.float.autoFontSize" type="checkbox" @change="scheduleFloatSave" />
                  <span />
                </span>
              </label>
              <label class="pref-line float-toggle-line">
                <span>文字描边 / 阴影</span>
                <span class="switch">
                  <input v-model="prefs.float.outline" type="checkbox" @change="scheduleFloatSave" />
                  <span />
                </span>
              </label>
              <div class="pref-line">
                <span>行高</span>
                <b>{{ prefs.float.lineHeight.toFixed(1) }}</b>
              </div>
              <input
                v-model.number="prefs.float.lineHeight"
                type="range"
                min="1.4"
                max="2.4"
                step="0.1"
                @input="scheduleFloatSave"
              />
            </div>
          </div>

          <div class="float-pref-column">
            <div class="float-preview">
              <div class="float-preview-inner" :style="floatPreviewStyle">
                <p>浮窗预览 · 拖动四边或四角可以缩放，按住正文可以拖动窗口。</p>
                <div class="float-preview-bar">
                  <i><b /></i>
                  <span>37%</span>
                </div>
              </div>
            </div>

            <div class="pref-block">
              <span class="pref-label">老板键（全局）</span>
              <div class="float-hotkey-row">
                <input
                  ref="hotkeyInput"
                  class="float-hotkey-input"
                  readonly
                  :value="prefs.float.hotkey"
                  placeholder="点击后按下组合键"
                  @keydown.stop.prevent="captureHotkey"
                />
                <button class="btn small ghost" @click="resetFloatHotkey">默认</button>
              </div>
              <p class="pref-note">即使焦点在其他程序也能隐藏 / 显示浮窗；支持 Ctrl、Alt、Shift、Super 组合键。</p>
              <p v-if="floatHotkeyError" class="notice error">{{ floatHotkeyError }}</p>
              <p v-else-if="floatMessage" class="pref-note">{{ floatMessage }}</p>
            </div>

            <div class="pref-block">
              <span class="pref-label">行为</span>
              <label class="pref-line float-toggle-line">
                <span>窗口置顶</span>
                <span class="switch">
                  <input v-model="prefs.float.alwaysOnTop" type="checkbox" @change="applyFloatTopToggle" />
                  <span />
                </span>
              </label>
              <label class="pref-line float-toggle-line">
                <span>滚轮翻页</span>
                <span class="switch">
                  <input v-model="prefs.float.wheel" type="checkbox" @change="scheduleFloatSave" />
                  <span />
                </span>
              </label>
              <label class="pref-line float-toggle-line">
                <span>点击左右区域翻页</span>
                <span class="switch">
                  <input v-model="prefs.float.clickZones" type="checkbox" @change="scheduleFloatSave" />
                  <span />
                </span>
              </label>
              <label class="pref-line float-toggle-line">
                <span>紧急隐藏（同时隐藏主窗口）</span>
                <span class="switch">
                  <input v-model="prefs.float.emergencyHide" type="checkbox" @change="scheduleFloatSave" />
                  <span />
                </span>
              </label>
            </div>

            <div class="pref-block">
              <span class="pref-label">默认位置与大小</span>
              <div class="pref-two">
                <label class="pref-field">
                  <span>宽度</span>
                  <input
                    v-model.number="prefs.float.width"
                    type="number"
                    :min="FLOAT_MIN_WIDTH"
                    @change="applyFloatSize"
                  />
                </label>
                <label class="pref-field">
                  <span>高度</span>
                  <input
                    v-model.number="prefs.float.height"
                    type="number"
                    :min="FLOAT_MIN_HEIGHT"
                    @change="applyFloatSize"
                  />
                </label>
              </div>
              <p class="pref-note">
                最小 {{ FLOAT_MIN_WIDTH }} × {{ FLOAT_MIN_HEIGHT }}，拖动 / 缩放后的位置会自动记忆。{{ floatGeometryLabel }}
              </p>
              <button class="btn small ghost" @click="resetFloatGeometry">重置浮窗位置和大小</button>
            </div>

            <div class="float-open-row">
              <button class="btn primary" @click="openFloatNow">打开浮窗</button>
              <span class="muted small">阅读页右上角也可以随时进入</span>
            </div>
          </div>
        </div>
      </section>

      <section class="pref-card">
        <header class="pref-card-head">
          <div>
            <strong>阅读外观</strong>
            <span>新打开的章节与新书会使用这些默认值</span>
          </div>
          <span class="tag">{{ prefs.fontSize }} px · {{ prefs.lineHeight.toFixed(1) }} 行高</span>
        </header>

        <div class="pref-body">
          <div class="pref-block">
            <span class="pref-label">阅读主题</span>
            <div class="pref-theme-grid">
              <button
                v-for="theme in THEMES"
                :key="theme.id"
                class="pref-chip"
                :class="{ active: prefs.theme === theme.id }"
                @click="prefs.theme = theme.id"
              >
                <i :style="{ background: theme.swatch }" />{{ theme.name }}
              </button>
            </div>
          </div>

          <div class="pref-two">
            <label class="pref-field">
              <span>字体</span>
              <select v-model="prefs.fontFamily">
                <option value="serif">宋体 · 衬线</option>
                <option value="kai">楷体</option>
                <option value="sans">黑体 · 无衬线</option>
              </select>
            </label>
            <div class="pref-field">
              <span>翻页方式</span>
              <div class="pref-segmented">
                <button :class="{ active: prefs.mode === 'paged' }" @click="prefs.mode = 'paged'">横向分页</button>
                <button :class="{ active: prefs.mode === 'scroll' }" @click="prefs.mode = 'scroll'">滚动</button>
              </div>
            </div>
          </div>

          <div class="pref-block">
            <span class="pref-label">正文简繁</span>
            <div class="pref-segmented">
              <button
                :class="{ active: prefs.chineseScript === 'off' }"
                @click="selectChineseScript('off')"
              >
                不转换
              </button>
              <button
                :class="{ active: prefs.chineseScript === 'simplified' }"
                @click="selectChineseScript('simplified')"
              >
                统一简体
              </button>
              <button
                :class="{ active: prefs.chineseScript === 'traditional' }"
                @click="selectChineseScript('traditional')"
              >
                统一繁体
              </button>
            </div>
            <p class="pref-note">只影响显示，不修改原始正文与缓存；已打开的章节重新打开后生效。</p>
          </div>

          <div class="pref-block">
            <div class="pref-line"><span>字号</span><b>{{ prefs.fontSize }} px</b></div>
            <input v-model.number="prefs.fontSize" type="range" min="16" max="30" />
          </div>
          <div class="pref-block">
            <div class="pref-line"><span>行高</span><b>{{ prefs.lineHeight.toFixed(1) }}</b></div>
            <input v-model.number="prefs.lineHeight" type="range" min="1.6" max="2.2" step="0.1" />
          </div>
          <div class="pref-block">
            <div class="pref-line"><span>段距</span><b>{{ prefs.paraGap.toFixed(1) }} em</b></div>
            <input v-model.number="prefs.paraGap" type="range" min="0.4" max="1.6" step="0.1" />
          </div>
          <div class="pref-block">
            <div class="pref-line"><span>页宽</span><b>{{ prefs.pageWidth }} px</b></div>
            <input v-model.number="prefs.pageWidth" type="range" min="620" max="880" step="20" />
          </div>
        </div>
      </section>

      <div class="settings-side">
        <section class="pref-card">
          <header class="pref-card-head">
            <div>
              <strong>替换净化</strong>
              <span>控制所有启用规则的开关</span>
            </div>
            <label class="switch" :title="prefs.replaceEnabled ? '暂停全部规则' : '启用全部规则'">
              <input v-model="prefs.replaceEnabled" type="checkbox" />
              <span />
            </label>
          </header>
          <div class="pref-body">
            <p class="pref-note">
              {{ prefs.replaceEnabled ? '正文和章节标题会应用已启用的替换规则。' : '当前已暂停所有替换规则，原始内容会直接显示。' }}
            </p>
            <div class="pref-stat-inline">
              <span>已启用</span>
              <b>{{ enabledRules }} / {{ rules.length }} 条</b>
            </div>
            <RouterLink to="/replace" class="btn ghost full">
              <AppIcon name="filter" /> 管理替换规则
            </RouterLink>
          </div>
        </section>

        <section class="pref-card">
          <header class="pref-card-head">
            <div>
              <strong>缓存与存储</strong>
              <span>正文缓存支持离线回退，图片缓存用于封面与插图</span>
            </div>
            <span class="tag">{{ formatBytes(totalCache) }}</span>
          </header>
          <div class="pref-body">
            <div class="pref-line">
              <span>缓存正文到本地</span>
              <label class="switch" :title="prefs.cacheEnabled ? '停止缓存正文' : '开启正文缓存'">
                <input v-model="prefs.cacheEnabled" type="checkbox" />
                <span />
              </label>
            </div>
            <label class="pref-field">
              <span>正文缓存保留时间</span>
              <select v-model.number="prefs.cacheChapterDays">
                <option :value="7">7 天</option>
                <option :value="30">30 天</option>
                <option :value="90">90 天</option>
                <option :value="365">1 年</option>
              </select>
            </label>
            <label class="pref-field">
              <span>自动预载后续章节</span>
              <input
                v-model.number="prefs.preloadChapterCount"
                type="number"
                min="0"
                max="50"
              />
            </label>
            <p class="pref-note">
              默认 7 章；会同时向前预载最多 5 章，0 表示关闭。预载在后台静默进行，优先隐藏 WebView，失败不会弹出验证窗口。
            </p>
            <div class="pref-stat-inline">
              <span>封面 / 插图缓存</span>
              <b>{{ cacheStats?.coverFiles ?? 0 }} 张 · {{ formatBytes(cacheStats?.coverBytes) }}</b>
            </div>
            <div class="pref-stat-inline">
              <span>正文缓存</span>
              <b>{{ cacheStats?.chapterRows ?? 0 }} 章 · {{ formatBytes(cacheStats?.chapterBytes) }}</b>
            </div>
            <div class="cache-actions">
              <button class="btn small ghost" :disabled="!!cacheBusy" @click="clearCache('cover')">
                清理封面
              </button>
              <button class="btn small ghost" :disabled="!!cacheBusy" @click="clearCache('chapter')">
                清理正文
              </button>
              <button class="btn small danger-solid" :disabled="!!cacheBusy" @click="clearCache('all')">
                全部清理
              </button>
            </div>
            <p class="pref-note">缓存开关在保存设置后生效；清理操作立即执行，缓存会在重新阅读时重建。</p>
          </div>
        </section>

        <section class="pref-card">
          <header class="pref-card-head">
            <div>
              <strong>阅读器快捷键</strong>
              <span>在阅读页直接使用</span>
            </div>
          </header>
          <div class="pref-body">
            <div class="shortcut-list">
              <div class="shortcut-row"><kbd>←</kbd><kbd>→</kbd><span>上一页 / 下一页</span></div>
              <div class="shortcut-row"><kbd>Space</kbd><span>下一页</span></div>
              <div class="shortcut-row"><kbd>Ctrl</kbd><kbd>T</kbd><span>打开目录</span></div>
              <div class="shortcut-row"><kbd>Esc</kbd><span>关闭抽屉</span></div>
            </div>
            <p class="pref-note">滚动模式下方向键按屏幕高度滚动。</p>
          </div>
        </section>

        <section class="pref-card">
          <header class="pref-card-head">
            <div>
              <strong>数据与服务</strong>
              <span>本地 SQLite 存储</span>
            </div>
            <span class="tag">v{{ version || '—' }}</span>
          </header>
          <div class="pref-body">
            <div class="stats-grid">
              <div class="stat">
                <span>书架</span>
                <b>{{ stats?.bookCount ?? 0 }}</b>
              </div>
              <div class="stat">
                <span>书源</span>
                <b>{{ stats?.sourceCount ?? 0 }}</b>
              </div>
              <div class="stat">
                <span>替换规则</span>
                <b>{{ stats?.replaceRuleCount ?? 0 }}</b>
              </div>
              <div class="stat">
                <span>数据库</span>
                <b>{{ formatBytes(stats?.databaseBytes) }}</b>
              </div>
            </div>
            <p class="pref-path" :title="stats?.dataDir">{{ stats?.dataDir }}</p>
            <p class="pref-note">JSON 书源、SQLite 数据库与替换规则均保存在该目录。</p>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.settings-theme-card {
  grid-column: 1 / -1;
}

.app-theme-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
  gap: 12px;
}

.app-theme-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface-2);
  color: inherit;
  cursor: pointer;
  transition: border-color 150ms ease, transform 150ms ease, box-shadow 150ms ease;
}

.app-theme-card:hover {
  border-color: var(--text-3);
  transform: translateY(-1px);
}

.app-theme-card.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-glow);
}

.app-theme-preview {
  position: relative;
  width: 100%;
  height: 64px;
  overflow: hidden;
  border-radius: 8px;
  border: 1px solid rgb(0 0 0 / 12%);
}

.app-theme-preview i {
  position: absolute;
  left: 10px;
  top: 12px;
  width: 34px;
  height: 6px;
  border-radius: 3px;
}

.app-theme-preview b {
  position: absolute;
  left: 10px;
  bottom: 12px;
  width: 58px;
  height: 5px;
  border-radius: 3px;
  opacity: 0.45;
}

.app-theme-name {
  font-size: 12.5px;
}

.app-theme-check {
  position: absolute;
  top: 8px;
  right: 8px;
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--accent);
  color: var(--btn-primary-text);
}

.settings-float-card {
  grid-column: 1 / -1;
}

.float-pref-body {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: 26px;
}

.float-pref-column {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.float-preset-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.float-preset {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--surface-2);
  color: inherit;
  font-size: 12.5px;
  cursor: pointer;
}

.float-preset:hover {
  border-color: var(--text-3);
}

.float-preset.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-glow);
}

.float-preset i {
  width: 18px;
  height: 18px;
  flex: none;
  border: 1px solid rgb(127 127 127 / 30%);
  border-radius: 5px;
}

.float-color-input {
  width: 100%;
  height: 34px;
  padding: 3px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--surface-2);
  cursor: pointer;
}

.float-toggle-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
}

.float-preview {
  display: flex;
  min-height: 186px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background-color: var(--surface-2);
  background-image: repeating-conic-gradient(rgb(127 127 127 / 18%) 0% 25%, transparent 0% 50%);
  background-size: 16px 16px;
}

.float-preview-inner {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: space-between;
  padding: 14px 16px;
  border-radius: 9px;
  background: var(--f-bg);
  color: var(--f-text);
  font-family: var(--f-font);
  font-size: var(--f-size);
  line-height: 1.65;
  text-shadow: var(--f-shadow);
}

.float-preview-inner p {
  margin: 0;
  font-size: 13px;
  text-align: justify;
  text-indent: 2em;
}

.float-preview-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  font-family: var(--font-mono);
  font-size: 9px;
  opacity: 0.7;
}

.float-preview-bar i {
  flex: 1;
  height: 2px;
  overflow: hidden;
  border-radius: 2px;
  background: color-mix(in srgb, currentColor 22%, transparent);
}

.float-preview-bar b {
  display: block;
  width: 37%;
  height: 100%;
  background: color-mix(in srgb, currentColor 72%, transparent);
}

.float-hotkey-row {
  display: flex;
  gap: 8px;
}

.float-hotkey-input {
  flex: 1;
  min-width: 0;
  padding: 9px 11px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-2);
  color: inherit;
  font-family: var(--font-mono);
  font-size: 12.5px;
  text-align: center;
  cursor: pointer;
}

.float-hotkey-input:focus {
  border-color: var(--accent);
  outline: none;
}

.float-open-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

@media (max-width: 1100px) {
  .float-pref-body {
    grid-template-columns: 1fr;
  }
}
</style>
