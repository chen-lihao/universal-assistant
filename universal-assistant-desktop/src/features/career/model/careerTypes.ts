import type { SearchResult } from '../../../services/assistantApi'

export type CareerProfile = {
  id: string
  name: string
  knowledgeBaseId: string
  createdAt: string
  updatedAt: string
}

export type ResumeVersion = {
  id: string
  profileId: string
  knowledgeDocumentId: string
  parentVersionId?: string
  versionNumber: number
  title: string
  content: string
  sourceType: string
  sourceFormat: string
  createdAt: string
}

export type ParsedResume = {
  fileName: string
  format: 'md' | 'pdf' | 'docx' | 'doc'
  content: string
  warnings: string[]
}

export type JobTarget = {
  id: string
  profileId: string
  knowledgeDocumentId: string
  jobTitle: string
  company?: string
  description: string
  sourceUri?: string
  createdAt: string
  updatedAt: string
}

export type RequirementMatch = {
  requirement: string
  status: 'matched' | 'partial' | 'missing'
  evidence: string
  recommendation: string
}

export type ResumeChange = {
  id: string
  index: number
  section: string
  originalText: string
  suggestedText: string
  reason: string
  evidence: string[]
  evidenceVerified: boolean
  verificationIssues: string[]
  status: 'proposed' | 'accepted' | 'rejected' | 'blocked'
}

export type ResumeAnalysis = {
  changeSetId: string
  summary: string
  matchScore: number
  requirements: RequirementMatch[]
  changes: ResumeChange[]
  sources: SearchResult[]
  status: string
}

export type ResumeAnalysisSummary = {
  changeSetId: string
  resumeVersionId: string
  jobTargetId: string
  resumeTitle: string
  jobTitle: string
  company?: string
  matchScore: number
  status: string
  createdAt: string
}

export type InterviewQuestion = {
  index: number
  category: string
  question: string
  focus: string
}

export type InterviewFeedback = {
  score: number
  summary: string
  strengths: string[]
  improvements: string[]
  exampleAnswer: string
}

export type InterviewTurn = {
  index: number
  question: string
  answer: string
  feedback?: InterviewFeedback
  createdAt: string
}

export type InterviewSession = {
  id: string
  profileId: string
  resumeVersionId: string
  jobTargetId: string
  mode: 'practice' | 'formal'
  status: 'active' | 'completed'
  currentIndex: number
  maxQuestions: number
  currentQuestion?: InterviewQuestion
  latestFeedback?: InterviewFeedback
  turns: InterviewTurn[]
  finalReport?: string
  startedAt: string
  completedAt?: string
}

export type InterviewSessionSummary = {
  id: string
  resumeVersionId: string
  jobTargetId: string
  resumeTitle: string
  jobTitle: string
  company?: string
  mode: 'practice' | 'formal'
  status: 'active' | 'completed'
  currentIndex: number
  maxQuestions: number
  startedAt: string
  completedAt?: string
}
