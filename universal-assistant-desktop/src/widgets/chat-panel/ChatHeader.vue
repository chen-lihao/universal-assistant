<script setup lang="ts">
import { Moon, Sun, X } from '@lucide/vue'
import mascot from '../../assets/assistant-human-portrait.webp'
import type { UiPhase } from '../../features/chat/model/chatTypes'
import { useAppearanceStore } from '../../features/appearance/stores/appearanceStore'

const appearance = useAppearanceStore()

defineProps<{
  subtitle: string
  statusText: string
  isSending: boolean
  currentPhase: UiPhase
}>()

defineEmits<{
  close: []
}>()
</script>

<template>
  <header class="chat-header">
    <div class="brand">
      <span class="brand-avatar" aria-hidden="true">
        <img :src="mascot" alt="" />
      </span>
      <div class="brand-copy">
        <strong>
          <span>Universal Assistant</span>
          <small>数字精灵</small>
        </strong>
        <span>{{ subtitle }}</span>
      </div>
    </div>
    <div class="header-actions">
      <span
        class="header-status"
        :class="{ active: isSending, done: currentPhase === 'done', error: currentPhase === 'error' }"
      >
        <i aria-hidden="true" />
        {{ isSending ? statusText : currentPhase === 'idle' ? '就绪' : statusText }}
      </span>
      <button
        class="icon-button theme-button"
        type="button"
        :aria-label="appearance.theme === 'dark' ? '切换明亮主题' : '切换黑暗主题'"
        :title="appearance.theme === 'dark' ? '明亮主题' : '黑暗主题'"
        @click="appearance.toggleTheme()"
      >
        <Sun v-if="appearance.theme === 'dark'" :size="17" />
        <Moon v-else :size="17" />
      </button>
      <button class="icon-button" type="button" aria-label="关闭聊天框" title="关闭聊天框" @click="$emit('close')">
        <X :size="18" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.chat-header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 56px;
  gap: 12px;
  padding: 6px 14px 6px 12px;
  border-bottom: 1px solid var(--ua-border);
  background: var(--ua-panel);
  -webkit-app-region: drag;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.brand-avatar {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  overflow: hidden;
  border: 1px solid var(--ua-border);
  border-radius: 7px;
  background: var(--ua-companion-soft);
  box-shadow: none;
}

.brand-avatar::before {
  content: '';
  position: absolute;
  inset: 3px;
  z-index: 1;
  border: 1px solid rgba(255, 255, 255, 0.46);
  border-radius: 4px;
  pointer-events: none;
}

.brand-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center 34%;
}

.brand-copy {
  display: grid;
  gap: 1px;
  min-width: 0;
}

.brand-copy strong {
  display: flex;
  align-items: baseline;
  gap: 7px;
  overflow: hidden;
  color: var(--ua-ink);
  font-size: 13px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-copy strong small {
  flex: none;
  padding-left: 7px;
  border-left: 2px solid var(--ua-sakura);
  color: var(--ua-companion-strong);
  font-size: 10px;
  font-weight: 700;
}

.brand-copy span {
  overflow: hidden;
  color: var(--ua-muted);
  font-size: 11px;
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
  gap: 5px;
  flex: 0 0 auto;
}

.header-status,
.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--ua-border);
  background: var(--ua-panel);
  font: inherit;
}

.header-status {
  height: 28px;
  padding: 0 8px;
  border-color: transparent;
  color: var(--ua-muted);
  background: transparent;
  font-size: 11px;
  white-space: nowrap;
}

.header-status i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ua-companion);
}

.header-status.active {
  color: var(--ua-sun);
}

.header-status.active i {
  background: var(--ua-sun);
  animation: status-pulse 1.5s ease-in-out infinite;
}

.header-status.error {
  color: var(--ua-danger);
}

.header-status.error i {
  background: var(--ua-danger);
}

.icon-button {
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: 7px;
  color: var(--ua-muted);
  transition:
    color 160ms ease,
    background 160ms ease,
    border-color 160ms ease,
    transform 160ms ease;
}

.icon-button:hover {
  color: var(--ua-primary);
  background: var(--ua-primary-soft);
  border-color: var(--ua-border-strong);
  transform: translateY(-1px);
}

.icon-button.active {
  color: var(--ua-primary);
  background: var(--ua-primary-soft);
  border-color: var(--ua-border-strong);
}

.icon-button:disabled {
  cursor: not-allowed;
  opacity: 0.48;
}

@media (max-width: 640px) {
  .header-status {
    display: none;
  }
}

@keyframes status-pulse {
  50% {
    opacity: 0.45;
  }
}
</style>
