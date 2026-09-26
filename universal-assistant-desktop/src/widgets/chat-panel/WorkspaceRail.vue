<script setup lang="ts">
import { BriefcaseBusiness, FileText, History, MessageSquareText, Plus } from '@lucide/vue'

defineProps<{
  careerOpen: boolean
  historyOpen: boolean
  fileOpen: boolean
  isSending: boolean
}>()

defineEmits<{
  chat: []
  history: []
  career: []
  files: []
  newConversation: []
}>()
</script>

<template>
  <nav class="workspace-rail" aria-label="工作区导航">
    <div class="rail-primary">
      <button
        type="button"
        title="聊天"
        aria-label="聊天"
        :class="{ active: !careerOpen && !historyOpen && !fileOpen }"
        @click="$emit('chat')"
      >
        <MessageSquareText :size="19" />
      </button>
      <button
        type="button"
        title="历史会话"
        aria-label="历史会话"
        :class="{ active: historyOpen }"
        @click="$emit('history')"
      >
        <History :size="19" />
      </button>
      <button
        type="button"
        title="求职助手"
        aria-label="求职助手"
        :class="{ active: careerOpen }"
        @click="$emit('career')"
      >
        <BriefcaseBusiness :size="19" />
      </button>
      <button
        type="button"
        title="文件工作区"
        aria-label="文件工作区"
        :class="{ active: fileOpen }"
        @click="$emit('files')"
      >
        <FileText :size="19" />
      </button>
    </div>
    <button type="button" title="新会话" aria-label="新会话" :disabled="isSending" @click="$emit('newConversation')">
      <Plus :size="20" />
    </button>
  </nav>
</template>

<style scoped>
.workspace-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-width: 0;
  padding: 13px 7px 14px;
  border-right: 1px solid var(--ua-border);
  background: var(--ua-rail);
}

.rail-primary {
  display: grid;
  gap: 7px;
}

button {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 7px;
  color: var(--ua-muted);
  background: transparent;
  cursor: pointer;
  transition:
    color 140ms ease,
    background 140ms ease,
    border-color 140ms ease,
    transform 140ms ease;
}

button:hover {
  color: var(--ua-ink);
  border-color: var(--ua-border-strong);
  background: var(--ua-panel);
  transform: translateY(-1px);
}

button.active {
  color: var(--ua-primary-strong);
  border-color: var(--ua-border);
  background: var(--ua-panel);
  box-shadow: inset 3px 0 var(--ua-companion);
}

button:active:not(:disabled) {
  transform: translateY(1px);
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
</style>
