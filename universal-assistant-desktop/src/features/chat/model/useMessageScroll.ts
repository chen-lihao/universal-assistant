import { nextTick, ref } from 'vue'

export function useMessageScroll(threshold = 110) {
  const messageListRef = ref<HTMLElement | null>(null)
  const shouldAutoFollowMessages = ref(true)
  const showScrollToBottom = ref(false)

  function scrollMessagesToBottom() {
    void nextTick(() => {
      const list = messageListRef.value
      if (!list || (!shouldAutoFollowMessages.value && showScrollToBottom.value)) {
        return
      }

      list.scrollTop = list.scrollHeight
      updateMessageScrollState()
    })
  }

  function forceScrollMessagesToBottom() {
    shouldAutoFollowMessages.value = true
    showScrollToBottom.value = false
    void nextTick(() => {
      const list = messageListRef.value
      if (!list) {
        return
      }

      list.scrollTop = list.scrollHeight
      updateMessageScrollState()
    })
  }

  function isNearMessageBottom(list: HTMLElement) {
    return list.scrollHeight - list.scrollTop - list.clientHeight <= threshold
  }

  function updateMessageScrollState() {
    const list = messageListRef.value
    if (!list) {
      shouldAutoFollowMessages.value = true
      showScrollToBottom.value = false
      return
    }

    const nearBottom = isNearMessageBottom(list)
    shouldAutoFollowMessages.value = nearBottom
    showScrollToBottom.value = !nearBottom
  }

  return {
    messageListRef,
    shouldAutoFollowMessages,
    showScrollToBottom,
    scrollMessagesToBottom,
    forceScrollMessagesToBottom,
    updateMessageScrollState,
  }
}
