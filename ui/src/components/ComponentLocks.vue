<template>
    <div class="component-locks">
        <div class="header">
            <h4>Locks</h4>
            <n-tooltip trigger="hover" style="max-width: 460px;">
                <template #trigger>
                    <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                </template>
                A lock is the one gate that outlives a build. Every rule ReARM evaluates looks at a
                single release, so a rule about a commit is defeated by pushing another commit on
                top of it. A lock stays until somebody resolves the cause and says so on the record.
            </n-tooltip>
        </div>

        <p class="text-muted intro">
            While anything here is active, ReARM refuses to assign a version, create a release or
            change release content for this {{ componentWord }} — version assignment first, so a
            build fails before it spends its minutes. Reading is untouched, and so is moving,
            approving, rejecting or cancelling a release that already exists.
        </p>

        <n-alert v-if="!isWritable" type="default" style="margin-bottom: 0.75rem; font-size: 13px;">
            Read-only: locking and releasing need admin rights here.
        </n-alert>

        <div class="actions">
            <n-space>
                <n-button v-if="isWritable" type="primary" @click="openLock('COMPONENT')">
                    <template #icon><n-icon><Lock/></n-icon></template>
                    Lock this {{ componentWord }}
                </n-button>
                <n-button v-if="isWritable && branchOptions.length" @click="openLock('BRANCH')">
                    <template #icon><n-icon><Lock/></n-icon></template>
                    Lock one branch
                </n-button>
            </n-space>
        </div>

        <n-data-table :columns="columns" :data="locks" :pagination="false" :bordered="false"/>

        <!-- Raise -->
        <n-modal preset="dialog" :show-icon="false" style="width: 640px;" v-model:show="lockModalOpen"
            :title="draft.scope === 'BRANCH' ? 'Lock one branch' : 'Lock this ' + componentWord">
            <n-form :model="draft" label-placement="top" class="mt-3">
                <n-form-item v-if="draft.scope === 'BRANCH'" label="Branch" required>
                    <n-select v-model:value="draft.branch" :options="branchOptions"
                        placeholder="Which branch to lock"/>
                    <template #feedback>
                        A branch lock stops that branch's builds and leaves the rest of the
                        {{ componentWord }} working.
                    </template>
                </n-form-item>
                <n-form-item label="Reason" required>
                    <n-input v-model:value="draft.reason" placeholder="e.g. incident 4021, no builds until it is closed"/>
                    <template #feedback>Shown verbatim in every refusal, so write it for whoever hits it.</template>
                </n-form-item>
                <n-form-item label="Who may release it" required>
                    <n-radio-group v-model:value="draft.unlockLevel">
                        <n-radio value="ADMIN">Admin — an administrator of this {{ componentWord }}</n-radio>
                        <n-radio value="HUMAN">Human — any user, but not an agent</n-radio>
                        <n-radio value="AGENT">Agent — any recognized principal, including an agent through the API</n-radio>
                    </n-radio-group>
                </n-form-item>
                <n-form-item label="What must be true first" required>
                    <n-radio-group v-model:value="draft.attestationRequirement">
                        <n-radio value="NONE">Nothing — whoever holds the level above just releases it</n-radio>
                        <n-radio value="ANY">Every cause claimed by anyone</n-radio>
                        <n-radio value="HUMAN">Every cause claimed by a person</n-radio>
                    </n-radio-group>
                    <template #feedback>
                        A lock raised by hand has no causes, so a requirement other than "nothing"
                        has nothing to wait for. It matters on locks a rule raised.
                    </template>
                </n-form-item>
                <n-space>
                    <n-button type="primary"
                        :disabled="!draft.reason || (draft.scope === 'BRANCH' && !draft.branch)"
                        @click="raise">Lock</n-button>
                    <n-button @click="lockModalOpen = false">Cancel</n-button>
                </n-space>
            </n-form>
        </n-modal>

        <!-- Release -->
        <n-modal preset="dialog" :show-icon="false" style="width: 640px;" v-model:show="releaseModalOpen"
            title="Release lock">
            <n-form :model="releaseDraft" label-placement="top" class="mt-3">
                <p class="text-muted" v-if="releasing">{{ releasing.reason }}</p>
                <n-alert v-if="releasing && releasing.droppedCauses > 0" type="warning"
                    style="margin-bottom: 0.75rem; font-size: 13px;">
                    This lock had more causes than it keeps ({{ releasing.droppedCauses }} dropped), so it will
                    never release itself automatically — somebody has to look. If the dropped commits are still
                    unaccounted for, the rule will lock this again on the next build.
                </n-alert>
                <n-form-item label="Reason" required>
                    <n-input v-model:value="releaseDraft.reason" placeholder="e.g. incident closed, commits claimed"/>
                </n-form-item>
                <n-form-item v-if="isWritable">
                    <n-checkbox v-model:checked="releaseDraft.override">
                        Override — release without the requirement being met
                    </n-checkbox>
                    <template #feedback>
                        Administrators only, and never silent: the override is recorded on the
                        attestation that releases the lock.
                    </template>
                </n-form-item>
                <n-space>
                    <n-button type="primary" :disabled="!releaseDraft.reason" @click="release">Release</n-button>
                    <n-button @click="releaseModalOpen = false">Cancel</n-button>
                </n-space>
            </n-form>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useStore } from 'vuex'
import {
    NAlert, NButton, NCheckbox, NDataTable, NForm, NFormItem, NIcon, NInput, NModal, NRadio,
    NRadioGroup, NSelect, NSpace, NTag, NTooltip, useNotification
} from 'naive-ui'
import { QuestionMark, Lock, LockOpen } from '@vicons/tabler'
import commonFunctions from '@/utils/commonFunctions'

const props = withDefaults(defineProps<{
    orgUuid: string
    componentUuid: string
    isWritable: boolean
    componentWord?: string
}>(), { componentWord: 'component' })

const store = useStore()
const notification = useNotification()

const locks = ref<any[]>([])
const lockModalOpen = ref(false)
const releaseModalOpen = ref(false)
const releasing = ref<any>(null)

const draft = reactive({ scope: 'COMPONENT', branch: '', reason: '', unlockLevel: 'ADMIN',
    attestationRequirement: 'NONE' })
const branchOptions = ref<{ label: string, value: string }[]>([])
const releaseDraft = reactive({ reason: '', override: false })

const levelLabel = (l: string) => l === 'ADMIN' ? 'Admin' : l === 'HUMAN' ? 'Human' : 'Agent'
const requirementLabel = (r: string) =>
    r === 'HUMAN' ? 'every cause claimed by a person'
        : r === 'ANY' ? 'every cause claimed'
            : 'nothing'

const load = async () => {
    // The query is org-wide and each lock now says which component it belongs to, so the panel
    // filters rather than asking a second question.
    const all = await store.dispatch('fetchLocks', props.orgUuid)
    locks.value = (all || []).filter((l: any) => l.component === props.componentUuid)
}

const openLock = (scope: 'COMPONENT' | 'BRANCH') => {
    draft.scope = scope
    draft.branch = ''
    draft.reason = ''
    lockModalOpen.value = true
}

const raise = async () => {
    try {
        if (draft.scope === 'BRANCH') {
            await store.dispatch('lockBranch', {
                branchUuid: draft.branch,
                reason: draft.reason,
                unlockLevel: draft.unlockLevel,
                attestationRequirement: draft.attestationRequirement
            })
        } else {
            await store.dispatch('lockComponent', {
                componentUuid: props.componentUuid,
                reason: draft.reason,
                unlockLevel: draft.unlockLevel,
                attestationRequirement: draft.attestationRequirement
            })
        }
        notification.success({ title: 'Locked', content: 'No builds until it is released.', duration: 3500 })
        lockModalOpen.value = false
        draft.reason = ''
        await load()
    } catch (e: any) {
        notification.error({ title: 'Could not lock', content: commonFunctions.extractGraphQLErrorMessage(e), duration: 8000 })
    }
}

const openRelease = (lock: any) => {
    releasing.value = lock
    releaseDraft.reason = ''
    releaseDraft.override = false
    releaseModalOpen.value = true
}

const release = async () => {
    try {
        await store.dispatch('releaseLock', {
            orgUuid: props.orgUuid,
            lockUuid: releasing.value.uuid,
            reason: releaseDraft.reason,
            override: releaseDraft.override
        })
        notification.success({ title: 'Released', content: 'Recorded as an attestation.', duration: 3500 })
        releaseModalOpen.value = false
        await load()
    } catch (e: any) {
        notification.error({ title: 'Could not release', content: commonFunctions.extractGraphQLErrorMessage(e), duration: 8000 })
    }
}

const columns = computed(() => [
    {
        title: 'Status',
        key: 'status',
        width: 110,
        render: (row: any) => h(NTag, {
            size: 'small',
            type: row.status === 'ACTIVE' ? 'error' : 'default',
            bordered: false
        }, { default: () => row.status === 'ACTIVE' ? 'locked' : 'released' })
    },
    {
        title: 'Scope',
        key: 'scope',
        width: 150,
        render: (row: any) => row.scope === 'BRANCH'
            ? `branch ${row.branchName || ''}`.trim()
            : props.componentWord
    },
    { title: 'Reason', key: 'reason' },
    { title: 'Raised by', key: 'origin', width: 100, render: (row: any) => row.origin === 'POLICY' ? 'a rule' : 'by hand' },
    {
        title: 'Release needs',
        key: 'effectiveLevel',
        width: 210,
        render: (row: any) => h(NTooltip, { style: 'max-width: 360px;' }, {
            trigger: () => h('span', { style: 'cursor: help; border-bottom: 1px dotted #aaa;' },
                `${levelLabel(row.effectiveLevel || row.unlockLevel)} · ${requirementLabel(row.attestationRequirement)}`),
            default: () => row.escalatedLevel
                ? `Escalated to ${levelLabel(row.escalatedLevel)}: a cause was disowned or contested, so this is somebody's decision rather than an automatic release. Declared level was ${levelLabel(row.unlockLevel)}.`
                : 'The level this lock was raised with, and what has to be true of its causes first.'
        })
    },
    {
        title: 'Waiting on',
        key: 'causes',
        render: (row: any) => {
            const causes = row.causes || []
            if (!causes.length) return h('span', { class: 'text-muted' }, '—')
            const lines = causes.slice(0, 3).map((c: any) =>
                h('div', { style: 'font-size: 12px;' }, `${c.subjectType}: ${c.detail || c.subjectUuid}`))
            const hidden = causes.length - 3 + (row.droppedCauses || 0)
            if (hidden > 0) lines.push(h('div', { class: 'text-muted', style: 'font-size: 12px;' }, `and ${hidden} more`))
            return h('div', lines)
        }
    },
    {
        title: 'Actions',
        key: 'actions',
        width: 90,
        render: (row: any) => row.status === 'ACTIVE' && props.isWritable
            ? h(NIcon, { size: 22, class: 'clickable', title: 'Release', onClick: () => openRelease(row) },
                { default: () => h(LockOpen) })
            : null
    }
])

onMounted(async () => {
    await load()
    const branches = await store.dispatch('fetchBranches', props.componentUuid)
    branchOptions.value = (branches || []).map((b: any) => ({ label: b.name, value: b.uuid }))
})
</script>

<style scoped lang="scss">
.component-locks { padding: 0.5rem 0; }
.header { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.25rem; }
.intro { margin: 0 0 0.75rem 0; font-size: 13px; }
.actions { margin-bottom: 0.75rem; }
.mt-3 { margin-top: 0.75rem; }
.clickable { cursor: pointer; }
</style>
