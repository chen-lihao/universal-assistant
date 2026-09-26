<script setup lang="ts">
import { computed } from 'vue'
import ChatPanel from './components/ChatPanel.vue'
import PetAvatar from './components/PetAvatar.vue'
import FireworkOverlay from './widgets/pet-avatar/FireworkOverlay.vue'

const view = computed(() => {
  const search = new URLSearchParams(window.location.search)
  const requested = search.get('view')
  return requested === 'pet' || requested === 'effects' ? requested : 'chat'
})
</script>

<template>
  <PetAvatar v-if="view === 'pet'" />
  <FireworkOverlay v-else-if="view === 'effects'" />
  <ChatPanel v-else />
</template>
