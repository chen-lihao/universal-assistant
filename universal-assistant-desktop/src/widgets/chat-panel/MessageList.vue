<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import companion from '../../assets/assistant-human-idle-animated.webp'
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
  suggest: [prompt: string]
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
    <div class="message-column">
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
      <aside v-if="messages.length <= 1" class="companion-presence" aria-label="推荐问题">
        <div class="companion-copy">
          <small>数字精灵</small>
          <strong>今天想从哪里开始？</strong>
          <div class="suggestions">
            <button type="button" @click="$emit('suggest', '帮我规划明天的一日游')">规划一日游</button>
            <button type="button" @click="$emit('suggest', '总结一下今天的重要新闻')">看看今日新闻</button>
          </div>
        </div>
        <img :src="companion" alt="" />
      </aside>
    </div>
  </section>

  <button v-if="showScrollToBottom" class="scroll-bottom-button" type="button" @click="forceScrollMessagesToBottom">
    <ChevronDown :size="15" />
    回到底部
  </button>
</template>

<style scoped>
.message-list {
  min-height: 0;
  overflow-y: auto;
  background: var(--ua-bg);
  user-select: text;
}

.message-column {
  display: flex;
  flex-direction: column;
  gap: 18px;
  width: min(100%, 860px);
  min-height: 100%;
  margin-inline: auto;
  padding: 26px clamp(16px, 4vw, 34px) 38px;
}

.companion-presence {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  min-height: 230px;
  margin-block: auto;
  padding: 20px 0 12px;
  color: var(--ua-muted);
  overflow: hidden;
}

.companion-presence img {
  width: 148px;
  height: 210px;
  object-fit: contain;
  object-position: bottom;
  filter: drop-shadow(0 10px 14px rgba(33, 28, 47, 0.12));
}

.companion-copy {
  display: grid;
  align-content: center;
  gap: 8px;
  max-width: 400px;
}

.companion-copy small {
  color: var(--ua-companion-strong);
  font-size: 11px;
  font-weight: 700;
}

.companion-presence strong {
  color: var(--ua-ink);
  font-size: 18px;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 8px;
}

.suggestions button {
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--ua-border-strong);
  border-radius: 6px;
  color: var(--ua-ink-soft);
  background: var(--ua-panel);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition:
    color 160ms ease,
    border-color 160ms ease,
    background 160ms ease;
}

.suggestions button:hover {
  border-color: var(--ua-primary);
  color: var(--ua-primary-strong);
  background: var(--ua-primary-soft);
}

.scroll-bottom-button {
  position: absolute;
  right: 18px;
  bottom: 116px;
  z-index: 4;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 11px;
  border: 1px solid var(--ua-border-strong);
  border-radius: 6px;
  color: var(--ua-primary-strong);
  background: var(--ua-panel);
  box-shadow: var(--ua-shadow-md);
  font: inherit;
  font-size: 12px;
  backdrop-filter: blur(12px);
}

@media (max-width: 620px) {
  .message-column {
    padding-inline: 14px;
  }

  .companion-presence img {
    width: 98px;
    height: 150px;
  }

  .companion-presence {
    gap: 6px;
    min-height: 180px;
  }

  .companion-presence strong {
    font-size: 15px;
  }
}
</style>
