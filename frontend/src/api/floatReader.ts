export type FloatPreset = 'clear' | 'dark' | 'light' | 'custom'
export type FloatFont = 'serif' | 'sans' | 'kai'
export type FloatProgressPosition = 'bottom' | 'top' | 'hidden'

export interface FloatPrefs {
  preset: FloatPreset
  bgColor: string
  bgOpacity: number
  textColor: string
  fontFamily: FloatFont
  fontSize: number
  lineHeight: number
  autoFontSize: boolean
  outline: boolean
  alwaysOnTop: boolean
  emergencyHide: boolean
  hotkey: string
  wheel: boolean
  clickZones: boolean
  progress: FloatProgressPosition
  width: number
  height: number
}

export const FLOAT_SETTINGS_KEY = 'float'
export const FLOAT_SYNC_CHANNEL = 'legado:float-sync'
export const FLOAT_PREFS_CHANNEL = 'legado:float-prefs'
export const FLOAT_DEFAULT_HOTKEY = 'Ctrl+Alt+X'
export const FLOAT_MIN_WIDTH = 300
export const FLOAT_MIN_HEIGHT = 200

export const FLOAT_DEFAULTS: FloatPrefs = {
  preset: 'dark',
  bgColor: '#0b0c0f',
  bgOpacity: 0.78,
  textColor: '#ece7db',
  fontFamily: 'serif',
  fontSize: 17,
  lineHeight: 1.85,
  autoFontSize: true,
  outline: false,
  alwaysOnTop: true,
  emergencyHide: false,
  hotkey: FLOAT_DEFAULT_HOTKEY,
  wheel: true,
  clickZones: true,
  progress: 'bottom',
  width: 460,
  height: 620,
}

export const FLOAT_PRESETS = [
  { id: 'clear', name: '全透明', swatch: 'transparent', outline: true },
  { id: 'dark', name: '半透明黑底白字', swatch: 'rgba(11,12,15,0.78)', outline: false },
  { id: 'light', name: '半透明白底黑字', swatch: 'rgba(247,243,233,0.86)', outline: false },
  { id: 'custom', name: '自定义', swatch: 'linear-gradient(135deg,#5aa9d6,#d9a25e)', outline: false },
] as const

const PRESET_VALUES: Record<FloatPreset, Partial<FloatPrefs>> = {
  clear: {
    bgColor: '#000000',
    bgOpacity: 0,
    textColor: '#ffffff',
    outline: true,
  },
  dark: {
    bgColor: '#0b0c0f',
    bgOpacity: 0.78,
    textColor: '#ece7db',
    outline: false,
  },
  light: {
    bgColor: '#f7f3e9',
    bgOpacity: 0.86,
    textColor: '#22201c',
    outline: false,
  },
  custom: {},
}

export function applyFloatPreset(prefs: FloatPrefs, preset: FloatPreset): FloatPrefs {
  return { ...prefs, preset, ...PRESET_VALUES[preset] }
}

export interface FloatProgressMessage {
  type: 'progress'
  source: 'reader' | 'float'
  bookUrl: string
  index: number
  page: number | null
  percent?: number
  chapterTitle?: string
  /** 发送时刻（Date.now），用于过滤另一端迟到的旧进度，避免翻页被拉回。 */
  at?: number
}

export interface FloatPrefsMessage {
  type: 'prefs'
  prefs: FloatPrefs
}

export interface FloatState {
  open: boolean
  visible: boolean
  alwaysOnTop: boolean
  hotkey?: string | null
  hotkeyError?: string | null
  emergencyHide: boolean
  x?: number | null
  y?: number | null
  width: number
  height: number
  minWidth: number
  minHeight: number
}

export interface FloatOpenInput {
  bookUrl?: string
  index?: number
  page?: number
  percent?: number
  chapterTitle?: string
  top?: boolean
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function normalizeColor(value: unknown, fallback: string): string {
  return typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value.trim()) ? value.trim() : fallback
}

export function normalizeFloatPrefs(value: unknown): FloatPrefs {
  const raw = (value && typeof value === 'object' ? value : {}) as Partial<FloatPrefs>
  const preset: FloatPreset =
    raw.preset === 'clear' || raw.preset === 'dark' || raw.preset === 'light' || raw.preset === 'custom'
      ? raw.preset
      : FLOAT_DEFAULTS.preset
  const font: FloatFont =
    raw.fontFamily === 'sans' || raw.fontFamily === 'kai' || raw.fontFamily === 'serif'
      ? raw.fontFamily
      : FLOAT_DEFAULTS.fontFamily
  const progress: FloatProgressPosition =
    raw.progress === 'top' || raw.progress === 'hidden' || raw.progress === 'bottom'
      ? raw.progress
      : FLOAT_DEFAULTS.progress
  return {
    preset,
    bgColor: normalizeColor(raw.bgColor, FLOAT_DEFAULTS.bgColor),
    bgOpacity:
      typeof raw.bgOpacity === 'number' && Number.isFinite(raw.bgOpacity)
        ? clamp(raw.bgOpacity, 0, 1)
        : FLOAT_DEFAULTS.bgOpacity,
    textColor: normalizeColor(raw.textColor, FLOAT_DEFAULTS.textColor),
    fontFamily: font,
    fontSize:
      typeof raw.fontSize === 'number' && Number.isFinite(raw.fontSize)
        ? Math.round(clamp(raw.fontSize, 12, 30))
        : FLOAT_DEFAULTS.fontSize,
    lineHeight:
      typeof raw.lineHeight === 'number' && Number.isFinite(raw.lineHeight)
        ? Math.round(clamp(raw.lineHeight, 1.4, 2.4) * 10) / 10
        : FLOAT_DEFAULTS.lineHeight,
    autoFontSize: raw.autoFontSize !== false,
    outline: raw.outline === true,
    alwaysOnTop: raw.alwaysOnTop !== false,
    emergencyHide: raw.emergencyHide === true,
    hotkey:
      typeof raw.hotkey === 'string' && raw.hotkey.trim() ? raw.hotkey.trim() : FLOAT_DEFAULT_HOTKEY,
    wheel: raw.wheel !== false,
    clickZones: raw.clickZones !== false,
    progress,
    width:
      typeof raw.width === 'number' && Number.isFinite(raw.width)
        ? Math.round(clamp(raw.width, FLOAT_MIN_WIDTH, 2000))
        : FLOAT_DEFAULTS.width,
    height:
      typeof raw.height === 'number' && Number.isFinite(raw.height)
        ? Math.round(clamp(raw.height, FLOAT_MIN_HEIGHT, 3000))
        : FLOAT_DEFAULTS.height,
  }
}

export function hexToRgb(color: string): [number, number, number] {
  const match = /^#?([0-9a-f]{6})$/i.exec(color)
  if (!match) return [0, 0, 0]
  const value = parseInt(match[1], 16)
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255]
}

export function rgba(color: string, alpha: number): string {
  const [r, g, b] = hexToRgb(color)
  return `rgba(${r},${g},${b},${clamp(alpha, 0, 1)})`
}

function luminance(color: string): number {
  const [r, g, b] = hexToRgb(color)
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
}

export function floatFontStack(font: FloatFont): string {
  if (font === 'kai') return "'KaiTi','STKaiti','Kaiti SC',serif"
  if (font === 'sans') return "'Microsoft YaHei','PingFang SC','Noto Sans SC',system-ui,sans-serif"
  return "'Songti SC','SimSun','Noto Serif SC',Georgia,serif"
}

export function floatPageStyle(
  prefs: FloatPrefs,
  fontSize: number,
): Record<string, string> {
  const lightText = luminance(prefs.textColor) > 0.55
  const shadow = prefs.outline
    ? lightText
      ? '0 1px 2px rgba(0,0,0,.95), 0 0 6px rgba(0,0,0,.7)'
      : '0 1px 2px rgba(255,255,255,.9), 0 0 6px rgba(255,255,255,.55)'
    : 'none'
  const borderAlpha = prefs.bgOpacity < 0.06 ? 0 : 0.22
  return {
    '--f-bg': rgba(prefs.bgColor, prefs.bgOpacity),
    '--f-text': prefs.textColor,
    '--f-shadow': shadow,
    '--f-border': lightText
      ? `rgba(255,255,255,${borderAlpha})`
      : `rgba(0,0,0,${borderAlpha})`,
    '--f-size': `${fontSize}px`,
    '--f-lh': String(prefs.lineHeight),
    '--f-font': floatFontStack(prefs.fontFamily),
  }
}

function normalizeKey(code: string): string | null {
  if (/^Key[A-Z]$/.test(code)) return code.slice(3)
  if (/^Digit[0-9]$/.test(code)) return code.slice(5)
  if (/^Numpad[0-9]$/.test(code)) return `Num${code.slice(6)}`
  if (/^F([1-9]|1[0-9]|2[0-4])$/.test(code)) return code
  const map: Record<string, string> = {
    Backquote: '`',
    Minus: '-',
    Equal: '=',
    BracketLeft: '[',
    BracketRight: ']',
    Backslash: '\\',
    Semicolon: ';',
    Quote: "'",
    Comma: ',',
    Period: '.',
    Slash: '/',
    Space: 'Space',
    Enter: 'Enter',
    Tab: 'Tab',
    Backspace: 'Backspace',
    ArrowLeft: 'Left',
    ArrowRight: 'Right',
    ArrowUp: 'Up',
    ArrowDown: 'Down',
    PageUp: 'PageUp',
    PageDown: 'PageDown',
    Home: 'Home',
    End: 'End',
    Insert: 'Insert',
    Delete: 'Delete',
  }
  return map[code] ?? null
}

/** 把键盘事件转成壳内 global-hotkey 能解析的组合键字符串。 */
export function hotkeyFromEvent(event: KeyboardEvent): string | null {
  const key = normalizeKey(event.code)
  if (!key) return null
  const parts: string[] = []
  if (event.ctrlKey) parts.push('Ctrl')
  if (event.altKey) parts.push('Alt')
  if (event.shiftKey) parts.push('Shift')
  if (event.metaKey) parts.push('Super')
  const functionKey = /^F([1-9]|1[0-9]|2[0-4])$/.test(key)
  if (!parts.length && !functionKey) return null
  return [...parts, key].join('+')
}
