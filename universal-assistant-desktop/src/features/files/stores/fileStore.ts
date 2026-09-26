import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useFileStore = defineStore('file', () => {
  const filePanelOpen = ref(false)
  const selectedFilePath = ref('')
  const fileContent = ref('')
  const fileDirty = ref(false)
  const fileErrorText = ref('')
  const targetFormat = ref('md')

  const showFilePanel = computed(() => filePanelOpen.value)
  const selectedFileName = computed(() => {
    if (!selectedFilePath.value) {
      return ''
    }

    return selectedFilePath.value.split(/[\\/]/).pop() || selectedFilePath.value
  })

  function resetFileContent() {
    fileContent.value = ''
    fileDirty.value = false
  }

  return {
    filePanelOpen,
    selectedFilePath,
    fileContent,
    fileDirty,
    fileErrorText,
    targetFormat,
    showFilePanel,
    selectedFileName,
    resetFileContent,
  }
})
