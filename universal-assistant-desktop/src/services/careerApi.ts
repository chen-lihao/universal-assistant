import { getBackendUrl } from './assistantApi'
import type {
  CareerProfile,
  InterviewSession,
  InterviewSessionSummary,
  JobTarget,
  ParsedResume,
  ResumeAnalysis,
  ResumeAnalysisSummary,
  ResumeChange,
  ResumeVersion,
} from '../features/career/model/careerTypes'

async function request<T>(path: string, init?: RequestInit) {
  const backendUrl = await getBackendUrl()
  const response = await fetch(`${backendUrl}${path}`, {
    ...init,
    headers: {
      ...(init?.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...init?.headers,
    },
  })
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { error?: string } | null
    throw new Error(payload?.error || `Backend returned ${response.status}`)
  }
  return (await response.json()) as T
}

export const careerApi = {
  listProfiles: () => request<CareerProfile[]>('/api/career/profiles'),
  createProfile: (name: string) =>
    request<CareerProfile>('/api/career/profiles', {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),
  updateProfile: (profileId: string, name: string) =>
    request<CareerProfile>(`/api/career/profiles/${profileId}`, {
      method: 'PATCH',
      body: JSON.stringify({ name }),
    }),
  deleteProfile: (profileId: string) =>
    request<CareerProfile>(`/api/career/profiles/${profileId}`, { method: 'DELETE' }),
  listResumes: (profileId: string) => request<ResumeVersion[]>(`/api/career/profiles/${profileId}/resumes`),
  parseResume: (file: File) => {
    const body = new FormData()
    body.append('file', file)
    return request<ParsedResume>('/api/career/resumes/parse', { method: 'POST', body })
  },
  importResume: (profileId: string, title: string, content: string, sourceFormat: string) =>
    request<ResumeVersion>('/api/career/resumes', {
      method: 'POST',
      body: JSON.stringify({ profileId, title, content, sourceFormat }),
    }),
  listJobTargets: (profileId: string) => request<JobTarget[]>(`/api/career/profiles/${profileId}/job-targets`),
  createJobTarget: (payload: {
    profileId: string
    jobTitle: string
    company?: string
    description: string
    sourceUri?: string
  }) =>
    request<JobTarget>('/api/career/job-targets', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  analyzeResume: (payload: {
    profileId: string
    resumeVersionId: string
    jobTargetId: string
    realtimeResearch: boolean
  }) =>
    request<ResumeAnalysis>('/api/career/resume-analyses', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  listAnalyses: (profileId: string) =>
    request<ResumeAnalysisSummary[]>(`/api/career/profiles/${profileId}/resume-analyses`),
  getAnalysis: (changeSetId: string) => request<ResumeAnalysis>(`/api/career/resume-analyses/${changeSetId}`),
  updateChange: (changeId: string, decision: 'accepted' | 'rejected') =>
    request<ResumeChange>(`/api/career/resume-changes/${changeId}`, {
      method: 'PATCH',
      body: JSON.stringify({ decision }),
    }),
  applyChanges: (changeSetId: string) =>
    request<ResumeVersion>(`/api/career/resume-analyses/${changeSetId}/apply`, { method: 'POST' }),
  startInterview: (payload: {
    profileId: string
    resumeVersionId: string
    jobTargetId: string
    mode: 'practice' | 'formal'
    maxQuestions: number
  }) =>
    request<InterviewSession>('/api/career/interviews', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  listInterviews: (profileId: string) =>
    request<InterviewSessionSummary[]>(`/api/career/profiles/${profileId}/interviews`),
  getInterview: (sessionId: string) => request<InterviewSession>(`/api/career/interviews/${sessionId}`),
  answerInterview: (sessionId: string, answer: string) =>
    request<InterviewSession>(`/api/career/interviews/${sessionId}/answers`, {
      method: 'POST',
      body: JSON.stringify({ answer }),
    }),
}
