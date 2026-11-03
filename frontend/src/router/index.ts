import { createRouter, createWebHistory } from 'vue-router'
import BookshelfView from '../views/BookshelfView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'shelf',
      component: BookshelfView,
      meta: { title: '书架' },
    },
    {
      path: '/search',
      name: 'search',
      component: () => import('../views/SearchView.vue'),
      meta: { title: '搜索' },
    },
    {
      path: '/explore',
      name: 'explore',
      component: () => import('../views/ExploreView.vue'),
      meta: { title: '发现' },
    },
    {
      path: '/sources',
      name: 'sources',
      component: () => import('../views/SourcesView.vue'),
      meta: { title: '书源' },
    },
    {
      path: '/sources/edit',
      name: 'source-editor',
      component: () => import('../views/SourceEditorView.vue'),
      meta: { title: '编辑书源' },
    },
    {
      path: '/replace',
      name: 'replace',
      component: () => import('../views/ReplaceView.vue'),
      meta: { title: '替换净化' },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('../views/SettingsView.vue'),
      meta: { title: '设置' },
    },
    {
      path: '/read',
      name: 'reader',
      component: () => import('../views/ReaderView.vue'),
      meta: { title: '阅读', immersive: true },
    },
    {
      path: '/float',
      name: 'float-reader',
      component: () => import('../views/FloatReaderView.vue'),
      meta: { title: '浮窗阅读', immersive: true, floatWindow: true },
    },
    {
      path: '/comic',
      name: 'comic',
      component: () => import('../views/ComicReaderView.vue'),
      meta: { title: '漫画', immersive: true },
    },
    {
      path: '/video',
      name: 'video',
      component: () => import('../views/VideoPlayerView.vue'),
      meta: { title: '视频详情' },
    },
  ],
})

export default router
