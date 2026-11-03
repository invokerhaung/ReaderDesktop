#!/usr/bin/env node
import fs from 'node:fs'

function arg(name, fallback = '') {
  const index = process.argv.indexOf(`--${name}`)
  return index >= 0 ? process.argv[index + 1] : fallback
}

const origin = arg('origin', 'https://www.byqg.cc')
const androidSearchPath = arg('android-search')
const ourSearchPath = arg('our-search')
const androidChaptersPath = arg('android-chapters')
const ourChaptersPath = arg('our-chapters')
const androidContentPath = arg('android-content')
const ourContentPath = arg('our-content')

const readJSON = (path) => JSON.parse(fs.readFileSync(path, 'utf8'))
const unwrap = (value) => (value && !Array.isArray(value) && 'data' in value ? value.data : value)
const normalizeUrl = (value) => {
  if (!value) return ''
  try {
    const url = new URL(value, origin)
    return url.pathname + url.search
  } catch {
    return value
  }
}
const normalizeText = (value) =>
  String(value ?? '')
    .replace(/[\s\u3000]+/g, '')
    .replace(/[“”"'']/g, '')
    .trim()

const report = []
let failed = false
const line = (text = '') => {
  report.push(text)
  console.log(text)
}

if (androidSearchPath && ourSearchPath) {
  const android = unwrap(readJSON(androidSearchPath))
  const ours = unwrap(readJSON(ourSearchPath))
  const normalize = (items) =>
    items.map((item) => ({
      name: String(item.name ?? '').trim(),
      author: String(item.author ?? '').trim(),
      bookUrl: normalizeUrl(item.bookUrl),
      wordCount: String(item.wordCount ?? '').trim(),
    }))
  const a = normalize(android)
  const b = normalize(ours)
  let mismatch = 0
  const limit = Math.max(a.length, b.length)
  for (let i = 0; i < limit; i++) {
    const left = a[i]
    const right = b[i]
    if (!left || !right || left.name !== right.name || left.bookUrl !== right.bookUrl ||
        left.author !== right.author || left.wordCount !== right.wordCount) {
      mismatch += 1
      if (mismatch <= 5) {
        line(`  search[${i}] Android=${JSON.stringify(left)}`)
        line(`  search[${i}] Ours   =${JSON.stringify(right)}`)
      }
    }
  }
  line(`搜索: Android ${a.length} / Ours ${b.length} / 差异 ${mismatch}`)
  if (mismatch) failed = true
}

if (androidChaptersPath && ourChaptersPath) {
  const android = unwrap(readJSON(androidChaptersPath))
  const ours = unwrap(readJSON(ourChaptersPath))
  const normalize = (items) =>
    items.map((item) => ({
      title: String(item.title ?? '').trim(),
      url: normalizeUrl(item.url),
    }))
  const a = normalize(android)
  const b = normalize(ours)
  let mismatch = 0
  const limit = Math.min(a.length, b.length)
  for (let i = 0; i < limit; i++) {
    if (a[i].title !== b[i].title || a[i].url !== b[i].url) {
      mismatch += 1
      if (mismatch <= 5) {
        line(`  chapter[${i}] Android=${a[i].title} ${a[i].url}`)
        line(`  chapter[${i}] Ours   =${b[i].title} ${b[i].url}`)
      }
    }
  }
  if (a.length !== b.length) mismatch += Math.abs(a.length - b.length)
  line(`目录: Android ${a.length} / Ours ${b.length} / 差异 ${mismatch}`)
  if (mismatch) failed = true
}

if (androidContentPath && ourContentPath) {
  const androidRaw = fs.readFileSync(androidContentPath, 'utf8')
  let androidText
  try {
    androidText = unwrap(JSON.parse(androidRaw))
  } catch {
    androidText = androidRaw
  }
  const oursText = fs.readFileSync(ourContentPath, 'utf8')
  const a = normalizeText(androidText)
  const b = normalizeText(oursText)
  const equal = a === b
  line(`正文: Android ${a.length} 字 / Ours ${b.length} 字 / 去空白后${equal ? '完全一致' : '不一致'}`)
  if (!equal) {
    failed = true
    let index = 0
    while (index < Math.min(a.length, b.length) && a[index] === b[index]) index++
    line(`  首个差异位置: ${index}`)
    line(`  Android: ${a.slice(Math.max(0, index - 30), index + 70)}`)
    line(`  Ours   : ${b.slice(Math.max(0, index - 30), index + 70)}`)
  }
}

if (report.length === 0) {
  console.error('用法: node scripts/golden-diff.mjs --android-search <json> --our-search <json> ' +
    '--android-chapters <json> --our-chapters <json> ' +
    '--android-content <json> --our-content <txt> [--origin <url>]')
  process.exit(2)
}

process.exit(failed ? 1 : 0)
