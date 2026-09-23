<script setup lang="ts">
import mascot from '../../assets/assistant-mascot.png'
import type { FireworkParticle, ParticleStyle } from './types'

defineProps<{
  petState: PetState
  petAction: PetAction | null
  particles: FireworkParticle[]
  rocketVisible: boolean
  burstActive: boolean
  isClicking: boolean
  isDragging: boolean
  particleStyle: (particle: FireworkParticle) => ParticleStyle
}>()

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
      <span class="pet-speed-lines">
        <span />
        <span />
        <span />
      </span>
      <img class="pet-image" :src="mascot" alt="Universal Assistant mascot" />
      <span class="pet-wave" />
      <span class="pet-signal pet-signal-one" />
      <span class="pet-signal pet-signal-two" />
      <span class="pet-talk" />
      <span class="pet-emotion pet-emotion-happy">★</span>
      <span class="pet-emotion pet-emotion-curious">?</span>
      <span class="pet-emotion pet-emotion-sleepy">Zz</span>
      <span class="pet-emotion pet-emotion-error">!</span>
      <span class="pet-status" />
      <span v-if="rocketVisible" class="firework-rocket" />
      <span class="firework-origin" :class="{ active: burstActive }">
        <span
          v-for="particle in particles"
          :key="particle.id"
          class="firework-particle"
          :style="particleStyle(particle)"
        />
      </span>
    </button>
  </main>
</template>

<style src="./petSprite.css" scoped></style>
