export function mediaSrc(
  url: string,
  options: { source?: string | null; bookUrl?: string | null } = {},
): string {
  const params = new URLSearchParams({ url })
  if (options.source) params.set('source', options.source)
  if (options.bookUrl) params.set('book', options.bookUrl)
  return `/media?${params.toString()}`
}
