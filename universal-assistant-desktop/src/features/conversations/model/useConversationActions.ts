import { nextTick } from 'vue'
import { storeToRefs } from 'pinia'
import {
  deleteConversation,
  listConversations,
  loadConversationMessages,
  updateConversation,
  type ConversationSummary,
} from '../../../services/assistantApi'
import { useChatSessionStore } from '../../chat/stores/chatSessionStore'
import { useConversationStore } from '../stores/conversationStore'

export function useConversationActions(options: {
  resetEditing: () => void
  forceScrollMessagesToBottom: () => void
  scrollMessagesToBottom: () => void
}) {
  const chatStore = useChatSessionStore()
  const conversationStore = useConversationStore()
  const { conversations, conversationPanelOpen, conversationsLoading, renamingConversationId, renamingTitle } =
    storeToRefs(conversationStore)
  const { currentConversationId, draft, errorText, isSending, currentPhase } = storeToRefs(chatStore)

  let conversationRetryTimer: number | undefined
  let conversationRetryDelay = 2000

  function clearConversationRetry() {
    window.clearTimeout(conversationRetryTimer)
    conversationRetryTimer = undefined
    conversationRetryDelay = 2000
  }

  function scheduleConversationRetry() {
    if (conversationRetryTimer !== undefined) {
      return
    }

    const delay = conversationRetryDelay
    conversationRetryDelay = Math.min(Math.round(conversationRetryDelay * 1.8), 10000)
    conversationRetryTimer = window.setTimeout(async () => {
      conversationRetryTimer = undefined
      const loaded = await refreshConversations({ retryOnFailure: true })
      if (loaded && !currentConversationId.value && conversations.value.length > 0) {
        await openConversation(conversations.value[0])
      }
    }, delay)
  }

  async function refreshConversations(optionsArg: { retryOnFailure?: boolean } = {}) {
    conversationsLoading.value = true
    try {
      conversations.value = await listConversations()
      clearConversationRetry()
      if (errorText.value.includes('历史会话加载失败')) {
        errorText.value = ''
      }
      return true
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      errorText.value = `历史会话加载失败：${message}。后端恢复后会自动重试。`
      if (optionsArg.retryOnFailure) {
        scheduleConversationRetry()
      }
      return false
    } finally {
      conversationsLoading.value = false
    }
  }

  function startNewConversation() {
    chatStore.resetSession()
    options.resetEditing()
    conversationPanelOpen.value = false
    options.forceScrollMessagesToBottom()
  }

  function createNewConversation() {
    startNewConversation()
  }

  async function openConversation(conversation: ConversationSummary) {
    if (isSending.value) {
      return
    }

    try {
      errorText.value = ''
      const response = await loadConversationMessages(conversation.id)
      chatStore.setConversationMessages(response.conversationId, response.messages)
      options.resetEditing()
      draft.value = ''
      conversationPanelOpen.value = false
      currentPhase.value = 'idle'
      options.forceScrollMessagesToBottom()
    } catch (error) {
      errorText.value = error instanceof Error ? error.message : String(error)
    }
  }

  async function refreshCurrentConversationMessages() {
    if (!currentConversationId.value) {
      return
    }

    const response = await loadConversationMessages(currentConversationId.value)
    chatStore.setConversationMessages(response.conversationId, response.messages)
    options.scrollMessagesToBottom()
  }

  async function startRenameConversation(conversation: ConversationSummary) {
    if (isSending.value) {
      return
    }

    renamingConversationId.value = conversation.id
    renamingTitle.value = conversation.title

    await nextTick()
    const input = document.querySelector<HTMLInputElement>(`[data-rename-conversation-id="${conversation.id}"]`)
    input?.focus()
    input?.select()
  }

  function cancelRenameConversation() {
    conversationStore.cancelRename()
  }

  async function submitRenameConversation(conversation: ConversationSummary) {
    if (isSending.value) {
      return
    }

    const nextTitle = renamingTitle.value.trim()
    if (!nextTitle) {
      return
    }

    if (nextTitle === conversation.title) {
      cancelRenameConversation()
      return
    }

    try {
      const updated = await updateConversation(conversation.id, nextTitle)
      conversations.value = conversations.value.map((item) => (item.id === updated.id ? updated : item))
      cancelRenameConversation()
    } catch (error) {
      errorText.value = error instanceof Error ? error.message : String(error)
    }
  }

  async function removeConversation(conversation: ConversationSummary) {
    if (isSending.value) {
      return
    }

    const confirmed = window.confirm(`确认删除会话「${conversation.title}」？删除后不会出现在历史列表中。`)
    if (!confirmed) {
      return
    }

    try {
      await deleteConversation(conversation.id)
      conversations.value = conversations.value.filter((item) => item.id !== conversation.id)
      if (renamingConversationId.value === conversation.id) {
        cancelRenameConversation()
      }
      if (currentConversationId.value === conversation.id) {
        startNewConversation()
      }
    } catch (error) {
      errorText.value = error instanceof Error ? error.message : String(error)
    }
  }

  function disposeConversationActions() {
    clearConversationRetry()
  }

  return {
    refreshConversations,
    startNewConversation,
    createNewConversation,
    openConversation,
    refreshCurrentConversationMessages,
    startRenameConversation,
    cancelRenameConversation,
    submitRenameConversation,
    removeConversation,
    disposeConversationActions,
  }
}
