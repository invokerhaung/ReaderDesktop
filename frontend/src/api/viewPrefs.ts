export type ResultLayout = 'list' | 'grid'
export type ShelfLayout = 'wall' | 'list' | 'grid'
export type CoverRatio = '4:3' | '16:9'

const LAYOUT_KEY = 'legado:result-layout'
const RATIO_KEY = 'legado:result-ratio'
const SHELF_LAYOUT_KEY = 'legado:shelf-layout'

export function loadLayout(): ResultLayout {
  return localStorage.getItem(LAYOUT_KEY) === 'grid' ? 'grid' : 'list'
}

export function saveLayout(value: ResultLayout) {
  localStorage.setItem(LAYOUT_KEY, value)
}

export function loadRatio(): CoverRatio {
  return localStorage.getItem(RATIO_KEY) === '16:9' ? '16:9' : '4:3'
}

export function saveRatio(value: CoverRatio) {
  localStorage.setItem(RATIO_KEY, value)
}

export function loadShelfLayout(): ShelfLayout {
  const value = localStorage.getItem(SHELF_LAYOUT_KEY)
  return value === 'list' || value === 'grid' ? value : 'wall'
}

export function saveShelfLayout(value: ShelfLayout) {
  localStorage.setItem(SHELF_LAYOUT_KEY, value)
}
