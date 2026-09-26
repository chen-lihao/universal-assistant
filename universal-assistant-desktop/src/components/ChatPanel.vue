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
import WorkspaceRail from '../widgets/chat-panel/WorkspaceRail.vue'
import CareerWorkspace from '../widgets/career-workspace/CareerWorkspace.vue'

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
const {
  filePanelOpen,
  selectedFilePath,
  fileContent,
  fileDirty,
  fileErrorText,
  targetFormat,
  showFilePanel,
  selectedFileName,
} = storeToRefs(fileStore)
const { canEditUserMessage } = chatStore
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)
const messageListRef = ref<InstanceType<typeof MessageList> | null>(null)
const careerOpen = ref(false)

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

function useSuggestedPrompt(prompt: string) {
  draft.value = prompt
  composerRef.value?.focusDraftToEnd()
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
const headerSubtitle = computed(() =>
  careerOpen.value
    ? '求职助手 · 简历定制与模拟面试'
    : filePanelOpen.value
      ? `文件工作区${selectedFileName.value ? ` · ${selectedFileName.value}` : ''}`
      : `${assistantStatus.value} · ${modelLabel(selectedModel.value)} · ${currentConversationTitle.value}`,
)

function toggleCareerWorkspace() {
  careerOpen.value = !careerOpen.value
  if (careerOpen.value) {
    conversationPanelOpen.value = false
    filePanelOpen.value = false
  }
}

function openChatWorkspace() {
  careerOpen.value = false
  conversationPanelOpen.value = false
}

function toggleHistoryWorkspace() {
  careerOpen.value = false
  filePanelOpen.value = false
  conversationPanelOpen.value = !conversationPanelOpen.value
}

function toggleFileWorkspace() {
  careerOpen.value = false
  conversationPanelOpen.value = false
  filePanelOpen.value = !filePanelOpen.value
}

function openConversationFromSidebar(conversation: Parameters<typeof openConversation>[0]) {
  void openConversation(conversation)
  if (window.matchMedia('(max-width: 760px)').matches) {
    conversationPanelOpen.value = false
  }
}

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

function createConversationFromRail() {
  careerOpen.value = false
  conversationPanelOpen.value = false
  void createNewConversation()
}
</script>

<template>
  <main
    class="chat-window"
    :class="{
      'has-conversations': conversationPanelOpen && !careerOpen && !filePanelOpen,
      'career-mode': careerOpen,
      'file-mode': filePanelOpen && !careerOpen,
    }"
  >
    <ChatHeader
      :subtitle="headerSubtitle"
      :status-text="assistantStatus"
      :is-sending="isSending"
      :current-phase="currentPhase"
      @close="hideChat"
    />

    <WorkspaceRail
      :career-open="careerOpen"
      :history-open="conversationPanelOpen"
      :file-open="filePanelOpen"
      :is-sending="isSending"
      @chat="openChatWorkspace"
      @history="toggleHistoryWorkspace"
      @career="toggleCareerWorkspace"
      @files="toggleFileWorkspace"
      @new-conversation="createConversationFromRail"
    />

    <ConversationSidebar
      v-if="conversationPanelOpen && !careerOpen"
      v-model:renaming-title="renamingTitle"
      :conversations="conversations"
      :current-conversation-id="currentConversationId"
      :is-sending="isSending"
      :conversations-loading="conversationsLoading"
      :renaming-conversation-id="renamingConversationId"
      :error-text="errorText"
      @start-new="startNewConversation"
      @open="openConversationFromSidebar"
      @start-rename="startRenameConversation"
      @submit-rename="submitRenameConversation"
      @cancel-rename="cancelRenameConversation"
      @remove="removeConversation"
    />

    <button
      v-if="conversationPanelOpen && !careerOpen && !filePanelOpen"
      class="sidebar-scrim"
      type="button"
      aria-label="关闭历史会话"
      @click="conversationPanelOpen = false"
    />

    <CareerWorkspace v-if="careerOpen" />

    <FilePanel
      v-else-if="showFilePanel"
      v-model:file-content="fileContent"
      v-model:file-dirty="fileDirty"
      v-model:target-format="targetFormat"
      :is-electron="isElectron"
      :selected-file-name="selectedFileName"
      :selected-file-path="selectedFilePath"
      :error-text="fileErrorText"
      @close="filePanelOpen = false"
      @select-file="selectFile"
      @read-file="readSelectedFile"
      @convert-file="convertSelectedFile"
      @save-file="saveSelectedFile"
      @save-as="saveTextAs"
    />

    <MessageList
      v-else
      ref="messageListRef"
      :messages="messages"
      :is-sending="isSending"
      :editing-message-id="editingMessageId"
      :can-edit-user-message="canEditUserMessage"
      @edit="startEditMessage"
      @tool-decision="handleToolDecision"
      @suggest="useSuggestedPrompt"
    />

    <ChatComposer
      v-if="!careerOpen && !filePanelOpen"
      ref="composerRef"
      v-model:draft="draft"
      v-model:realtime-search="realtimeSearch"
      v-model:selected-model="selectedModel"
      :is-sending="isSending"
      :is-editing-draft="isEditingDraft"
      :placeholder="draftPlaceholder"
      :model-options="modelOptions"
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
  grid-template-columns: 54px minmax(0, 1fr);
  grid-template-rows: auto minmax(0, 1fr) auto;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  isolation: isolate;
  color: var(--ua-ink);
  background: var(--ua-bg);
  user-select: none;
}

.chat-window::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  z-index: 8;
  width: 2px;
  background: var(--ua-companion);
  pointer-events: none;
}

.chat-window > :deep(.chat-header) {
  grid-column: 1 / -1;
  grid-row: 1;
}

.chat-window > :deep(.workspace-rail) {
  grid-column: 1;
  grid-row: 2 / 4;
}

.chat-window.has-conversations {
  grid-template-columns: 54px minmax(210px, 248px) minmax(0, 1fr);
}

.chat-window > :deep(.message-list),
.chat-window > :deep(.career-workspace),
.chat-window > :deep(.file-panel) {
  grid-column: 2 / -1;
  grid-row: 2;
  min-width: 0;
}

.chat-window > :deep(.composer) {
  grid-column: 2 / -1;
  grid-row: 3;
  min-width: 0;
}

.chat-window.has-conversations > :deep(.message-list),
.chat-window.has-conversations > :deep(.composer) {
  grid-column: 3;
}

.chat-window > :deep(.conversation-panel) {
  grid-column: 2;
  grid-row: 2 / 4;
  min-height: 0;
}

.chat-window > :deep(.file-panel) {
  min-height: 0;
}

.sidebar-scrim {
  display: none;
}

@media (max-width: 760px) {
  .chat-window.has-conversations {
    grid-template-columns: 54px minmax(0, 1fr);
  }

  .chat-window.has-conversations > :deep(.message-list),
  .chat-window.has-conversations > :deep(.composer) {
    grid-column: 2;
  }

  .chat-window > :deep(.conversation-panel) {
    position: absolute;
    z-index: 11;
    top: 56px;
    bottom: 0;
    left: 54px;
    width: min(300px, calc(100vw - 70px));
    box-shadow: var(--ua-shadow-md);
  }

  .sidebar-scrim {
    position: absolute;
    z-index: 10;
    top: 56px;
    right: 0;
    bottom: 0;
    left: 54px;
    display: block;
    padding: 0;
    border: 0;
    background: rgba(32, 43, 58, 0.16);
  }
}
</style>
