import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAppearanceStore } from './appearanceStore'

describe('appearanceStore', () => {
  let values: Map<string, string>
  let storageListener: ((event: StorageEvent) => void) | undefined
  let root: { dataset: Record<string, string>; style: { colorScheme: string } }

  beforeEach(() => {
    values = new Map()
    storageListener = undefined
    root = { dataset: {}, style: { colorScheme: '' } }
    vi.stubGlobal('document', { documentElement: root })
    vi.stubGlobal('window', {
      localStorage: {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
      },
      matchMedia: () => ({ matches: false }),
      addEventListener: (name: string, listener: (event: StorageEvent) => void) => {
        if (name === 'storage') storageListener = listener
      },
    })
    setActivePinia(createPinia())
  })

  afterEach(() => vi.unstubAllGlobals())

  it('restores and persists the selected theme', () => {
    values.set('universal-assistant-theme', 'dark')
    const store = useAppearanceStore()
    store.initializeTheme()

    expect(root.dataset.theme).toBe('dark')
    store.toggleTheme()
    expect(root.dataset.theme).toBe('light')
    expect(values.get('universal-assistant-theme')).toBe('light')
  })

  it('follows theme changes made in another window', () => {
    const store = useAppearanceStore()
    store.initializeTheme()
    storageListener?.({ key: 'universal-assistant-theme', newValue: 'dark' } as StorageEvent)

    expect(store.theme).toBe('dark')
    expect(root.style.colorScheme).toBe('dark')
  })
})
