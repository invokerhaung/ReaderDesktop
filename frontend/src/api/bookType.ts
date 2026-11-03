export const BOOK_TYPE = {
  text: 8,
  audio: 32,
  image: 64,
  video: 2048,
  rss: 4096,
} as const

export function isImageBook(type?: number | null): boolean {
  return ((type ?? BOOK_TYPE.text) & BOOK_TYPE.image) !== 0
}

export function isVideoBook(type?: number | null): boolean {
  return ((type ?? BOOK_TYPE.text) & BOOK_TYPE.video) !== 0
}

export function bookTypeLabel(type?: number | null): string {
  if (isImageBook(type)) return '漫画'
  if (isVideoBook(type)) return '视频'
  if (((type ?? 0) & BOOK_TYPE.audio) !== 0) return '音频'
  if (((type ?? 0) & BOOK_TYPE.rss) !== 0) return '订阅'
  return '小说'
}

export function bookRouteName(type?: number | null): 'comic' | 'video' | 'reader' {
  if (isImageBook(type)) return 'comic'
  if (isVideoBook(type)) return 'video'
  return 'reader'
}
