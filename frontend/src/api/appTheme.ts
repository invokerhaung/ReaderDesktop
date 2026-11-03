export type AppTheme = 'ink' | 'slate' | 'black' | 'paper' | 'eye'

export const APP_THEMES = [
  { id: 'ink', name: '墨夜', swatch: '#0d0f12', accent: '#d9a25e', scheme: 'dark' },
  { id: 'slate', name: '青灰', swatch: '#0e141b', accent: '#5aa9d6', scheme: 'dark' },
  { id: 'black', name: '纯黑', swatch: '#000000', accent: '#d9a25e', scheme: 'dark' },
  { id: 'paper', name: '纸白', swatch: '#f3efe6', accent: '#a8662a', scheme: 'light' },
  { id: 'eye', name: '护眼', swatch: '#e9e7d8', accent: '#8a8443', scheme: 'light' },
] as const

const THEME_KEY = 'legado:app-theme'

export function normalizeAppTheme(value: unknown): AppTheme {
  return APP_THEMES.some((theme) => theme.id === value) ? (value as AppTheme) : 'ink'
}

export function loadAppTheme(): AppTheme {
  return normalizeAppTheme(localStorage.getItem(THEME_KEY))
}
//圣诞节覅圣诞节覅设计的覅
export function applyAppTheme(theme: AppTheme, persist = true): void {
  const target = normalizeAppTheme(theme)
  const meta = APP_THEMES.find((item) => item.id === target)
  document.documentElement.setAttribute('data-app-theme', target)
  document.documentElement.style.colorScheme = meta?.scheme ?? 'dark'
  document.body.style.backgroundColor = ''
  if (persist) localStorage.setItem(THEME_KEY, target)
  // 同步 Windows 原生标题栏的深浅色；浮窗没有标题栏，且不应影响主窗口
  if (!document.documentElement.hasAttribute('data-float-window')) {
    void fetch('/api/window-theme', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dark: meta?.scheme !== 'light' }),
    }).catch(() => undefined)
  }
  window.dispatchEvent(new CustomEvent('legado:app-theme-changed', { detail: target }))
}
