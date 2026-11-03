import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { applyAppTheme, loadAppTheme } from './api/appTheme'
import './styles.css'
import './reader.css'
import './float.css'
import './dialogs.css'
import './manage.css'

// 浮窗从首帧起就应是透明背景，并且不能改动主窗口的原生标题栏主题
if (window.location.pathname.startsWith('/float')) {
  document.documentElement.setAttribute('data-float-window', 'true')
}

applyAppTheme(loadAppTheme(), false)

createApp(App)
  .use(createPinia())
  .use(router)
  .mount('#app')
