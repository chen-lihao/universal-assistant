import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BrowserWindow } from 'electron'
import { createWindowMotion } from './windowMotion'

describe('windowMotion', () => {
  afterEach(() => vi.useRealTimers())

  it('moves through a multi-point path without restarting easing at each point', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    const bounds = { x: 0, y: 0, width: 100, height: 100 }
    const window = {
      id: 1,
      getBounds: () => ({ ...bounds }),
      setPosition: (x: number, y: number) => {
        bounds.x = x
        bounds.y = y
      },
      isDestroyed: () => false,
    } as unknown as BrowserWindow
    const onComplete = vi.fn()

    createWindowMotion({}).animateWindowPath(
      window,
      [
        { x: 100, y: 0, duration: 100 },
        { x: 200, y: 0, duration: 100 },
      ],
      onComplete,
    )

    vi.advanceTimersByTime(112)
    expect(bounds.x).toBeGreaterThan(100)
    vi.advanceTimersByTime(160)
    expect(bounds.x).toBe(200)
    expect(onComplete).toHaveBeenCalledOnce()
  })
})
