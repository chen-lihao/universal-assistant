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
  gap: 10px;
}

.agent-steps {
  overflow: hidden;
  border: 1px solid rgba(20, 184, 166, 0.25);
  border-radius: 12px;
  color: #334155;
  background: rgba(240, 253, 250, 0.78);
}

.agent-steps summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-bottom: 1px solid rgba(20, 184, 166, 0.16);
  color: #0f766e;
  cursor: default;
  list-style: none;
}

.agent-steps summary::-webkit-details-marker {
  display: none;
}

.agent-steps summary span {
  font-weight: 700;
}

.agent-steps summary small {
  margin-left: auto;
  color: #64748b;
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
  color: #dc2626;
}

.agent-steps li div {
  display: flex;
  gap: 8px;
  align-items: baseline;
  justify-content: space-between;
}

.agent-steps li strong {
  color: #1f2937;
  font-size: 13px;
}

.agent-steps li small {
  flex: none;
  color: #64748b;
  font-size: 11px;
}

.agent-steps li p {
  margin: 4px 0 0;
  color: #475569;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.sources {
  display: grid;
  gap: 4px;
}

.sources a {
  display: grid;
  gap: 2px;
  color: #2563eb;
  font-size: 12px;
  text-decoration: none;
  user-select: text;
}

.sources a small {
  color: #64748b;
  font-size: 11px;
}

.sources a:hover {
  text-decoration: underline;
}

.status-block,
.error-block {
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 12px;
}

.status-block {
  color: #475569;
  background: #f1f5f9;
}

.error-block {
  color: #991b1b;
  background: #fef2f2;
}
</style>
