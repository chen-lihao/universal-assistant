<script setup lang="ts">
import { computed, ref } from 'vue'
import { BriefcaseBusiness, Check, ChevronDown, FileText, Pencil, Plus, Target, Trash2, X } from '@lucide/vue'
import { storeToRefs } from 'pinia'
import assistantGuide from '../../assets/assistant-human-portrait.webp'
import { useCareerStore } from '../../features/career/stores/careerStore'

const store = useCareerStore()
const {
  profiles,
  resumes,
  jobTargets,
  selectedProfile,
  selectedProfileId,
  selectedResumeId,
  selectedJobTargetId,
  loading,
} = storeToRefs(store)
const profileName = ref('')
const showCreateProfile = ref(false)
const renaming = ref(false)
const renameValue = ref('')
const confirmingDelete = ref(false)
const sidebarExpanded = ref(false)

const setupProgress = computed(() => [selectedProfileId.value, selectedResumeId.value, selectedJobTargetId.value])

async function createProfile() {
  await store.createProfile(profileName.value)
  profileName.value = ''
  showCreateProfile.value = false
}

function startRename() {
  renameValue.value = selectedProfile.value?.name || ''
  renaming.value = true
  confirmingDelete.value = false
}

async function submitRename() {
  if (!renameValue.value.trim()) return
  await store.renameProfile(renameValue.value.trim())
  renaming.value = false
}

async function deleteProfile() {
  await store.deleteProfile()
  confirmingDelete.value = false
}
</script>

<template>
  <aside class="career-sidebar" :class="{ expanded: sidebarExpanded || !profiles.length }">
    <div class="sidebar-heading">
      <div class="career-guide">
        <span class="guide-avatar" aria-hidden="true"><img :src="assistantGuide" alt="" /></span>
        <span class="guide-copy">
          <small><BriefcaseBusiness :size="12" /> 职业导航模块</small>
          <strong>求职档案</strong>
        </span>
      </div>
      <button type="button" title="新建求职档案" @click="showCreateProfile = !showCreateProfile">
        <Plus :size="16" />
      </button>
    </div>

    <button
      v-if="profiles.length"
      class="sidebar-toggle"
      type="button"
      :aria-expanded="sidebarExpanded || !profiles.length"
      aria-controls="career-sidebar-body"
      @click="sidebarExpanded = !sidebarExpanded"
    >
      <span>{{ selectedProfile?.name || '创建求职档案' }}</span>
      <small>{{ setupProgress.filter(Boolean).length }}/3 已就绪</small>
      <ChevronDown :size="16" :class="{ rotated: sidebarExpanded }" />
    </button>

    <div id="career-sidebar-body" class="sidebar-body" :class="{ open: sidebarExpanded || !profiles.length }">
      <p v-if="!profiles.length && !loading" class="profile-hint">先为这次求职创建档案，简历与面试记录会保存在其中。</p>
      <form
        v-if="showCreateProfile || (!profiles.length && !loading)"
        class="profile-create"
        @submit.prevent="createProfile"
      >
        <input v-model="profileName" placeholder="档案名称" :disabled="loading" />
        <button type="submit" :disabled="loading || !profileName.trim()">创建</button>
      </form>

      <template v-if="profiles.length">
        <label class="field-label" for="career-profile">档案</label>
        <select
          id="career-profile"
          v-model="selectedProfileId"
          :disabled="loading"
          @change="store.selectProfile(selectedProfileId)"
        >
          <option value="" disabled>选择求职档案</option>
          <option v-for="profile in profiles" :key="profile.id" :value="profile.id">{{ profile.name }}</option>
        </select>
      </template>

      <div v-if="selectedProfileId" class="profile-actions">
        <button type="button" title="重命名求职档案" @click="startRename"><Pencil :size="13" /></button>
        <button type="button" class="danger" title="删除求职档案" @click="confirmingDelete = true">
          <Trash2 :size="13" />
        </button>
      </div>

      <form v-if="renaming" class="profile-edit" @submit.prevent="submitRename">
        <input v-model="renameValue" aria-label="新的档案名称" :disabled="loading" />
        <button type="submit" title="确认重命名" :disabled="loading"><Check :size="13" /></button>
        <button type="button" title="取消重命名" @click="renaming = false"><X :size="13" /></button>
      </form>

      <div v-if="confirmingDelete" class="delete-confirm" role="alert">
        <p>删除后将同时移除该档案的简历、岗位、分析、面试记录与知识库，且无法恢复。</p>
        <div>
          <button type="button" @click="confirmingDelete = false">取消</button>
          <button type="button" class="danger" :disabled="loading" @click="deleteProfile">确认删除</button>
        </div>
      </div>

      <template v-if="selectedProfileId">
        <label class="field-label" for="career-resume"><FileText :size="14" /> 简历版本</label>
        <select id="career-resume" v-model="selectedResumeId" :disabled="loading">
          <option value="" disabled>尚未导入简历</option>
          <option v-for="resume in resumes" :key="resume.id" :value="resume.id">
            V{{ resume.versionNumber }} · {{ resume.title }}
          </option>
        </select>

        <label class="field-label" for="career-target"><Target :size="14" /> 目标岗位</label>
        <select id="career-target" v-model="selectedJobTargetId" :disabled="loading">
          <option value="" disabled>尚未创建目标岗位</option>
          <option v-for="target in jobTargets" :key="target.id" :value="target.id">
            {{ target.company ? `${target.company} · ` : '' }}{{ target.jobTitle }}
          </option>
        </select>

        <div class="setup-status" aria-label="Setup progress">
          <span v-for="(step, index) in setupProgress" :key="index" :class="{ done: step }"></span>
          <small>{{ setupProgress.filter(Boolean).length }}/3 已就绪</small>
        </div>

        <p class="sidebar-note">原始简历仅保存在本地。进入 RAG 和模型前会隐藏联系方式，接受修改后生成可追溯版本。</p>
      </template>
    </div>
  </aside>
</template>

<style scoped>
.career-sidebar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  min-height: 0;
  padding: 17px 14px;
  border-right: 1px solid var(--ua-border);
  background: var(--ua-panel-soft);
}

.sidebar-body {
  display: flex;
  flex-direction: column;
  gap: 9px;
  min-height: 0;
  overflow-y: auto;
}

.sidebar-toggle {
  display: none;
}

.sidebar-heading,
.field-label,
.setup-status {
  display: flex;
  align-items: center;
}

.sidebar-heading {
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--ua-ink);
  font-size: 13px;
  font-weight: 700;
}

.field-label {
  gap: 6px;
}

.career-guide {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.guide-avatar {
  display: inline-flex;
  flex: 0 0 auto;
  width: 42px;
  height: 48px;
  overflow: hidden;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  background: var(--ua-companion-soft);
}

.guide-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center 38%;
}

.guide-copy {
  display: grid;
  gap: 1px;
  min-width: 0;
}

.guide-copy small {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ua-companion-strong);
  font-size: 10px;
  font-weight: 700;
}

.guide-copy strong {
  color: var(--ua-ink);
  font-size: 14px;
}

.sidebar-heading button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  color: var(--ua-muted);
  background: var(--ua-panel);
}

.profile-create {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
}

.profile-hint {
  margin: 0;
  color: var(--ua-muted);
  font-size: 11px;
  line-height: 1.5;
}

.profile-actions {
  display: flex;
  justify-content: flex-end;
  gap: 5px;
  margin-top: -4px;
}

.profile-actions button,
.profile-edit button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--ua-border);
  border-radius: 6px;
  color: var(--ua-muted);
  background: var(--ua-panel);
}

.profile-actions button.danger {
  color: var(--ua-danger);
}

.profile-edit {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 5px;
}

.profile-edit input {
  min-width: 0;
  height: 30px;
  padding: 0 8px;
  border: 1px solid var(--ua-primary);
  border-radius: 6px;
  font: inherit;
}

.delete-confirm {
  padding: 9px;
  border: 1px solid var(--ua-danger);
  border-radius: 7px;
  color: var(--ua-danger);
  background: var(--ua-danger-soft);
  font-size: 10px;
}

.delete-confirm p {
  margin: 0 0 8px;
  line-height: 1.5;
}

.delete-confirm div {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

.delete-confirm button {
  min-height: 26px;
  padding: 0 8px;
  border: 1px solid var(--ua-border-strong);
  border-radius: 6px;
  color: var(--ua-ink-soft);
  background: var(--ua-panel);
  font: inherit;
}

.delete-confirm button.danger {
  border-color: var(--ua-danger);
  color: var(--ua-on-primary);
  background: var(--ua-danger);
}

.profile-create input,
select {
  min-width: 0;
  height: 36px;
  border: 1px solid var(--ua-border-strong);
  border-radius: 7px 7px 3px 7px;
  color: var(--ua-ink);
  background: var(--ua-panel);
  font: inherit;
}

.profile-create input {
  padding: 0 9px;
}

.profile-create button {
  border: 0;
  border-radius: 7px;
  color: var(--ua-on-primary);
  background: var(--ua-primary);
}

select {
  width: 100%;
  padding: 0 30px 0 9px;
}

.field-label {
  margin-top: 4px;
  color: var(--ua-muted);
  font-size: 11px;
  font-weight: 700;
}

.setup-status {
  gap: 5px;
  margin-top: 8px;
}

.setup-status span {
  width: 20px;
  height: 4px;
  border-radius: 2px;
  background: var(--ua-border);
}

.setup-status span.done {
  background: var(--ua-primary);
}

.setup-status small {
  margin-left: 4px;
  color: var(--ua-muted);
  font-size: 10px;
}

.sidebar-note {
  margin: auto 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--ua-border);
  color: var(--ua-muted);
  font-size: 11px;
  line-height: 1.55;
}

@media (max-width: 740px) {
  .career-sidebar {
    padding: 10px 14px;
    border-right: 0;
    border-bottom: 1px solid var(--ua-border);
  }

  .sidebar-heading {
    display: none;
  }

  .sidebar-toggle {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    min-height: 38px;
    padding: 0 4px;
    border: 0;
    color: var(--ua-ink);
    background: transparent;
    font: inherit;
    text-align: left;
    cursor: pointer;
  }

  .sidebar-toggle span {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    font-size: 13px;
    font-weight: 650;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .sidebar-toggle small {
    color: var(--ua-muted);
    font-size: 11px;
    white-space: nowrap;
  }

  .sidebar-toggle svg {
    transition: transform 180ms ease;
  }

  .sidebar-toggle svg.rotated {
    transform: rotate(180deg);
  }

  .sidebar-body:not(.open) {
    display: none;
  }

  .sidebar-body.open {
    max-height: min(45vh, 320px);
    padding: 10px 2px 8px;
  }

  .career-sidebar.expanded .sidebar-heading {
    display: flex;
  }
}
</style>
