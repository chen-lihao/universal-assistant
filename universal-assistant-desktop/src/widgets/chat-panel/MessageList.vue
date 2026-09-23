<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import { useMessageScroll } from '../../features/chat/model/useMessageScroll'
import type { UiMessage } from '../../features/chat/model/chatTypes'
import MessageItem from './MessageItem.vue'

defineProps<{
  messages: UiMessage[]
  isSending: boolean
  editingMessageId?: string | number
  canEditUserMessage: (message: UiMessage) => boolean
}>()

defineEmits<{
  edit: [message: UiMessage]
  toolDecision: [message: UiMessage, decision: 'approved' | 'denied']
}>()

const {
  messageListRef,
  showScrollToBottom,
  scrollMessagesToBottom,
  forceScrollMessagesToBottom,
  updateMessageScrollState,
} = useMessageScroll()

defineExpose({
  scrollMessagesToBottom,
  forceScrollMessagesToBottom,
})
</script>

<template>
  <section ref="messageListRef" class="message-list" aria-live="polite" @scroll="updateMessageScrollState">
    <MessageItem
      v-for="message in messages"
      :key="message.id"
      :message="message"
      :is-sending="isSending"
      :editing="editingMessageId === message.id"
      :can-edit="canEditUserMessage(message)"
      @edit="$emit('edit', $event)"
      @tool-decision="(item, decision) => $emit('toolDecision', item, decision)"
    />
  </section>

  <button v-if="showScrollToBottom" class="scroll-bottom-button" type="button" @click="forceScrollMessagesToBottom">
    <ChevronDown :size="15" />
    回到底部
  </button>
</template>

<style scoped>
.message-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  padding: 18px 16px;
  overflow-y: auto;
  background: #f5f7fb;
  user-select: text;
}

.scroll-bottom-button {
  position: absolute;
  right: 18px;
  bottom: 128px;
  z-index: 4;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 11px;
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: 999px;
  color: #1d4ed8;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 10px 28px rgba(22, 34, 51, 0.14);
  font: inherit;
  font-size: 12px;
  backdrop-filter: blur(12px);
}
</style>
