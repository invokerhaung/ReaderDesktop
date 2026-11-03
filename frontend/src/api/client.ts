export interface Health {
  status: string
  version: string
}

export interface Book {
  name: string
  author: string
  bookUrl: string
  origin: string
  originName: string
  coverUrl?: string | null
  intro?: string | null
  kind?: string | null
  latestChapterTitle?: string | null
  totalChapterNum: number
  durChapterIndex: number
  durChapterPos?: number
  type?: number
}

export interface SearchBook {
  name: string
  author: string
  bookUrl: string
  coverUrl?: string | null
  intro?: string | null
  kind?: string | null
  lastChapter?: string | null
  wordCount?: string | null
  tocUrl?: string | null
  origin?: string | null
  originName?: string | null
  type?: number
}

export interface AddBookInput {
  bookUrl: string
  sourceKey?: string
  name?: string
  author?: string
  coverUrl?: string | null
  intro?: string | null
  kind?: string | null
  wordCount?: string | null
  tocUrl?: string | null
  latestChapterTitle?: string | null
  type?: number
}

export const SHELF_CHANGED_EVENT = 'legado:shelf-changed'

export interface Chapter {
  index: number
  title: string
  url: string
  isVip?: boolean
  resourceUrl?: string | null
}

export interface VideoResolutionItem {
  index: number
  name: string
  url: string
}

export interface VideoStream {
  bookUrl: string
  index: number
  title: string
  kind: 'hls' | 'file' | 'drm'
  drm: boolean
  url: string
  name: string
  defaultIndex: number
  resolutions: VideoResolutionItem[]
}

export interface PreviewSession {
  id: string
  book: Book
  chapters: Chapter[]
}

export interface BookSource {
  bookSourceName: string
  bookSourceUrl: string
  bookSourceGroup?: string | null
  enabled: boolean
  bookSourceType: number
}

export interface Content {
  index: number
  title?: string
  content: string
  blocks?: ContentBlock[]
}

export interface ContentBlock {
  type: 'text' | 'image'
  text?: string
  src?: string
  alt?: string | null
  style?: string | null
}

export interface ReplaceRule {
  id: number
  name: string
  group?: string | null
  pattern: string
  replacement: string
  scope?: string | null
  scopeTitle: boolean
  scopeContent: boolean
  excludeScope?: string | null
  isEnabled: boolean
  isRegex: boolean
  timeoutMillisecond: number
  sortOrder: number
}

export interface AppSettings {
  theme?: string
  fontFamily?: string
  fontSize?: number
  lineHeight?: number
  paraGap?: number
  pageWidth?: number
  mode?: string
  replaceEnabled?: boolean
  chineseScript?: string
  float?: unknown
  [key: string]: unknown
}

export interface Bookmark {
  id: number
  bookUrl: string
  chapterIndex: number
  chapterTitle: string
  mode: 'paged' | 'scroll' | 'comic' | 'video'
  position: number
  excerpt?: string | null
  createdAt: number
}

export interface Stats {
  dataDir: string
  databaseBytes: number
  bookCount: number
  sourceCount: number
  replaceRuleCount: number
  bookmarkCount: number
}

export interface CacheStats {
  coverBytes: number
  coverFiles: number
  chapterRows: number
  chapterBytes: number
}

interface ApiEnvelope<T> {
  isSuccess: boolean
  errorMsg?: string | null
  data?: T
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init,
  })
  const text = await response.text()
  let envelope: ApiEnvelope<T> | null = null
  try {
    envelope = JSON.parse(text) as ApiEnvelope<T>
  } catch {
    // non-json response
  }
  if (!response.ok || !envelope?.isSuccess) {
    throw new Error(envelope?.errorMsg || `请求失败 (${response.status})`)
  }
  return envelope.data as T
}

export async function getHealth(): Promise<Health> {
  const response = await fetch('/healthz')
  if (!response.ok) throw new Error(`health ${response.status}`)
  return (await response.json()) as Health
}

export const api = {
  sources: () => request<BookSource[]>('/api/sources'),

  importSourceUrl: (url: string) =>
    request<{ imported: number }>('/api/sources/import', {
      method: 'POST',
      body: JSON.stringify({ url }),
    }),

  importSourceJson: (json: unknown) =>
    request<{ imported: number }>('/api/sources/import', {
      method: 'POST',
      body: JSON.stringify(json),
    }),

  deleteSource: (key: string) =>
    request<{ deleted: string }>(`/api/sources?key=${encodeURIComponent(key)}`, {
      method: 'DELETE',
    }),

  setSourceEnabled: (key: string, enabled: boolean) =>
    request<{ key: string; enabled: boolean }>('/api/sources/enabled', {
      method: 'POST',
      body: JSON.stringify({ key, enabled }),
    }),

  sourceGroups: () =>
    request<{ groups: { name: string; count: number }[]; ungrouped: number }>(
      '/api/source-groups',
    ),

  renameSourceGroup: (from: string, to: string) =>
    request<{ from: string; to: string }>('/api/source-groups/rename', {
      method: 'POST',
      body: JSON.stringify({ from, to }),
    }),

  deleteSourceGroup: (name: string) =>
    request<{ deleted: string }>('/api/source-groups/delete', {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),

  createSourceGroup: (name: string, assignUngrouped = true) =>
    request<{ created: string; assignUngrouped: boolean }>('/api/source-groups/create', {
      method: 'POST',
      body: JSON.stringify({ name, assignUngrouped }),
    }),

  setSourceGroups: (key: string, groups: string[]) =>
    request<{ key: string; groups: string[] }>('/api/sources/groups', {
      method: 'POST',
      body: JSON.stringify({ key, groups }),
    }),

  loginSource: (key: string, url?: string) =>
    request<{ key: string; cookies: number }>('/api/sources/login', {
      method: 'POST',
      body: JSON.stringify({ key, url }),
    }),

  clearSourceCookies: (key: string) =>
    request<{ cleared: string }>(
      `/api/sources/cookies?key=${encodeURIComponent(key)}`,
      { method: 'DELETE' },
    ),

  verifySource: (url: string, title?: string) =>
    request<{ url: string; cookies: number }>('/api/sources/verify', {
      method: 'POST',
      body: JSON.stringify({ url, title }),
    }),

  sourceDetail: (key: string) =>
    request<{ json: string }>(`/api/sources/detail?key=${encodeURIComponent(key)}`),

  saveSource: (json: string) =>
    request<{ saved: string }>('/api/sources/save', {
      method: 'POST',
      body: json,
    }),

  testSource: (key: string, keyword?: string) =>
    request<{
      ok: boolean
      elapsedMs: number
      count?: number
      error?: string
      first?: SearchBook | null
    }>('/api/sources/test', {
      method: 'POST',
      body: JSON.stringify({ key, keyword }),
    }),

  exploreKinds: (key: string) =>
    request<{ key: string; name: string; kinds: { title: string; url: string }[] }>(
      `/api/explore?key=${encodeURIComponent(key)}`,
    ),

  exploreBooks: (key: string, url: string, page = 1) =>
    request<SearchBook[]>('/api/explore', {
      method: 'POST',
      body: JSON.stringify({ key, url, page }),
    }),

  search: (keyword: string) =>
    request<SearchBook[]>('/api/search', {
      method: 'POST',
      body: JSON.stringify({ keyword }),
    }),

  searchStream: (
    keyword: string,
    handlers: {
      onStart?: (s: { key: string; name: string }) => void
      onResults?: (s: { key: string; books: SearchBook[] }) => void
      onError?: (s: { key: string; error: string }) => void
      onDone?: (s: { total: number; error?: string }) => void
    },
  ): (() => void) => {
    const es = new EventSource(`/api/search/stream?kw=${encodeURIComponent(keyword)}`)
    const bind = <T,>(event: string, handler?: (data: T) => void) => {
      es.addEventListener(event, (ev) => {
        try {
          handler?.(JSON.parse((ev as MessageEvent).data) as T)
        } catch {
          // ignore malformed frame
        }
      })
    }
    bind('source-start', handlers.onStart)
    bind('source-results', handlers.onResults)
    bind('source-error', handlers.onError)
    bind('done', (data: { total: number; error?: string }) => {
      handlers.onDone?.(data)
      es.close()
    })
    es.onerror = () => {
      handlers.onDone?.({ total: -1 })
      es.close()
    }
    return () => es.close()
  },

  books: () => request<Book[]>('/api/books'),

  addBook: async (book: AddBookInput) => {
    const result = await request<Book>('/api/books', {
      method: 'POST',
      body: JSON.stringify(book),
    })
    window.dispatchEvent(new Event(SHELF_CHANGED_EVENT))
    return result
  },

  deleteBook: (url: string) =>
    request<{ deleted: string }>(`/api/books?url=${encodeURIComponent(url)}`, {
      method: 'DELETE',
    }),

  chapters: (url: string) =>
    request<Chapter[]>(`/api/books/chapters?url=${encodeURIComponent(url)}`),

  refreshChapters: (url: string) =>
    request<Chapter[]>('/api/books/chapters/refresh', {
      method: 'POST',
      body: JSON.stringify({ url }),
    }),

  content: (url: string, index: number, options: { refresh?: boolean } = {}) => {
    const params = new URLSearchParams({ url, index: String(index) })
    if (options.refresh) params.set('refresh', '1')
    return request<Content>(`/api/books/content?${params.toString()}`)
  },

  video: (
    url: string,
    index: number,
    options: { resolution?: number; refresh?: boolean } = {},
  ) => {
    const params = new URLSearchParams({ url, index: String(index) })
    if (options.resolution != null) params.set('resolution', String(options.resolution))
    if (options.refresh) params.set('refresh', '1')
    return request<VideoStream>(`/api/books/video?${params.toString()}`)
  },

  openPreview: (book: SearchBook) =>
    request<PreviewSession>('/api/preview/open', {
      method: 'POST',
      body: JSON.stringify({
        bookUrl: book.bookUrl,
        sourceKey: book.origin ?? undefined,
        name: book.name,
        author: book.author,
        coverUrl: book.coverUrl,
        intro: book.intro,
        kind: book.kind,
        wordCount: book.wordCount,
        tocUrl: book.tocUrl,
        lastChapter: book.lastChapter,
        type: book.type,
      }),
    }),

  previewSession: (id: string) =>
    request<PreviewSession>(`/api/preview/session?id=${encodeURIComponent(id)}`),

  previewContent: (id: string, index: number, options: { refresh?: boolean } = {}) => {
    const params = new URLSearchParams({ id, index: String(index) })
    if (options.refresh) params.set('refresh', '1')
    return request<Content>(`/api/preview/content?${params.toString()}`)
  },

  previewVideo: (
    id: string,
    index: number,
    options: { resolution?: number; refresh?: boolean } = {},
  ) => {
    const params = new URLSearchParams({ id, index: String(index) })
    if (options.resolution != null) params.set('resolution', String(options.resolution))
    if (options.refresh) params.set('refresh', '1')
    return request<VideoStream>(`/api/preview/video?${params.toString()}`)
  },

  closePreview: (id: string) =>
    request<{ closed: string }>('/api/preview/close', {
      method: 'POST',
      body: JSON.stringify({ id }),
    }),

  addBooksByUrl: (urls: string, sourceKey?: string) =>
    request<{
      total: number
      success: number
      results: { url: string; ok: boolean; book?: Book; error?: string }[]
    }>('/api/books/by-url', {
      method: 'POST',
      body: JSON.stringify({ urls, sourceKey }),
    }),

  switchBook: (fromUrl: string, toUrl: string, sourceKey?: string) =>    request<Book>('/api/books/switch', {
      method: 'POST',
      body: JSON.stringify({ fromUrl, toUrl, sourceKey }),
    }),

  saveProgress: (url: string, index: number, position?: number) =>
    request<{ url: string; index: number; position?: number }>('/api/books/progress', {
      method: 'PUT',
      body: JSON.stringify({ url, index, position }),
    }),

  bookmarks: (url: string) =>
    request<Bookmark[]>(`/api/bookmarks?url=${encodeURIComponent(url)}`),

  addBookmark: (bookmark: {
    bookUrl: string
    chapterIndex: number
    chapterTitle: string
    mode: 'paged' | 'scroll' | 'comic' | 'video'
    position: number
    excerpt?: string
  }) =>
    request<Bookmark>('/api/bookmarks', {
      method: 'POST',
      body: JSON.stringify(bookmark),
    }),

  deleteBookmark: (id: number) =>
    request<{ deleted: number }>(`/api/bookmarks?id=${id}`, {
      method: 'DELETE',
    }),

  replaceRules: () => request<ReplaceRule[]>('/api/replace-rules'),

  saveReplaceRule: (rule: ReplaceRule) =>
    request<ReplaceRule>('/api/replace-rules', {
      method: 'POST',
      body: JSON.stringify(rule),
    }),

  deleteReplaceRule: (id: number) =>
    request<{ deleted: number }>(`/api/replace-rules?id=${id}`, {
      method: 'DELETE',
    }),

  setReplaceRuleEnabled: (id: number, enabled: boolean) =>
    request<{ id: number; enabled: boolean }>('/api/replace-rules/enabled', {
      method: 'POST',
      body: JSON.stringify({ id, enabled }),
    }),

  importReplaceRules: (json: unknown) =>
    request<{ imported: number; skipped: number }>('/api/replace-rules/import', {
      method: 'POST',
      body: JSON.stringify(json),
    }),

  importReplaceRulesUrl: (url: string) =>
    request<{ imported: number; skipped: number }>('/api/replace-rules/import', {
      method: 'POST',
      body: JSON.stringify({ url }),
    }),

  testReplaceRule: (payload: {
    text: string
    pattern: string
    replacement: string
    isRegex: boolean
    timeoutMillisecond?: number
  }) =>
    request<{ result: string; changed: boolean }>('/api/replace-rules/test', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  settings: () => request<AppSettings>('/api/settings'),

  saveSettings: (settings: AppSettings) =>
    request<AppSettings>('/api/settings', {
      method: 'PUT',
      body: JSON.stringify(settings),
    }),

  floatCommand: <T = Record<string, unknown>>(
    action: string,
    payload: Record<string, unknown> = {},
  ) =>
    request<T>(`/api/float/${action}`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  stats: () => request<Stats>('/api/stats'),

  cacheStats: () => request<CacheStats>('/api/cache'),

  clearCache: (type: 'cover' | 'chapter' | 'all') =>
    request<CacheStats>(`/api/cache?type=${type}`, {
      method: 'DELETE',
    }),
}

export async function addSearchBookToShelf(book: SearchBook): Promise<Book> {
  return api.addBook({
    bookUrl: book.bookUrl,
    sourceKey: book.origin ?? undefined,
    name: book.name,
    author: book.author,
    coverUrl: book.coverUrl,
    intro: book.intro,
    kind: book.kind,
    wordCount: book.wordCount,
    tocUrl: book.tocUrl,
    latestChapterTitle: book.lastChapter,
    type: book.type,
  })
}
