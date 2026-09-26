import type { BrowserWindow } from 'electron'

export type WindowPoint = { x: number; y: number }
export type WindowBounds = WindowPoint & { width: number; height: number }

export function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max)
}

export function randomBetween(min: number, max: number) {
  return min + Math.random() * (max - min)
}

function easeOutCubic(progress: number) {
  return 1 - Math.pow(1 - progress, 3)
}

export function createWindowMotion(options: { beforeWindowChange?: (window: BrowserWindow) => void }) {
  const windowAnimationTimers = new Map<number, ReturnType<typeof setTimeout>>()
  const windowOpacityTimers = new Map<number, ReturnType<typeof setTimeout>>()

  function cancelWindowAnimation(window: BrowserWindow) {
    const timer = windowAnimationTimers.get(window.id)
    if (timer) {
      clearTimeout(timer)
      windowAnimationTimers.delete(window.id)
    }
  }

  function cancelWindowOpacityAnimation(window: BrowserWindow) {
    const timer = windowOpacityTimers.get(window.id)
    if (timer) {
      clearTimeout(timer)
      windowOpacityTimers.delete(window.id)
    }
  }

  function setWindowPosition(window: BrowserWindow, x: number, y: number) {
    options.beforeWindowChange?.(window)
    window.setPosition(Math.round(x), Math.round(y))
  }

  function setWindowBounds(window: BrowserWindow, bounds: WindowBounds) {
    options.beforeWindowChange?.(window)
    window.setBounds({
      x: Math.round(bounds.x),
      y: Math.round(bounds.y),
      width: Math.round(bounds.width),
      height: Math.round(bounds.height),
    })
  }

  function animateWindowTo(
    window: BrowserWindow,
    targetX: number,
    targetY: number,
    duration = 180,
    onComplete?: () => void,
  ) {
    cancelWindowAnimation(window)

    if (window.isDestroyed()) {
      return
    }

    const start = window.getBounds()
    const distance = Math.hypot(targetX - start.x, targetY - start.y)
    if (distance < 4) {
      setWindowPosition(window, targetX, targetY)
      onComplete?.()
      return
    }

    const startedAt = Date.now()
    const tick = () => {
      if (window.isDestroyed()) {
        windowAnimationTimers.delete(window.id)
        return
      }

      const elapsed = Date.now() - startedAt
      const progress = clamp(elapsed / duration, 0, 1)
      const eased = easeOutCubic(progress)
      const nextX = start.x + (targetX - start.x) * eased
      const nextY = start.y + (targetY - start.y) * eased

      setWindowPosition(window, nextX, nextY)

      if (progress >= 1) {
        windowAnimationTimers.delete(window.id)
        onComplete?.()
        return
      }

      windowAnimationTimers.set(window.id, setTimeout(tick, 16))
    }

    tick()
  }

  function animateWindowBounds(window: BrowserWindow, target: WindowBounds, duration = 180, onComplete?: () => void) {
    cancelWindowAnimation(window)

    if (window.isDestroyed()) {
      return
    }

    const start = window.getBounds()
    const startedAt = Date.now()
    const tick = () => {
      if (window.isDestroyed()) {
        windowAnimationTimers.delete(window.id)
        return
      }

      const progress = clamp((Date.now() - startedAt) / duration, 0, 1)
      const eased = easeOutCubic(progress)
      setWindowBounds(window, {
        x: start.x + (target.x - start.x) * eased,
        y: start.y + (target.y - start.y) * eased,
        width: start.width + (target.width - start.width) * eased,
        height: start.height + (target.height - start.height) * eased,
      })

      if (progress >= 1) {
        windowAnimationTimers.delete(window.id)
        onComplete?.()
        return
      }

      windowAnimationTimers.set(window.id, setTimeout(tick, 16))
    }

    tick()
  }

  function animateWindowPath(
    window: BrowserWindow,
    points: Array<{ x: number; y: number; duration: number }>,
    onComplete?: () => void,
  ) {
    cancelWindowAnimation(window)

    if (window.isDestroyed() || points.length === 0) {
      onComplete?.()
      return
    }

    const start = window.getBounds()
    const path = [{ x: start.x, y: start.y }, ...points]
    const totalDuration = points.reduce((sum, point) => sum + point.duration, 0)
    const startedAt = Date.now()

    const tick = () => {
      if (window.isDestroyed()) {
        windowAnimationTimers.delete(window.id)
        return
      }

      const progress = clamp((Date.now() - startedAt) / totalDuration, 0, 1)
      const elapsed = ((1 - Math.cos(Math.PI * progress)) / 2) * totalDuration
      let segmentStart = 0
      let index = 0
      while (index < points.length - 1 && elapsed > segmentStart + points[index].duration) {
        segmentStart += points[index].duration
        index += 1
      }

      const point = points[index]
      const previous = path[index]
      const segmentProgress = clamp((elapsed - segmentStart) / point.duration, 0, 1)
      setWindowPosition(
        window,
        previous.x + (point.x - previous.x) * segmentProgress,
        previous.y + (point.y - previous.y) * segmentProgress,
      )

      if (progress >= 1) {
        windowAnimationTimers.delete(window.id)
        onComplete?.()
        return
      }

      windowAnimationTimers.set(window.id, setTimeout(tick, 16))
    }

    tick()
  }

  function animateWindowOpacity(window: BrowserWindow, targetOpacity: number, duration = 180, onComplete?: () => void) {
    cancelWindowOpacityAnimation(window)

    if (window.isDestroyed()) {
      return
    }

    const startOpacity = window.getOpacity()
    const startedAt = Date.now()
    const tick = () => {
      if (window.isDestroyed()) {
        windowOpacityTimers.delete(window.id)
        return
      }

      const progress = clamp((Date.now() - startedAt) / duration, 0, 1)
      const eased = easeOutCubic(progress)
      const nextOpacity = startOpacity + (targetOpacity - startOpacity) * eased
      window.setOpacity(nextOpacity)

      if (progress >= 1) {
        windowOpacityTimers.delete(window.id)
        onComplete?.()
        return
      }

      windowOpacityTimers.set(window.id, setTimeout(tick, 16))
    }

    tick()
  }

  function moveWindowTo(window: BrowserWindow, x: number, y: number, animated = false) {
    if (animated) {
      animateWindowTo(window, x, y)
      return
    }

    cancelWindowAnimation(window)
    setWindowPosition(window, x, y)
  }

  return {
    animateWindowBounds,
    animateWindowOpacity,
    animateWindowPath,
    animateWindowTo,
    cancelWindowAnimation,
    cancelWindowOpacityAnimation,
    moveWindowTo,
    setWindowBounds,
    setWindowPosition,
  }
}
