<script setup lang="ts">
import { Edit3, Search } from '@lucide/vue'
import assistantPortrait from '../../assets/assistant-human-portrait.webp'
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
      <img :src="assistantPortrait" alt="" />
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
  display: flex;
  align-items: flex-end;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.message-row.user {
  justify-content: flex-end;
}

.message-row.assistant {
  justify-content: flex-start;
}

.message-avatar {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  overflow: hidden;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  background: var(--ua-companion-soft);
  box-shadow: none;
}

.message-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center 38%;
}

.message-stack {
  display: grid;
  gap: 6px;
  min-width: 0;
  max-width: min(91%, 760px);
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
  min-width: 0;
  padding: 13px 16px;
  border: 1px solid var(--ua-border);
  border-radius: 7px;
  box-shadow: none;
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
  color: var(--ua-muted);
}

.message-row.user .message {
  color: var(--ua-ink);
  border-color: var(--ua-user-border);
  background: var(--ua-user-bubble);
  border-radius: 7px 7px 2px 7px;
}

.message-row.user.editing .message {
  outline: 3px solid rgba(206, 100, 131, 0.18);
  box-shadow: none;
}

.message-row.assistant .message {
  position: relative;
  background: var(--ua-panel);
  border-radius: 2px 7px 7px 7px;
}

.message-row.assistant .message::before {
  content: '';
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: -1px;
  width: 2px;
  border-radius: 0 3px 3px 0;
  background: var(--ua-companion);
}

.message-row.user .message {
  position: relative;
}

.message-row.user .message::after {
  content: '';
  position: absolute;
  top: 0;
  right: 16px;
  width: 5px;
  height: 3px;
  border-radius: 0 0 3px 3px;
  background: var(--ua-companion);
}

.message-actions {
  display: flex;
  justify-content: flex-end;
  padding-right: 6px;
}

.message-actions .ghost-icon.small {
  color: var(--ua-muted);
  background: var(--ua-panel);
  border-color: var(--ua-border);
  box-shadow: none;
}

.ghost-icon,
.tool-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--ua-border);
  border-radius: 10px;
  color: var(--ua-ink-soft);
  background: var(--ua-panel);
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
  border-radius: 7px 7px 3px 7px;
  font-size: 12px;
}

.tool-button:hover:not(:disabled),
.ghost-icon:hover:not(:disabled) {
  border-color: var(--ua-border-strong);
  background: var(--ua-primary-soft);
  color: var(--ua-primary-strong);
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
  color: var(--ua-muted);
}

.message-progress small {
  font-size: 12px;
}

.tool-confirmation {
  display: grid;
  gap: 6px;
  margin-top: 9px;
  padding: 10px;
  border: 1px solid var(--ua-sun);
  border-radius: 8px;
  color: var(--ua-ink);
  background: var(--ua-sun-soft);
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
  color: var(--ua-ink);
  font-size: 12px;
}

.tool-confirmation small {
  color: var(--ua-ink-soft);
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
  border-radius: 4px;
  color: var(--ua-muted);
  background: var(--ua-bg-deep);
  font-size: 11px;
}

.message-row.user .message-meta small {
  color: var(--ua-primary-strong);
  background: var(--ua-hover);
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
  background: var(--ua-companion);
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
