<template>
    <div class="aiAgentsOfOrg">
        <div class="page-head">
            <div>
                <h4>AI Agents</h4>
                <p class="sub">
                    Coding agents registered to this workspace. Each agent runs sessions that
                    produce artifacts, commits, pull requests and releases.
                </p>
            </div>
            <n-space>
                <n-button quaternary @click="openPolicies">Manage policies →</n-button>
            </n-space>
        </div>

        <n-spin v-if="loading" size="small"/>

        <n-space vertical :size="20">
            <!-- KPI row (cross-cutting dashboard metrics) -->
            <div class="kpis" v-if="kpis">
                <n-card class="kpi" size="small">
                    <div class="kpi__l">Active sessions</div>
                    <div class="kpi__v">{{ kpis.activeSessions }}</div>
                    <div class="kpi__d">across {{ kpis.registeredAgents }} agents</div>
                </n-card>
                <n-card class="kpi" size="small">
                    <div class="kpi__l">Closed (30d)</div>
                    <div class="kpi__v">{{ kpis.closedSessions30d }}</div>
                </n-card>
                <n-card class="kpi" size="small">
                    <div class="kpi__l">Artifacts produced</div>
                    <div class="kpi__v">{{ kpis.artifactsProduced7d }}</div>
                    <div class="kpi__d">last 7 days</div>
                </n-card>
                <n-card class="kpi" size="small">
                    <div class="kpi__l">Registered agents</div>
                    <div class="kpi__v">{{ kpis.registeredAgents }}</div>
                </n-card>
            </div>

            <!-- Agent card grid -->
            <div>
                <div class="section-head">
                    <h5>Registered agents</h5>
                    <n-button
                        v-if="rootAgents.length > HERO_LIMIT"
                        quaternary size="small"
                        @click="openAllAgents"
                    >
                        See all {{ rootAgents.length }} →
                    </n-button>
                </div>
                <div v-if="agents.length === 0" class="empty">
                    No agents yet. Sessions auto-register an agent on first init.
                </div>
                <div class="agent-grid" v-else>
                    <n-card
                        v-for="a in heroAgents"
                        :key="a.uuid"
                        class="acard"
                        size="small"
                        hoverable
                        @click="openAgent(a.uuid)"
                    >
                        <div class="acard__top">
                            <div class="acard__mark" :style="{ background: a.color || '#888' }">
                                {{ a.iconKind || '◆' }}
                            </div>
                            <div class="acard__head">
                                <div class="acard__name">
                                    <span>{{ a.effectiveDisplayName || a.name }}</span>
                                    <n-icon v-if="isOrgAdmin" class="acard__edit-name" size="16"
                                            title="Edit display name" @click.stop="openEditName(a)"><EditIcon/></n-icon>
                                </div>
                                <div class="acard__ids">
                                    <n-tooltip trigger="hover">
                                        <template #trigger>
                                            <code class="acard__chip"><span class="acard__chip-l">uuid</span>{{ shortUuid(a.uuid) }}</code>
                                        </template>
                                        ReARM-issued row uuid. Used in the URL, in commit
                                        trailers (<code>ReARM-Agent</code>), and as the
                                        owner reference for signing keys. Unique per agent.
                                    </n-tooltip>
                                    <n-tooltip v-if="a.agentIdentity" trigger="hover" :width="320">
                                        <template #trigger>
                                            <code class="acard__chip" :class="identityShareCount(a.agentIdentity) > 1 ? 'acard__chip--shared' : ''">
                                                <span class="acard__chip-l">identity</span>{{ shortUuid(a.agentIdentity) }}<span v-if="identityShareCount(a.agentIdentity) > 1" class="acard__shared">+{{ identityShareCount(a.agentIdentity) - 1 }}</span>
                                            </code>
                                        </template>
                                        Credential-scoped identity. Agents registered through the same
                                        FREEFORM key (or OIDC subject, when wired) share one identity —
                                        their commits / sessions roll up to the same credential.
                                        <span v-if="identityShareCount(a.agentIdentity) > 1">
                                            <br><br>This identity is shared with
                                            {{ identityShareCount(a.agentIdentity) - 1 }} other
                                            agent{{ identityShareCount(a.agentIdentity) > 2 ? 's' : '' }}
                                            in this org.
                                        </span>
                                    </n-tooltip>
                                </div>
                                <div class="acard__sub" v-if="a.model">
                                    <template v-if="a.model.publisher">{{ a.model.publisher }} · </template><code>{{ a.model.name }}{{ a.model.version && a.model.version !== 'unknown' ? ' @ ' + a.model.version : '' }}</code>
                                </div>
                            </div>
                            <n-tag v-if="a.status === 'ARCHIVED'" size="small" type="default">
                                ARCHIVED
                            </n-tag>
                        </div>
                        <div class="acard__row">
                            <div><span class="acard__num">{{ a.sessionCounts?.openSessions ?? 0 }}</span><span class="acard__lab">open</span></div>
                            <div><span class="acard__num">{{ a.sessionCounts?.closedSessions ?? 0 }}</span><span class="acard__lab">closed</span></div>
                            <div><span class="acard__num">{{ a.subAgents?.length ?? 0 }}</span><span class="acard__lab">sub-agents</span></div>
                        </div>
                        <div class="acard__seen">
                            <span>
                                <span class="acard__lab">first seen</span>
                                {{ formatDate(a.createdDate) }}
                                <n-tooltip v-if="a.createdDate" trigger="hover" placement="top">
                                    <template #trigger>
                                        <n-icon class="acard__info" :size="13" @click.stop>
                                            <Info20Regular/>
                                        </n-icon>
                                    </template>
                                    {{ formatDateTimePrecise(a.createdDate) }}
                                </n-tooltip>
                            </span>
                            <span>
                                <span class="acard__lab">last seen</span>
                                {{ formatDate(a.lastActivityAt) }}
                                <n-tooltip v-if="a.lastActivityAt" trigger="hover" placement="top">
                                    <template #trigger>
                                        <n-icon class="acard__info" :size="13" @click.stop>
                                            <Info20Regular/>
                                        </n-icon>
                                    </template>
                                    {{ formatDateTimePrecise(a.lastActivityAt) }}
                                </n-tooltip>
                            </span>
                        </div>
                    </n-card>
                </div>
            </div>

            <!-- Live sessions table -->
            <div>
                <h5>Live sessions</h5>
                <n-data-table
                    :columns="sessionColumns"
                    :data="openSessions"
                    :pagination="{ pageSize: 10 }"
                />
            </div>
        </n-space>

        <n-modal v-model:show="showEditName" preset="card" title="Edit agent display name" style="width: 480px;">
            <n-input v-model:value="nameDraft" placeholder="Display name (blank = use registration name)"
                     @keyup.enter="saveName"/>
            <p class="edit-name-hint">
                Cosmetic label shown in the dashboard. The agent's registration name
                (supplied by the runtime via --agent-name) is unchanged.
            </p>
            <template #footer>
                <n-space justify="end">
                    <n-button @click="showEditName = false">Cancel</n-button>
                    <n-button type="primary" :loading="savingName" @click="saveName">Save</n-button>
                </n-space>
            </template>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCard, NDataTable, NIcon, NInput, NModal, NSpace, NSpin, NTag, NTooltip, DataTableColumns, useNotification } from 'naive-ui'
import { Info20Regular } from '@vicons/fluent'
import { Edit as EditIcon } from '@vicons/tabler'

const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

const orgUuid = computed(() => route.params.orguuid as string)

const myUser = computed<any>(() => store.getters.myuser)
const isOrgAdmin = computed<boolean>(() => {
    const org = orgUuid.value
    const perms = myUser.value?.permissions?.permissions
    if (!org || !perms) return false
    return perms.some((p: any) => p.org === org && p.object === org
        && p.scope === 'ORGANIZATION' && p.type === 'ADMIN')
})

const showEditName = ref<boolean>(false)
const editingAgent = ref<any>(null)
const nameDraft = ref<string>('')
const savingName = ref<boolean>(false)

function openEditName (a: any) {
    editingAgent.value = a
    nameDraft.value = a.displayName ?? ''
    showEditName.value = true
}

async function saveName () {
    if (!editingAgent.value) return
    savingName.value = true
    try {
        const updated = await store.dispatch('setAgentDisplayName', {
            uuid: editingAgent.value.uuid,
            displayName: nameDraft.value.trim() || null,
        })
        editingAgent.value.displayName = updated.displayName
        showEditName.value = false
        editingAgent.value = null
        notification.success({ content: 'Display name saved' })
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}` })
    } finally {
        savingName.value = false
    }
}
const loading = ref<boolean>(false)
const kpis = ref<any>(null)
const agents = ref<any[]>([])
const openSessions = ref<any[]>([])

const HERO_LIMIT = 8

const rootAgents = computed(() => agents.value.filter(a => a.agentType === 'ROOT'))

// Hero shows the 8 most recently active agents — abandoned agents
// shouldn't crowd out live ones once the org grows past a handful.
// "See all" link surfaces the rest in a dedicated table view.
const heroAgents = computed(() =>
    [...rootAgents.value]
        .sort((a, b) => {
            const lhs = a.lastActivityAt ?? a.createdDate ?? ''
            const rhs = b.lastActivityAt ?? b.createdDate ?? ''
            return rhs.localeCompare(lhs)
        })
        .slice(0, HERO_LIMIT)
)

function formatDate (iso: string | null | undefined): string {
    if (!iso) return '—'
    const d = new Date(iso)
    return isNaN(d.getTime()) ? '—' : d.toLocaleDateString('en-CA')
}

// Second-precision tooltip variant — the visible card text stays at
// day granularity to keep the row dense, the tooltip surfaces the
// exact moment for users who care about ordering bursts of activity.
function formatDateTimePrecise (iso: string | null | undefined): string {
    if (!iso) return ''
    const d = new Date(iso)
    if (isNaN(d.getTime())) return ''
    return d.toLocaleString('en-CA', {
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
        hour12: false,
    })
}

const identityCounts = computed<Record<string, number>>(() => {
    const m: Record<string, number> = {}
    for (const a of agents.value) {
        if (!a?.agentIdentity) continue
        m[a.agentIdentity] = (m[a.agentIdentity] || 0) + 1
    }
    return m
})

function identityShareCount (id: string | null | undefined): number {
    if (!id) return 0
    return identityCounts.value[id] || 0
}

function shortUuid (u: string | null | undefined): string {
    return u ? `${u.slice(0, 8)}…${u.slice(-4)}` : ''
}

onMounted(async () => {
    await refreshAll()
})

async function refreshAll () {
    loading.value = true
    try {
        const [k, a, s] = await Promise.all([
            store.dispatch('fetchAgentDashboardKpis', orgUuid.value),
            store.dispatch('fetchAgentsOfOrg', orgUuid.value),
            store.dispatch('fetchSessionsOfOrg', { orgUuid: orgUuid.value, statuses: ['OPEN'] }),
        ])
        kpis.value = k
        agents.value = a ?? []
        openSessions.value = s ?? []
    } finally {
        loading.value = false
    }
}

function openAgent (uuid: string) {
    router.push({ name: 'AiAgentView', params: { uuid } })
}

function openAllAgents () {
    router.push({ name: 'AiAgentsTableOfOrg', params: { orguuid: orgUuid.value } })
}

function openSession (uuid: string) {
    router.push({ name: 'AiAgentSessionView', params: { uuid } })
}

function openPolicies () {
    // Policies live under Org Settings → Policies → AI Agent Policies (inner
    // tab). The outer-tab hint is consumed by OrgSettings on mount; the inner
    // n-tabs default-value picks up the AI Agent Policies pane.
    router.push({
        name: 'OrgSettings',
        params: { orguuid: orgUuid.value },
        query: { tab: 'policies' },
    })
}

const sessionColumns: DataTableColumns<any> = [
    {
        title: 'Session',
        key: 'clientSessionId',
        width: 220,
        render: (row: any) => h('a', {
            href: '#',
            onClick: (e: Event) => { e.preventDefault(); openSession(row.uuid) },
        }, h('code', null, row.clientSessionId ?? row.uuid.slice(0, 8))),
    },
    { title: 'Title', key: 'title' },
    { title: 'Started', key: 'startedAt', render: (row: any) => row.startedAt ? new Date(row.startedAt).toLocaleString('en-CA') : '—' },
    { title: 'Artifacts', key: 'artifacts', width: 110, render: (row: any) => row.artifacts?.length ?? 0 },
    { title: 'Commits', key: 'commits', width: 100, render: (row: any) => row.commits?.length ?? 0 },
    { title: 'PRs', key: 'pullRequests', width: 80, render: (row: any) => row.pullRequests?.length ?? 0 },
    { title: 'Releases', key: 'releases', width: 100, render: (row: any) => row.releases?.length ?? 0 },
]
</script>

<style scoped>
.aiAgentsOfOrg { padding: 16px; }
.sub { color: var(--n-text-color-3, #666); font-size: 13px; margin-bottom: 16px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.kpi__l { text-transform: uppercase; font-size: 11px; letter-spacing: 0.06em; color: var(--n-text-color-3, #666); }
.kpi__v { font-size: 32px; font-weight: 600; margin-top: 4px; }
.kpi__d { font-size: 12px; color: var(--n-text-color-3, #666); margin-top: 2px; }
/* Cap at 4 cards per row so each card has room to breathe. The
 * max(280px, …) keeps the auto-fit floor on narrow screens — once the
 * viewport drops below ~1130px the 23% lower bound stops winning and
 * the grid falls back to as many 280px-min cards as fit. */
.agent-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(max(280px, 23%), 1fr)); gap: 12px; margin-top: 8px; }
.acard__top { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 12px; }
.acard__mark { width: 36px; height: 36px; border-radius: 8px; color: white; display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: 18px; flex-shrink: 0; }
.acard__head { flex: 1; min-width: 0; }
.acard__name { font-weight: 600; display: flex; align-items: center; gap: 6px; }
.acard__edit-name { cursor: pointer; color: var(--n-text-color-3, #888); }
.acard__edit-name:hover { color: var(--n-primary-color, #18a058); }
.edit-name-hint { font-size: 12px; color: var(--n-text-color-3, #666); margin: 8px 0 0; }
.acard__ids { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
.acard__chip { font-family: monospace; font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--n-color-embedded, #f5f5f5); color: var(--n-text-color-2, #555); border: 1px solid transparent; }
.acard__chip-l { text-transform: uppercase; font-size: 9px; letter-spacing: 0.06em; color: var(--n-text-color-3, #888); margin-right: 4px; }
.acard__chip--shared { background: #fff8e1; border-color: #ffe082; color: #5d4037; }
.acard__shared { margin-left: 4px; font-size: 10px; font-weight: 600; color: #b26a00; }
.acard__sub { font-size: 12px; color: var(--n-text-color-3, #666); margin-top: 4px; }
.acard__row { display: flex; gap: 24px; }
.acard__num { font-size: 22px; font-weight: 600; margin-right: 6px; }
.acard__lab { font-size: 11px; color: var(--n-text-color-3, #666); text-transform: uppercase; letter-spacing: 0.04em; }
.acard__seen { display: flex; gap: 16px; margin-top: 10px; font-size: 12px; color: var(--n-text-color-3, #666); }
.acard__seen .acard__lab { margin-right: 6px; }
.acard__info { vertical-align: middle; margin-left: 4px; color: var(--n-text-color-3, #999); cursor: help; }
.acard__info:hover { color: var(--n-text-color-2, #555); }
.section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.empty { color: var(--n-text-color-3, #666); font-style: italic; padding: 12px 0; }
</style>
