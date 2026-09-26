<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, History, MessageSquareText, Play, Star } from '@lucide/vue'
import { storeToRefs } from 'pinia'
import MarkdownMessage from '../../components/MarkdownMessage.vue'
import { useCareerStore } from '../../features/career/stores/careerStore'

const store = useCareerStore()
const { readyForCareerTask, interview, interviewHistory, loading } = storeToRefs(store)
const mode = ref<'practice' | 'formal'>('practice')
const maxQuestions = ref(6)
const answer = ref('')

const progress = computed(() => {
  if (!interview.value) return 0
  return Math.round((interview.value.currentIndex / interview.value.maxQuestions) * 100)
})

async function submitAnswer() {
  if (!answer.value.trim()) return
  const current = answer.value
  answer.value = ''
  await store.answerInterview(current)
}

function formatStartedAt(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section class="interview-studio">
    <div v-if="!interview" class="interview-setup">
      <header>
        <MessageSquareText :size="22" />
        <div>
          <strong>岗位定向模拟面试</strong>
          <span>问题基于当前简历版本和目标岗位生成</span>
        </div>
      </header>

      <div class="mode-control" role="group" aria-label="Interview mode">
        <button type="button" :class="{ active: mode === 'practice' }" @click="mode = 'practice'">
          练习模式
          <small>逐题反馈</small>
        </button>
        <button type="button" :class="{ active: mode === 'formal' }" @click="mode = 'formal'">
          正式模式
          <small>结束后反馈</small>
        </button>
      </div>

      <label for="question-count">题目数量</label>
      <div class="question-count">
        <input id="question-count" v-model.number="maxQuestions" type="range" min="3" max="12" />
        <strong>{{ maxQuestions }} 题</strong>
      </div>

      <button
        type="button"
        class="start-button"
        :disabled="loading || !readyForCareerTask"
        @click="store.startInterview(mode, maxQuestions)"
      >
        <Play :size="15" fill="currentColor" /> 开始面试
      </button>
      <p v-if="!readyForCareerTask" class="setup-hint">请先在左侧选择简历版本和目标岗位。</p>
    </div>

    <template v-else>
      <div class="interview-progress">
        <div>
          <strong>{{ interview.mode === 'practice' ? '练习模式' : '正式模式' }}</strong>
          <span>{{ interview.currentIndex }}/{{ interview.maxQuestions }} 已完成</span>
        </div>
        <div class="progress-track"><span :style="{ width: `${progress}%` }"></span></div>
      </div>

      <div v-if="interview.currentQuestion" class="question-panel">
        <span class="question-index">Q{{ interview.currentIndex + 1 }}</span>
        <small>{{ interview.currentQuestion.category }}</small>
        <h2>{{ interview.currentQuestion.question }}</h2>
        <p>考察重点：{{ interview.currentQuestion.focus }}</p>
        <textarea
          v-model="answer"
          rows="8"
          placeholder="按真实面试作答。建议使用 STAR 结构，并明确个人行动与结果。"
          @keydown.meta.enter.prevent="submitAnswer"
          @keydown.ctrl.enter.prevent="submitAnswer"
        ></textarea>
        <div class="answer-actions">
          <span>Ctrl/Cmd + Enter 提交</span>
          <button type="button" :disabled="loading || !answer.trim()" @click="submitAnswer">
            {{ loading ? '评估中' : '提交回答' }} <ArrowRight :size="15" />
          </button>
        </div>
      </div>

      <section v-if="interview.latestFeedback" class="feedback-panel">
        <header>
          <strong>本题反馈</strong>
          <span><Star :size="14" fill="currentColor" /> {{ interview.latestFeedback.score }}/5</span>
        </header>
        <p>{{ interview.latestFeedback.summary }}</p>
        <div class="feedback-columns">
          <div>
            <small>做得好的地方</small>
            <ul>
              <li v-for="item in interview.latestFeedback.strengths" :key="item">{{ item }}</li>
            </ul>
          </div>
          <div>
            <small>优先改进</small>
            <ul>
              <li v-for="item in interview.latestFeedback.improvements" :key="item">{{ item }}</li>
            </ul>
          </div>
        </div>
        <blockquote>{{ interview.latestFeedback.exampleAnswer }}</blockquote>
      </section>

      <section v-if="interview.status === 'completed'" class="final-report">
        <header>
          <strong>面试已完成</strong>
          <button type="button" @click="interview = null">开始新一轮</button>
        </header>
        <MarkdownMessage :content="interview.finalReport || '已完成模拟面试。'" />
      </section>

      <details v-if="interview.turns.length" class="turn-history">
        <summary>查看回答记录（{{ interview.turns.length }}）</summary>
        <article v-for="turn in interview.turns" :key="turn.index">
          <strong>{{ turn.question }}</strong>
          <p>{{ turn.answer }}</p>
        </article>
      </details>
    </template>

    <details v-if="interviewHistory.length" class="interview-history">
      <summary><History :size="14" /> 历史模拟面试（{{ interviewHistory.length }}）</summary>
      <button
        v-for="item in interviewHistory"
        :key="item.id"
        type="button"
        :class="{ active: interview?.id === item.id }"
        @click="store.openInterview(item.id)"
      >
        <span>
          <strong>{{ item.company ? `${item.company} · ` : '' }}{{ item.jobTitle }}</strong>
          <small>{{ item.resumeTitle }} · {{ item.mode === 'practice' ? '练习模式' : '正式模式' }}</small>
        </span>
        <span class="interview-history-meta">
          <small>{{ item.currentIndex }}/{{ item.maxQuestions }} 题</small>
          <small>{{ item.status === 'completed' ? '已完成' : '进行中' }} · {{ formatStartedAt(item.startedAt) }}</small>
        </span>
      </button>
    </details>
  </section>
</template>

<style scoped src="./interviewStudio.css"></style>
