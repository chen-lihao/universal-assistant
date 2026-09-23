import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { ConversationSummary } from '../../../services/assistantApi'

export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref<ConversationSummary[]>([])
  const conversationPanelOpen = ref(false)
  const conversationsLoading = ref(false)
  const renamingConversationId = ref<string>()
  const renamingTitle = ref('')

  function cancelRename() {
    renamingConversationId.value = undefined
    renamingTitle.value = ''
  }

  return {
    conversations,
    conversationPanelOpen,
    conversationsLoading,
    renamingConversationId,
    renamingTitle,
    cancelRename,
  }
})
