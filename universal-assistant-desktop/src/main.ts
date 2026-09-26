import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import { pinia } from './app/pinia'
import { useAppearanceStore } from './features/appearance/stores/appearanceStore'

useAppearanceStore(pinia).initializeTheme()
createApp(App).use(pinia).mount('#app')
