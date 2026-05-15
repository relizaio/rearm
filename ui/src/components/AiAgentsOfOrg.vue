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
            <n-button quaternary @click="openPolicies">Manage policies →</n-button>
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
                <h5>Registered agents</h5>
                <div v-if="agents.length === 0" class="empty">
                    No agents yet. Sessions auto-register an agent on first init.
                </div>
                <div class="agent-grid" v-else>
                    <n-card
                        v-for="a in rootAgents"
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
                            <div>
                                <div class="acard__name">
                                    {{ a.name }}<span v-if="a.agentIdentity" class="acard__id"> — {{ a.agentIdentity }}</span>
                                </div>
                                <div class="acard__sub" v-if="a.model">
                                    {{ a.model.publisher }} · <code>{{ a.model.name }}{{ a.model.version ? ' @ ' + a.model.version : '' }}</code>
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
                    @update:row-click="(row: any) => openSession(row.uuid)"
                />
            </div>
        </n-space>
    </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCard, NDataTable, NSpace, NSpin, NTag, DataTableColumns } from 'naive-ui'

const store = useStore()
const route = useRoute()
const router = useRouter()

const orgUuid = computed(() => route.params.orguuid as string)
const loading = ref<boolean>(false)
const kpis = ref<any>(null)
const agents = ref<any[]>([])
const openSessions = ref<any[]>([])

const rootAgents = computed(() => agents.value.filter(a => a.agentType === 'ROOT'))

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

function openSession (uuid: string) {
    router.push({ name: 'AiAgentSessionView', params: { uuid } })
}

function openPolicies () {
    router.push({ name: 'AiAgentPoliciesOfOrg', params: { orguuid: orgUuid.value } })
}

const sessionColumns: DataTableColumns<any> = [
    { title: 'Session', key: 'clientSessionId', width: 200, render: (row: any) => h('code', null, row.clientSessionId ?? row.uuid.slice(0, 8)) },
    { title: 'Title', key: 'title' },
    { title: 'Branch', key: 'branch', render: (row: any) => row.branch ? h('code', null, row.branch) : '—' },
    { title: 'Started', key: 'startedAt', render: (row: any) => row.startedAt ? new Date(row.startedAt).toLocaleString() : '—' },
    { title: 'Artifacts', key: 'artifacts', width: 110, render: (row: any) => row.artifacts?.length ?? 0 },
    { title: 'Commits', key: 'commits', width: 100, render: (row: any) => row.commits?.length ?? 0 },
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
.agent-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 12px; margin-top: 8px; }
.acard__top { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.acard__mark { width: 36px; height: 36px; border-radius: 8px; color: white; display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: 18px; }
.acard__name { font-weight: 600; }
.acard__id { font-weight: 400; font-family: monospace; font-size: 12px; color: var(--n-text-color-3, #666); }
.acard__sub { font-size: 12px; color: var(--n-text-color-3, #666); }
.acard__row { display: flex; gap: 24px; }
.acard__num { font-size: 22px; font-weight: 600; margin-right: 6px; }
.acard__lab { font-size: 11px; color: var(--n-text-color-3, #666); text-transform: uppercase; letter-spacing: 0.04em; }
.empty { color: var(--n-text-color-3, #666); font-style: italic; padding: 12px 0; }
</style>
