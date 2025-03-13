import { createApp } from 'vue'
import App from './App.vue'
import VueFeather from 'vue-feather'
import Router from './router'
import Store from './store'

createApp(App)
    .use(Router.Router)
    .use(Store.store)
    .component(VueFeather.name, VueFeather)
    .mount('#app')