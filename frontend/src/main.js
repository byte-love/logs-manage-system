import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import './styles/theme.css'
import App from './App.vue'
import router from './router'
import { installAuthInterceptor } from './api/http'

installAuthInterceptor(router)

// Element Plus 默认英文；配 zh-cn 让日期选择器/分页等控件显示中文
createApp(App)
  .use(ElementPlus, { locale: zhCn })
  .use(router)
  .mount('#app')
