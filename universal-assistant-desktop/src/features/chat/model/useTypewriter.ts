import { onUnmounted } from 'vue'

type TypewriterOptions<T> = {
  appendText: (target: T, text: string) => void
  onTick?: () => void
  onDone: (target: T) => void
}

export function useTypewriter<T>(options: TypewriterOptions<T>) {
  let typewriterTimer: number | undefined
  let typewriterQueue = ''
  let typewriterTarget: T | undefined
  let pendingDoneTarget: T | undefined

  function clearTimer() {
    window.clearInterval(typewriterTimer)
    typewriterTimer = undefined
  }

  function startTypewriter() {
    if (typewriterTimer !== undefined) {
      return
    }

    typewriterTimer = window.setInterval(() => {
      if (!typewriterTarget || !typewriterQueue) {
        clearTimer()
        if (pendingDoneTarget) {
          const target = pendingDoneTarget
          pendingDoneTarget = undefined
          options.onDone(target)
        }
        return
      }

      const chunkSize = typewriterQueue.length > 80 ? 3 : typewriterQueue.length > 24 ? 2 : 1
      options.appendText(typewriterTarget, typewriterQueue.slice(0, chunkSize))
      typewriterQueue = typewriterQueue.slice(chunkSize)
      options.onTick?.()
    }, 28)
  }

  function enqueue(target: T, content: string) {
    if (!content) {
      return
    }

    typewriterTarget = target
    typewriterQueue += content
    startTypewriter()
  }

  function markDone(target: T) {
    if (typewriterTarget === target && typewriterQueue) {
      pendingDoneTarget = target
      return
    }

    options.onDone(target)
  }

  function flush(target: T) {
    if (typewriterTarget !== target) {
      return
    }

    options.appendText(target, typewriterQueue)
    typewriterQueue = ''
    clearTimer()
    typewriterTarget = undefined
    if (pendingDoneTarget === target) {
      pendingDoneTarget = undefined
    }
  }

  function clearFor(target: T) {
    if (typewriterTarget !== target && pendingDoneTarget !== target) {
      return
    }

    typewriterQueue = ''
    clearTimer()
    if (typewriterTarget === target) {
      typewriterTarget = undefined
    }
    if (pendingDoneTarget === target) {
      pendingDoneTarget = undefined
    }
  }

  onUnmounted(clearTimer)

  return {
    enqueue,
    markDone,
    flush,
    clearFor,
  }
}
