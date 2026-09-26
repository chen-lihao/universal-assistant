<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, FileInput, FileUp, Globe2, History, RotateCcw, ShieldCheck, Sparkles, X } from '@lucide/vue'
import { storeToRefs } from 'pinia'
import { useCareerStore } from '../../features/career/stores/careerStore'

const store = useCareerStore()
const { selectedProfileId, selectedJobTargetId, readyForCareerTask, analysis, analysisHistory, loading, selectedResume } = storeToRefs(store)
const resumeTitle = ref('基础简历')
const resumeContent = ref('')
const resumeInput = ref<HTMLInputElement | null>(null)
const resumeEditor = ref<HTMLTextAreaElement | null>(null)
const resumeFormat = ref('text')
const resumeFileName = ref('')
const resumeWarnings = ref<string[]>([])
const resumeFileError = ref('')
const evidenceHelp = ref(false)
const jobTitle = ref('')
const company = ref('')
const jobDescription = ref('')
const sourceUri = ref('')
const realtimeResearch = ref(true)

const guideSteps = computed(() => [
  { label: '创建档案', done: Boolean(selectedProfileId.value) },
  { label: '导入简历', done: Boolean(selectedResume.value) },
  { label: '添加目标岗位', done: Boolean(selectedJobTargetId.value) },
  { label: '分析并审核', done: Boolean(analysis.value) },
])

const guideHint = computed(() => {
  if (!selectedProfileId.value) return '请先在左侧创建求职档案。'
  if (!selectedResume.value) return '上传 PDF、Word 或 Markdown 简历，核对提取文本后保存。'
  if (!selectedJobTargetId.value) return '填写目标岗位与完整职位描述。'
  if (!analysis.value) return '材料已就绪，可以开始证据化匹配分析。'
  return '逐条核对建议；仅有原始简历证据的修改可以接受。'
})

const acceptedCount = computed(
  () => analysis.value?.changes.filter((change) => change.status === 'accepted' && change.evidenceVerified).length || 0,
)

async function readResumeFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  resumeFileError.value = ''
  resumeWarnings.value = []
  if (file.size > 5 * 1024 * 1024) {
    resumeFileError.value = '简历文件不能超过 5 MB。'
    input.value = ''
    return
  }
  try {
    const parsed = await store.parseResume(file)
    resumeTitle.value = parsed.fileName.replace(/\.[^.]+$/, '')
    resumeContent.value = parsed.content
    resumeFormat.value = parsed.format
    resumeFileName.value = parsed.fileName
    resumeWarnings.value = parsed.warnings
  } catch (cause) {
    resumeFileError.value = cause instanceof Error ? cause.message : '无法解析简历文件。'
  } finally {
    input.value = ''
  }
}

async function importResume() {
  try {
    await store.importResume(resumeTitle.value, resumeContent.value, resumeFormat.value)
    resumeContent.value = ''
    resumeFileName.value = ''
    resumeWarnings.value = []
    resumeFormat.value = 'text'
    evidenceHelp.value = false
  } catch {
    // The store displays the request error in the workspace banner.
  }
}

async function createTarget() {
  try {
    await store.createJobTarget({
      jobTitle: jobTitle.value,
      company: company.value,
      description: jobDescription.value,
      sourceUri: sourceUri.value,
    })
    jobDescription.value = ''
  } catch {
    // The store displays the request error in the workspace banner.
  }
}

function prepareEvidence() {
  if (!resumeContent.value.trim()) {
    resumeContent.value = selectedResume.value?.content || ''
    resumeTitle.value = `${selectedResume.value?.title || '基础简历'} - 补充证据`
    resumeFormat.value = 'text'
    resumeFileName.value = ''
  }
  evidenceHelp.value = true
  resumeEditor.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  resumeEditor.value?.focus()
}
</script>

<template>
  <section class="resume-studio">
    <div class="career-guide" aria-label="求职操作步骤">
      <ol>
        <li v-for="(step, index) in guideSteps" :key="step.label" :class="{ done: step.done, current: !step.done && guideSteps.slice(0, index).every((item) => item.done) }">
          <span>{{ step.done ? '✓' : index + 1 }}</span>{{ step.label }}
        </li>
      </ol>
      <p>{{ guideHint }}</p>
    </div>
    <div v-if="!selectedProfileId" class="empty-state">
      <FileInput :size="24" />
      <strong>先创建求职档案</strong>
      <span>档案用于隔离简历、岗位和面试证据。</span>
    </div>

    <template v-else>
      <div class="input-band">
        <section>
          <header><FileInput :size="16" /> 导入简历</header>
          <div class="inline-fields">
            <input v-model="resumeTitle" placeholder="简历标题" />
            <input ref="resumeInput" class="visually-hidden" type="file" accept=".pdf,.docx,.doc,.md,.markdown" aria-label="选择简历文件" @change="readResumeFile" />
            <button type="button" class="secondary" :disabled="loading" @click="resumeInput?.click()"><FileUp :size="15" /> 选择文件</button>
          </div>
          <span class="import-note">支持 PDF、Word（DOCX/DOC）和 Markdown；解析后请核对文字，原文件不会在解析阶段保存。</span>
          <span v-if="resumeFileName" class="import-note selected">待保存：{{ resumeFileName }}</span>
          <p v-if="resumeFileError" class="import-error" role="alert">{{ resumeFileError }}</p>
          <p v-for="warning in resumeWarnings" :key="warning" class="import-warning">{{ warning }}</p>
          <p v-if="evidenceHelp" class="import-warning">请补充真实经历并保存新版本，然后重新运行分析；旧分析仍对应原简历。</p>
          <textarea ref="resumeEditor" v-model="resumeContent" rows="6" aria-label="简历解析文本" placeholder="粘贴简历文本，或选择 PDF、Word、Markdown 文件"></textarea>
          <button type="button" :disabled="loading || !resumeContent.trim()" @click="importResume">保存为新版本</button>
        </section>

        <section>
          <header><Globe2 :size="16" /> 目标岗位</header>
          <div class="inline-fields target-fields">
            <input v-model="jobTitle" placeholder="岗位名称" />
            <input v-model="company" placeholder="公司（可选）" />
          </div>
          <textarea v-model="jobDescription" rows="5" placeholder="粘贴完整职位描述（JD）"></textarea>
          <input v-model="sourceUri" class="source-input" placeholder="职位来源链接（可选）" />
          <button type="button" :disabled="loading || !jobTitle.trim() || !jobDescription.trim()" @click="createTarget">
            保存目标岗位
          </button>
        </section>
      </div>

      <div class="analysis-toolbar">
        <div>
          <strong>证据化匹配分析</strong>
          <span>{{
            selectedResume ? `当前：V${selectedResume.versionNumber} ${selectedResume.title}` : '请选择简历与岗位'
          }}</span>
        </div>
        <label class="search-toggle">
          <input v-model="realtimeResearch" type="checkbox" />
          公司实时调研
        </label>
        <button type="button" :disabled="loading || !readyForCareerTask" @click="store.analyzeResume(realtimeResearch)">
          <Sparkles :size="15" /> {{ loading ? '处理中' : '开始分析' }}
        </button>
      </div>

      <details v-if="analysisHistory.length" class="analysis-history">
        <summary><History :size="14" /> 历史分析（{{ analysisHistory.length }}）</summary>
        <button
          v-for="item in analysisHistory"
          :key="item.changeSetId"
          type="button"
          :class="{ active: analysis?.changeSetId === item.changeSetId }"
          @click="store.openAnalysis(item.changeSetId)"
        >
          <span>{{ item.company ? `${item.company} · ` : '' }}{{ item.jobTitle }}</span>
          <small>{{ item.resumeTitle }} · {{ item.matchScore }}/100 · {{ item.status }}</small>
        </button>
      </details>

      <div v-if="analysis" class="analysis-result">
        <header class="score-header">
          <div>
            <small>岗位匹配度</small>
            <strong>{{ analysis.matchScore }}<span>/100</span></strong>
          </div>
          <p>{{ analysis.summary }}</p>
        </header>

        <section class="requirements">
          <h3>要求矩阵</h3>
          <div v-for="item in analysis.requirements" :key="item.requirement" class="requirement-row">
            <span class="status-dot" :class="item.status"></span>
            <strong>{{ item.requirement }}</strong>
            <span>{{ item.evidence }}</span>
            <small>{{ item.recommendation }}</small>
          </div>
        </section>

        <section class="changes">
          <div class="section-heading">
            <h3>可审核修改</h3>
            <span><ShieldCheck :size="14" /> 仅允许有证据的修改</span>
          </div>
          <article v-for="change in analysis.changes" :key="change.id" class="change-row" :class="change.status">
            <header>
              <strong>{{ change.section }}</strong>
              <span :class="{ verified: change.evidenceVerified }">
                {{ analysis.status === 'applied' ? '已生成版本' : change.evidenceVerified ? '证据已核验' : '待补充证据' }}
              </span>
            </header>
            <div class="diff-line removed">{{ change.originalText }}</div>
            <div class="diff-line added">{{ change.suggestedText }}</div>
            <p>{{ change.reason }}</p>
            <div v-if="change.evidence.length" class="evidence-list">
              <strong>原始简历证据</strong>
              <span v-for="quote in change.evidence" :key="quote">{{ quote }}</span>
            </div>
            <div v-if="!change.evidenceVerified" class="verification-issues" role="note">
              <strong>需要处理</strong>
              <ul><li v-for="issue in (change.verificationIssues?.length ? change.verificationIssues : ['该历史建议缺少可核验的简历证据。'])" :key="issue">{{ issue }}</li></ul>
              <button type="button" class="secondary" @click="prepareEvidence">补充简历证据</button>
            </div>
            <footer>
              <button
                type="button"
                class="accept"
                :disabled="loading || !change.evidenceVerified || change.status === 'accepted' || analysis.status === 'applied'"
                :title="!change.evidenceVerified ? '请先补充原始简历证据并重新分析' : ''"
                @click="store.decideChange(change.id, 'accepted')"
              >
                <Check :size="14" /> {{ change.status === 'accepted' && change.evidenceVerified ? '已接受' : '接受' }}
              </button>
              <button
                type="button"
                class="reject"
                :disabled="loading || change.status === 'rejected' || analysis.status === 'applied'"
                @click="store.decideChange(change.id, 'rejected')"
              >
                <X :size="14" /> {{ change.status === 'rejected' ? '已忽略' : '忽略' }}
              </button>
            </footer>
          </article>
          <p v-if="analysis.changes.length === 0" class="no-changes">未生成自动改写。请根据要求矩阵补充可验证材料。</p>
        </section>

        <div class="apply-bar">
          <span>{{ acceptedCount }} 条修改待生成新版本</span>
          <button
            type="button"
            :disabled="loading || acceptedCount === 0 || analysis.status === 'applied'"
            @click="store.applyAcceptedChanges"
          >
            <RotateCcw :size="15" /> {{ analysis.status === 'applied' ? '已生成新版本' : '生成定制版本' }}
          </button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped src="./resumeStudio.css"></style>
