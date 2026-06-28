<script setup lang="ts">
import { computed } from 'vue'
import ChatPanel from './components/ChatPanel.vue'
import PetAvatar from './components/PetAvatar.vue'

const view = computed(() => {
  const search = new URLSearchParams(window.location.search)
  return search.get('view') === 'pet' ? 'pet' : 'chat'
})
</script>

<template>
  <PetAvatar v-if="view === 'pet'" />
  <ChatPanel v-else />
</template>
