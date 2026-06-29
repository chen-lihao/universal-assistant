<script setup lang="ts">
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  content: string
}>()

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true,
})

const renderedContent = computed(() => DOMPurify.sanitize(markdown.render(props.content || '')))
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
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
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
  margin: 10px 0;
  border-collapse: collapse;
}

.markdown-message :deep(th),
.markdown-message :deep(td) {
  padding: 7px 9px;
  border: 1px solid rgba(103, 119, 150, 0.2);
}

.markdown-message :deep(th) {
  background: #f8fafc;
  font-weight: 700;
}
</style>
