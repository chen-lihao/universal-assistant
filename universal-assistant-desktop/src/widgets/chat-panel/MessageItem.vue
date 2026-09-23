<script setup lang="ts">
import { Bot, Edit3, Search } from '@lucide/vue'
import MessageRenderer from '../../components/MessageRenderer.vue'
import { modelLabel, phaseLabel, type UiMessage } from '../../features/chat/model/chatTypes'

defineProps<{
  message: UiMessage
  isSending: boolean
  editing: boolean
  canEdit: boolean
}>()

defineEmits<{
  edit: [message: UiMessage]
  toolDecision: [message: UiMessage, decision: 'approved' | 'denied']
}>()
</script>

<template>
  <article class="message-row" :class="[message.role, { editing }]">
    <span v-if="message.role === 'assistant'" class="message-avatar" aria-hidden="true">
      <Bot :size="15" />
    </span>
    <div class="message-stack">
      <div class="message">
        <MessageRenderer
          v-if="
            message.role === 'assistant' &&
            (message.content || message.contentBlocks?.length || message.agentSteps?.length || message.sources?.length)
          "
          :content="message.content"
          :blocks="message.contentBlocks"
          :agent-steps="message.agentSteps"
          :sources="message.sources"
        />
        <p
          v-if="message.role === 'assistant' && message.status === 'cancelled' && !message.content"
          class="cancelled-fallback"
        >
          回答已中断。你可以编辑上一条问题后重新发送。
        </p>
        <p v-else-if="message.role === 'user'">{{ message.content }}</p>
        <div
          v-if="
            message.role === 'assistant' &&
            !message.content &&
            !message.contentBlocks?.length &&
            !message.agentSteps?.length &&
            !message.sources?.length &&
            message.status !== 'cancelled'
          "
          class="message-progress"
        >
          <span class="typing-dots" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          <small>{{ phaseLabel(message.phase) || '准备中' }}</small>
        </div>
        <div v-if="message.pendingTool" class="tool-confirmation">
          <strong>需要实时检索</strong>
          <p>{{ message.pendingTool.reason }}</p>
          <small v-if="message.pendingTool.input">检索词：{{ message.pendingTool.input }}</small>
          <div>
            <button
              class="tool-button compact"
              type="button"
              :disabled="isSending"
              @click="$emit('toolDecision', message, 'approved')"
            >
              <Search :size="13" />
              允许检索
            </button>
            <button
              class="tool-button compact"
              type="button"
              :disabled="isSending"
              @click="$emit('toolDecision', message, 'denied')"
            >
              不联网回答
            </button>
          </div>
        </div>
        <div
          v-if="
            message.phase ||
            message.editedAt ||
            message.status === 'cancelled' ||
            message.realtimeSearchUsed ||
            (message.model && message.role === 'assistant') ||
            message.modelAvailable === false
          "
          class="message-meta"
        >
          <small v-if="message.phase && message.role === 'assistant'">{{ phaseLabel(message.phase) }}</small>
          <small v-if="message.status === 'cancelled'">可编辑问题后重发</small>
          <small v-if="message.editedAt && message.role === 'user'">已编辑</small>
          <small v-if="message.realtimeSearchUsed">实时检索</small>
          <small v-if="message.model && message.role === 'assistant'">{{ modelLabel(message.model) }}</small>
          <small v-if="message.modelAvailable === false">模型未连接或调用失败</small>
        </div>
      </div>
      <div v-if="message.role === 'user' && !editing && canEdit" class="message-actions">
        <button
          class="ghost-icon small"
          type="button"
          title="编辑并重发"
          :disabled="isSending"
          @click="$emit('edit', message)"
        >
          <Edit3 :size="13" />
        </button>
      </div>
    </div>
  </article>
</template>

<style scoped>
.message-row {
  --message-column-width: min(86%, 860px);
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.message-row.user {
  justify-content: flex-end;
}

.message-row.assistant {
  --message-column-width: min(calc(86% - 36px), 824px);
  justify-content: flex-start;
}

.message-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  border-radius: 10px;
  color: #ffffff;
  background: #14b8a6;
  box-shadow: 0 8px 16px rgba(20, 184, 166, 0.22);
}

.message-stack {
  display: grid;
  gap: 6px;
  width: var(--message-column-width);
  max-width: var(--message-column-width);
}

.message-row.user .message-stack {
  justify-items: end;
}

.message-row.assistant .message-stack {
  justify-items: start;
}

.message {
  width: fit-content;
  max-width: 100%;
  padding: 11px 13px;
  border: 1px solid rgba(103, 119, 150, 0.14);
  border-radius: 18px;
  box-shadow: 0 10px 26px rgba(30, 41, 59, 0.06);
  user-select: text;
}

.message p {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.58;
}

.message .cancelled-fallback {
  color: #526075;
}

.message-row.user .message {
  color: #ffffff;
  border-color: #2563eb;
  background: #2563eb;
  border-bottom-right-radius: 6px;
}

.message-row.user.editing .message {
  outline: 3px solid rgba(37, 99, 235, 0.16);
  box-shadow: 0 12px 30px rgba(37, 99, 235, 0.14);
}

.message-row.assistant .message {
  width: 100%;
  background: #ffffff;
  border-bottom-left-radius: 6px;
}

.message-actions {
  display: flex;
  justify-content: flex-end;
  padding-right: 6px;
}

.message-actions .ghost-icon.small {
  color: #5b6b86;
  background: rgba(255, 255, 255, 0.92);
  border-color: rgba(103, 119, 150, 0.18);
  box-shadow: 0 8px 18px rgba(30, 41, 59, 0.08);
}

.ghost-icon,
.tool-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid rgba(103, 119, 150, 0.18);
  border-radius: 10px;
  color: #344057;
  background: #ffffff;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
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

.tool-button.compact {
  min-height: 28px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 12px;
}

.tool-button:hover:not(:disabled),
.ghost-icon:hover:not(:disabled) {
  border-color: rgba(37, 99, 235, 0.28);
  background: #eff6ff;
  color: #1d4ed8;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.message-progress {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 24px;
  color: #64748b;
}

.message-progress small {
  font-size: 12px;
}

.tool-confirmation {
  display: grid;
  gap: 6px;
  margin-top: 9px;
  padding: 10px;
  border: 1px solid rgba(245, 158, 11, 0.24);
  border-radius: 12px;
  color: #5b3a07;
  background: rgba(255, 251, 235, 0.9);
}

.tool-confirmation div {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.tool-confirmation strong {
  font-size: 13px;
}

.tool-confirmation p {
  color: #5b3a07;
  font-size: 12px;
}

.tool-confirmation small {
  color: #8a6116;
  font-size: 11px;
  word-break: break-word;
}

.message-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}

.message-meta small {
  display: inline-flex;
  align-items: center;
  min-height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  color: #59657a;
  background: #eef2f8;
  font-size: 11px;
}

.message-row.user .message-meta small {
  color: #eff6ff;
  background: rgba(255, 255, 255, 0.18);
}

.typing-dots {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.typing-dots span {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: #14b8a6;
  animation: typing-dot 900ms ease-in-out infinite;
}

.typing-dots span:nth-child(2) {
  animation-delay: 120ms;
}

.typing-dots span:nth-child(3) {
  animation-delay: 240ms;
}

@keyframes typing-dot {
  0%,
  100% {
    transform: translateY(0);
    opacity: 0.35;
  }
  50% {
    transform: translateY(-4px);
    opacity: 1;
  }
}
</style>
