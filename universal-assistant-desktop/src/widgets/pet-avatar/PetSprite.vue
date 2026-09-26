<script setup lang="ts">
import { computed } from 'vue'
import happyPose from '../../assets/assistant-human-happy.webp'
import idlePose from '../../assets/assistant-human-idle-animated.webp'
import runningPose from '../../assets/assistant-human-running-animated.webp'
import speakingPose from '../../assets/assistant-human-speaking-animated.webp'
import thinkingPose from '../../assets/assistant-human-thinking.webp'

const props = defineProps<{
  petState: PetState
  petAction: PetAction | null
  isClicking: boolean
  isDragging: boolean
}>()

const poseSources = {
  idle: idlePose,
  thinking: thinkingPose,
  speaking: speakingPose,
  happy: happyPose,
  running: runningPose,
} as const

const activePose = computed<keyof typeof poseSources>(() => {
  if (props.petAction === 'run' || props.petState === 'running') return 'running'
  if (props.petAction === 'jump' || props.petAction === 'fireworks' || props.petState === 'happy') return 'happy'
  if (props.petState === 'thinking' || props.petState === 'curious') return 'thinking'
  if (props.petState === 'speaking') return 'speaking'
  return 'idle'
})

defineEmits<{
  pointerdown: [event: PointerEvent]
  pointermove: [event: PointerEvent]
  pointerup: [event: PointerEvent]
  pointercancel: [event: PointerEvent]
  pointerenter: [event: PointerEvent]
  pointerleave: [event: PointerEvent]
  contextmenu: [event: MouseEvent]
}>()
</script>

<template>
  <main class="pet-shell" :class="[`state-${petState}`, petAction ? `action-${petAction}` : '']">
    <button
      class="pet-button"
      :class="{ 'is-clicking': isClicking, 'is-dragging': isDragging }"
      type="button"
      aria-label="Open assistant chat"
      @pointerdown="$emit('pointerdown', $event)"
      @pointermove="$emit('pointermove', $event)"
      @pointerup="$emit('pointerup', $event)"
      @pointercancel="$emit('pointercancel', $event)"
      @pointerenter="$emit('pointerenter', $event)"
      @pointerleave="$emit('pointerleave', $event)"
      @contextmenu="$emit('contextmenu', $event)"
    >
      <span class="pet-aura" />
      <img
        v-for="(source, pose) in poseSources"
        :key="pose"
        class="pet-image"
        :class="{ active: activePose === pose }"
        :src="source"
        :alt="activePose === pose ? 'Universal Assistant virtual companion' : ''"
        :aria-hidden="activePose === pose ? undefined : true"
      />
      <span class="pet-expression" aria-hidden="true">
        {{ petState === 'sleepy' ? 'Zz' : petState === 'error' ? '!' : petState === 'curious' ? '?' : '✦' }}
      </span>
    </button>
  </main>
</template>

<style src="./petStage.css" scoped></style>
