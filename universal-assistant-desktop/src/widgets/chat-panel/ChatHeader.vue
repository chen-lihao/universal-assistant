<script setup lang="ts">
import { Bot, History as HistoryIcon, Plus, Sparkles, X } from '@lucide/vue'
import type { UiPhase } from '../../features/chat/model/chatTypes'

defineProps<{
  subtitle: string
  statusText: string
  isSending: boolean
  currentPhase: UiPhase
  conversationPanelOpen: boolean
}>()

defineEmits<{
  newConversation: []
  toggleHistory: []
  close: []
}>()
</script>

<template>
  <header class="chat-header">
    <div class="brand">
      <span class="brand-avatar" aria-hidden="true">
        <Bot :size="18" />
      </span>
      <div class="brand-copy">
        <strong>Universal Assistant</strong>
        <span>{{ subtitle }}</span>
      </div>
    </div>
    <div class="header-actions">
      <span
        class="status-pill"
        :class="{ active: isSending, done: currentPhase === 'done', error: currentPhase === 'error' }"
      >
        <Sparkles :size="13" />
        {{ isSending ? statusText : currentPhase === 'idle' ? '就绪' : statusText }}
      </span>
      <button
        class="icon-button"
        type="button"
        aria-label="New conversation"
        title="新会话"
        :disabled="isSending"
        @click="$emit('newConversation')"
      >
        <Plus :size="18" />
      </button>
      <button
        class="icon-button"
        type="button"
        aria-label="Conversation history"
        title="历史会话"
        :class="{ active: conversationPanelOpen }"
        @click="$emit('toggleHistory')"
      >
        <HistoryIcon :size="18" />
      </button>
      <button class="icon-button" type="button" aria-label="Close chat" title="关闭" @click="$emit('close')">
        <X :size="18" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 64px;
  padding: 12px 14px 12px 16px;
  border-bottom: 1px solid rgba(103, 119, 150, 0.16);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(16px);
  -webkit-app-region: drag;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.brand-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  border-radius: 12px;
  color: #ffffff;
  background: #2563eb;
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.22);
}

.brand-copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.brand-copy strong {
  overflow: hidden;
  color: #141b2d;
  font-size: 15px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-copy span {
  overflow: hidden;
  color: #6a7488;
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-header button {
  -webkit-app-region: no-drag;
}

.header-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

.status-pill,
.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid rgba(103, 119, 150, 0.18);
  background: #ffffff;
  font: inherit;
}

.status-pill {
  height: 28px;
  padding: 0 9px;
  border-color: rgba(20, 184, 166, 0.2);
  border-radius: 999px;
  color: #0f766e;
  background: rgba(20, 184, 166, 0.1);
  font-size: 12px;
}

.status-pill.active {
  color: #b45309;
  background: rgba(245, 158, 11, 0.14);
  border-color: rgba(245, 158, 11, 0.24);
}

.status-pill.done {
  color: #0f766e;
  background: rgba(20, 184, 166, 0.12);
  border-color: rgba(20, 184, 166, 0.24);
}

.status-pill.error {
  color: #b91c1c;
  background: rgba(239, 68, 68, 0.12);
  border-color: rgba(239, 68, 68, 0.24);
}

.icon-button {
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: 10px;
  color: #59657a;
}

.icon-button:hover {
  color: #ef4444;
  background: #fff1f2;
  border-color: rgba(239, 68, 68, 0.24);
}

.icon-button.active {
  color: #2563eb;
  background: #eff6ff;
  border-color: rgba(37, 99, 235, 0.28);
}

.icon-button:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

@media (max-width: 420px) {
  .status-pill {
    display: none;
  }
}
</style>
