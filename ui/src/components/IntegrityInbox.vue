<template>
    <div class="integrity-inbox">
        <div class="header">
            <h4>Build Integrity</h4>
            <n-tooltip trigger="hover" style="max-width: 460px;">
                <template #trigger>
                    <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                </template>
                What is currently stopping builds, and what nobody has claimed. A lock refuses
                version assignment, release creation and release content until its causes are
                resolved — it is the one gate that outlives the build that raised it.
            </n-tooltip>
        </div>

        <n-alert v-if="!loading && !anything" type="success" style="font-size: 13px;">
            Nothing is locked and every commit behind a lock is accounted for.
        </n-alert>

        <div v-if="inbox && inbox.escalatedLocks && inbox.escalatedLocks.length" class="section">
            <h5>Needs an administrator ({{ inbox.escalatedLocks.length }})</h5>
            <p class="text-muted hint">
                A cause was disowned, or two principals claimed the same commit. These do not release
                themselves however many claims arrive — somebody has to decide.
            </p>
            <n-data-table :columns="escalatedColumns" :data="inbox.escalatedLocks" :pagination="false" :bordered="false"/>
        </div>

        <div v-if="inbox && inbox.activeLocks && inbox.activeLocks.length" class="section">
            <h5>Active locks ({{ inbox.activeLocks.length }})</h5>
            <n-data-table :columns="lockColumns" :data="inbox.activeLocks" :pagination="false" :bordered="false"/>
        </div>

        <div v-if="inbox && inbox.unrecognizedCommits && inbox.unrecognizedCommits.length" class="section">
            <h5>Commits nobody is accountable for ({{ inbox.unrecognizedCommits.length }})</h5>
            <p class="text-muted hint">
                Behind the locks above. A commit becomes accountable when an enrolled key signed it, an
                agent session owns it, or somebody claims it — claiming is available on the release page,
                and on the agent lane through <code>rearm attest</code>.
            </p>
            <n-data-table :columns="commitColumns" :data="inbox.unrecognizedCommits" :pagination="false" :bordered="false"/>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { NAlert, NDataTable, NIcon, NTag, NTooltip } from 'naive-ui'
import { QuestionMark } from '@vicons/tabler'

const props = defineProps<{ orgUuid: string }>()

const store = useStore()
const inbox = ref<any>(null)
const loading = ref(true)

const anything = computed(() => {
    if (!inbox.value) return false
    return (inbox.value.activeLocks || []).length > 0
        || (inbox.value.unrecognizedCommits || []).length > 0
})

const levelLabel = (l: string) => l === 'ADMIN' ? 'Admin' : l === 'HUMAN' ? 'Human' : 'Agent'

const whereColumn = {
    title: 'Where',
    key: 'where',
    width: 240,
    render: (row: any) => row.branchName
        ? `${row.componentName || '?'} · branch ${row.branchName}`
        : (row.componentName || '?')
}

const lockColumns = computed(() => [
    whereColumn,
    { title: 'Reason', key: 'reason' },
    { title: 'Raised by', key: 'origin', width: 100, render: (row: any) => row.origin === 'POLICY' ? 'a rule' : 'by hand' },
    {
        title: 'Release needs',
        key: 'effectiveLevel',
        width: 120,
        render: (row: any) => levelLabel(row.effectiveLevel || row.unlockLevel)
    },
    {
        title: 'Waiting on',
        key: 'causes',
        width: 120,
        render: (row: any) => {
            const n = (row.causes || []).length + (row.droppedCauses || 0)
            return h('span', n === 1 ? '1 cause' : `${n} causes`)
        }
    }
])

const escalatedColumns = computed(() => [
    whereColumn,
    { title: 'Reason', key: 'reason' },
    {
        title: 'Release needs',
        key: 'effectiveLevel',
        width: 120,
        render: (row: any) => h(NTag, { size: 'small', type: 'error', bordered: false },
            { default: () => levelLabel(row.effectiveLevel) })
    }
])

const commitColumns = computed(() => [
    {
        title: 'Commit',
        key: 'commit',
        width: 140,
        render: (row: any) => h('code', { style: 'font-size: 12px;' },
            row.commit ? String(row.commit).slice(0, 10) : '—')
    },
    { title: 'Component', key: 'componentName', width: 220 },
    { title: 'Why', key: 'detail' },
    {
        title: 'Claims',
        key: 'claimState',
        width: 120,
        render: (row: any) => {
            if (row.claimState === 'NONE') return h('span', { class: 'text-muted' }, 'none')
            const tone = row.claimState === 'MINE' ? 'success' : 'error'
            return h(NTag, { size: 'tiny', type: tone, bordered: false },
                { default: () => row.claimState === 'NOT_MINE' ? 'disowned' : row.claimState.toLowerCase() })
        }
    }
])

onMounted(async () => {
    try {
        inbox.value = await store.dispatch('fetchIntegrityInbox', props.orgUuid)
    } finally {
        loading.value = false
    }
})
</script>

<style scoped lang="scss">
.integrity-inbox { padding: 0.5rem 0; }
.header { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem; }
.section { margin-bottom: 1.5rem; }
.hint { font-size: 13px; margin: 0 0 0.5rem 0; }
</style>
