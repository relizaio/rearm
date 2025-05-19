import { createApp } from 'vue'
import App from './App.vue'
import VueFeather from 'vue-feather'

createApp(App)
  .component(VueFeather.name, VueFeather)
  .mount('#app')