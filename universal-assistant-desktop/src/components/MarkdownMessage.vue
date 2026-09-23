<script setup lang="ts">
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  content: string
}>()

const markdown = new MarkdownIt({
  breaks: true,
  html: true,
  linkify: true,
}).enable('table')

function isTableLine(line: string) {
  const trimmed = line.trim()
  return trimmed.startsWith('|') && trimmed.endsWith('|') && (trimmed.match(/\|/g)?.length || 0) >= 2
}

function isTableSeparator(line: string) {
  return /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$/.test(line.trim())
}

function tableColumnCount(line: string) {
  const trimmed = line.trim()
  if (!trimmed.includes('|')) {
    return 0
  }

  const normalized = trimmed.startsWith('|') ? trimmed : `|${trimmed}`
  const cells = normalized.split('|').slice(1, normalized.endsWith('|') ? -1 : undefined)
  return cells.length
}

function splitAttachedTableHeader(line: string, separatorLine: string) {
  const trimmed = line.trimEnd()
  if (!trimmed.endsWith('|') || trimmed.trimStart().startsWith('|')) {
    return null
  }

  const separatorColumns = tableColumnCount(separatorLine)
  const pipeIndexes = [...trimmed.matchAll(/\|/g)].map((match) => match.index || 0)
  for (const pipeIndex of pipeIndexes) {
    const title = trimmed.slice(0, pipeIndex).trimEnd()
    const header = trimmed.slice(pipeIndex).trim()
    if (title && isTableLine(header) && tableColumnCount(header) === separatorColumns) {
      return { title, header }
    }
  }

  return null
}

function isHorizontalRule(line: string) {
  return /^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$/.test(line)
}

function normalizeMarkdown(content: string) {
  const normalizedLines: string[] = []
  const lines = (content || '').replace(/\r\n/g, '\n').split('\n')
  let inFence = false

  const pushBlankIfNeeded = () => {
    if (normalizedLines.length > 0 && normalizedLines[normalizedLines.length - 1].trim()) {
      normalizedLines.push('')
    }
  }

  const previousLine = () => {
    for (let i = normalizedLines.length - 1; i >= 0; i -= 1) {
      if (normalizedLines[i].trim()) {
        return normalizedLines[i]
      }
    }
    return ''
  }

  for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
    const rawLine = lines[lineIndex]
    let line = rawLine
    if (/^\s*```/.test(line) || /^\s*~~~/.test(line)) {
      inFence = !inFence
      normalizedLines.push(line)
      continue
    }

    if (!inFence) {
      const headingTable = line.match(/^(#{1,6}\s+.*?)(\s+\|[^|]+(?:\|[^|]+)+\|\s*)$/)
      if (headingTable) {
        normalizedLines.push(headingTable[1].trimEnd())
        normalizedLines.push('')
        line = headingTable[2].trimStart()
      }

      if (isTableSeparator(line) && normalizedLines.length > 0) {
        const previousIndex = normalizedLines.length - 1
        const attachedHeader = splitAttachedTableHeader(normalizedLines[previousIndex], line)
        if (attachedHeader) {
          normalizedLines[previousIndex] = attachedHeader.title
          pushBlankIfNeeded()
          normalizedLines.push(attachedHeader.header)
        }
      }

      if (isHorizontalRule(line)) {
        pushBlankIfNeeded()
      } else if (
        (isTableLine(line) || isTableSeparator(line)) &&
        !isTableLine(previousLine()) &&
        !isTableSeparator(previousLine())
      ) {
        pushBlankIfNeeded()
      }
    }

    normalizedLines.push(line)

    if (!inFence && isHorizontalRule(line)) {
      normalizedLines.push('')
    }
  }

  return normalizedLines.join('\n').replace(/\n{3,}/g, '\n\n')
}

const renderedContent = computed(() => DOMPurify.sanitize(markdown.render(normalizeMarkdown(props.content || ''))))
</script>

<template>
  <div class="markdown-message" v-html="renderedContent" />
</template>

<style scoped>
.markdown-message {
  overflow-wrap: anywhere;
  color: inherit;
  font-size: 14px;
  line-height: 1.62;
  user-select: text;
}

.markdown-message :deep(*) {
  margin-top: 0;
}

.markdown-message :deep(*:last-child) {
  margin-bottom: 0;
}

.markdown-message :deep(p) {
  margin: 0 0 9px;
}

.markdown-message :deep(h1),
.markdown-message :deep(h2),
.markdown-message :deep(h3) {
  margin: 12px 0 7px;
  color: #111827;
  font-weight: 700;
  line-height: 1.28;
}

.markdown-message :deep(h1) {
  font-size: 20px;
}

.markdown-message :deep(h2) {
  font-size: 17px;
}

.markdown-message :deep(h3) {
  font-size: 15px;
}

.markdown-message :deep(ul),
.markdown-message :deep(ol) {
  margin: 7px 0 10px;
  padding-left: 22px;
}

.markdown-message :deep(li) {
  margin: 4px 0;
}

.markdown-message :deep(a) {
  color: #2563eb;
  text-decoration: none;
}

.markdown-message :deep(a:hover) {
  text-decoration: underline;
}

.markdown-message :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 12px;
  border-left: 3px solid rgba(20, 184, 166, 0.42);
  border-radius: 0 8px 8px 0;
  color: #475569;
  background: rgba(20, 184, 166, 0.08);
}

.markdown-message :deep(code) {
  padding: 2px 5px;
  border-radius: 6px;
  color: #0f172a;
  background: #eef2f8;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
  font-size: 0.92em;
}

.markdown-message :deep(pre) {
  overflow-x: auto;
  margin: 10px 0;
  padding: 11px 12px;
  border: 1px solid rgba(103, 119, 150, 0.16);
  border-radius: 10px;
  background: #0f172a;
}

.markdown-message :deep(pre code) {
  display: block;
  padding: 0;
  color: #e5e7eb;
  background: transparent;
  white-space: pre;
}

.markdown-message :deep(table) {
  display: block;
  overflow-x: auto;
  width: 100%;
  min-width: min(520px, 100%);
  margin: 10px 0;
  border-collapse: collapse;
  border-spacing: 0;
  white-space: normal;
}

.markdown-message :deep(th),
.markdown-message :deep(td) {
  padding: 7px 9px;
  border: 1px solid rgba(103, 119, 150, 0.2);
  text-align: left;
  vertical-align: top;
}

.markdown-message :deep(th) {
  background: #f8fafc;
  font-weight: 700;
}

.markdown-message :deep(tbody tr:nth-child(even)) {
  background: rgba(248, 250, 252, 0.75);
}
</style>
