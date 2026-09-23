<script setup lang="ts">
import PetSprite from '../widgets/pet-avatar/PetSprite.vue'
import { usePetAvatar } from '../widgets/pet-avatar/usePetAvatar'

const pet = usePetAvatar()
</script>

<template>
  <PetSprite
    :pet-state="pet.petState.value"
    :pet-action="pet.petAction.value"
    :particles="pet.particles.value"
    :rocket-visible="pet.rocketVisible.value"
    :burst-active="pet.burstActive.value"
    :is-clicking="pet.isClicking.value"
    :is-dragging="pet.isDragging.value"
    :particle-style="pet.particleStyle"
    @pointerdown="pet.startDrag"
    @pointermove="pet.moveDrag"
    @pointerup="pet.finishDrag"
    @pointercancel="pet.cancelDrag"
    @pointerenter="pet.handlePointerEnter"
    @pointerleave="pet.handlePointerLeave"
    @contextmenu="pet.openPetMenu"
  />
</template>
