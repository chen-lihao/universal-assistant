<script setup lang="ts">
import { computed } from 'vue'
import { Sparkles } from '@lucide/vue'
import MarkdownMessage from './MarkdownMessage.vue'
import type { AgentStep, MessageBlock, SearchResult } from '../services/assistantApi'

const props = defineProps<{
  content: string
  blocks?: MessageBlock[]
  agentSteps?: AgentStep[]
  sources?: SearchResult[]
}>()

function stepTypeLabel(type: string) {
  const labels: Record<string, string> = {
    thought: '思考',
    plan: '计划',
    action: '动作',
    observation: '观察',
    memory: '记忆',
    reflection: '反思',
    final: '总结',
    error: '错误',
    warning: '提醒',
    limit: '限制',
    tool_confirmation: '确认',
    user_decision: '确认',
  }
  return labels[type] || type || '步骤'
}

function stepStatusLabel(status: string) {
  const labels: Record<string, string> = {
    completed: '完成',
    failed: '失败',
    degraded: '降级',
    running: '执行中',
    waiting: '等待',
    cancelled: '中断',
    invalidated: '已失效',
    unavailable: '不可用',
  }
  return labels[status] || status || '记录'
}

function sourceLabel(source: SearchResult) {
  return source.provider || safeHost(source.url)
}

function safeHost(url: string) {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return 'web'
  }
}

const renderBlocks = computed<MessageBlock[]>(() => {
  if (props.blocks?.length) {
    let blocks = [...props.blocks]
    if (props.content && !blocks.some((block) => block.type === 'markdown')) {
      const insertAt = blocks.findIndex((block) => block.type === 'sources' || block.type === 'status')
      const markdownBlock: MessageBlock = {
        id: 'runtime-markdown',
        type: 'markdown',
        content: props.content,
      }
      if (insertAt >= 0) {
        blocks = [...blocks.slice(0, insertAt), markdownBlock, ...blocks.slice(insertAt)]
      } else {
        blocks.push(markdownBlock)
      }
    }
    if (props.sources?.length && !blocks.some((block) => block.type === 'sources')) {
      blocks.push({
        id: 'runtime-sources',
        type: 'sources',
        items: props.sources,
      })
    }
    return blocks
  }

  const fallbackBlocks: MessageBlock[] = []
  if (props.agentSteps?.length) {
    fallbackBlocks.push({
      id: 'legacy-execution',
      type: 'execution',
      steps: props.agentSteps,
      collapsed: false,
    })
  }
  if (props.content) {
    fallbackBlocks.push({
      id: 'legacy-markdown',
      type: 'markdown',
      content: props.content,
    })
  }
  if (props.sources?.length) {
    fallbackBlocks.push({
      id: 'legacy-sources',
      type: 'sources',
      items: props.sources,
    })
  }
  return fallbackBlocks
})
</script>

<template>
  <div class="message-renderer">
    <template v-for="block in renderBlocks" :key="block.id">
      <details v-if="block.type === 'execution' && block.steps?.length" class="agent-steps" open>
        <summary>
          <Sparkles :size="13" />
          <span>执行过程</span>
          <small>{{ block.steps.length }} 步</small>
        </summary>
        <ol>
          <li v-for="step in block.steps" :key="step.id" :class="{ failed: step.status === 'failed' }">
            <div>
              <strong>{{ step.title }}</strong>
              <small>{{ stepTypeLabel(step.type) }} · {{ stepStatusLabel(step.status) }}</small>
            </div>
            <p v-if="step.content">{{ step.content }}</p>
          </li>
        </ol>
      </details>

      <MarkdownMessage v-else-if="block.type === 'markdown' && block.content" :content="block.content" />

      <div v-else-if="block.type === 'sources' && block.items?.length" class="sources">
        <div class="sources-heading">
          <span>参考来源</span>
          <small>{{ block.items.length }} 项</small>
        </div>
        <a v-for="source in block.items" :key="source.url" :href="source.url" target="_blank" rel="noreferrer">
          <span>{{ source.title }}</span>
          <small>{{ sourceLabel(source) }}</small>
        </a>
      </div>

      <div v-else-if="block.type === 'status'" class="status-block">
        {{ block.text }}
      </div>

      <div v-else-if="block.type === 'error'" class="error-block">
        {{ block.message }}
      </div>
    </template>
  </div>
</template>

<style scoped>
.message-renderer {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
  min-width: 0;
}

.agent-steps {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  color: var(--ua-ink-soft);
  background: var(--ua-primary-soft);
}

.agent-steps summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--ua-border);
  color: var(--ua-primary-strong);
  cursor: default;
  list-style: none;
}

.agent-steps summary::-webkit-details-marker {
  display: none;
}

.agent-steps summary span {
  font-weight: 700;
}

.agent-steps summary b {
  font-size: 11px;
  font-weight: 600;
}

.agent-steps summary small {
  margin-left: auto;
  color: var(--ua-muted);
  font-size: 11px;
}

.agent-steps ol {
  display: grid;
  gap: 8px;
  max-height: 260px;
  margin: 0;
  padding: 10px 13px 12px 28px;
  overflow: auto;
}

.agent-steps li {
  padding-left: 4px;
}

.agent-steps li.failed strong {
  color: var(--ua-danger);
}

.agent-steps li div {
  display: flex;
  gap: 8px;
  align-items: baseline;
  justify-content: space-between;
}

.agent-steps li strong {
  color: var(--ua-ink);
  font-size: 13px;
}

.agent-steps li small {
  flex: none;
  color: var(--ua-muted);
  font-size: 11px;
}

.agent-steps li p {
  margin: 4px 0 0;
  color: var(--ua-ink-soft);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.sources {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(210px, 100%), 1fr));
  gap: 6px;
}

.sources-heading {
  display: flex;
  grid-column: 1 / -1;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px 3px;
  color: var(--ua-ink-soft);
  font-size: 12px;
  font-weight: 700;
}

.sources-heading::before {
  content: '';
  width: 18px;
  height: 2px;
  margin-right: 7px;
  background: var(--ua-sakura);
}

.sources-heading span {
  margin-right: auto;
}

.sources-heading small {
  color: var(--ua-muted);
  font-size: 10px;
  font-weight: 600;
}

.sources a {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  color: var(--ua-primary-strong);
  background: var(--ua-panel-soft);
  font-size: 12px;
  text-decoration: none;
  user-select: text;
  transition:
    border-color 160ms ease,
    background 160ms ease,
    transform 160ms ease;
}

.sources a small {
  color: var(--ua-muted);
  font-size: 11px;
}

.sources a:hover {
  border-color: var(--ua-primary);
  background: var(--ua-primary-soft);
  transform: translateY(-1px);
}

.status-block,
.error-block {
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
}

.status-block {
  color: var(--ua-ink-soft);
  background: var(--ua-bg-deep);
}

.error-block {
  color: var(--ua-danger);
  background: var(--ua-danger-soft);
}
</style>
