import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import '@zhicai/shared/prototype.css'
import '@zhicai/shared/app.css'
createApp(App).use(router).mount('#app')
