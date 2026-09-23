<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { modelLabel, modelOptions } from '../features/chat/model/chatTypes'
import { useChatStreaming } from '../features/chat/model/useChatStreaming'
import { useChatSessionStore } from '../features/chat/stores/chatSessionStore'
import { useMessageEditingStore } from '../features/chat/stores/messageEditingStore'
import { useConversationActions } from '../features/conversations/model/useConversationActions'
import { useConversationStore } from '../features/conversations/stores/conversationStore'
import { useFileActions } from '../features/files/model/useFileActions'
import { useFileStore } from '../features/files/stores/fileStore'
import ChatComposer from '../widgets/chat-panel/ChatComposer.vue'
import ChatHeader from '../widgets/chat-panel/ChatHeader.vue'
import ConversationSidebar from '../widgets/chat-panel/ConversationSidebar.vue'
import FilePanel from '../widgets/chat-panel/FilePanel.vue'
import MessageList from '../widgets/chat-panel/MessageList.vue'

const chatStore = useChatSessionStore()
const messageEditingStore = useMessageEditingStore()
const conversationStore = useConversationStore()
const fileStore = useFileStore()
const {
  messages,
  currentConversationId,
  draft,
  realtimeSearch,
  selectedModel,
  isSending,
  currentPhase,
  errorText,
  assistantStatus,
} = storeToRefs(chatStore)
const { editingMessageId, isEditingDraft, draftPlaceholder } = storeToRefs(messageEditingStore)
const { conversations, conversationPanelOpen, conversationsLoading, renamingConversationId, renamingTitle } =
  storeToRefs(conversationStore)
const { filePanelOpen, selectedFilePath, fileContent, fileDirty, targetFormat, showFilePanel, selectedFileName } =
  storeToRefs(fileStore)
const { canEditUserMessage } = chatStore
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)
const messageListRef = ref<InstanceType<typeof MessageList> | null>(null)

function scrollMessagesToBottom() {
  messageListRef.value?.scrollMessagesToBottom()
}

function forceScrollMessagesToBottom() {
  messageListRef.value?.forceScrollMessagesToBottom()
}

function appendAssistantMessage(content: string) {
  chatStore.appendMessage({ role: 'assistant', content })
  forceScrollMessagesToBottom()
}

const { selectFile, readSelectedFile, saveSelectedFile, saveTextAs, convertSelectedFile } = useFileActions({
  appendAssistantMessage,
})

const isElectron = computed(() => Boolean(window.assistant))
const currentConversationTitle = computed(() => {
  if (!currentConversationId.value) {
    return '新会话'
  }

  return (
    conversations.value.find((conversation) => conversation.id === currentConversationId.value)?.title || '当前会话'
  )
})
const headerSubtitle = computed(
  () => `${assistantStatus.value} · ${modelLabel(selectedModel.value)} · ${currentConversationTitle.value}`,
)

function resetEditingState() {
  messageEditingStore.clearEditing()
}

const {
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
} = useConversationActions({
  resetEditing: resetEditingState,
  forceScrollMessagesToBottom,
  scrollMessagesToBottom,
})

const {
  send,
  stopGeneration,
  startEditMessage,
  cancelEditMessage,
  handleToolDecision,
  hideChat,
  disposeChatStreaming,
} = useChatStreaming({
  scrollMessagesToBottom,
  forceScrollMessagesToBottom,
  refreshConversations,
  refreshCurrentConversationMessages,
  focusDraftToEnd: () => composerRef.value?.focusDraftToEnd(),
})

onMounted(async () => {
  await refreshConversations({ retryOnFailure: true })
  if (!currentConversationId.value && conversations.value.length > 0) {
    await openConversation(conversations.value[0])
  }
})

onUnmounted(() => {
  disposeConversationActions()
  disposeChatStreaming()
})
</script>

<template>
  <main class="chat-window" :class="{ 'has-conversations': conversationPanelOpen }">
    <ChatHeader
      :subtitle="headerSubtitle"
      :status-text="assistantStatus"
      :is-sending="isSending"
      :current-phase="currentPhase"
      :conversation-panel-open="conversationPanelOpen"
      @new-conversation="createNewConversation"
      @toggle-history="conversationPanelOpen = !conversationPanelOpen"
      @close="hideChat"
    />

    <ConversationSidebar
      v-if="conversationPanelOpen"
      v-model:renaming-title="renamingTitle"
      :conversations="conversations"
      :current-conversation-id="currentConversationId"
      :is-sending="isSending"
      :conversations-loading="conversationsLoading"
      :renaming-conversation-id="renamingConversationId"
      :error-text="errorText"
      @start-new="startNewConversation"
      @open="openConversation"
      @start-rename="startRenameConversation"
      @submit-rename="submitRenameConversation"
      @cancel-rename="cancelRenameConversation"
      @remove="removeConversation"
    />

    <MessageList
      ref="messageListRef"
      :messages="messages"
      :is-sending="isSending"
      :editing-message-id="editingMessageId"
      :can-edit-user-message="canEditUserMessage"
      @edit="startEditMessage"
      @tool-decision="handleToolDecision"
    />

    <FilePanel
      v-if="showFilePanel"
      v-model:file-content="fileContent"
      v-model:file-dirty="fileDirty"
      v-model:target-format="targetFormat"
      :is-electron="isElectron"
      :selected-file-name="selectedFileName"
      :selected-file-path="selectedFilePath"
      :error-text="errorText"
      @close="filePanelOpen = false"
      @select-file="selectFile"
      @read-file="readSelectedFile"
      @convert-file="convertSelectedFile"
      @save-file="saveSelectedFile"
      @save-as="saveTextAs"
    />

    <ChatComposer
      ref="composerRef"
      v-model:draft="draft"
      v-model:realtime-search="realtimeSearch"
      v-model:selected-model="selectedModel"
      :is-sending="isSending"
      :show-file-panel="showFilePanel"
      :is-editing-draft="isEditingDraft"
      :placeholder="draftPlaceholder"
      :model-options="modelOptions"
      @toggle-file-panel="filePanelOpen = !filePanelOpen"
      @cancel-edit="cancelEditMessage"
      @submit="send"
      @stop="stopGeneration"
    />
  </main>
</template>

<style scoped>
.chat-window {
  position: relative;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  color: #172033;
  background: #f5f7fb;
  user-select: none;
}

.chat-window.has-conversations {
  grid-template-rows: auto auto minmax(0, 1fr) auto auto;
}
</style>
