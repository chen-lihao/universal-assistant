import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { careerApi } from '../../../services/careerApi'
import type {
  CareerProfile,
  InterviewSession,
  InterviewSessionSummary,
  JobTarget,
  ResumeAnalysis,
  ResumeAnalysisSummary,
  ResumeVersion,
} from '../model/careerTypes'

export const useCareerStore = defineStore('career', () => {
  const profiles = ref<CareerProfile[]>([])
  const resumes = ref<ResumeVersion[]>([])
  const jobTargets = ref<JobTarget[]>([])
  const selectedProfileId = ref('')
  const selectedResumeId = ref('')
  const selectedJobTargetId = ref('')
  const analysis = ref<ResumeAnalysis | null>(null)
  const analysisHistory = ref<ResumeAnalysisSummary[]>([])
  const interview = ref<InterviewSession | null>(null)
  const interviewHistory = ref<InterviewSessionSummary[]>([])
  const loading = ref(false)
  const error = ref('')
  const initialized = ref(false)

  const selectedProfile = computed(() => profiles.value.find((item) => item.id === selectedProfileId.value))
  const selectedResume = computed(() => resumes.value.find((item) => item.id === selectedResumeId.value))
  const selectedJobTarget = computed(() => jobTargets.value.find((item) => item.id === selectedJobTargetId.value))
  const readyForCareerTask = computed(() =>
    Boolean(selectedProfileId.value && selectedResumeId.value && selectedJobTargetId.value),
  )

  async function run<T>(action: () => Promise<T>) {
    loading.value = true
    error.value = ''
    try {
      return await action()
    } catch (cause) {
      error.value =
        cause instanceof TypeError
          ? '后端服务暂不可用，启动后端后点击重试。'
          : cause instanceof Error
            ? cause.message
            : '求职助手操作失败。'
      throw cause
    } finally {
      loading.value = false
    }
  }

  async function initialize() {
    if (initialized.value) return
    await run(async () => {
      profiles.value = await careerApi.listProfiles()
      initialized.value = true
      if (profiles.value.length > 0) {
        await selectProfile(profiles.value[0].id, false)
      }
    })
  }

  async function retryInitialize() {
    initialized.value = false
    await initialize()
  }

  async function createProfile(name: string) {
    const profile = await run(() => careerApi.createProfile(name))
    profiles.value = [profile, ...profiles.value]
    await selectProfile(profile.id)
    return profile
  }

  async function selectProfile(profileId: string, manageLoading = true) {
    const load = async () => {
      selectedProfileId.value = profileId
      const [loadedResumes, loadedTargets, loadedAnalyses, loadedInterviews] = await Promise.all([
        careerApi.listResumes(profileId),
        careerApi.listJobTargets(profileId),
        careerApi.listAnalyses(profileId),
        careerApi.listInterviews(profileId),
      ])
      resumes.value = loadedResumes
      jobTargets.value = loadedTargets
      analysisHistory.value = loadedAnalyses
      interviewHistory.value = loadedInterviews
      selectedResumeId.value = loadedResumes[0]?.id || ''
      selectedJobTargetId.value = loadedTargets[0]?.id || ''
      analysis.value = null
      interview.value = null
    }
    if (manageLoading) await run(load)
    else await load()
  }

  async function parseResume(file: File) {
    return run(() => careerApi.parseResume(file))
  }

  async function importResume(title: string, content: string, sourceFormat = 'text') {
    const resume = await run(() => careerApi.importResume(selectedProfileId.value, title, content, sourceFormat))
    resumes.value = [resume, ...resumes.value]
    selectedResumeId.value = resume.id
    return resume
  }

  async function renameProfile(name: string) {
    const updated = await run(() => careerApi.updateProfile(selectedProfileId.value, name))
    profiles.value = profiles.value.map((profile) => (profile.id === updated.id ? updated : profile))
  }

  async function deleteProfile() {
    const profileId = selectedProfileId.value
    await run(() => careerApi.deleteProfile(profileId))
    profiles.value = profiles.value.filter((profile) => profile.id !== profileId)
    selectedProfileId.value = ''
    resumes.value = []
    jobTargets.value = []
    analysisHistory.value = []
    interviewHistory.value = []
    analysis.value = null
    interview.value = null
    if (profiles.value[0]) await selectProfile(profiles.value[0].id)
  }

  async function createJobTarget(payload: {
    jobTitle: string
    company?: string
    description: string
    sourceUri?: string
  }) {
    const target = await run(() => careerApi.createJobTarget({ profileId: selectedProfileId.value, ...payload }))
    jobTargets.value = [target, ...jobTargets.value]
    selectedJobTargetId.value = target.id
    return target
  }

  async function analyzeResume(realtimeResearch: boolean) {
    analysis.value = await run(() =>
      careerApi.analyzeResume({
        profileId: selectedProfileId.value,
        resumeVersionId: selectedResumeId.value,
        jobTargetId: selectedJobTargetId.value,
        realtimeResearch,
      }),
    )
    analysisHistory.value = await careerApi.listAnalyses(selectedProfileId.value)
  }

  async function openAnalysis(changeSetId: string) {
    const summary = analysisHistory.value.find((item) => item.changeSetId === changeSetId)
    if (summary) {
      selectedResumeId.value = summary.resumeVersionId
      selectedJobTargetId.value = summary.jobTargetId
    }
    analysis.value = await run(() => careerApi.getAnalysis(changeSetId))
  }

  async function decideChange(changeId: string, decision: 'accepted' | 'rejected') {
    const updated = await run(() => careerApi.updateChange(changeId, decision))
    if (analysis.value) {
      analysis.value.changes = analysis.value.changes.map((change) => (change.id === updated.id ? updated : change))
    }
  }

  async function applyAcceptedChanges() {
    if (!analysis.value) return
    const version = await run(() => careerApi.applyChanges(analysis.value!.changeSetId))
    resumes.value = [version, ...resumes.value]
    selectedResumeId.value = version.id
    analysis.value.status = 'applied'
    analysisHistory.value = await careerApi.listAnalyses(selectedProfileId.value)
  }

  async function startInterview(mode: 'practice' | 'formal', maxQuestions: number) {
    interview.value = await run(() =>
      careerApi.startInterview({
        profileId: selectedProfileId.value,
        resumeVersionId: selectedResumeId.value,
        jobTargetId: selectedJobTargetId.value,
        mode,
        maxQuestions,
      }),
    )
    interviewHistory.value = await careerApi.listInterviews(selectedProfileId.value)
  }

  async function openInterview(sessionId: string) {
    const summary = interviewHistory.value.find((item) => item.id === sessionId)
    if (summary) {
      selectedResumeId.value = summary.resumeVersionId
      selectedJobTargetId.value = summary.jobTargetId
    }
    interview.value = await run(() => careerApi.getInterview(sessionId))
  }

  async function answerInterview(answer: string) {
    if (!interview.value) return
    interview.value = await run(() => careerApi.answerInterview(interview.value!.id, answer))
    interviewHistory.value = await careerApi.listInterviews(selectedProfileId.value)
  }

  return {
    profiles,
    resumes,
    jobTargets,
    selectedProfileId,
    selectedResumeId,
    selectedJobTargetId,
    selectedProfile,
    selectedResume,
    selectedJobTarget,
    readyForCareerTask,
    analysis,
    analysisHistory,
    interview,
    interviewHistory,
    loading,
    error,
    initialize,
    retryInitialize,
    createProfile,
    selectProfile,
    importResume,
    parseResume,
    renameProfile,
    deleteProfile,
    createJobTarget,
    analyzeResume,
    openAnalysis,
    decideChange,
    applyAcceptedChanges,
    startInterview,
    openInterview,
    answerInterview,
  }
})
