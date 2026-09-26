<script setup lang="ts">
import { Check, Edit3, Plus, Trash2, X } from '@lucide/vue'
import type { ConversationSummary } from '../../services/assistantApi'

defineProps<{
  conversations: ConversationSummary[]
  currentConversationId?: string
  isSending: boolean
  conversationsLoading: boolean
  renamingConversationId?: string
  renamingTitle: string
  errorText?: string
}>()

const emit = defineEmits<{
  startNew: []
  open: [conversation: ConversationSummary]
  startRename: [conversation: ConversationSummary]
  submitRename: [conversation: ConversationSummary]
  cancelRename: []
  remove: [conversation: ConversationSummary]
  'update:renamingTitle': [value: string]
}>()

function formatConversationTime(value: string) {
  if (!value) {
    return ''
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }

  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function updateRenamingTitle(event: Event) {
  emit('update:renamingTitle', (event.target as HTMLInputElement).value)
}
</script>

<template>
  <section class="conversation-panel">
    <div class="conversation-panel-header">
      <div>
        <small>会话记录</small>
        <strong>历史会话</strong>
      </div>
      <button class="tool-button compact" type="button" :disabled="isSending" @click="$emit('startNew')">
        <Plus :size="14" />
        新会话
      </button>
    </div>
    <div class="conversation-list">
      <div
        v-for="conversation in conversations"
        :key="conversation.id"
        class="conversation-item"
        :class="{
          active: conversation.id === currentConversationId,
          renaming: renamingConversationId === conversation.id,
        }"
      >
        <form
          v-if="renamingConversationId === conversation.id"
          class="conversation-rename"
          @submit.prevent="$emit('submitRename', conversation)"
        >
          <input
            :value="renamingTitle"
            type="text"
            aria-label="会话名称"
            :data-rename-conversation-id="conversation.id"
            @input="updateRenamingTitle"
            @keydown.esc.prevent="$emit('cancelRename')"
          />
          <button class="ghost-icon small" type="submit" title="保存" :disabled="isSending || !renamingTitle.trim()">
            <Check :size="13" />
          </button>
          <button
            class="ghost-icon small"
            type="button"
            title="取消"
            :disabled="isSending"
            @click="$emit('cancelRename')"
          >
            <X :size="13" />
          </button>
        </form>
        <template v-else>
          <button class="conversation-open" type="button" :disabled="isSending" @click="$emit('open', conversation)">
            <span>{{ conversation.title }}</span>
            <small>{{ formatConversationTime(conversation.updatedAt) }}</small>
          </button>
          <div class="conversation-actions">
            <button
              class="ghost-icon small"
              type="button"
              title="重命名"
              :disabled="isSending"
              @click="$emit('startRename', conversation)"
            >
              <Edit3 :size="13" />
            </button>
            <button
              class="ghost-icon small danger"
              type="button"
              title="删除"
              :disabled="isSending"
              @click="$emit('remove', conversation)"
            >
              <Trash2 :size="13" />
            </button>
          </div>
        </template>
      </div>
      <p v-if="!conversationsLoading && conversations.length === 0" class="empty-conversation">暂无历史会话</p>
      <p v-if="conversationsLoading" class="empty-conversation">加载中...</p>
    </div>
    <p v-if="errorText" class="error-text">{{ errorText }}</p>
  </section>
</template>

<style scoped>
.conversation-panel {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 12px;
  padding: 17px 12px;
  border-right: 1px solid var(--ua-border);
  background: var(--ua-panel-soft);
  overflow: hidden;
}

.conversation-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.conversation-panel-header strong {
  color: var(--ua-ink);
  font-size: 13px;
}

.conversation-panel-header > div {
  display: grid;
  gap: 2px;
}

.conversation-panel-header > div small {
  color: var(--ua-companion-strong);
  font-size: 11px;
  font-weight: 700;
}

.tool-button,
.ghost-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  color: var(--ua-ink-soft);
  background: var(--ua-panel);
  cursor: pointer;
  font: inherit;
  font-size: 13px;
}

.tool-button.compact {
  min-height: 28px;
  padding: 0 9px;
  border-radius: 7px 7px 3px 7px;
  font-size: 12px;
}

.tool-button:hover:not(:disabled),
.ghost-icon:hover:not(:disabled) {
  border-color: var(--ua-border-strong);
  background: var(--ua-primary-soft);
  color: var(--ua-primary-strong);
}

.ghost-icon {
  width: 30px;
  padding: 0;
}

.ghost-icon.small {
  width: 26px;
  height: 26px;
  border-radius: 8px;
}

.ghost-icon.danger:hover:not(:disabled) {
  color: var(--ua-danger);
  border-color: var(--ua-danger);
  background: var(--ua-danger-soft);
}

.conversation-list {
  display: grid;
  align-content: start;
  gap: 3px;
  min-height: 0;
  overflow-y: auto;
}

.conversation-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 4px;
  width: 100%;
  min-height: 38px;
  padding: 7px 7px 7px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: var(--ua-ink-soft);
  background: transparent;
}

.conversation-open {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  font: inherit;
  text-align: left;
}

.conversation-open span {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-open small {
  color: var(--ua-muted);
  font-size: 10px;
}

.conversation-item:hover,
.conversation-item.active {
  color: var(--ua-primary-strong);
  background: var(--ua-primary-soft);
  border-color: var(--ua-border);
}

.conversation-item.renaming {
  grid-template-columns: minmax(0, 1fr);
}

.conversation-item:disabled,
button:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.conversation-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.conversation-rename {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: minmax(0, 1fr) 26px 26px;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.conversation-rename input {
  width: 100%;
  min-width: 0;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--ua-border-strong);
  border-radius: 8px;
  outline: none;
  color: var(--ua-ink);
  background: var(--ua-panel);
  font: inherit;
  font-size: 13px;
}

.conversation-rename input:focus {
  border-color: var(--ua-primary);
  box-shadow: var(--ua-focus);
}

.empty-conversation {
  margin: 6px 2px;
  color: var(--ua-muted);
  font-size: 12px;
}

.error-text {
  margin: 0;
  color: var(--ua-danger);
  font-size: 12px;
}
</style>
