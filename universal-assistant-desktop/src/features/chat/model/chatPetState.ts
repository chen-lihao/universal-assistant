export function createChatPetStateController() {
  let petIdleTimer: number | undefined

  function setPetState(state: PetState) {
    void window.assistant?.setPetState(state)
  }

  function schedulePetIdle(delay = 1400) {
    window.clearTimeout(petIdleTimer)
    petIdleTimer = window.setTimeout(() => setPetState('idle'), delay)
  }

  function markPetSpeaking(delay = 1400) {
    setPetState('speaking')
    if (delay > 0) {
      schedulePetIdle(delay)
    }
  }

  function markPetError() {
    setPetState('error')
    schedulePetIdle(2200)
  }

  async function hideChat() {
    setPetState('idle')
    await window.assistant?.hideChat()
  }

  function dispose() {
    window.clearTimeout(petIdleTimer)
  }

  return {
    setPetState,
    schedulePetIdle,
    markPetSpeaking,
    markPetError,
    hideChat,
    dispose,
  }
}
