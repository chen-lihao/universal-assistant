<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import mascot from '../assets/assistant-mascot.png'

const petState = ref<PetState>('idle')
const isClicking = ref(false)
const isDragging = ref(false)
const isHovering = ref(false)
let removePetStateListener: (() => void) | undefined
let clickTimer: number | undefined
let hoverTimer: number | undefined
let idleTimer: number | undefined
let transientStateTimer: number | undefined
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
  return petState.value !== 'thinking' && petState.value !== 'speaking' && petState.value !== 'error'
}

function setPetState(state: PetState) {
  void window.assistant?.setPetState(state)
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

  await window.assistant?.toggleChat()
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
  scheduleIdleMood()
})

onUnmounted(() => {
  removePetStateListener?.()
  window.clearTimeout(clickTimer)
  window.clearTimeout(hoverTimer)
  window.clearTimeout(idleTimer)
  window.clearTimeout(transientStateTimer)
  if (dragFrame !== undefined) {
    window.cancelAnimationFrame(dragFrame)
  }
})
</script>

<template>
  <main class="pet-shell" :class="`state-${petState}`">
    <button
      class="pet-button"
      :class="{ 'is-clicking': isClicking, 'is-dragging': isDragging }"
      type="button"
      aria-label="Open assistant chat"
      @pointerdown="startDrag"
      @pointermove="moveDrag"
      @pointerup="finishDrag"
      @pointercancel="cancelDrag"
      @pointerenter="handlePointerEnter"
      @pointerleave="handlePointerLeave"
    >
      <span class="pet-aura" />
      <img class="pet-image" :src="mascot" alt="Universal Assistant mascot" />
      <span class="pet-signal pet-signal-one" />
      <span class="pet-signal pet-signal-two" />
      <span class="pet-talk" />
      <span class="pet-emotion pet-emotion-happy">★</span>
      <span class="pet-emotion pet-emotion-curious">?</span>
      <span class="pet-emotion pet-emotion-sleepy">Zz</span>
      <span class="pet-emotion pet-emotion-error">!</span>
      <span class="pet-status" />
    </button>
  </main>
</template>

<style scoped>
.pet-shell {
  width: 100vw;
  height: 100vh;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: transparent;
  user-select: none;
}

.pet-button {
  position: relative;
  width: 150px;
  height: 184px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: grab;
  -webkit-app-region: no-drag;
  animation: pet-float 3.4s ease-in-out infinite;
  transform-origin: 50% 82%;
  outline: none;
  filter: drop-shadow(0 10px 14px rgba(12, 24, 22, 0.18));
}

.pet-button.is-dragging {
  cursor: grabbing;
  animation: none;
  transform: translateY(-2px) scale(1.02);
}

.pet-button:focus-visible {
  outline: none;
}

.pet-button:focus-visible .pet-aura {
  opacity: 1;
  box-shadow: 0 0 0 3px rgba(18, 131, 121, 0.35);
}

.pet-button.is-clicking {
  animation: pet-click 360ms cubic-bezier(0.34, 1.56, 0.64, 1);
}

.state-thinking .pet-button {
  animation: pet-thinking 1.5s ease-in-out infinite;
}

.state-speaking .pet-button {
  animation: pet-speaking 760ms ease-in-out infinite;
}

.state-happy .pet-button {
  animation: pet-happy 900ms cubic-bezier(0.34, 1.56, 0.64, 1) infinite;
}

.state-curious .pet-button {
  animation: pet-curious 1.8s ease-in-out infinite;
}

.state-sleepy .pet-button {
  animation: pet-sleepy 3.6s ease-in-out infinite;
  filter: drop-shadow(0 7px 10px rgba(12, 24, 22, 0.14));
}

.state-running .pet-button {
  animation: pet-running 360ms ease-in-out infinite;
}

.state-error .pet-button {
  animation: pet-error 460ms ease-in-out infinite;
}

.pet-aura {
  position: absolute;
  left: 18px;
  right: 18px;
  bottom: 8px;
  height: 24px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(18, 131, 121, 0.26), rgba(18, 131, 121, 0));
  filter: blur(2px);
  animation: aura-pulse 3.4s ease-in-out infinite;
}

.state-happy .pet-aura {
  background: radial-gradient(circle, rgba(255, 200, 87, 0.46), rgba(255, 200, 87, 0));
  animation-duration: 900ms;
}

.state-curious .pet-aura {
  background: radial-gradient(circle, rgba(96, 165, 250, 0.36), rgba(96, 165, 250, 0));
}

.state-running .pet-aura {
  height: 18px;
  background: radial-gradient(circle, rgba(38, 219, 205, 0.48), rgba(38, 219, 205, 0));
  animation: run-aura 360ms ease-in-out infinite;
}

.state-error .pet-aura {
  background: radial-gradient(circle, rgba(255, 90, 95, 0.42), rgba(255, 90, 95, 0));
  animation-duration: 420ms;
}

.pet-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
  transform-origin: 50% 78%;
  animation: pet-breathe 2.7s ease-in-out infinite;
  user-select: none;
  -webkit-user-drag: none;
}

.state-speaking .pet-image {
  animation: pet-breathe 1.1s ease-in-out infinite;
}

.state-running .pet-image {
  animation: pet-run-breathe 420ms ease-in-out infinite;
}

.state-sleepy .pet-image {
  animation: pet-sleep-breathe 3.2s ease-in-out infinite;
}

.pet-signal {
  position: absolute;
  left: 50%;
  top: 4px;
  width: 38px;
  height: 38px;
  border: 2px solid rgba(38, 219, 205, 0.55);
  border-radius: 999px;
  transform: translateX(-50%) scale(0.2);
  opacity: 0;
  pointer-events: none;
}

.state-thinking .pet-signal,
.state-speaking .pet-signal,
.state-curious .pet-signal {
  animation: signal-pulse 1.4s ease-out infinite;
}

.state-speaking .pet-signal {
  animation-duration: 900ms;
}

.pet-signal-two {
  animation-delay: 360ms;
}

.pet-talk {
  position: absolute;
  left: 68px;
  top: 80px;
  width: 18px;
  height: 8px;
  border-radius: 999px;
  background: rgba(93, 255, 239, 0.86);
  box-shadow: 0 0 14px rgba(93, 255, 239, 0.92);
  opacity: 0;
  transform: scaleX(0.72);
  pointer-events: none;
}

.state-speaking .pet-talk {
  animation: talk-flash 520ms ease-in-out infinite;
}

.pet-emotion {
  position: absolute;
  top: 10px;
  right: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 999px;
  color: #0b1720;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 10px 24px rgba(12, 24, 22, 0.2);
  font-size: 20px;
  font-weight: 800;
  opacity: 0;
  pointer-events: none;
  transform: translateY(8px) scale(0.72);
}

.state-happy .pet-emotion-happy {
  opacity: 1;
  animation: emotion-pop 900ms ease-in-out infinite;
  color: #9a5b00;
  background: #ffe9a6;
}

.state-curious .pet-emotion-curious {
  opacity: 1;
  animation: emotion-pop 1.4s ease-in-out infinite;
  color: #1d4ed8;
  background: #dbeafe;
}

.state-sleepy .pet-emotion-sleepy {
  opacity: 1;
  animation: sleep-float 1.8s ease-in-out infinite;
  color: #475569;
  background: #e2e8f0;
  font-size: 15px;
}

.state-error .pet-emotion-error {
  opacity: 1;
  animation: emotion-pop 460ms ease-in-out infinite;
  color: #ffffff;
  background: #ff5a5f;
}

.pet-status {
  position: absolute;
  right: 24px;
  bottom: 24px;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: #29d38b;
  box-shadow: 0 0 0 3px rgba(247, 245, 239, 0.9), 0 0 16px rgba(41, 211, 139, 0.8);
}

.state-thinking .pet-status {
  background: #ffc857;
  box-shadow: 0 0 0 3px rgba(247, 245, 239, 0.92), 0 0 18px rgba(255, 200, 87, 0.82);
  animation: status-pulse 900ms ease-in-out infinite;
}

.state-speaking .pet-status {
  background: #26dbcd;
  box-shadow: 0 0 0 3px rgba(247, 245, 239, 0.92), 0 0 18px rgba(38, 219, 205, 0.86);
  animation: status-pulse 620ms ease-in-out infinite;
}

.state-happy .pet-status,
.state-curious .pet-status,
.state-running .pet-status {
  background: #26dbcd;
  box-shadow: 0 0 0 3px rgba(247, 245, 239, 0.92), 0 0 18px rgba(38, 219, 205, 0.86);
}

.state-sleepy .pet-status {
  background: #8b9aa5;
  box-shadow: 0 0 0 3px rgba(247, 245, 239, 0.9), 0 0 8px rgba(139, 154, 165, 0.5);
}

.state-error .pet-status {
  background: #ff5a5f;
  box-shadow: 0 0 0 3px rgba(247, 245, 239, 0.92), 0 0 18px rgba(255, 90, 95, 0.82);
  animation: status-pulse 420ms ease-in-out infinite;
}

@keyframes pet-float {
  0%,
  100% {
    transform: translateY(0) rotate(0deg);
  }
  50% {
    transform: translateY(-7px) rotate(-1.4deg);
  }
}

@keyframes pet-thinking {
  0%,
  100% {
    transform: translateY(0) rotate(-2deg);
  }
  50% {
    transform: translateY(-6px) rotate(2deg);
  }
}

@keyframes pet-speaking {
  0%,
  100% {
    transform: translateY(0) scale(1);
  }
  50% {
    transform: translateY(-4px) scale(1.025, 0.985);
  }
}

@keyframes pet-happy {
  0%,
  100% {
    transform: translateY(0) scale(1);
  }
  38% {
    transform: translateY(-14px) scale(1.045, 0.96) rotate(-2deg);
  }
  70% {
    transform: translateY(-3px) scale(0.99, 1.02) rotate(1deg);
  }
}

@keyframes pet-curious {
  0%,
  100% {
    transform: translateY(0) rotate(0deg);
  }
  32% {
    transform: translateY(-4px) rotate(-7deg);
  }
  68% {
    transform: translateY(-4px) rotate(7deg);
  }
}

@keyframes pet-sleepy {
  0%,
  100% {
    transform: translateY(2px) scale(0.985);
  }
  50% {
    transform: translateY(8px) scale(0.97, 1.02);
  }
}

@keyframes pet-running {
  0%,
  100% {
    transform: translateY(0) rotate(-3deg) scaleX(1.02);
  }
  50% {
    transform: translateY(-6px) rotate(3deg) scaleX(0.98);
  }
}

@keyframes pet-error {
  0%,
  100% {
    transform: translateX(0) rotate(0deg);
  }
  25% {
    transform: translateX(-3px) rotate(-2deg);
  }
  75% {
    transform: translateX(3px) rotate(2deg);
  }
}

@keyframes pet-click {
  0% {
    transform: translateY(0) scale(1);
  }
  42% {
    transform: translateY(-12px) scale(1.06, 0.94);
  }
  100% {
    transform: translateY(0) scale(1);
  }
}

@keyframes pet-breathe {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.025, 0.985);
  }
}

@keyframes pet-run-breathe {
  0%,
  100% {
    transform: scale(1.02, 0.98);
  }
  50% {
    transform: scale(0.98, 1.02);
  }
}

@keyframes pet-sleep-breathe {
  0%,
  100% {
    transform: scale(0.98);
    opacity: 0.92;
  }
  50% {
    transform: scale(0.97, 1.01);
    opacity: 0.82;
  }
}

@keyframes aura-pulse {
  0%,
  100% {
    transform: scaleX(0.86);
    opacity: 0.62;
  }
  50% {
    transform: scaleX(1.08);
    opacity: 0.9;
  }
}

@keyframes run-aura {
  0%,
  100% {
    transform: scaleX(0.72);
    opacity: 0.5;
  }
  50% {
    transform: scaleX(1.28);
    opacity: 0.95;
  }
}

@keyframes signal-pulse {
  0% {
    opacity: 0;
    transform: translateX(-50%) scale(0.24);
  }
  22% {
    opacity: 0.86;
  }
  100% {
    opacity: 0;
    transform: translateX(-50%) scale(1.18);
  }
}

@keyframes status-pulse {
  0%,
  100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.22);
  }
}

@keyframes talk-flash {
  0%,
  100% {
    opacity: 0.22;
    transform: scaleX(0.62);
  }
  50% {
    opacity: 0.95;
    transform: scaleX(1.16);
  }
}

@keyframes emotion-pop {
  0%,
  100% {
    transform: translateY(8px) scale(0.72);
  }
  45% {
    transform: translateY(-4px) scale(1);
  }
}

@keyframes sleep-float {
  0% {
    transform: translateY(10px) scale(0.72);
    opacity: 0;
  }
  30% {
    opacity: 1;
  }
  100% {
    transform: translateY(-18px) scale(1.04);
    opacity: 0;
  }
}
</style>
