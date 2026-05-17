<template>
    <div class="prView">
        <div v-if="pr">
            <h1>
                Pull Request {{ pr.identity }}
                <n-tag :type="stateTag(pr.state)" size="small" bordered="false">{{ pr.state }}</n-tag>
            </h1>
            <p v-if="pr.title" class="title-line">{{ pr.title }}</p>

            <div class="meta">
                <p>
                    <strong>Target VCS:</strong> {{ vcsName(pr.targetVcsRepository) }}
                    <component :is="renderUuidTooltip" :uuid="pr.targetVcsRepository" :label="'Target VCS UUID'" />
                </p>
                <p v-if="pr.sourceVcsRepository && pr.sourceVcsRepository !== pr.targetVcsRepository">
                    <strong>Source VCS (cross-repo):</strong> {{ vcsName(pr.sourceVcsRepository) }}
                    <component :is="renderUuidTooltip" :uuid="pr.sourceVcsRepository" :label="'Source VCS UUID'" />
                </p>
                <p v-if="pr.sourceBranchName"><strong>Source branch:</strong> {{ pr.sourceBranchName }}</p>
                <p v-if="pr.targetBranchName"><strong>Target branch:</strong> {{ pr.targetBranchName }}</p>
                <p v-if="pr.endpoint">
                    <strong>SCM:</strong>&nbsp;<a :href="pr.endpoint" target="_blank" rel="noopener">{{ pr.endpoint }}</a>
                </p>
                <p><strong>Commits attributed:</strong> {{ (pr.commits || []).length }}</p>
                <p v-if="headSce">
                    <strong>Head SHA:</strong>
                    <code>{{ headSce.commit ? shortSha(headSce.commit) : '—' }}</code>
                    <component :is="renderUuidTooltip" :uuid="headSce.uuid" :label="'Head SCE UUID'" />
                    <span v-if="headSce.commitMessage" class="commit-msg">— {{ headSce.commitMessage }}</span>
                </p>
                <p v-if="(pr.agents || []).length">
                    <strong>Agents:</strong>
                    <span v-for="(a, i) in pr.agents" :key="a.uuid" class="agent-pill">
                        <router-link :to="{ name: 'AiAgentView', params: { uuid: a.uuid } }">
                            <span class="agent-chip" :style="{ background: a.color || '#888' }">{{ a.iconKind || '◆' }}</span>
                            {{ a.name }}<span class="agent-id" v-if="a.uuid"> — {{ a.uuid.slice(0, 8) }}…{{ a.uuid.slice(-4) }}</span>
                        </router-link><span v-if="i &lt; pr.agents.length - 1">, </span>
                    </span>
                </p>
            </div>

            <h3 class="mt-4">
                Releases at PR head
                <n-tooltip trigger="hover">
                    <template #trigger>
                        <n-icon size="16" style="cursor: help;"><QuestionMark/></n-icon>
                    </template>
                    Releases whose primary source-code-entry is one of this PR's commits, scoped to the PR's org.
                    Same set the aggregator uses to compute the PR-level verdict.
                </n-tooltip>
            </h3>
            <n-data-table
                v-if="(pr.attributedReleases || []).length"
                :columns="attributedReleaseCols"
                :data="pr.attributedReleases"/>
            <p v-else class="empty">No releases attributed yet.</p>

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
                :data="prEventsDesc"/>
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
                :data="releaseEventsDesc"/>
            <p v-else class="empty">None recorded yet.</p>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute, RouterLink } from 'vue-router'
import { NDataTable, NIcon, NTag, NTooltip, useNotification, DataTableColumns } from 'naive-ui'
import { QuestionMark } from '@vicons/tabler'
import { Info20Regular, Copy20Regular } from '@vicons/fluent'

const store = useStore()
const route = useRoute()
const notification = useNotification()
const prUuid = computed(() => route.params.uuid as string)
const pr = ref<any>(null)

const stateTag = (s: string) => s === 'OPEN' ? 'success' : (s === 'MERGED' ? 'info' : 'default')
const validationTag = (v: string) => {
    if (v === 'SUCCESS') return 'success'
    if (v === 'FAILURE' || v === 'CANCELLED') return 'error'
    if (v === 'PENDING') return 'warning'
    return 'default'
}

const vcsName = (uuid: string) => {
    if (!uuid) return '—'
    const repo = store.getters.vcsRepoById(uuid)
    return repo?.name || uuid
}

const headSce = computed(() => {
    const cd = pr.value?.commitDetails
    return cd && cd.length ? cd[cd.length - 1] : null
})

// Both event lists are appended in arrival order on the backend; flip
// to descending so the newest verdict is at the top of the table.
const sortByDateDesc = (rows: any[]) => [...rows].sort((a, b) => {
    const ta = a?.date ? new Date(a.date).getTime() : 0
    const tb = b?.date ? new Date(b.date).getTime() : 0
    return tb - ta
})
const prEventsDesc = computed(() => sortByDateDesc(pr.value?.prValidationEvents || []))
const releaseEventsDesc = computed(() => sortByDateDesc(pr.value?.releaseValidationEvents || []))

const shortSha = (sha: string) => sha ? sha.slice(0, 12) : '—'

const fmtDate = (raw: string | null | undefined) => {
    if (!raw) return '—'
    const d = new Date(raw)
    if (Number.isNaN(d.getTime())) return '—'
    return d.toLocaleDateString('en-CA')
}

const fmtDateTime = (raw: string | null | undefined) => {
    if (!raw) return '—'
    const d = new Date(raw)
    if (Number.isNaN(d.getTime())) return '—'
    return d.toLocaleDateString('en-CA') + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

async function copyUuid (text: string, label: string) {
    try {
        await navigator.clipboard.writeText(text)
        notification.info({ title: 'Copied', content: `${label}: ${text}`, duration: 3000 })
    } catch (e) {
        console.error(e)
    }
}

// Reusable Info icon with hover tooltip showing UUID + a copy button.
// Mirrors the pattern in VcsReposOfOrg.vue. Defined as a render component
// so it can be dropped into the template via <component :is>.
const renderUuidTooltip = defineComponent({
    props: { uuid: { type: String, default: '' }, label: { type: String, default: 'UUID' } },
    setup (props) {
        return () => {
            if (!props.uuid) return null
            return h(NTooltip, { trigger: 'hover' }, {
                trigger: () => h(NIcon, { size: 16, style: 'cursor: help; vertical-align: middle; margin-left: 4px;' },
                    { default: () => h(Info20Regular) }),
                default: () => [
                    h('span', { style: 'font-family: monospace; font-size: 12px;' }, `${props.label}: ${props.uuid}`),
                    h(NIcon, {
                        size: 16,
                        class: 'clickable',
                        style: 'cursor: pointer; vertical-align: middle; margin-left: 6px;',
                        onClick: () => copyUuid(props.uuid, props.label)
                    }, { default: () => h(Copy20Regular) })
                ]
            })
        }
    }
})

const attributedReleaseCols: DataTableColumns<any> = [
    {
        title: 'Component',
        key: 'component',
        render: (row) => row.componentDetails?.name || row.component?.slice(0, 8) || '—'
    },
    {
        title: 'Version',
        key: 'version',
        render: (row) => h(RouterLink as any,
            { to: { name: 'ReleaseView', params: { uuid: row.uuid } } },
            { default: () => row.version || '—' })
    },
    {
        title: 'Lifecycle',
        key: 'lifecycle',
        width: 140,
        render: (row) => h(NTag, { size: 'small', bordered: false }, { default: () => row.lifecycle || '—' })
    },
    {
        title: 'Created',
        key: 'createdDate',
        width: 110,
        render: (row) => fmtDate(row.createdDate)
    },
    {
        title: '',
        key: 'uuidInfo',
        width: 50,
        render: (row) => h(renderUuidTooltip, { uuid: row.uuid, label: 'Release UUID' })
    }
]

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
        title: 'Head SHA',
        key: 'sourceCodeEntry',
        render: (row) => {
            const sce = (pr.value?.commitDetails || []).find((c: any) => c.uuid === row.sourceCodeEntry)
            const display = sce?.commit ? shortSha(sce.commit) : (row.sourceCodeEntry || '').slice(0, 8)
            return h('span', {}, [
                h('code', {}, display),
                h(renderUuidTooltip, { uuid: row.sourceCodeEntry, label: 'SCE UUID' })
            ])
        }
    },
    {
        title: 'Releases',
        key: 'attributedReleases',
        width: 90,
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
        render: (row) => {
            // Prefer the historical superset (validatedReleaseDetails) so
            // events for releases superseded by newer-per-component still
            // resolve to a name; fall back to attributedReleases to
            // tolerate older backends that don't ship the new resolver.
            const lookup = pr.value?.validatedReleaseDetails || pr.value?.attributedReleases || []
            const matched = lookup.find((r: any) => r.uuid === row.release)
            const label = matched
                ? `${matched.componentDetails?.name || ''} ${matched.version || ''}`.trim()
                : (row.release || '').slice(0, 8)
            return h('span', {}, [
                h('code', {}, label),
                h(renderUuidTooltip, { uuid: row.release, label: 'Release UUID' })
            ])
        }
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
            await store.dispatch('fetchVcsRepos', pr.value.org)
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
.commit-msg { color: #555; margin-left: 0.5rem; }
.empty { color: #888; font-style: italic; }
.mt-4 { margin-top: 1.25rem; }
.agent-pill { margin-left: 4px; }
.agent-chip {
    display: inline-block;
    width: 1.2em;
    height: 1.2em;
    line-height: 1.2em;
    text-align: center;
    color: white;
    border-radius: 4px;
    font-size: 0.85em;
    margin-right: 4px;
    vertical-align: middle;
}
.agent-id { font-family: monospace; font-size: 0.85em; color: #888; }
</style>
