import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useCareerStore } from './careerStore'

const careerApi = vi.hoisted(() => ({
  listProfiles: vi.fn(),
  createProfile: vi.fn(),
  updateProfile: vi.fn(),
  deleteProfile: vi.fn(),
  listResumes: vi.fn(),
  parseResume: vi.fn(),
  importResume: vi.fn(),
  listJobTargets: vi.fn(),
  createJobTarget: vi.fn(),
  listAnalyses: vi.fn(),
  getAnalysis: vi.fn(),
  analyzeResume: vi.fn(),
  updateChange: vi.fn(),
  applyChanges: vi.fn(),
  listInterviews: vi.fn(),
  getInterview: vi.fn(),
  startInterview: vi.fn(),
  answerInterview: vi.fn(),
}))

vi.mock('../../../services/careerApi', () => ({ careerApi }))

const profile = (id: string, name: string) => ({
  id,
  name,
  knowledgeBaseId: `kb-${id}`,
  createdAt: '2026-09-24T00:00:00Z',
  updatedAt: '2026-09-24T00:00:00Z',
})

const resume = (id: string, profileId: string) => ({
  id,
  profileId,
  knowledgeDocumentId: `doc-${id}`,
  versionNumber: 1,
  title: `Resume ${id}`,
  content: 'Java and PostgreSQL experience',
  sourceType: 'imported',
  createdAt: '2026-09-24T00:00:00Z',
})

const target = (id: string, profileId: string) => ({
  id,
  profileId,
  knowledgeDocumentId: `doc-${id}`,
  jobTitle: 'Java Engineer',
  company: 'Example',
  description: 'Spring Boot and PostgreSQL',
  createdAt: '2026-09-24T00:00:00Z',
  updatedAt: '2026-09-24T00:00:00Z',
})

describe('careerStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    careerApi.listResumes.mockResolvedValue([])
    careerApi.listJobTargets.mockResolvedValue([])
    careerApi.listAnalyses.mockResolvedValue([])
    careerApi.listInterviews.mockResolvedValue([])
  })

  it('loads profile resources and history together', async () => {
    careerApi.listProfiles.mockResolvedValue([profile('p1', 'Primary')])
    careerApi.listResumes.mockResolvedValue([resume('r1', 'p1')])
    careerApi.listJobTargets.mockResolvedValue([target('j1', 'p1')])
    careerApi.listAnalyses.mockResolvedValue([
      {
        changeSetId: 'a1',
        resumeVersionId: 'r1',
        jobTargetId: 'j1',
        resumeTitle: 'Resume r1',
        jobTitle: 'Java Engineer',
        matchScore: 80,
        status: 'reviewing',
        createdAt: '2026-09-24T00:00:00Z',
      },
    ])
    careerApi.listInterviews.mockResolvedValue([
      {
        id: 'i1',
        resumeVersionId: 'r1',
        jobTargetId: 'j1',
        resumeTitle: 'Resume r1',
        jobTitle: 'Java Engineer',
        mode: 'practice',
        status: 'active',
        currentIndex: 1,
        maxQuestions: 3,
        startedAt: '2026-09-24T00:00:00Z',
      },
    ])

    const store = useCareerStore()
    await store.initialize()

    expect(store.selectedProfileId).toBe('p1')
    expect(store.selectedResumeId).toBe('r1')
    expect(store.selectedJobTargetId).toBe('j1')
    expect(store.analysisHistory).toHaveLength(1)
    expect(store.interviewHistory).toHaveLength(1)
  })

  it('restores the resume and target associated with historical work', async () => {
    careerApi.listProfiles.mockResolvedValue([profile('p1', 'Primary')])
    careerApi.listResumes.mockResolvedValue([resume('r1', 'p1'), resume('r2', 'p1')])
    careerApi.listJobTargets.mockResolvedValue([target('j1', 'p1'), target('j2', 'p1')])
    careerApi.listAnalyses.mockResolvedValue([
      {
        changeSetId: 'a1',
        resumeVersionId: 'r2',
        jobTargetId: 'j2',
        resumeTitle: 'Resume r2',
        jobTitle: 'Java Engineer',
        matchScore: 70,
        status: 'reviewing',
        createdAt: '2026-09-24T00:00:00Z',
      },
    ])
    careerApi.listInterviews.mockResolvedValue([
      {
        id: 'i1',
        resumeVersionId: 'r2',
        jobTargetId: 'j2',
        resumeTitle: 'Resume r2',
        jobTitle: 'Java Engineer',
        mode: 'practice',
        status: 'active',
        currentIndex: 0,
        maxQuestions: 3,
        startedAt: '2026-09-24T00:00:00Z',
      },
    ])
    careerApi.getAnalysis.mockResolvedValue({
      changeSetId: 'a1',
      summary: 'Matched',
      matchScore: 70,
      requirements: [],
      changes: [],
      sources: [],
      status: 'reviewing',
    })
    careerApi.getInterview.mockResolvedValue({
      id: 'i1',
      profileId: 'p1',
      resumeVersionId: 'r2',
      jobTargetId: 'j2',
      mode: 'practice',
      status: 'active',
      currentIndex: 0,
      maxQuestions: 3,
      turns: [],
      startedAt: '2026-09-24T00:00:00Z',
    })

    const store = useCareerStore()
    await store.initialize()
    await store.openAnalysis('a1')
    expect(store.selectedResumeId).toBe('r2')
    expect(store.selectedJobTargetId).toBe('j2')

    await store.openInterview('i1')
    expect(store.interview?.id).toBe('i1')
    expect(store.selectedResumeId).toBe('r2')
    expect(store.selectedJobTargetId).toBe('j2')
  })

  it('rebuilds state from the next profile after deletion', async () => {
    careerApi.listProfiles.mockResolvedValue([profile('p1', 'Primary'), profile('p2', 'Backup')])
    careerApi.deleteProfile.mockResolvedValue(profile('p1', 'Primary'))

    const store = useCareerStore()
    await store.initialize()
    await store.deleteProfile()

    expect(careerApi.deleteProfile).toHaveBeenCalledWith('p1')
    expect(store.selectedProfileId).toBe('p2')
    expect(store.profiles.map((item) => item.id)).toEqual(['p2'])
  })

  it('previews a file without saving and keeps its source format on import', async () => {
    careerApi.listProfiles.mockResolvedValue([profile('p1', 'Primary')])
    careerApi.parseResume.mockResolvedValue({
      fileName: 'resume.pdf',
      format: 'pdf',
      content: 'Spring Boot experience',
      warnings: [],
    })
    careerApi.importResume.mockResolvedValue({ ...resume('r1', 'p1'), sourceFormat: 'pdf' })

    const store = useCareerStore()
    await store.initialize()
    const file = new File(['PDF'], 'resume.pdf', { type: 'application/pdf' })
    const parsed = await store.parseResume(file)
    expect(parsed.content).toBe('Spring Boot experience')
    expect(careerApi.importResume).not.toHaveBeenCalled()

    await store.importResume('resume', parsed.content, parsed.format)
    expect(careerApi.importResume).toHaveBeenCalledWith('p1', 'resume', parsed.content, 'pdf')
    expect(store.selectedResume?.sourceFormat).toBe('pdf')
  })
})
