<template>
    <div class="prView">
        <div v-if="pr">
            <h1>
                Pull Request {{ pr.identity }}
                <n-tag :type="stateTag(pr.state)" size="small" bordered="false">{{ pr.state }}</n-tag>
            </h1>
            <p v-if="pr.title" class="title-line">{{ pr.title }}</p>

            <div class="meta">
                <p><strong>Target VCS:</strong> {{ vcsName(pr.targetVcsRepository) }}</p>
                <p v-if="pr.sourceVcsRepository && pr.sourceVcsRepository !== pr.targetVcsRepository">
                    <strong>Source VCS (cross-repo):</strong> {{ vcsName(pr.sourceVcsRepository) }}
                </p>
                <p v-if="pr.sourceBranchName"><strong>Source branch:</strong> {{ pr.sourceBranchName }}</p>
                <p v-if="pr.targetBranchName"><strong>Target branch:</strong> {{ pr.targetBranchName }}</p>
                <p v-if="pr.endpoint">
                    <strong>SCM:</strong>
                    <a :href="pr.endpoint" target="_blank" rel="noopener">{{ pr.endpoint }}</a>
                </p>
                <p><strong>Commits attributed:</strong> {{ (pr.commits || []).length }}</p>
                <p v-if="(pr.commits || []).length">
                    <strong>Head SCE:</strong>
                    <code>{{ pr.commits[pr.commits.length - 1] }}</code>
                </p>
            </div>

            <h3 class="mt-4">PR validation events
                <n-tooltip trigger="hover">
                    <template #trigger>
                        <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                    </template>
                    Outbound dispatches the aggregator made to the SCM. Latest entry whose
                    sourceCodeEntry matches the head is the current verdict.
                </n-tooltip>
            </h3>
            <n-data-table
                v-if="(pr.prValidationEvents || []).length"
                :columns="prEventCols"
                :data="pr.prValidationEvents"/>
            <p v-else class="empty">None recorded yet.</p>

            <h3 class="mt-4">Release validation events
                <n-tooltip trigger="hover">
                    <template #trigger>
                        <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                    </template>
                    Inbound contributions from individual releases attributed to this PR. The
                    aggregator folds the latest event per release into the next dispatch.
                </n-tooltip>
            </h3>
            <n-data-table
                v-if="(pr.releaseValidationEvents || []).length"
                :columns="releaseEventCols"
                :data="pr.releaseValidationEvents"/>
            <p v-else class="empty">None recorded yet.</p>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import { NDataTable, NIcon, NTag, NTooltip, DataTableColumns } from 'naive-ui'
import { QuestionMark } from '@vicons/tabler'

const store = useStore()
const route = useRoute()
const prUuid = computed(() => route.params.uuid as string)
const pr = ref<any>(null)

const stateTag = (s: string) => s === 'OPEN' ? 'success' : (s === 'MERGED' ? 'info' : 'default')
const validationTag = (v: string) => {
    if (v === 'SUCCESS') return 'success'
    if (v === 'FAILURE' || v === 'CANCELLED') return 'error'
    return 'default'
}

const vcsName = (uuid: string) => {
    if (!uuid) return '—'
    const repo = store.getters.vcsRepoById(uuid)
    return repo?.name || uuid
}

const fmtDateTime = (raw: string | null | undefined) => {
    if (!raw) return '—'
    const d = new Date(raw)
    if (Number.isNaN(d.getTime())) return '—'
    return d.toLocaleDateString('en-CA') + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const prEventCols: DataTableColumns<any> = [
    {
        title: 'When',
        key: 'date',
        width: 160,
        render: (row) => fmtDateTime(row.date)
    },
    {
        title: 'Verdict',
        key: 'validationState',
        width: 110,
        render: (row) => h(NTag, { type: validationTag(row.validationState), size: 'small', bordered: false },
            { default: () => row.validationState })
    },
    {
        title: 'Head SCE',
        key: 'sourceCodeEntry',
        render: (row) => h('code', {}, (row.sourceCodeEntry || '').slice(0, 8))
    },
    {
        title: 'Releases',
        key: 'attributedReleases',
        render: (row) => (row.attributedReleases || []).length
    },
    {
        title: 'Comment',
        key: 'comment',
        render: (row) => row.comment || '—'
    }
]

const releaseEventCols: DataTableColumns<any> = [
    {
        title: 'When',
        key: 'date',
        width: 160,
        render: (row) => fmtDateTime(row.date)
    },
    {
        title: 'Release',
        key: 'release',
        render: (row) => h('code', {}, (row.release || '').slice(0, 8))
    },
    {
        title: 'Outcome',
        key: 'validationResult',
        width: 110,
        render: (row) => h(NTag, { type: validationTag(row.validationResult), size: 'small', bordered: false },
            { default: () => row.validationResult })
    }
]

async function load () {
    if (prUuid.value) {
        pr.value = await store.dispatch('fetchPullRequest', prUuid.value)
        if (pr.value?.org) {
            await store.dispatch('fetchVcsReposOfOrganization', pr.value.org)
        }
    }
}

onMounted(load)
watch(prUuid, load)
</script>

<style scoped lang="scss">
.prView { padding: 1rem; }
.title-line { color: #555; margin-top: -0.5rem; }
.meta {
    background: #fafafa;
    border: 1px solid #eee;
    padding: 0.75rem 1rem;
    border-radius: 4px;
    p { margin: 0.25rem 0; }
}
.empty { color: #888; font-style: italic; }
.mt-4 { margin-top: 1.25rem; }
</style>
