import { ref } from 'vue'
import { defineStore } from 'pinia'

export type AppearanceTheme = 'light' | 'dark'

const STORAGE_KEY = 'universal-assistant-theme'

function preferredTheme(): AppearanceTheme {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY)
    if (saved === 'light' || saved === 'dark') return saved
  } catch {
    // Keep the system preference if storage is unavailable.
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export const useAppearanceStore = defineStore('appearance', () => {
  const theme = ref<AppearanceTheme>(preferredTheme())

  function applyTheme() {
    document.documentElement.dataset.theme = theme.value
    document.documentElement.style.colorScheme = theme.value
  }

  function setTheme(nextTheme: AppearanceTheme) {
    theme.value = nextTheme
    applyTheme()
    try {
      window.localStorage.setItem(STORAGE_KEY, nextTheme)
    } catch {
      // The current window still uses the selected theme.
    }
  }

  function toggleTheme() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  function initializeTheme() {
    applyTheme()
    window.addEventListener('storage', (event) => {
      if (event.key === STORAGE_KEY && (event.newValue === 'light' || event.newValue === 'dark')) {
        theme.value = event.newValue
        applyTheme()
      }
    })
  }

  return { theme, initializeTheme, setTheme, toggleTheme }
})
