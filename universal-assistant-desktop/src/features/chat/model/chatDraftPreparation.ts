import type { Ref } from 'vue'
import { isSupportedModel, modelLabel, modelOptions, type UiMessage } from './chatTypes'

export type PreparedChatDraft = {
  message: string
  model: string
}

type DraftPreparationOptions = {
  selectedModel: Ref<string>
  appendAssistantMessage: (message: Omit<UiMessage, 'id'>) => UiMessage
}

function unsupportedModelMessage(model: string) {
  return `暂不支持模型 ${model}。当前可选模型：${modelOptions.map((option) => option.value).join('、')}。`
}

export function prepareChatDraft(text: string, options: DraftPreparationOptions): PreparedChatDraft | null {
  const modelCommand = text.match(/^\/models?\s+([a-zA-Z0-9_.-]+)(?:\s+([\s\S]*))?$/i)
  if (modelCommand) {
    const model = modelCommand[1]
    if (!isSupportedModel(model)) {
      options.appendAssistantMessage({
        role: 'assistant',
        content: unsupportedModelMessage(model),
        modelAvailable: false,
      })
      return null
    }

    options.selectedModel.value = model
    const commandMessage = (modelCommand[2] || '').trim()
    if (!commandMessage) {
      options.appendAssistantMessage({ role: 'assistant', content: `已切换模型为 ${modelLabel(model)}。`, model })
      return null
    }

    return { message: commandMessage, model }
  }

  const modelMention = text.match(/^@([a-zA-Z0-9_.-]+)\s+([\s\S]+)$/i)
  if (modelMention) {
    const model = modelMention[1]
    if (!isSupportedModel(model)) {
      options.appendAssistantMessage({
        role: 'assistant',
        content: unsupportedModelMessage(model),
        modelAvailable: false,
      })
      return null
    }

    options.selectedModel.value = model
    return { message: modelMention[2].trim(), model }
  }

  return { message: text, model: options.selectedModel.value }
}
