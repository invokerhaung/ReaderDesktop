(function () {
  'use strict'

  const $ = (selector, root = document) => root.querySelector(selector)
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector))
  const clamp = (value, min, max) => Math.min(max, Math.max(min, value))
  const esc = (value) =>
    String(value)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')

  const state = {
    view: 'shelf',
    shelfMode: 'grid',
    group: '全部',
    selectedSource: SOURCES[0].key,
    editorTab: 'overview',
    editorDensity: 'comfortable',
    localFilter: '全部',
    searchTimer: null,
    paletteIndex: 0,
    paletteItems: [],
    ttsPct: 34,
    ttsTimer: null,
    reader: {
      bookId: 'changye',
      chapterIndex: 6,
      pageMode: 'paged',
      page: 0,
      pages: 1,
      theme: 'ink',
      fontSize: 19,
      lineHeight: 1.9,
      paraGap: 0.9,
      pageWidth: 720,
      font: 'serif',
      tts: false,
    },
  }

  const VIEW_TITLES = {
    shelf: '书架',
    search: '发现',
    sources: '书源',
    local: '本地',
    stats: '统计',
    settings: '设置',
  }

  const THEMES = ['ink', 'paper', 'eye', 'black']

  /* ---------------- toast ---------------- */

  function toast(message, type = '') {
    const box = $('#toasts')
    const el = document.createElement('div')
    el.className = `toast ${type}`
    el.textContent = message
    box.appendChild(el)
    setTimeout(() => {
      el.classList.add('leaving')
      setTimeout(() => el.remove(), 220)
    }, 2400)
  }

  /* ---------------- shelf ---------------- */

  function coverMarkup(book) {
    const badge = book.updated ? '<span class="badge">更新</span>' : ''
    const rail = book.progress > 0
      ? `<div class="progress-rail"><i style="width:${book.progress}%"></i></div>`
      : ''
    if (book.cover) {
      return `<div class="cover">
        <img src="${book.cover}" alt="${esc(book.title)}封面" loading="lazy" />
        ${badge}${rail}
      </div>`
    }
    return `<div class="cover">
      <div class="glyph" style="--tone:${book.tone}">${esc(book.glyph)}</div>
      ${badge}${rail}
    </div>`
  }

  function cardMarkup(book) {
    const meta = `<div class="book-meta">
      <div class="book-title">${esc(book.title)}</div>
      <div class="book-author">${esc(book.author)} · ${esc(book.words)}</div>
      <div class="book-chapter">${esc(book.chapter)}</div>
    </div>`

    if (state.shelfMode === 'list') {
      return `<article class="book-card" data-id="${book.id}" tabindex="0" role="button" aria-label="打开${esc(book.title)}">
        ${coverMarkup(book)}
        ${meta}
        <div class="book-side">
          <div class="mini-progress"><i style="width:${book.progress}%"></i></div>
          <span>${book.progress}%</span>
          <span>${book.cached ? `已缓存 ${book.cached} 章` : '未缓存'}</span>
        </div>
      </article>`
    }

    return `<article class="book-card" data-id="${book.id}" tabindex="0" role="button" aria-label="打开${esc(book.title)}">
      ${coverMarkup(book)}
      ${meta}
      <button class="card-more" data-detail="${book.id}" title="书籍详情" aria-label="书籍详情">
        <svg class="icon"><use href="#i-more"/></svg>
      </button>
    </article>`
  }

  function renderShelf() {
    const grid = $('#bookGrid')
    const list = BOOKS.filter((book) => state.group === '全部' || book.group === state.group)
    grid.className = `book-grid${state.shelfMode === 'list' ? ' list' : ''}`
    grid.innerHTML = list.map(cardMarkup).join('')
    $$('.book-card', grid).forEach((card) => {
      const open = () => openReader(card.dataset.id)
      card.addEventListener('click', open)
      card.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          open()
        }
      })
    })
    $$('[data-detail]', grid).forEach((button) => {
      button.addEventListener('click', (event) => {
        event.stopPropagation()
        openBookDetail(button.dataset.detail)
      })
    })
    $('.book-grid').classList.toggle('empty', list.length === 0)
    if (list.length === 0) {
      grid.innerHTML = '<p class="muted-note">这个分组还没有书。切换到“全部”看看。</p>'
    }
  }

  /* ---------------- search ---------------- */

  function runSearch(keyword) {
    const kw = keyword.trim()
    if (!kw) {
      toast('先输入书名或作者')
      $('#searchInput').focus()
      return
    }

    $('#searchInput').value = kw
    $('#searchResults').innerHTML = ''
    $('#searchSuggest').hidden = true
    $('#searchProgress').hidden = false
    $('#searchStatus').textContent = `正在搜索「${kw}」`
    $('#progressBar').style.width = '0%'
    $('#progressLabel').textContent = '正在连接书源…'
    $('#progressCount').textContent = '0 / 8'

    let pct = 0
    let index = 0
    clearInterval(state.searchTimer)
    state.searchTimer = setInterval(() => {
      pct = Math.min(100, pct + 5 + Math.random() * 8)
      index = Math.min(SOURCES.length - 1, Math.floor((pct / 100) * SOURCES.length))
      $('#progressBar').style.width = `${pct}%`
      $('#progressCount').textContent = `${Math.round((pct / 100) * 8)} / 8`
      if (pct < 100) {
        $('#progressLabel').textContent = `正在解析 ${SOURCES[index].name}…`
      } else {
        $('#progressLabel').textContent = '合并重复结果…'
      }
      if (pct >= 100) {
        clearInterval(state.searchTimer)
        setTimeout(() => {
          $('#searchProgress').hidden = true
          renderSearchResults(kw)
          $('#searchStatus').textContent = `找到 ${SEARCH_RESULTS.length} 本 · 耗时 1.2s`
        }, 260)
      }
    }, 90)
  }

  function renderSearchResults(keyword) {
    const list = $('#searchResults')
    list.innerHTML = SEARCH_RESULTS.map((item, index) => {
      const sources = item.sources
        .map((source) => `<span class="source-tag"><i></i>${esc(source.name)}<em>${source.latency}ms</em></span>`)
        .join('')
      return `<article class="result-card">
        <div class="result-cover"><img src="${item.cover}" alt="${esc(item.title)}封面" loading="lazy" /></div>
        <div class="result-main">
          <div class="result-title"><strong>${esc(item.title)}</strong><span>${esc(item.author)} · ${esc(item.words)}</span></div>
          <div class="result-intro">${esc(item.intro)} · 最新：${esc(item.latest)}</div>
          <div class="result-sources">${sources}</div>
        </div>
        <div class="result-actions">
          <button class="btn ghost small" data-preview="${index}">详情</button>
          <button class="btn primary small" data-add="${index}">加入书架</button>
        </div>
      </article>`
    }).join('')

    $$('[data-add]', list).forEach((button) => {
      button.addEventListener('click', () => {
        if (button.dataset.added) return
        button.dataset.added = '1'
        button.textContent = '已在书架'
        button.classList.remove('primary')
        toast(`已加入书架：${keyword}`, 'success')
      })
    })
    $$('[data-preview]', list).forEach((button) => {
      button.addEventListener('click', () => {
        const item = SEARCH_RESULTS[Number(button.dataset.preview)]
        openBookDetail(item.bookId)
      })
    })
  }

  /* ---------------- sources ---------------- */

  function currentSource() {
    return SOURCES.find((source) => source.key === state.selectedSource) || SOURCES[0]
  }

  function renderSourceList() {
    const keyword = ($('#sourceSearch')?.value || '').trim().toLowerCase()
    const list = SOURCES.filter((source) =>
      !keyword || source.name.toLowerCase().includes(keyword) || source.url.toLowerCase().includes(keyword)
    )
    $('#sourceList').innerHTML = list.map((source) => {
      const dot = source.status === 'ok' ? '' : source.status === 'warn' ? 'warn' : 'off'
      const latency = source.enabled ? `${source.latency}ms` : '已停用'
      return `<div class="source-item${source.key === state.selectedSource ? ' active' : ''}" data-source="${source.key}" role="button" tabindex="0">
        <div class="name"><i class="${dot}"></i>${esc(source.name)}</div>
        <div class="latency">${latency}</div>
        <div class="url">${esc(source.url)}</div>
      </div>`
    }).join('')

    $$('.source-item').forEach((item) => {
      const select = () => selectSource(item.dataset.source)
      item.addEventListener('click', select)
      item.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          select()
        }
      })
    })

    const enabled = SOURCES.filter((source) => source.enabled).length
    $('#sourceCount').textContent = `显示 ${list.length} / ${SOURCES.length} · 启用 ${enabled}`
  }

  function selectSource(key) {
    state.selectedSource = key
    renderSourceList()
    renderEditor()
    $('#consoleLog').innerHTML = ''
    resetConsole()
  }

  function resetConsole() {
    const source = currentSource()
    consoleLog(`已载入书源：${source.name}`)
    consoleLog(`地址 ${source.url}`)
    consoleLog(source.enabled ? `最近响应 ${source.latency}ms · 等待运行测试` : '书源已停用', source.enabled ? 'ok' : 'warn')
  }

  function renderEditor() {
    const source = currentSource()
    $('#editorName').textContent = source.name
    $('#editorUrl').textContent = source.url
    $('#editorEnabled').checked = source.enabled
    const status = $('#editorStatus')
    status.className = `pill ${source.status === 'ok' ? 'pill-ok' : 'pill-warn'}`
    status.innerHTML = `<i class="dot"></i>${source.status === 'ok' ? '可用' : '需验证'} · ${source.enabled ? `${source.latency}ms` : '已停用'}`

    $$('#editorTabs button').forEach((button) => {
      button.classList.toggle('active', button.dataset.tab === state.editorTab)
    })

    const schema = EDITOR_SCHEMA[state.editorTab] || []
    let html = ''
    let sectionOpen = false
    let fieldCount = 0
    let requiredCount = 0

    schema.forEach((item) => {
      if (item.section) {
        if (sectionOpen) html += '</div></div>'
        html += `<div class="form-section">
          <div class="section-head"><span>${esc(item.section)}</span>${item.note ? `<em>${esc(item.note)}</em>` : ''}</div>
          <div class="form-grid">`
        sectionOpen = true
        return
      }

      fieldCount += 1
      if (item.required) requiredCount += 1
      const value = item.value(source)
      const mono = item.mono ? ' mono' : ''
      const control = item.area
        ? `<textarea class="field-input${mono}" rows="3">${esc(value)}</textarea>`
        : `<input class="field-input${mono}" value="${esc(value)}" />`
      html += `<div class="field${item.wide ? ' wide' : ''}">
        <label>${esc(item.label)}${item.required ? '<i class="req">必填</i>' : ''}</label>
        ${control}
        ${item.hint ? `<div class="hint">${esc(item.hint)}</div>` : ''}
      </div>`
    })

    if (sectionOpen) html += '</div></div>'
    const body = $('#editorBody')
    body.innerHTML = html
    body.classList.toggle('dense', state.editorDensity === 'dense')
    $('#editorMeta').textContent = `${fieldCount} 字段 · ${requiredCount} 必填`
  }

  function consoleLog(text, cls = '') {
    const time = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    const line = document.createElement('div')
    line.className = 'line'
    line.innerHTML = `<span class="time">[${time}]</span><span class="${cls}">${esc(text)}</span>`
    $('#consoleLog').appendChild(line)
    $('#consoleLog').scrollTop = $('#consoleLog').scrollHeight
  }

  function runSourceTest() {
    const source = currentSource()
    const button = $('#runTest')
    button.disabled = true
    $('#consoleStatus').textContent = '运行中…'
    $('#consoleLog').innerHTML = ''
    consoleLog(`开始测试：${source.name}`)
    consoleLog(`GET ${source.url}/search?q=长夜行`)

    setTimeout(() => consoleLog('连接成功 · TLS 1.3', 'ok'), 320)
    setTimeout(() => consoleLog(`HTTP 200 · ${source.latency || 312}ms · 42 KB`, 'ok'), 700)
    setTimeout(() => consoleLog('CSS 规则命中 class.book-list li × 6'), 1050)
    setTimeout(() => {
      consoleLog('解析结果：6 条，去重后 5 条', 'ok')
      consoleLog('测试通过', 'ok')
      $('#consoleStatus').textContent = '成功 · 6 条'
      button.disabled = false
      toast(`${source.name} 测试通过`, 'success')
    }, 1450)
  }

  /* ---------------- local / stats ---------------- */

  function formatSize(bytes) {
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  }

  const LOCAL_TONES = [
    'linear-gradient(150deg,#3a2f26,#191614)',
    'linear-gradient(150deg,#26313b,#12161a)',
    'linear-gradient(150deg,#2a3330,#121715)',
    'linear-gradient(150deg,#332b33,#151218)',
    'linear-gradient(150deg,#3a2c25,#171211)',
  ]

  function addLocalFiles(files) {
    Array.from(files).forEach((file, index) => {
      const ext = (file.name.split('.').pop() || '').toUpperCase()
      const format = ['TXT', 'EPUB', 'PDF'].includes(ext) ? ext : 'TXT'
      LOCAL_BOOKS.unshift({
        id: `local-${Date.now()}-${index}`,
        title: file.name.replace(/\.[^.]+$/, ''),
        author: '本地文件',
        format,
        size: formatSize(file.size),
        meta: format === 'PDF' ? '等待解析页数' : '等待解析章节',
        last: '刚刚',
        progress: 0,
        tone: LOCAL_TONES[index % LOCAL_TONES.length],
        glyph: [...file.name][0] || '书',
      })
    })
    renderLocal()
    if (files.length) toast(`已导入 ${files.length} 个文件`, 'success')
  }

  function renderLocal() {
    const list = LOCAL_BOOKS.filter((book) => state.localFilter === '全部' || book.format === state.localFilter)
    const counts = ['TXT', 'EPUB', 'PDF']
      .map((format) => `${format} ${LOCAL_BOOKS.filter((book) => book.format === format).length}`)
      .join(' · ')
    $('#localCount').textContent = `${LOCAL_BOOKS.length} 个文件`
    $('#localFormats').textContent = counts

    const container = $('#localList')
    if (!list.length) {
      container.innerHTML = '<div class="local-empty">这个格式还没有文件。拖拽或点击“导入文件”。</div>'
      return
    }

    container.innerHTML = list.map((book) => `<div class="local-row">
      <div class="local-book">
        <div class="local-thumb" style="background:${book.tone}">${esc(book.glyph)}</div>
        <div class="local-book-text"><strong>${esc(book.title)}</strong><span>${esc(book.author)}</span></div>
      </div>
      <span class="local-badge">${esc(book.format)}</span>
      <span class="local-size">${esc(book.size)}</span>
      <span class="local-meta">${esc(book.meta)}</span>
      <span class="local-last">${esc(book.last)}</span>
      <div class="local-progress"><span class="bar"><i style="width:${book.progress}%"></i></span>${book.progress}%</div>
      <div class="local-actions">
        <button class="icon-btn" data-local-open="${book.id}" title="打开"><svg class="icon"><use href="#i-arrow-right"/></svg></button>
        <button class="icon-btn" data-local-del="${book.id}" title="移除"><svg class="icon"><use href="#i-trash"/></svg></button>
      </div>
    </div>`).join('')

    $$('[data-local-open]', container).forEach((button) => {
      button.addEventListener('click', () => {
        const book = LOCAL_BOOKS.find((item) => item.id === button.dataset.localOpen)
        toast(`打开《${book.title}》 · 阅读器将在 Phase 4 接入`)
      })
    })
    $$('[data-local-del]', container).forEach((button) => {
      button.addEventListener('click', () => {
        const index = LOCAL_BOOKS.findIndex((item) => item.id === button.dataset.localDel)
        if (index < 0) return
        const [removed] = LOCAL_BOOKS.splice(index, 1)
        renderLocal()
        toast(`已移除《${removed.title}》`)
      })
    })
  }

  function renderChart() {
    const chart = $('#chart')
    const max = Math.max(...STATS)
    chart.innerHTML = STATS.map((value, index) => `<div class="bar-wrap">
      <div class="bar" data-height="${Math.round((value / max) * 78)}" style="height:0"></div>
      <span class="bar-label">${STAT_LABELS[index]}</span>
    </div>`).join('')
    requestAnimationFrame(() => {
      $$('.bar', chart).forEach((bar) => {
        bar.style.height = `${bar.dataset.height}%`
      })
    })
  }

  /* ---------------- reader ---------------- */

  function currentBook() {
    return BOOKS.find((book) => book.id === state.reader.bookId) || BOOKS[0]
  }

  function chapterTitle() {
    return CHAPTERS[state.reader.chapterIndex] || CHAPTERS[0]
  }

  function openReader(bookId) {
    state.reader.bookId = bookId || state.reader.bookId
    state.reader.page = 0
    const reader = $('#reader')
    reader.hidden = false
    $('#readerBook').textContent = currentBook().title
    $('#readerChapter').textContent = chapterTitle()
    renderToc()
    applyReaderVars()
    reader.classList.remove('mode-scroll')
    if (state.reader.pageMode === 'scroll') reader.classList.add('mode-scroll')
    requestAnimationFrame(() => {
      layoutPages()
      showChrome()
    })
  }

  function closeReader() {
    $('#reader').hidden = true
    closeDrawers()
    $('#ttsBar').hidden = true
    state.reader.tts = false
    stopTtsTimer()
    hideSelection()
  }

  function makeBlocks() {
    const title = chapterTitle().replace(/^第\s*\d+\s*章\s*/, '')
    const blocks = []
    const heading = document.createElement('h1')
    heading.textContent = title
    blocks.push(heading)
    CHAPTER_TEXT.forEach((text) => {
      const paragraph = document.createElement('p')
      paragraph.textContent = text
      blocks.push(paragraph)
    })
    return blocks
  }

  function createPage() {
    const page = document.createElement('div')
    page.className = 'page'
    return page
  }

  function layoutPages() {
    const pagesEl = $('#readerPages')
    const viewport = $('#readerViewport')
    if (!pagesEl || !viewport || $('#reader').hidden) return

    pagesEl.innerHTML = ''
    pagesEl.style.transform = 'translateX(0)'
    const blocks = makeBlocks()

    if (state.reader.pageMode === 'scroll') {
      const page = createPage()
      blocks.forEach((block) => page.appendChild(block))
      pagesEl.appendChild(page)
      state.reader.pages = 1
      state.reader.page = 0
      updatePageIndicator()
      return
    }

    const pageHeight = viewport.clientHeight
    let page = createPage()
    pagesEl.appendChild(page)
    blocks.forEach((block) => {
      page.appendChild(block)
      if (page.scrollHeight > pageHeight && page.childNodes.length > 1) {
        page.removeChild(block)
        page = createPage()
        pagesEl.appendChild(page)
        page.appendChild(block)
      }
    })

    state.reader.pages = pagesEl.children.length
    state.reader.page = clamp(state.reader.page, 0, state.reader.pages - 1)
    updatePageIndicator()
    applyPageTransform()
  }

  function pageStep() {
    const page = $('#readerPages')?.firstElementChild
    const width = page ? page.getBoundingClientRect().width : state.reader.pageWidth
    return width + 64
  }

  function applyPageTransform() {
    const pagesEl = $('#readerPages')
    if (!pagesEl) return
    if (state.reader.pageMode === 'scroll') {
      pagesEl.style.transform = 'none'
      return
    }
    pagesEl.style.transform = `translateX(${-state.reader.page * pageStep()}px)`
  }

  function updatePageIndicator() {
    $('#pageIndicator').textContent = `${state.reader.page + 1} / ${state.reader.pages}`
    const pct = state.reader.pages <= 1 ? 100 : ((state.reader.page + 1) / state.reader.pages) * 100
    $('#readerProgressBar').style.width = `${pct}%`
  }

  function goPage(delta) {
    if (state.reader.pageMode === 'scroll') return
    const next = clamp(state.reader.page + delta, 0, state.reader.pages - 1)
    if (next === state.reader.page) return
    state.reader.page = next
    applyPageTransform()
    updatePageIndicator()
  }

  function applyReaderVars() {
    const reader = $('#reader')
    reader.dataset.theme = state.reader.theme
    reader.dataset.font = state.reader.font
    reader.style.setProperty('--r-size', `${state.reader.fontSize}px`)
    reader.style.setProperty('--r-lh', String(state.reader.lineHeight))
    reader.style.setProperty('--r-para', `${state.reader.paraGap}em`)
    reader.style.setProperty('--page-width', `${state.reader.pageWidth}px`)

    $('#fontSizeVal').textContent = `${state.reader.fontSize} px`
    $('#lineHeightVal').textContent = state.reader.lineHeight.toFixed(1)
    $('#paraGapVal').textContent = `${state.reader.paraGap.toFixed(1)} em`
    $('#pageWidthVal').textContent = `${state.reader.pageWidth} px`
    $('#fontSize').value = String(state.reader.fontSize)
    $('#lineHeight').value = String(Math.round(state.reader.lineHeight * 10))
    $('#paraGap').value = String(Math.round(state.reader.paraGap * 10))
    $('#pageWidth').value = String(state.reader.pageWidth)
    $('#fontFamily').value = state.reader.font

    const preview = $('#previewPage')
    if (preview) {
      preview.dataset.theme = state.reader.theme
      preview.style.setProperty('--pv-size', `${state.reader.fontSize}px`)
      preview.style.setProperty('--pv-lh', String(state.reader.lineHeight))
      preview.style.setProperty('--pv-para', `${state.reader.paraGap}em`)
    }
    if ($('#previewSpec')) {
      $('#previewSpec').textContent = `${state.reader.fontSize} px · ${state.reader.lineHeight.toFixed(1)} · ${state.reader.pageWidth} px`
    }

    $('#setFontSize').value = String(state.reader.fontSize)
    $('#setLineHeight').value = String(Math.round(state.reader.lineHeight * 10))
    $('#setParaGap').value = String(Math.round(state.reader.paraGap * 10))
    $('#setPageWidth').value = String(state.reader.pageWidth)
    $('#setFontSizeVal').textContent = `${state.reader.fontSize} px`
    $('#setLineHeightVal').textContent = state.reader.lineHeight.toFixed(1)
    $('#setParaGapVal').textContent = `${state.reader.paraGap.toFixed(1)} em`
    $('#setPageWidthVal').textContent = `${state.reader.pageWidth} px`

    $$('[data-theme-picker] .theme-chip').forEach((chip) => {
      chip.classList.toggle('active', chip.dataset.theme === state.reader.theme)
    })
    $$('#pageMode button').forEach((button) => {
      button.classList.toggle('active', button.dataset.mode === state.reader.pageMode)
    })
    $$('[data-setting="pageMode"] button').forEach((button) => {
      button.classList.toggle('active', button.dataset.value === state.reader.pageMode)
    })
  }

  let relayoutTimer = null
  function scheduleRelayout() {
    clearTimeout(relayoutTimer)
    relayoutTimer = setTimeout(layoutPages, 80)
  }

  function cycleTheme() {
    const index = THEMES.indexOf(state.reader.theme)
    state.reader.theme = THEMES[(index + 1) % THEMES.length]
    applyReaderVars()
    const names = { ink: '墨夜', paper: '纸白', eye: '护眼', black: '纯黑' }
    toast(`阅读主题：${names[state.reader.theme]}`)
  }

  function renderToc() {
    const list = $('#tocList')
    if (!list) return
    list.innerHTML = CHAPTERS.map((title, index) =>
      `<button class="toc-item${index === state.reader.chapterIndex ? ' active' : ''}" data-chapter="${index}">${esc(title)}</button>`
    ).join('')
    $$('.toc-item', list).forEach((item) => {
      item.addEventListener('click', () => {
        state.reader.chapterIndex = Number(item.dataset.chapter)
        state.reader.page = 0
        $('#readerChapter').textContent = chapterTitle()
        renderToc()
        layoutPages()
        closeDrawers()
        toast(`已跳转到 ${chapterTitle()}`)
      })
    })
  }

  function filterToc(keyword) {
    const value = keyword.trim()
    $$('.toc-item').forEach((item) => {
      item.style.display = !value || item.textContent.includes(value) ? '' : 'none'
    })
  }

  /* ---------------- drawers / chrome ---------------- */

  function openDrawer(id) {
    closeDrawers(id)
    $(`#${id}`).classList.add('open')
    showChrome(true)
  }

  function closeDrawers(exceptId) {
    $$('.drawer').forEach((drawer) => {
      if (drawer.id !== exceptId) drawer.classList.remove('open')
    })
  }

  function anyDrawerOpen() {
    return $$('.drawer.open').length > 0
  }

  function hideSelection() {
    $('#selectionToolbar').hidden = true
  }

  let chromeTimer = null
  function showChrome(keep = false) {
    const reader = $('#reader')
    if (reader.hidden) return
    reader.classList.remove('chrome-hidden')
    clearTimeout(chromeTimer)
    chromeTimer = setTimeout(() => {
      if (reader.hidden || anyDrawerOpen() || !$('#ttsBar').hidden || !$('#selectionToolbar').hidden) return
      reader.classList.add('chrome-hidden')
    }, keep ? 5200 : 2600)
  }

  /* ---------------- tts ---------------- */

  function startTtsTimer() {
    stopTtsTimer()
    state.ttsTimer = setInterval(() => {
      state.ttsPct = (state.ttsPct + 0.6) % 100
      $('.tts-progress i').style.width = `${state.ttsPct}%`
    }, 500)
  }

  function stopTtsTimer() {
    clearInterval(state.ttsTimer)
    state.ttsTimer = null
  }

  function toggleTts(force) {
    const bar = $('#ttsBar')
    const visible = force !== undefined ? force : bar.hidden
    bar.hidden = !visible
    state.reader.tts = visible
    $('#ttsPlay').innerHTML = visible
      ? '<svg class="icon"><use href="#i-pause"/></svg>'
      : '<svg class="icon"><use href="#i-play"/></svg>'
    if (visible) {
      startTtsTimer()
      showChrome(true)
    } else {
      stopTtsTimer()
    }
  }

  /* ---------------- palette ---------------- */

  function renderPalette(filter = '') {
    const keyword = filter.trim().toLowerCase()
    state.paletteItems = COMMANDS.filter((command) => command.label.toLowerCase().includes(keyword))
    state.paletteIndex = clamp(state.paletteIndex, 0, Math.max(0, state.paletteItems.length - 1))
    $('#cmdList').innerHTML = state.paletteItems.map((command, index) => `<button class="cmd-item${index === state.paletteIndex ? ' active' : ''}" data-command="${command.id}">
      <svg class="icon"><use href="#${command.icon}"/></svg>
      <span>${esc(command.label)}</span>
      <span class="hint">${esc(command.hint)}</span>
    </button>`).join('') || '<div class="cmd-item">没有匹配的命令</div>'

    $$('.cmd-item[data-command]').forEach((item) => {
      item.addEventListener('click', () => executeCommand(item.dataset.command))
      item.addEventListener('mousemove', () => {
        state.paletteIndex = state.paletteItems.findIndex((command) => command.id === item.dataset.command)
        $$('.cmd-item').forEach((el, index) => el.classList.toggle('active', index === state.paletteIndex))
      })
    })
  }

  function openPalette() {
    const overlay = $('#cmdPalette')
    overlay.hidden = false
    state.paletteIndex = 0
    $('#cmdInput').value = ''
    renderPalette('')
    setTimeout(() => $('#cmdInput').focus(), 30)
  }

  function closePalette() {
    $('#cmdPalette').hidden = true
  }

  function executeCommand(id) {
    closePalette()
    switch (id) {
      case 'shelf': switchView('shelf'); break
      case 'search':
        switchView('search')
        setTimeout(() => $('#searchInput').focus(), 60)
        break
      case 'sources': switchView('sources'); break
      case 'reader': openReader('changye'); break
      case 'theme': cycleTheme(); break
      case 'import':
        switchView('local')
        setTimeout(() => $('#localInput').click(), 80)
        break
      case 'settings': switchView('settings'); break
      case 'importSources': openImportSources(); break
      case 'backup': openBackup('local'); break
      case 'welcome': openWelcome(); break
      case 'update': openUpdate(); break
      case 'about': openAbout(); break
      default: break
    }
  }

  /* ---------------- navigation ---------------- */

  function switchView(view) {
    state.view = view
    $$('.nav-item').forEach((item) => item.classList.toggle('active', item.dataset.view === view))
    $$('.view').forEach((section) => section.classList.toggle('active', section.id === `view-${view}`))
    $('#crumbRoot').textContent = VIEW_TITLES[view] || ''
    $('#crumbSub').textContent = view === 'shelf' ? state.group : view === 'sources' ? '全部书源' : ''
    if (view === 'shelf') renderShelf()
    if (view === 'sources') renderSourceList()
    if (view === 'local') renderLocal()
    if (view === 'stats') renderChart()
    if (view === 'search') setTimeout(() => $('#searchInput').focus(), 80)
    $('.views').scrollTop = 0
  }

  function activateSettingsPanel(panel) {
    $$('#settingsNav button').forEach((button) => button.classList.toggle('active', button.dataset.panel === panel))
    $$('.settings-panel').forEach((section) => section.classList.toggle('active', section.dataset.panel === panel))
  }

  /* ---------------- modal / side panel ---------------- */

  function modalFoot(left, right) {
    return `<span class="foot-left">${left || ''}</span><div class="foot-right">${right || ''}</div>`
  }

  function openModal({ title, subtitle = '', body = '', footer = '', width = 720, mount }) {
    const overlay = $('#modalOverlay')
    overlay.hidden = false
    $('#modalTitle').textContent = title
    $('#modalSubtitle').textContent = subtitle
    $('#modalSubtitle').hidden = !subtitle
    $('#modalBox').style.setProperty('--modal-w', `${width}px`)
    $('#modalBody').innerHTML = body
    $('#modalFoot').innerHTML = footer
    $('#modalBody').scrollTop = 0
    if (mount) mount()
  }

  function closeModal() {
    $('#modalOverlay').hidden = true
  }

  function openPanel(title, body, mount) {
    $('#panelTitle').textContent = title
    $('#panelBody').innerHTML = body
    $('#panelScrim').hidden = false
    $('#sidePanel').hidden = false
    $('#panelBody').scrollTop = 0
    if (mount) mount()
  }

  function closePanel() {
    $('#panelScrim').hidden = true
    $('#sidePanel').hidden = true
  }

  function coverImage(book) {
    if (book.cover) return `<img src="${book.cover}" alt="${esc(book.title)}" />`
    return `<div class="glyph" style="background:${book.tone}">${esc(book.glyph)}</div>`
  }

  /* ---------------- book detail ---------------- */

  function openBookDetail(bookId) {
    const book = BOOKS.find((item) => item.id === bookId) || BOOKS[0]
    const sources = SOURCES.filter((source) => source.enabled).slice(0, 3)
    const body = `<div class="detail-hero">
      <div class="detail-cover">${coverImage(book)}</div>
      <div class="detail-info">
        <h3>${esc(book.title)}</h3>
        <div class="author">${esc(book.author)} · ${esc(book.status)} · ${esc(book.words)}</div>
        <div class="detail-tags">
          <span class="detail-tag">${esc(book.group)}</span>
          <span class="detail-tag">${book.cached ? `已缓存 ${book.cached} 章` : '未缓存'}</span>
          <span class="detail-tag">${esc(book.latest)}</span>
        </div>
      </div>
    </div>
    <p class="detail-intro">${esc(book.intro)}</p>
    <div class="detail-actions">
      <button class="btn primary small" data-detail-action="read">继续阅读</button>
      <button class="btn ghost small" data-detail-action="switch">换源</button>
      <button class="btn ghost small" data-detail-action="cache">缓存</button>
      <button class="btn ghost small" data-detail-action="shelf">${book.progress > 0 ? '已在书架' : '加入书架'}</button>
    </div>
    <section class="detail-section">
      <h4>来源 · ${sources.length}</h4>
      ${sources.map((source, index) => `<div class="source-option${index === 0 ? ' current' : ''}">
        <div class="name"><strong>${esc(source.name)}</strong><span>${esc(source.note)}</span></div>
        <span class="meta">${source.latency}ms</span>
      </div>`).join('')}
    </section>
    <section class="detail-section">
      <h4>目录 · 共 ${CHAPTERS.length} 章</h4>
      ${CHAPTERS.slice(0, 5).map((chapter) => `<div class="chapter-mini">
        <span>${esc(chapter)}</span>
        <span>${chapter === book.chapter ? '在读' : ''}</span>
      </div>`).join('')}
      <button class="text-btn" data-detail-action="read">查看全部章节</button>
    </section>`

    openPanel('书籍详情', body)

    $$('#panelBody [data-detail-action]').forEach((button) => {
      button.addEventListener('click', () => {
        const action = button.dataset.detailAction
        if (action === 'read') {
          closePanel()
          openReader(book.id)
        } else if (action === 'switch') {
          closePanel()
          openSwitchSource(book.id)
        } else if (action === 'cache') {
          closePanel()
          openCacheManager()
        } else {
          toast('已在书架', 'success')
        }
      })
    })
  }

  /* ---------------- switch source ---------------- */

  function openSwitchSource(bookId) {
    const book = BOOKS.find((item) => item.id === bookId) || BOOKS[0]
    const body = `<div class="search-progress" style="margin-bottom:16px">
      <div class="progress-track"><i style="width:100%"></i></div>
      <div class="progress-caption"><span>已匹配 ${SOURCE_MATCHES.length} 个来源</span><span>耗时 1.2s</span></div>
    </div>
    ${SOURCE_MATCHES.map((source, index) => `<div class="match-row${source.current ? ' current' : ''}">
      <div class="match-source"><strong>${esc(source.name)}${source.current ? ' · 当前' : ''}</strong><span>${esc(source.words)}</span></div>
      <div class="match-latest">${esc(source.latest)}</div>
      <div class="match-source"><span>${source.latency}ms</span></div>
      <span class="match-score">${source.score}%</span>
      <button class="btn ${source.current ? 'ghost' : 'primary'} small" data-switch="${index}" ${source.current ? 'disabled' : ''}>${source.current ? '当前' : '切换'}</button>
    </div>`).join('')}`

    openModal({
      title: `换源 · 《${book.title}》`,
      subtitle: '按章节完整度与响应速度排序，切换后保留阅读位置',
      width: 800,
      body,
      footer: modalFoot('当前来源：墨香居', '<button class="btn ghost" data-modal-close>取消</button>'),
      mount() {
        $$('[data-switch]').forEach((button) => {
          button.addEventListener('click', () => {
            const source = SOURCE_MATCHES[Number(button.dataset.switch)]
            closeModal()
            toast(`已切换到 ${source.name}，阅读位置已保留`, 'success')
          })
        })
      },
    })
  }

  /* ---------------- import sources ---------------- */

  const importState = { tab: 'file', selected: new Set() }

  function importRowsHtml() {
    const label = { new: '新增', dup: '重复', error: '格式错误' }
    return IMPORT_RESULTS.map((item, index) => `<div class="import-row">
      <input class="check" type="checkbox" data-import-index="${index}" ${importState.selected.has(index) ? 'checked' : ''} ${item.status === 'error' ? 'disabled' : ''} />
      <span>${esc(item.name)}</span>
      <span class="url">${esc(item.url)}</span>
      <span>${esc(item.group)}</span>
      <span class="import-status ${item.status}">${label[item.status]}</span>
    </div>`).join('')
  }

  function renderImportWizard() {
    const filePane = `<div class="import-drop">
      <strong>拖拽 JSON 文件到这里</strong>
      <span>支持一次导入多个书源，或点击下方选择文件</span>
      <button class="btn ghost small" id="pickSourceFile" style="margin-top:12px">选择文件</button>
    </div>`
    const urlPane = `<div class="field">
      <label>订阅地址</label>
      <div style="display:flex;gap:8px">
        <input class="mini-input" id="sourceUrlInput" style="flex:1;width:auto" placeholder="https://example.com/sources.json" />
        <button class="btn primary small" id="readSourceUrl">读取</button>
      </div>
      <p class="form-note">支持 JSON 订阅、Legado 订阅链接与 legado://import/... 深链。</p>
    </div>`
    const deepPane = `<div class="field">
      <label>深链</label>
      <div style="display:flex;gap:8px">
        <input class="mini-input" id="sourceDeepInput" style="flex:1;width:auto" placeholder="legado://import/booksource?src=..." />
        <button class="btn primary small" id="readDeepLink">解析</button>
      </div>
      <p class="form-note">安装版注册该协议后，可从浏览器一键唤起导入。</p>
    </div>`
    const jsonPane = `<div class="field">
      <label>粘贴 JSON</label>
      <textarea class="field-input mono" id="sourceJsonInput" rows="6" placeholder='[{"bookSourceName":"示例书源","bookSourceUrl":"https://..."}]'></textarea>
      <button class="btn ghost small" id="parseJson" style="margin-top:8px">解析 JSON</button>
    </div>`
    const pane = { file: filePane, url: urlPane, deep: deepPane, json: jsonPane }[importState.tab]

    openModal({
      title: '导入书源',
      subtitle: '支持文件、订阅 URL、深链与粘贴 JSON；导入前会校验重复与格式',
      width: 780,
      body: `<div class="modal-tabs" id="importTabs">
        <button class="${importState.tab === 'file' ? 'active' : ''}" data-import-tab="file">文件</button>
        <button class="${importState.tab === 'url' ? 'active' : ''}" data-import-tab="url">订阅 URL</button>
        <button class="${importState.tab === 'deep' ? 'active' : ''}" data-import-tab="deep">深链</button>
        <button class="${importState.tab === 'json' ? 'active' : ''}" data-import-tab="json">粘贴 JSON</button>
      </div>
      ${pane}
      <div style="display:flex;justify-content:space-between;align-items:center;margin-top:16px">
        <span class="muted-note">已读取 ${IMPORT_RESULTS.length} 条，失败 1 条</span>
        <button class="text-btn" id="toggleAllImports">全选 / 全不选</button>
      </div>
      <div class="import-list">${importRowsHtml()}</div>`,
      footer: modalFoot(
        `已选择 <b id="importCount">${importState.selected.size}</b> 个书源`,
        '<button class="btn ghost" data-modal-close>取消</button><button class="btn primary" id="doImport">导入</button>',
      ),
      mount() {
        $$('#importTabs button').forEach((button) => {
          button.addEventListener('click', () => {
            importState.tab = button.dataset.importTab
            renderImportWizard()
          })
        })
        $$('[data-import-index]').forEach((checkbox) => {
          checkbox.addEventListener('change', () => {
            const index = Number(checkbox.dataset.importIndex)
            if (checkbox.checked) importState.selected.add(index)
            else importState.selected.delete(index)
            $('#importCount').textContent = String(importState.selected.size)
          })
        })
        $('#toggleAllImports')?.addEventListener('click', () => {
          const selectable = IMPORT_RESULTS.map((item, index) => (item.status !== 'error' ? index : -1)).filter((index) => index >= 0)
          importState.selected = importState.selected.size === selectable.length ? new Set() : new Set(selectable)
          renderImportWizard()
        })
        $('#pickSourceFile')?.addEventListener('click', () => toast('文件选择将在 Phase 2 接入'))
        $('#readSourceUrl')?.addEventListener('click', () => toast('已读取订阅：4 条书源', 'success'))
        $('#readDeepLink')?.addEventListener('click', () => toast('深链解析完成：4 条书源', 'success'))
        $('#parseJson')?.addEventListener('click', () => toast('JSON 解析完成：4 条书源', 'success'))
        $('#doImport')?.addEventListener('click', () => {
          const valid = Array.from(importState.selected).filter((index) => IMPORT_RESULTS[index]?.status !== 'error')
          if (!valid.length) {
            toast('请先选择要导入的书源')
            return
          }
          const button = $('#doImport')
          button.disabled = true
          button.textContent = '校验中…'
          setTimeout(() => {
            button.textContent = `导入中 1/${valid.length}`
            setTimeout(() => {
              button.textContent = `导入中 ${valid.length}/${valid.length}`
              setTimeout(() => {
                closeModal()
                toast(`已导入 ${valid.length} 个书源`, 'success')
              }, 320)
            }, 420)
          }, 320)
        })
      },
    })
  }

  function openImportSources() {
    importState.selected = new Set(IMPORT_RESULTS.map((item, index) => (item.status !== 'error' ? index : -1)).filter((index) => index >= 0))
    importState.tab = 'file'
    renderImportWizard()
  }

  /* ---------------- backup / restore ---------------- */

  function backupRowHtml(item, index, remote = false) {
    return `<div class="backup-row">
      <div><strong>${esc(item.name)}</strong><span>${esc(item.time)} · ${esc(item.size)}${item.type ? ` · ${esc(item.type)}` : ''}</span></div>
      <div class="foot-right">
        <button class="btn ghost small" data-restore="${remote ? `remote-${index}` : index}">恢复</button>
        ${remote ? '' : `<button class="icon-btn" data-delete-backup="${index}" title="删除"><svg class="icon"><use href="#i-trash"/></svg></button>`}
      </div>
    </div>`
  }

  function openBackup(tab = 'local') {
    const localPane = `<div class="backup-list">${BACKUPS.map((item, index) => backupRowHtml(item, index)).join('')}</div>
      <p class="form-note">备份包含书架、书源、书签、阅读进度与净化规则；不包含缓存与登录状态。</p>`
    const remotePane = `<div class="field"><label>服务地址</label><input class="mini-input" style="width:100%" value="https://dav.example.com/reader" /></div>
      <div class="field"><label>账号</label><input class="mini-input" style="width:100%" value="reader@example.com" /></div>
      <div class="field"><label>应用密码</label><input class="mini-input" style="width:100%" type="password" value="app-password" /></div>
      <div class="button-row"><button class="btn ghost small" id="testDav">测试连接</button><button class="btn ghost small" id="uploadDav">上传当前备份</button></div>
      <div class="muted-note" style="margin:16px 0 8px">远端备份</div>
      <div class="backup-list">${REMOTE_BACKUPS.map((item, index) => backupRowHtml(item, index, true)).join('')}</div>`
    const filePane = `<div class="import-drop">
      <strong>选择 ZIP 备份文件</strong>
      <span>支持 Legado 新版 ZIP 备份与旧版 JSON 备份</span>
      <button class="btn ghost small" id="pickBackupFile" style="margin-top:12px">选择文件</button>
    </div>
    <p class="form-note">恢复前会先预览将导入的数据量；跨应用恢复只迁移网络书籍。</p>`

    const pane = { local: localPane, remote: remotePane, file: filePane }[tab]

    openModal({
      title: '备份与恢复',
      subtitle: '本地 ZIP 或 WebDAV，恢复前先预览数据量',
      width: 820,
      body: `<div class="modal-tabs" id="backupTabs">
        <button class="${tab === 'local' ? 'active' : ''}" data-backup-tab="local">本地备份</button>
        <button class="${tab === 'remote' ? 'active' : ''}" data-backup-tab="remote">WebDAV</button>
        <button class="${tab === 'file' ? 'active' : ''}" data-backup-tab="file">从文件恢复</button>
      </div>
      ${pane}`,
      footer: modalFoot('上次备份：今天 09:12', '<button class="btn ghost" data-modal-close>关闭</button><button class="btn primary" id="newBackupNow">立即备份</button>'),
      mount() {
        $$('#backupTabs button').forEach((button) => {
          button.addEventListener('click', () => openBackup(button.dataset.backupTab))
        })
        $$('[data-restore]').forEach((button) => {
          button.addEventListener('click', () => {
            const raw = button.dataset.restore
            const backup = raw.startsWith('remote-')
              ? REMOTE_BACKUPS[Number(raw.slice(7))]
              : BACKUPS[Number(raw)]
            showRestorePreview(backup)
          })
        })
        $$('[data-delete-backup]').forEach((button) => {
          button.addEventListener('click', () => {
            const index = Number(button.dataset.deleteBackup)
            const [removed] = BACKUPS.splice(index, 1)
            openBackup('local')
            toast(`已删除 ${removed.name}`)
          })
        })
        $('#testDav')?.addEventListener('click', () => toast('WebDAV 连接成功 · 128ms', 'success'))
        $('#uploadDav')?.addEventListener('click', () => toast('已上传当前备份', 'success'))
        $('#pickBackupFile')?.addEventListener('click', () => toast('文件选择将在 Phase 2 接入'))
        $('#newBackupNow')?.addEventListener('click', () => toast('备份完成：3.8 MB', 'success'))
      },
    })
  }

  function showRestorePreview(backup) {
    const counts = backup.counts || BACKUPS[0].counts
    openModal({
      title: '恢复备份',
      subtitle: `${backup.name} · ${backup.time} · ${backup.size}`,
      width: 680,
      body: `<div class="restore-summary">
        <div class="restore-stat"><span>书架</span><strong>${counts.books}</strong></div>
        <div class="restore-stat"><span>书源</span><strong>${counts.sources}</strong></div>
        <div class="restore-stat"><span>书签</span><strong>${counts.bookmarks}</strong></div>
        <div class="restore-stat"><span>净化规则</span><strong>${counts.rules}</strong></div>
      </div>
      <div class="radio-row">
        <label><input type="radio" name="restoreMode" checked /> 合并（保留现有数据）</label>
        <label><input type="radio" name="restoreMode" /> 覆盖（清空后导入）</label>
      </div>
      <p class="form-note">覆盖模式会先清空同类数据；跨应用恢复不会迁移本地文件与登录状态。</p>`,
      footer: modalFoot('恢复前建议先创建一次当前备份', '<button class="btn ghost" id="backToBackups">返回</button><button class="btn primary" id="doRestore">开始恢复</button>'),
      mount() {
        $('#backToBackups').addEventListener('click', () => openBackup('local'))
        $('#doRestore').addEventListener('click', () => {
          const button = $('#doRestore')
          button.disabled = true
          button.textContent = '恢复中…'
          setTimeout(() => {
            closeModal()
            toast('恢复完成：24 本书、8 个书源', 'success')
          }, 1100)
        })
      },
    })
  }

  /* ---------------- cache manager ---------------- */

  function openCacheManager() {
    const body = `<div class="cache-summary">
      <div class="cache-stat"><span>已缓存章节</span><strong>139 章</strong></div>
      <div class="cache-stat"><span>占用空间</span><strong>870 MB</strong></div>
      <div class="cache-stat"><span>进行中</span><strong>1 本</strong></div>
    </div>
    <div class="cache-table">
      <div class="cache-head"><span>书籍</span><span>已缓存</span><span>大小</span><span>状态</span><span></span></div>
      ${CACHE_BOOKS.map((book) => `<div class="cache-row">
        <div class="cache-book">
          <img class="cache-thumb" src="${book.cover}" alt="${esc(book.title)}" />
          <div class="cache-book-text"><strong>${esc(book.title)}</strong><span>${book.cached} / ${book.total} 章</span></div>
        </div>
        <div class="cache-progress"><span class="bar"><i style="width:${book.progress}%"></i></span>${book.progress}%</div>
        <span class="local-size">${esc(book.size)}</span>
        <span class="muted-note">${esc(book.status)}</span>
        <div class="local-actions">
          <button class="btn ghost small" data-cache-action="update" data-book="${book.id}">更新</button>
          <button class="icon-btn" data-cache-action="clear" data-book="${book.id}" title="清理"><svg class="icon"><use href="#i-trash"/></svg></button>
        </div>
      </div>`).join('')}
    </div>
    <div class="muted-note" style="margin:18px 0 8px">后台任务</div>
    <div class="task-list">${TASKS.filter((task) => task.id === 'cache').map(taskCardHtml).join('')}</div>`

    openModal({
      title: '缓存管理',
      subtitle: '按书查看已缓存章节、占用空间与下载队列',
      width: 860,
      body,
      footer: modalFoot(`共 ${CACHE_BOOKS.length} 本有缓存`, '<button class="btn ghost" id="clearAllCache">清理全部缓存</button><button class="btn ghost" data-modal-close>关闭</button>'),
      mount() {
        $$('[data-cache-action]').forEach((button) => {
          button.addEventListener('click', () => {
            const book = CACHE_BOOKS.find((item) => item.id === button.dataset.book)
            if (button.dataset.cacheAction === 'clear') toast(`已清理《${book.title}》缓存`)
            else toast(`已加入更新队列：${book.title}`, 'success')
          })
        })
        $('#clearAllCache')?.addEventListener('click', () => toast('已清理 870 MB 缓存', 'success'))
      },
    })
  }

  /* ---------------- login / verification ---------------- */

  function openLogin(sourceKey) {
    const source = SOURCES.find((item) => item.key === sourceKey) || SOURCES[0]
    const body = `<div class="login-grid">
      <div class="login-info">
        <h4>${esc(source.name)}</h4>
        <p>${esc(source.url)}</p>
        <p>当前状态：<b style="color:hsl(var(--warn))">需要验证</b></p>
        <p>完成验证后，Cookie 会回传到书源，之后的搜索与正文请求自动带上。</p>
        <div class="cookie-list">
          <div class="cookie-row">cf_clearance=7f3a…c91b · HttpOnly</div>
          <div class="cookie-row">__cf_bm=Uq2x…9aK · 30 分钟</div>
          <div class="cookie-row">session=eyJhbGci… · 7 天</div>
        </div>
      </div>
      <div class="login-browser">
        <div class="browser-bar"><i class="dot"></i><span class="url">${esc(source.url)}/login</span><span>WebView2</span></div>
        <div class="browser-view">
          <div class="spinner"></div>
          <strong>Cloudflare 正在验证…</strong>
          <span>在独立窗口中完成验证，完成后 Cookie 会自动回传；也可以手动导入 Cookie。</span>
          <button class="btn ghost small" id="openSystemBrowser">打开系统浏览器</button>
        </div>
      </div>
    </div>`

    openModal({
      title: `登录 · ${source.name}`,
      subtitle: '验证窗口独立于主界面，避免 X-Frame-Options 与 iframe 检测',
      width: 780,
      body,
      footer: modalFoot('Cookie 仅保存在本机', '<button class="btn ghost" id="clearLoginCookies">清除 Cookie</button><button class="btn primary" id="finishLogin">我已完成验证</button>'),
      mount() {
        $('#openSystemBrowser')?.addEventListener('click', () => toast('正在打开系统浏览器…'))
        $('#clearLoginCookies')?.addEventListener('click', () => toast('已清除该书的登录 Cookie'))
        $('#finishLogin').addEventListener('click', () => {
          source.status = 'ok'
          source.latency = 220
          source.note = '登录成功'
          renderSourceList()
          renderEditor()
          closeModal()
          toast(`${source.name} 登录成功`, 'success')
        })
      },
    })
  }

  /* ---------------- tasks ---------------- */

  function updateTaskBadge() {
    const running = TASKS.filter((task) => task.status === 'running').length
    const badge = $('#taskBadge')
    badge.textContent = String(running)
    badge.hidden = running === 0
  }

  function taskCardHtml(task) {
    const done = task.status === 'done'
    return `<div class="task-card${done ? ' done' : ''}">
      <div class="task-title">
        <svg class="icon"><use href="#${task.icon}"/></svg>
        <span>${esc(task.title)}</span>
        ${done ? '<span class="muted-note" style="margin-left:auto">完成</span>' : `<button class="icon-btn" data-task-cancel="${task.id}" title="取消"><svg class="icon"><use href="#i-x"/></svg></button>`}
      </div>
      <div class="task-detail">${esc(task.detail)}</div>
      <div class="task-progress"><i style="width:${task.progress}%"></i></div>
    </div>`
  }

  function renderTaskPanel() {
    const running = TASKS.filter((task) => task.status === 'running').length
    const done = TASKS.length - running
    $('#panelBody').innerHTML = `<div class="muted-note" style="margin-bottom:12px">${running} 个进行中 · ${done} 个已完成</div>
      <div class="task-list">${TASKS.map(taskCardHtml).join('')}</div>`
    $$('[data-task-cancel]').forEach((button) => {
      button.addEventListener('click', () => {
        const index = TASKS.findIndex((task) => task.id === button.dataset.taskCancel)
        if (index < 0) return
        const [removed] = TASKS.splice(index, 1)
        updateTaskBadge()
        renderTaskPanel()
        toast(`已取消：${removed.title}`)
      })
    })
  }

  function openTaskCenter() {
    openPanel('任务中心', '')
    renderTaskPanel()
  }

  function addCacheTask(book) {
    const id = `cache-${book.id}`
    if (TASKS.some((task) => task.id === id)) {
      toast('这本书已经在缓存队列中')
      return
    }
    TASKS.unshift({
      id,
      title: `缓存《${book.title}》`,
      detail: `0 / ${CHAPTERS.length} 章 · 排队中`,
      progress: 4,
      status: 'running',
      icon: 'i-download',
    })
    updateTaskBadge()
    toast(`已加入缓存队列：《${book.title}》`, 'success')
  }

  /* ---------------- about / update / welcome ---------------- */

  function openAbout() {
    openModal({
      title: '关于',
      subtitle: '墨读 · Legado Desktop',
      width: 560,
      body: `<div class="about-card">
        <div class="brand-mark large">墨</div>
        <div>
          <h3>墨读 · Legado Desktop</h3>
          <p>版本 ${UPDATE_INFO.current} · Windows x64 · 骨架阶段</p>
          <p>基于 Legado 3.0 规则引擎，GPLv3 开源许可。</p>
        </div>
      </div>
      <div class="setting-row"><span>上游</span><code>legado@7640c219a</code></div>
      <div class="setting-row"><span>数据目录</span><code>C:\\Users\\htxin\\AppData\\Roaming\\Reader</code></div>
      <div class="setting-row"><span>开源许可</span><b>GPLv3</b></div>`,
      footer: modalFoot('© 2026 Legado Desktop contributors', '<button class="btn ghost" data-modal-close>关闭</button><button class="btn primary" id="aboutCheckUpdate">检查更新</button>'),
      mount() {
        $('#aboutCheckUpdate')?.addEventListener('click', () => openUpdate())
      },
    })
  }

  function openUpdate() {
    openModal({
      title: '发现新版本',
      subtitle: `${UPDATE_INFO.current} → ${UPDATE_INFO.latest} · ${UPDATE_INFO.date}`,
      width: 560,
      body: `<ul class="update-notes">${UPDATE_INFO.notes.map((note) => `<li>${esc(note)}</li>`).join('')}</ul>
        <p class="form-note">更新会保留本地书源、书架与阅读进度。</p>`,
      footer: modalFoot('Windows x64 · 约 42 MB', '<button class="btn ghost" data-modal-close>稍后</button><button class="btn primary" id="downloadUpdate">下载更新</button>'),
      mount() {
        $('#downloadUpdate')?.addEventListener('click', () => {
          closeModal()
          toast('已开始下载更新，完成后会提示重启', 'success')
        })
      },
    })
  }

  function openWelcome() {
    openModal({
      title: '把书带进来',
      subtitle: '三步开始：导入书源、添加本地书，或从手机备份恢复',
      width: 780,
      body: `<div class="welcome-grid">
        ${WELCOME_CARDS.map((card) => `<button class="welcome-card" data-welcome="${card.id}">
          <span class="welcome-icon"><svg class="icon"><use href="#${card.icon}"/></svg></span>
          <strong>${esc(card.title)}</strong>
          <span>${esc(card.desc)}</span>
        </button>`).join('')}
      </div>
      <p class="form-note" style="margin-top:14px">本应用不内置书源与内容，请导入你拥有合法使用权的来源。</p>`,
      footer: modalFoot('之后也可以在设置或命令面板重新打开', '<button class="btn ghost" data-modal-close>先逛逛</button>'),
      mount() {
        $$('[data-welcome]').forEach((button) => {
          button.addEventListener('click', () => {
            const target = button.dataset.welcome
            closeModal()
            if (target === 'sources') openImportSources()
            else if (target === 'local') switchView('local')
            else openBackup()
          })
        })
      },
    })
  }

  /* ---------------- reader navigation ---------------- */

  function jumpChapter(index) {
    state.reader.chapterIndex = clamp(index, 0, CHAPTERS.length - 1)
    state.reader.page = 0
    $('#readerChapter').textContent = chapterTitle()
    renderToc()
    layoutPages()
    closeDrawers()
    toast(`已跳转到 ${chapterTitle()}`)
  }

  function renderBookmarks() {
    const list = $('#bookmarkList')
    if (!list) return
    list.innerHTML = BOOKMARKS.map((item, index) => `<button class="bookmark-item" data-bookmark="${index}">
      <strong>${esc(item.title)}</strong>
      <span>${esc(item.excerpt)}</span>
      <em>${esc(item.time)}</em>
    </button>`).join('') || '<div class="book-search-empty">还没有书签。阅读时点击右上角“添加书签”。</div>'
    $$('[data-bookmark]', list).forEach((button) => {
      button.addEventListener('click', () => jumpChapter(BOOKMARKS[Number(button.dataset.bookmark)].chapter))
    })
  }

  function runBookSearch(query) {
    const value = query.trim()
    const box = $('#bookSearchResults')
    if (!value) {
      box.innerHTML = '<div class="book-search-empty">输入关键词搜索本章或全书</div>'
      return
    }
    const hits = CHAPTER_TEXT.map((text, index) => ({ text, index })).filter((item) => item.text.includes(value))
    box.innerHTML = hits.length
      ? hits.map((hit) => `<button class="book-search-item" data-book-search="${hit.index}">
          <strong>第 ${hit.index + 1} 段</strong>${esc(hit.text)}
        </button>`).join('')
      : `<div class="book-search-empty">没有找到「${esc(value)}」</div>`
    $$('[data-book-search]', box).forEach((button) => {
      button.addEventListener('click', () => {
        closeDrawers()
        toast(`已定位到第 ${Number(button.dataset.bookSearch) + 1} 段`)
      })
    })
  }

  function setReaderDrawerTab(tab) {
    $$('#readerDrawerTabs button').forEach((button) => button.classList.toggle('active', button.dataset.drawerTab === tab))
    $$('#readerToc .drawer-pane').forEach((pane) => { pane.hidden = pane.dataset.pane !== tab })
    if (tab === 'bookmarks') renderBookmarks()
    if (tab === 'search') {
      setTimeout(() => $('#bookSearchInput')?.focus(), 60)
      runBookSearch($('#bookSearchInput')?.value || '')
    }
  }

  function toggleReaderMenu(force) {
    const menu = $('#readerMenu')
    const show = force !== undefined ? force : menu.hidden
    menu.hidden = !show
    if (show) showChrome(true)
  }

  /* ---------------- bindings ---------------- */

  function bind() {
    $$('.nav-item').forEach((item) => item.addEventListener('click', () => switchView(item.dataset.view)))
    $$('[data-view-jump]').forEach((item) => item.addEventListener('click', () => switchView(item.dataset.viewJump)))

    $('#collapseBtn').addEventListener('click', () => {
      $('#sidebar').classList.toggle('collapsed')
    })

    $('#cmdTrigger').addEventListener('click', openPalette)
    $('#themeToggle').addEventListener('click', cycleTheme)
    $('#taskBtn').addEventListener('click', openTaskCenter)

    $('#modalClose').addEventListener('click', closeModal)
    $('#modalOverlay').addEventListener('click', (event) => {
      if (event.target === $('#modalOverlay')) closeModal()
    })
    $('#panelClose').addEventListener('click', closePanel)
    $('#panelScrim').addEventListener('click', closePanel)
    document.addEventListener('click', (event) => {
      if (event.target.closest('[data-modal-close]')) closeModal()
    })

    $('#importBtn').addEventListener('click', () => switchView('local'))
    $('#newGroupBtn').addEventListener('click', () => toast('分组管理将在 Phase 2 接入'))

    $('#viewToggle').addEventListener('click', (event) => {
      const button = event.target.closest('button[data-mode]')
      if (!button) return
      state.shelfMode = button.dataset.mode
      $$('#viewToggle button').forEach((item) => item.classList.toggle('active', item === button))
      renderShelf()
    })

    $('#groupTabs').addEventListener('click', (event) => {
      const button = event.target.closest('button[data-group]')
      if (!button) return
      state.group = button.dataset.group
      $$('#groupTabs button').forEach((item) => item.classList.toggle('active', item === button))
      $('#crumbSub').textContent = state.group
      renderShelf()
    })

    $('#continueCard').addEventListener('click', () => openReader('changye'))

    $('#searchForm').addEventListener('submit', (event) => {
      event.preventDefault()
      runSearch($('#searchInput').value)
    })

    $$('#searchSuggest [data-kw]').forEach((button) => {
      button.addEventListener('click', () => runSearch(button.dataset.kw))
    })

    $('#sourceSearch').addEventListener('input', renderSourceList)

    $('#editorTabs').addEventListener('click', (event) => {
      const button = event.target.closest('button[data-tab]')
      if (!button) return
      state.editorTab = button.dataset.tab
      renderEditor()
    })

    $('#densityToggle').addEventListener('click', (event) => {
      const button = event.target.closest('button[data-density]')
      if (!button) return
      state.editorDensity = button.dataset.density
      $$('#densityToggle button').forEach((item) => item.classList.toggle('active', item === button))
      $('#editorBody').classList.toggle('dense', state.editorDensity === 'dense')
    })

    $('#editorEnabled').addEventListener('change', (event) => {
      const source = currentSource()
      source.enabled = event.target.checked
      source.status = source.enabled ? (source.latency > 700 ? 'warn' : 'ok') : 'off'
      renderSourceList()
      renderEditor()
      toast(`${source.name} 已${source.enabled ? '启用' : '停用'}`)
    })

    $('#saveSource').addEventListener('click', () => {
      toast(`已保存：${currentSource().name}`, 'success')
    })

    $('#loginSource').addEventListener('click', () => openLogin(currentSource().key))

    $('#runTest').addEventListener('click', runSourceTest)

    $$('#view-sources .head-actions .btn').forEach((button) => {
      if (button.id === 'sourceImport') {
        button.addEventListener('click', openImportSources)
        return
      }
      button.addEventListener('click', () => toast(`${button.textContent.trim()}将在 Phase 2 接入`))
    })

    $('#localPick').addEventListener('click', () => $('#localInput').click())
    $('#localPick2').addEventListener('click', () => $('#localInput').click())
    $('#scanLocal').addEventListener('click', () => toast('扫描完成：目录下新增 2 个文件', 'success'))
    $('#localTabs').addEventListener('click', (event) => {
      const button = event.target.closest('button[data-format]')
      if (!button) return
      state.localFilter = button.dataset.format
      $$('#localTabs button').forEach((item) => item.classList.toggle('active', item === button))
      renderLocal()
    })
    $('#localInput').addEventListener('change', (event) => {
      if (event.target.files?.length) addLocalFiles(event.target.files)
      event.target.value = ''
    })

    const dropzone = $('#dropzone')
    ;['dragenter', 'dragover'].forEach((type) => dropzone.addEventListener(type, (event) => {
      event.preventDefault()
      dropzone.classList.add('dragover')
    }))
    ;['dragleave', 'drop'].forEach((type) => dropzone.addEventListener(type, (event) => {
      event.preventDefault()
      dropzone.classList.remove('dragover')
    }))
    dropzone.addEventListener('drop', (event) => {
      if (event.dataTransfer?.files?.length) addLocalFiles(event.dataTransfer.files)
    })

    $('#cleanCache').addEventListener('click', () => toast('已清理 1.2 GB 缓存', 'success'))

    $$('.settings-panel .segmented').forEach((group) => {
      group.addEventListener('click', (event) => {
        const button = event.target.closest('button')
        if (!button) return
        $$('button', group).forEach((item) => item.classList.toggle('active', item === button))
        if (group.dataset.setting === 'pageMode') {
          state.reader.pageMode = button.dataset.value
          $('#reader').classList.toggle('mode-scroll', state.reader.pageMode === 'scroll')
          applyReaderVars()
          if (!$('#reader').hidden) requestAnimationFrame(layoutPages)
        }
      })
    })

    $$('.inline-range input').forEach((input) => {
      input.addEventListener('input', () => {
        const value = input.value
        const unit = input.parentElement.querySelector('b')
        if (!unit) return
        if (input.min === '16' && input.max === '30') unit.textContent = `${value} px`
        else if (input.max === '22') unit.textContent = (Number(value) / 10).toFixed(1)
        else unit.textContent = `${value}${input.dataset.suffix || ''}`
      })
    })

    const bindRange = (selector, update) => {
      const input = $(selector)
      if (!input) return
      input.addEventListener('input', () => {
        update(Number(input.value))
        applyReaderVars()
      })
    }
    bindRange('#setFontSize', (value) => { state.reader.fontSize = value })
    bindRange('#setLineHeight', (value) => { state.reader.lineHeight = value / 10 })
    bindRange('#setParaGap', (value) => { state.reader.paraGap = value / 10 })
    bindRange('#setPageWidth', (value) => { state.reader.pageWidth = value })

    $('#settingsNav').addEventListener('click', (event) => {
      const button = event.target.closest('button[data-panel]')
      if (button) activateSettingsPanel(button.dataset.panel)
    })

    $$('[data-toggle]').forEach((input) => {
      input.addEventListener('change', () => {
        toast(`${input.dataset.toggle}已${input.checked ? '开启' : '关闭'}`)
      })
    })

    $('#resetReading').addEventListener('click', () => {
      state.reader.fontSize = 19
      state.reader.lineHeight = 1.9
      state.reader.paraGap = 0.9
      state.reader.pageWidth = 720
      state.reader.pageMode = 'paged'
      $('#reader').classList.remove('mode-scroll')
      applyReaderVars()
      toast('阅读设置已恢复默认')
    })

    $('#openDataDir')?.addEventListener('click', () => toast('将在资源管理器中打开数据目录'))
    $('#backupNow')?.addEventListener('click', () => openBackup('local'))
    $('#restoreBackup')?.addEventListener('click', () => openBackup('file'))
    $('#resetShortcuts')?.addEventListener('click', () => toast('快捷键已恢复默认', 'success'))
    $('#checkUpdate')?.addEventListener('click', openUpdate)
    $('#aboutUpdate')?.addEventListener('click', openUpdate)
    $('#openProject')?.addEventListener('click', () => toast('项目主页将在正式版提供'))
    $$('.backup-row .btn').forEach((button) => {
      button.addEventListener('click', () => toast('恢复面板将在 Phase 2 接入'))
    })

    $$('[data-theme-picker]').forEach((picker) => {
      picker.addEventListener('click', (event) => {
        const chip = event.target.closest('.theme-chip')
        if (!chip) return
        state.reader.theme = chip.dataset.theme
        applyReaderVars()
        toast(`阅读主题：${chip.textContent.trim()}`)
      })
    })

    $('#readerBack').addEventListener('click', closeReader)
    $('#prevZone').addEventListener('click', () => goPage(-1))
    $('#nextZone').addEventListener('click', () => goPage(1))
    $('#tocBtn').addEventListener('click', () => {
      setReaderDrawerTab('toc')
      openDrawer('readerToc')
    })
    $('#readerSettingsBtn').addEventListener('click', () => openDrawer('readerSettings'))
    $('#ttsBtn').addEventListener('click', () => toggleTts())
    $('#ttsClose').addEventListener('click', () => toggleTts(false))
    $('#ttsPlay').addEventListener('click', () => {
      const playing = $('#ttsPlay').innerHTML.includes('i-pause')
      $('#ttsPlay').innerHTML = playing
        ? '<svg class="icon"><use href="#i-play"/></svg>'
        : '<svg class="icon"><use href="#i-pause"/></svg>'
      if (playing) stopTtsTimer()
      else startTtsTimer()
    })

    $('#readerMore').addEventListener('click', () => toggleReaderMenu())
    $$('#readerMenu [data-menu]').forEach((button) => {
      button.addEventListener('click', () => {
        const action = button.dataset.menu
        toggleReaderMenu(false)
        if (action === 'bookmark') {
          BOOKMARKS.unshift({
            chapter: state.reader.chapterIndex,
            title: chapterTitle(),
            excerpt: CHAPTER_TEXT[0],
            time: '刚刚',
          })
          renderBookmarks()
          toast('已添加书签', 'success')
        } else if (action === 'cache') {
          addCacheTask(currentBook())
        } else if (action === 'switch') {
          openSwitchSource(currentBook().id)
        } else if (action === 'search') {
          setReaderDrawerTab('search')
          openDrawer('readerToc')
        }
      })
    })

    $$('[data-close-drawer]').forEach((button) => button.addEventListener('click', closeDrawers))

    $('#readerDrawerTabs').addEventListener('click', (event) => {
      const button = event.target.closest('button[data-drawer-tab]')
      if (button) setReaderDrawerTab(button.dataset.drawerTab)
    })

    $('#bookSearchInput').addEventListener('input', (event) => runBookSearch(event.target.value))
    $('#bookSearchInput').addEventListener('keydown', (event) => {
      if (event.key === 'Enter') runBookSearch(event.target.value)
    })

    $('#tocSearch').addEventListener('input', (event) => filterToc(event.target.value))

    $('#fontFamily').addEventListener('change', (event) => {
      state.reader.font = event.target.value
      applyReaderVars()
      scheduleRelayout()
    })

    $('#fontSize').addEventListener('input', (event) => {
      state.reader.fontSize = Number(event.target.value)
      applyReaderVars()
      scheduleRelayout()
    })

    $('#lineHeight').addEventListener('input', (event) => {
      state.reader.lineHeight = Number(event.target.value) / 10
      applyReaderVars()
      scheduleRelayout()
    })

    $('#paraGap').addEventListener('input', (event) => {
      state.reader.paraGap = Number(event.target.value) / 10
      applyReaderVars()
      scheduleRelayout()
    })

    $('#pageWidth').addEventListener('input', (event) => {
      state.reader.pageWidth = Number(event.target.value)
      applyReaderVars()
      scheduleRelayout()
    })

    $('#pageMode').addEventListener('click', (event) => {
      const button = event.target.closest('button[data-mode]')
      if (!button) return
      state.reader.pageMode = button.dataset.mode
      $('#reader').classList.toggle('mode-scroll', state.reader.pageMode === 'scroll')
      state.reader.page = 0
      applyReaderVars()
      requestAnimationFrame(layoutPages)
    })

    $('#resetReader').addEventListener('click', () => {
      state.reader.fontSize = 19
      state.reader.lineHeight = 1.9
      state.reader.paraGap = 0.9
      state.reader.pageWidth = 720
      state.reader.font = 'serif'
      state.reader.theme = 'ink'
      applyReaderVars()
      layoutPages()
      toast('已恢复默认排版')
    })

    $('#cmdInput').addEventListener('input', (event) => {
      state.paletteIndex = 0
      renderPalette(event.target.value)
    })

    $('#cmdInput').addEventListener('keydown', (event) => {
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        state.paletteIndex = clamp(state.paletteIndex + 1, 0, state.paletteItems.length - 1)
        renderPalette($('#cmdInput').value)
      } else if (event.key === 'ArrowUp') {
        event.preventDefault()
        state.paletteIndex = clamp(state.paletteIndex - 1, 0, state.paletteItems.length - 1)
        renderPalette($('#cmdInput').value)
      } else if (event.key === 'Enter') {
        event.preventDefault()
        const item = state.paletteItems[state.paletteIndex]
        if (item) executeCommand(item.id)
      } else if (event.key === 'Escape') {
        closePalette()
      }
    })

    $('#cmdPalette').addEventListener('click', (event) => {
      if (event.target === $('#cmdPalette')) closePalette()
    })

    $('#readerViewport').addEventListener('mouseup', (event) => {
      setTimeout(() => {
        const selection = window.getSelection()
        const text = selection?.toString().trim()
        if (!text) {
          hideSelection()
          return
        }
        const range = selection.getRangeAt(0).getBoundingClientRect()
        const toolbar = $('#selectionToolbar')
        toolbar.hidden = false
        toolbar.style.left = `${clamp(range.left + range.width / 2 - 60, 16, window.innerWidth - 160)}px`
        toolbar.style.top = `${Math.max(70, range.top - 44)}px`
        showChrome(true)
      }, 10)
    })

    $('#readerViewport').addEventListener('mousedown', hideSelection)

    $$('#selectionToolbar button').forEach((button) => {
      button.addEventListener('click', async () => {
        const text = window.getSelection()?.toString().trim() || ''
        const action = button.dataset.sel
        hideSelection()
        if (action === 'copy') {
          try {
            await navigator.clipboard.writeText(text)
            toast('已复制', 'success')
          } catch {
            toast('复制失败：浏览器未授权剪贴板')
          }
        } else if (action === 'search') {
          closeReader()
          switchView('search')
          $('#searchInput').value = text.slice(0, 12)
          runSearch(text.slice(0, 12))
        } else {
          toast('已添加书签', 'success')
        }
      })
    })

    document.addEventListener('mousemove', () => {
      if (!$('#reader').hidden) showChrome()
    })

    $('#reader').addEventListener('mousedown', (event) => {
      if (event.target === $('#readerStage')) closeDrawers()
    })

    document.addEventListener('click', (event) => {
      if (!$('#readerMenu').hidden && !event.target.closest('#readerMenu') && !event.target.closest('#readerMore')) {
        toggleReaderMenu(false)
      }
    })

    document.addEventListener('keydown', (event) => {
      const typing = ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName)

      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        if ($('#cmdPalette').hidden) openPalette()
        else closePalette()
        return
      }

      if (event.key === 'Escape') {
        if (!$('#modalOverlay').hidden) closeModal()
        else if (!$('#sidePanel').hidden) closePanel()
        else if (!$('#cmdPalette').hidden) closePalette()
        else if (!$('#readerMenu').hidden) toggleReaderMenu(false)
        else if (anyDrawerOpen()) closeDrawers()
        else hideSelection()
        return
      }

      if (typing) return
      if ($('#reader').hidden) return

      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 't') {
        event.preventDefault()
        setReaderDrawerTab('toc')
        openDrawer('readerToc')
        return
      }

      if (event.key === 'ArrowLeft') {
        event.preventDefault()
        goPage(-1)
      } else if (event.key === 'ArrowRight' || event.key === ' ') {
        event.preventDefault()
        goPage(1)
      }
    })

    window.addEventListener('resize', () => {
      if (!$('#reader').hidden) scheduleRelayout()
    })
  }

  /* ---------------- init ---------------- */

  renderShelf()
  renderLocal()
  renderSourceList()
  renderEditor()
  resetConsole()
  renderBookmarks()
  applyReaderVars()
  bind()
  updateTaskBadge()

  const params = new URLSearchParams(location.search)
  const initialView = params.get('view')
  if (initialView && VIEW_TITLES[initialView]) {
    switchView(initialView)
  } else {
    switchView('shelf')
  }
  if (initialView === 'settings' && params.get('settingsPanel')) {
    activateSettingsPanel(params.get('settingsPanel'))
  }
  if (initialView === 'local' && params.get('format')) {
    state.localFilter = params.get('format')
    $$('#localTabs button').forEach((button) => {
      button.classList.toggle('active', button.dataset.format === state.localFilter)
    })
    renderLocal()
  }
  if (params.get('density') === 'dense') {
    state.editorDensity = 'dense'
    $$('#densityToggle button').forEach((button) => {
      button.classList.toggle('active', button.dataset.density === 'dense')
    })
    $('#editorBody').classList.add('dense')
  }
  if (params.get('editorTab')) {
    state.editorTab = params.get('editorTab')
    $$('#editorTabs button').forEach((button) => {
      button.classList.toggle('active', button.dataset.tab === state.editorTab)
    })
    renderEditor()
    if (state.editorDensity === 'dense') $('#editorBody').classList.add('dense')
  }
  if (params.get('q')) {
    switchView('search')
    runSearch(params.get('q'))
  }
  if (params.get('reader') === '1') {
    const themeParam = params.get('theme')
    if (THEMES.includes(themeParam)) state.reader.theme = themeParam
    const modeParam = params.get('mode')
    if (modeParam === 'scroll' || modeParam === 'paged') state.reader.pageMode = modeParam
    openReader(params.get('book') || 'changye')
    if (params.get('panel') === 'settings') openDrawer('readerSettings')
    if (params.get('panel') === 'toc') openDrawer('readerToc')
    if (params.get('drawer')) {
      setReaderDrawerTab(params.get('drawer'))
      openDrawer('readerToc')
    }
    if (params.get('tts') === '1') toggleTts(true)
    const pageParam = Number(params.get('page') || '1')
    if (pageParam > 1) {
      requestAnimationFrame(() => {
        state.reader.page = clamp(pageParam - 1, 0, state.reader.pages - 1)
        applyPageTransform()
        updatePageIndicator()
      })
    }
  }

  const modalParam = params.get('modal')
  if (modalParam === 'detail') openBookDetail(params.get('book') || 'changye')
  if (modalParam === 'switch') openSwitchSource(params.get('book') || 'changye')
  if (modalParam === 'import') {
    openImportSources()
    if (params.get('tab')) {
      importState.tab = params.get('tab')
      renderImportWizard()
    }
  }
  if (modalParam === 'backup') openBackup(params.get('tab') || 'local')
  if (modalParam === 'restore') showRestorePreview(BACKUPS[Number(params.get('index') || 0)])
  if (modalParam === 'cache') openCacheManager()
  if (modalParam === 'login') openLogin(params.get('source') || SOURCES[0].key)
  if (modalParam === 'about') openAbout()
  if (modalParam === 'update') openUpdate()
  if (modalParam === 'welcome') openWelcome()
  if (params.get('tasks') === '1') openTaskCenter()
})()
