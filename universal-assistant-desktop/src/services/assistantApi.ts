export type ChatMessage = {
  role: 'user' | 'assistant' | 'system'
  content: string
}

export type SearchResult = {
  title: string
  url: string
  snippet: string
  provider?: string
}

export type ChatRequest = {
  message: string
  realtimeSearch?: boolean
  model?: string
  history?: ChatMessage[]
  conversationId?: string
}

export type ChatResponse = {
  answer: string
  realtimeSearchUsed: boolean
  modelAvailable: boolean
  model?: string
  sources: SearchResult[]
  conversationId?: string
  messageId?: string
  agentRunId?: string
  agentSteps?: AgentStep[]
}

export type ChatStreamPhase = 'thinking' | 'planning' | 'acting' | 'searching' | 'reflecting' | 'answering' | 'done' | 'error'

export type AgentStep = {
  id: string
  runId: string
  index: number
  type: string
  title: string
  content?: string
  status: string
  createdAt?: string
}

export type ChatStreamEvent = {
  type: 'status' | 'meta' | 'agent_step' | 'delta' | 'done' | 'error'
  phase?: ChatStreamPhase
  message?: string
  content?: string
  realtimeSearchUsed?: boolean
  modelAvailable?: boolean
  model?: string
  sources?: SearchResult[]
  conversationId?: string
  messageId?: string
  agentRunId?: string
  agentStep?: AgentStep
}

export type ChatStreamHandlers = {
  onEvent: (event: ChatStreamEvent) => void
}

export type ConversationSummary = {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export type ConversationMessage = {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  model?: string
  realtimeSearchUsed?: boolean
  modelAvailable?: boolean
  sources?: SearchResult[]
  createdAt: string
}

export type ConversationMessagesResponse = {
  conversationId: string
  messages: ConversationMessage[]
}

let cachedBackendUrl: string | null = null

export async function getBackendUrl() {
  if (cachedBackendUrl) {
    return cachedBackendUrl
  }

  cachedBackendUrl = window.assistant ? await window.assistant.getBackendUrl() : 'http://localhost:8080'
  return cachedBackendUrl
}

export async function sendChat(request: ChatRequest) {
  const backendUrl = await getBackendUrl()
  const response = await fetch(`${backendUrl}/api/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    throw new Error(`Backend returned ${response.status}`)
  }

  return (await response.json()) as ChatResponse
}

export async function listConversations() {
  const backendUrl = await getBackendUrl()
  const response = await fetch(`${backendUrl}/api/conversations`)

  if (!response.ok) {
    throw new Error(`Backend returned ${response.status}`)
  }

  return (await response.json()) as ConversationSummary[]
}

export async function createConversation(title?: string) {
  const backendUrl = await getBackendUrl()
  const response = await fetch(`${backendUrl}/api/conversations`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ title }),
  })

  if (!response.ok) {
    throw new Error(`Backend returned ${response.status}`)
  }

  return (await response.json()) as ConversationSummary
}

export async function loadConversationMessages(conversationId: string) {
  const backendUrl = await getBackendUrl()
  const response = await fetch(`${backendUrl}/api/conversations/${conversationId}/messages`)

  if (!response.ok) {
    throw new Error(`Backend returned ${response.status}`)
  }

  return (await response.json()) as ConversationMessagesResponse
}

export async function streamChat(request: ChatRequest, handlers: ChatStreamHandlers) {
  const backendUrl = await getBackendUrl()
  const response = await fetch(`${backendUrl}/api/chat/stream`, {
    method: 'POST',
    headers: {
      Accept: 'application/x-ndjson, application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    throw new Error(`Backend returned ${response.status}`)
  }

  if (!response.body) {
    throw new Error('Backend did not provide a stream body')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const consumeLine = (line: string) => {
    const trimmed = line.trim()
    if (!trimmed) {
      return
    }

    handlers.onEvent(JSON.parse(trimmed) as ChatStreamEvent)
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) {
      consumeLine(line)
    }
  }

  buffer += decoder.decode()
  consumeLine(buffer)
}
