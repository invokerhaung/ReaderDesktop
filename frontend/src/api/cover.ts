export interface CoverContext {
  source?: string | null
  bookUrl?: string | null
  kind?: 'cover' | 'content'
}

/**
 * 图片统一走本地代理：由后端按书源请求头/Referer 拉取并缓存，避免防盗链。
 */
function proxySrc(url: string | null | undefined, context: CoverContext, kind: 'cover' | 'content'): string {
  const raw = url?.trim()
  if (!raw) return ''
  if (/^(data|blob):/i.test(raw)) return raw
  if (!/^https?:\/\//i.test(raw) && !raw.startsWith('//')) return raw
  const params = new URLSearchParams({ url: raw })
  if (context.source) params.set('source', context.source)
  if (context.bookUrl) params.set('book', context.bookUrl)
  if (kind === 'content') params.set('kind', 'content')
  return `/img?${params.toString()}`
}

export function coverSrc(url: string | null | undefined, context: CoverContext = {}): string {
  return proxySrc(url, context, 'cover')
}

export function imageSrc(url: string | null | undefined, context: CoverContext = {}): string {
  return proxySrc(url, context, 'content')
}

export function hideBrokenCover(event: Event): void {
  const image = event.target as HTMLImageElement | null
  if (image) image.style.display = 'none'
}
