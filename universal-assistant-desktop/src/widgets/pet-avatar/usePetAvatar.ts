import { onMounted, onUnmounted, ref } from 'vue'

export function usePetAvatar() {
  const petState = ref<PetState>('idle')
  const petAction = ref<PetAction | null>(null)
  const isClicking = ref(false)
  const isDragging = ref(false)
  const isHovering = ref(false)

  let removePetStateListener: (() => void) | undefined
  let removePetActionListener: (() => void) | undefined
  let clickTimer: number | undefined
  let hoverTimer: number | undefined
  let idleTimer: number | undefined
  let transientStateTimer: number | undefined
  let actionTimer: number | undefined
  let menuReleaseTimer: number | undefined
  let dragFrame: number | undefined
  let dragPointerId: number | undefined
  let dragStartX = 0
  let dragStartY = 0
  let dragLastX = 0
  let dragLastY = 0
  let pendingDeltaX = 0
  let pendingDeltaY = 0
  let dragMoved = false

  function canUseInteractiveState() {
    return (
      petAction.value === null &&
      petState.value !== 'thinking' &&
      petState.value !== 'speaking' &&
      petState.value !== 'error'
    )
  }

  function setPetState(state: PetState) {
    void window.assistant?.setPetState(state)
  }

  function clearAction() {
    window.clearTimeout(actionTimer)
    petAction.value = null
  }

  function finishAction(duration = 1200) {
    window.clearTimeout(actionTimer)
    actionTimer = window.setTimeout(() => {
      petAction.value = null
      if (!isHovering.value && !isDragging.value && canUseInteractiveState()) {
        setPetState('idle')
      } else if (isHovering.value && canUseInteractiveState()) {
        setPetState('curious')
      }
      scheduleIdleMood()
    }, duration)
  }

  function runPetAction(action: PetAction) {
    clearAction()

    if (action === 'idle') {
      setPetState('idle')
      scheduleIdleMood()
      return
    }

    petAction.value = action

    if (action === 'wave') {
      setPetState('happy')
      finishAction(1400)
      return
    }

    if (action === 'jump') {
      setPetState('happy')
      finishAction(1250)
      return
    }

    if (action === 'fireworks') {
      setPetState('happy')
      finishAction(2400)
      return
    }

    if (action === 'run') {
      setPetState('running')
      finishAction(1650)
      return
    }

    if (action === 'sleep') {
      setPetState('sleepy')
      finishAction(5200)
    }
  }

  function scheduleIdleMood(delay = 35000) {
    window.clearTimeout(idleTimer)
    idleTimer = window.setTimeout(() => {
      if (!isHovering.value && !isDragging.value && canUseInteractiveState()) {
        setPetState('sleepy')
      }
    }, delay)
  }

  function setTemporaryPetState(state: PetState, duration = 900) {
    if (!canUseInteractiveState() && state !== 'error') {
      return
    }

    window.clearTimeout(transientStateTimer)
    setPetState(state)
    transientStateTimer = window.setTimeout(() => {
      if (!isDragging.value && canUseInteractiveState()) {
        setPetState(isHovering.value ? 'curious' : 'idle')
      }
      scheduleIdleMood()
    }, duration)
  }

  async function toggleChat() {
    isClicking.value = true
    window.clearTimeout(clickTimer)
    clickTimer = window.setTimeout(() => {
      isClicking.value = false
    }, 360)

    await window.assistant?.showChat()
  }

  function setPetPointerActive(active: boolean) {
    void window.assistant?.setPetPointerActive(active)
  }

  function handlePointerEnter() {
    isHovering.value = true
    setPetPointerActive(true)
    window.clearTimeout(idleTimer)
    window.clearTimeout(hoverTimer)
    hoverTimer = window.setTimeout(() => {
      if (isHovering.value && !isDragging.value && canUseInteractiveState()) {
        setPetState('curious')
      }
    }, 700)
  }

  function openPetMenu(event: MouseEvent) {
    event.preventDefault()
    setPetPointerActive(true)
    void window.assistant?.showPetMenu()
    window.clearTimeout(menuReleaseTimer)
    menuReleaseTimer = window.setTimeout(() => {
      if (dragPointerId === undefined) {
        setPetPointerActive(false)
      }
    }, 1600)
  }

  function handlePointerLeave() {
    isHovering.value = false
    window.clearTimeout(hoverTimer)
    if (!isDragging.value && canUseInteractiveState()) {
      setPetState('idle')
    }
    scheduleIdleMood()
    if (dragPointerId === undefined) {
      setPetPointerActive(false)
    }
  }

  function flushPetMove() {
    dragFrame = undefined
    if (!pendingDeltaX && !pendingDeltaY) {
      return
    }

    const deltaX = pendingDeltaX
    const deltaY = pendingDeltaY
    pendingDeltaX = 0
    pendingDeltaY = 0
    void window.assistant?.movePetBy({ deltaX, deltaY })
  }

  function schedulePetMove(deltaX: number, deltaY: number) {
    pendingDeltaX += deltaX
    pendingDeltaY += deltaY

    if (dragFrame === undefined) {
      dragFrame = window.requestAnimationFrame(flushPetMove)
    }
  }

  function startDrag(event: PointerEvent) {
    if (event.button !== 0) {
      return
    }

    dragPointerId = event.pointerId
    dragStartX = event.screenX
    dragStartY = event.screenY
    dragLastX = event.screenX
    dragLastY = event.screenY
    dragMoved = false
    isDragging.value = false
    window.clearTimeout(idleTimer)
    window.clearTimeout(hoverTimer)
    setPetPointerActive(true)
    const target = event.currentTarget as HTMLElement
    target.setPointerCapture(event.pointerId)
  }

  function moveDrag(event: PointerEvent) {
    if (dragPointerId !== event.pointerId) {
      return
    }

    const totalDistance = Math.hypot(event.screenX - dragStartX, event.screenY - dragStartY)
    if (totalDistance >= 5) {
      dragMoved = true
      isDragging.value = true
      if (canUseInteractiveState()) {
        setPetState('running')
      }
    }

    if (!dragMoved) {
      return
    }

    schedulePetMove(event.screenX - dragLastX, event.screenY - dragLastY)
    dragLastX = event.screenX
    dragLastY = event.screenY
  }

  function finishDrag(event: PointerEvent) {
    if (dragPointerId !== event.pointerId) {
      return
    }

    if (dragFrame !== undefined) {
      window.cancelAnimationFrame(dragFrame)
      flushPetMove()
    }

    const target = event.currentTarget as HTMLElement
    if (target.hasPointerCapture(event.pointerId)) {
      target.releasePointerCapture(event.pointerId)
    }
    dragPointerId = undefined

    if (dragMoved) {
      window.setTimeout(() => {
        isDragging.value = false
        if (!isHovering.value) {
          setPetPointerActive(false)
        }
        setTemporaryPetState('happy', 760)
      }, 80)
      return
    }

    setTemporaryPetState('happy', 720)
    window.setTimeout(() => {
      void toggleChat()
    }, 240)
    if (!isHovering.value) {
      setPetPointerActive(false)
    }
  }

  function cancelDrag() {
    if (dragFrame !== undefined) {
      window.cancelAnimationFrame(dragFrame)
      flushPetMove()
    }

    dragPointerId = undefined
    dragMoved = false
    isDragging.value = false
    window.clearTimeout(hoverTimer)
    if (!isHovering.value) {
      setPetPointerActive(false)
    }
    scheduleIdleMood()
  }

  onMounted(() => {
    removePetStateListener = window.assistant?.onPetState((state) => {
      petState.value = state
    })
    removePetActionListener = window.assistant?.onPetAction((action) => {
      runPetAction(action)
    })
    scheduleIdleMood()
  })

  onUnmounted(() => {
    removePetStateListener?.()
    removePetActionListener?.()
    window.clearTimeout(clickTimer)
    window.clearTimeout(hoverTimer)
    window.clearTimeout(idleTimer)
    window.clearTimeout(transientStateTimer)
    window.clearTimeout(actionTimer)
    window.clearTimeout(menuReleaseTimer)
    if (dragFrame !== undefined) {
      window.cancelAnimationFrame(dragFrame)
    }
  })

  return {
    petState,
    petAction,
    isClicking,
    isDragging,
    startDrag,
    moveDrag,
    finishDrag,
    cancelDrag,
    handlePointerEnter,
    handlePointerLeave,
    openPetMenu,
  }
}
