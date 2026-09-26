<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { FilePenLine, MessageSquareText } from '@lucide/vue'
import { storeToRefs } from 'pinia'
import { useCareerStore } from '../../features/career/stores/careerStore'
import CareerSidebar from './CareerSidebar.vue'
import InterviewStudio from './InterviewStudio.vue'
import ResumeStudio from './ResumeStudio.vue'

const store = useCareerStore()
const { error, loading } = storeToRefs(store)
const activeView = ref<'resume' | 'interview'>('resume')

onMounted(() => {
  void initialize()
})

async function initialize() {
  await store.retryInitialize().catch(() => undefined)
}
</script>

<template>
  <section class="career-workspace">
    <CareerSidebar />
    <div class="career-main">
      <nav class="career-tabs" aria-label="Career tools">
        <button type="button" :class="{ active: activeView === 'resume' }" @click="activeView = 'resume'">
          <FilePenLine :size="15" /> 简历定制
        </button>
        <button type="button" :class="{ active: activeView === 'interview' }" @click="activeView = 'interview'">
          <MessageSquareText :size="15" /> 模拟面试
        </button>
        <span v-if="loading" class="workspace-status">处理中…</span>
      </nav>

      <div v-if="error" class="workspace-error" role="alert">
        <span>{{ error }}</span>
        <button type="button" @click="initialize">重试</button>
      </div>
      <div class="workspace-scroll">
        <ResumeStudio v-if="activeView === 'resume'" />
        <InterviewStudio v-else />
      </div>
    </div>
  </section>
</template>

<style scoped>
.career-workspace {
  display: grid;
  grid-template-columns: minmax(210px, 232px) minmax(0, 1fr);
  min-height: 0;
  background: var(--ua-bg);
}

.career-main {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
}

.career-tabs {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 48px;
  padding: 0 clamp(12px, 2vw, 22px);
  border-bottom: 1px solid var(--ua-border);
  background: var(--ua-panel);
}

.career-tabs::after {
  content: none;
}

.career-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 48px;
  padding: 0 14px;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--ua-muted);
  background: transparent;
  font: inherit;
  font-size: 13px;
}

.career-tabs button.active {
  border-bottom-color: var(--ua-companion);
  color: var(--ua-companion-strong);
  font-weight: 700;
}

.workspace-status {
  margin-left: auto;
  color: var(--ua-sun);
  font-size: 11px;
}

.workspace-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 18px;
  border-bottom: 1px solid rgba(229, 72, 77, 0.24);
  color: var(--ua-danger);
  background: var(--ua-danger-soft);
  font-size: 11px;
}

.workspace-error button {
  padding: 3px 9px;
  border: 1px solid rgba(229, 72, 77, 0.24);
  border-radius: 6px;
  color: var(--ua-danger);
  background: var(--ua-panel);
  font: inherit;
}

.workspace-scroll {
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

@media (max-width: 740px) {
  .career-workspace {
    grid-template-rows: auto minmax(0, 1fr);
    grid-template-columns: 1fr;
    overflow: hidden;
  }

  .career-workspace :deep(.career-sidebar) {
    border-right: 0;
    border-bottom: 1px solid var(--ua-border);
  }

  .career-main {
    min-height: 0;
  }
}
</style>
