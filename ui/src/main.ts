import { createApp } from 'vue'
import App from './App.vue'
import Router from './router'
import Store from './store'

createApp(App)
    .use(Router.Router)
    .use(Store.store)
    .mount('#app')