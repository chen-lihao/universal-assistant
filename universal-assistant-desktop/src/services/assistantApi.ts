export type ChatMessage = {
  role: 'user' | 'assistant' | 'system'
  content: string
}

export type SearchResult = {
  title: string
  url: string
  snippet: string
}

export type ChatRequest = {
  message: string
  realtimeSearch?: boolean
  model?: string
  history?: ChatMessage[]
}

export type ChatResponse = {
  answer: string
  realtimeSearchUsed: boolean
  modelAvailable: boolean
  model?: string
  sources: SearchResult[]
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
