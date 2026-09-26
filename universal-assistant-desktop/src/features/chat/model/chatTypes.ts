import type {
  AgentStep,
  ChatMessage,
  ChatStreamPhase,
  MessageBlock,
  SearchResult,
} from '../../../services/assistantApi'

export type UiMessage = ChatMessage & {
  id: number | string
  messageId?: string
  sources?: SearchResult[]
  contentBlocks?: MessageBlock[]
  realtimeSearchUsed?: boolean
  modelAvailable?: boolean
  model?: string
  agentRunId?: string
  agentSteps?: AgentStep[]
  status?: string
  revision?: number
  editedAt?: string
  localEditable?: boolean
  pendingTool?: {
    name: string
    input: string
    reason: string
  }
  activeMarkdownBlockId?: string
  phase?: ChatStreamPhase
  isStreaming?: boolean
}

export type UiPhase = ChatStreamPhase | 'idle'

export const DEFAULT_MODEL = 'deepseek-v4-pro'

export const modelOptions = [
  { value: 'deepseek-v4-pro', label: 'DeepSeek V4 Pro' },
  { value: 'deepseek-v4-flash', label: 'DeepSeek V4 Flash' },
] as const

export const phaseLabels: Record<UiPhase, string> = {
  idle: '在线',
  thinking: '思考中',
  planning: '规划中',
  acting: '调用工具中',
  searching: '检索中',
  waiting_confirmation: '等待确认',
  reflecting: '反思中',
  answering: '回答中',
  cancelled: '回答已中断',
  done: '回答完毕',
  error: '出错',
}

export function welcomeMessage(): UiMessage {
  return {
    id: 'welcome',
    role: 'assistant',
    content: '你好，今天想先处理什么？',
    modelAvailable: true,
  }
}

export function modelLabel(model?: string) {
  return modelOptions.find((option) => option.value === model)?.label || model || ''
}

export function phaseLabel(phase?: UiPhase) {
  return phase ? phaseLabels[phase] : ''
}

export function isSupportedModel(model: string) {
  return modelOptions.some((option) => option.value === model)
}

export function mergeSources(existing: SearchResult[] | undefined, incoming: SearchResult[] | undefined) {
  const merged = new Map<string, SearchResult>()
  for (const source of existing || []) {
    if (source.url) {
      merged.set(source.url, source)
    }
  }
  for (const source of incoming || []) {
    if (source.url) {
      merged.set(source.url, source)
    }
  }
  return Array.from(merged.values())
}
