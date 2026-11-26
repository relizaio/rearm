<template>
    <div class="upstream-of-org" data-testid="upstream-of-org">
        <div v-if="!available" data-testid="upstream-not-available">{{ notAvailableSentence }}</div>
        <template v-else>
            <h4>Upstream</h4>
            <n-tabs type="line" :value="activeTab" @update:value="onTab">
                <n-tab-pane name="follows" tab="Follows">
                    <n-space justify="space-between" align="center" class="upstream-follows-head">
                        <n-space align="center">
                            <n-select :value="sourceFilter" :options="sourceFilterOptions" clearable placeholder="All sources" style="width: 240px"
                                data-testid="upstream-follows-source-filter" @update:value="onSourceFilter" />
                            <n-select v-model:value="stateFilter" :options="STATE_OPTIONS" style="width: 180px" data-testid="upstream-follows-state-filter" />
                        </n-space>
                        <n-button v-if="isOrgAdmin && state === 'LIST'" type="primary" size="small" data-testid="upstream-follow-an-item" @click="startOpen = true">
                            Follow an item
                        </n-button>
                    </n-space>
                    <n-spin :show="loading">
                        <div v-if="state === 'FORBIDDEN'" data-testid="upstream-follows-forbidden">{{ forbiddenSentence }}</div>
                        <div v-else-if="state === 'ERROR'" class="upstream-error" data-testid="upstream-follows-error">{{ loadError }}</div>
                        <div v-else-if="state === 'LIST' && !follows.length" data-testid="upstream-follows-empty">
                            <span>{{ isOrgAdmin ? 'Nothing is followed yet. Choose Follow an item to start.' : 'Nothing is followed yet.' }}</span>
                            <div v-if="!activeSources.length" data-testid="upstream-follows-no-source">
                                Add an upstream source first.
                                <router-link :to="sourcesTab" data-testid="upstream-sources-tab-link">Upstream Sources</router-link>
                            </div>
                        </div>
                        <upstream-follows-list v-else-if="state === 'LIST'" :org-uuid="orgUuid" :follows="follows" :sources="sources" />
                    </n-spin>
                </n-tab-pane>
                <n-tab-pane name="tei" tab="By TEI">
                    <upstream-one-off-panel :org-uuid="orgUuid" :sources="sources" :can-write="canWrite" @source-added="loadSources" />
                </n-tab-pane>
            </n-tabs>
            <upstream-follow-start-modal
                v-if="isOrgAdmin"
                v-model:show="startOpen"
                :org-uuid="orgUuid"
                :sources="activeSources"
                :source="sourceFilter" />
        </template>
    </div>
</template>

<script lang="ts" setup>
// The Upstream page (ReARM Pro, TPF-185 design 3.7 and 3.10): the follows of the organization, and
// for an administrator the start of a follow; the tab "By TEI" (?tab=tei) ingests one release by its
// TEI. The page tests the edition gate first and sends no request when it is false.
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { NButton, NSelect, NSpace, NSpin, NTabPane, NTabs } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { isForbidden, isUuid, refusalOf, upstreamSourcesAvailable } from '@/utils/upstream'
import { GET_UPSTREAM_FOLLOWS } from '@/utils/upstreamFollowQueries'
import { GET_UPSTREAM_SOURCES } from '@/utils/upstreamSourceQueries'
import { forbiddenSentence, notAvailableSentence } from '@/utils/upstreamLabels'
import UpstreamFollowsList from './UpstreamFollowsList.vue'
import UpstreamFollowStartModal from './UpstreamFollowStartModal.vue'
import UpstreamOneOffPanel from './UpstreamOneOffPanel.vue'

const ALL_STATES = 'ALL'
const STATE_OPTIONS = [
    { label: 'Following', value: 'ACTIVE' },
    { label: 'Unfollowed', value: 'UNFOLLOWED' },
    { label: 'All', value: ALL_STATES },
]

type ListState = 'LOADING' | 'LIST' | 'FORBIDDEN' | 'ERROR'

const store = useStore()
const route = useRoute()
const router = useRouter()

const myUser = computed((): any => store.getters.myuser)
const available = computed((): boolean => upstreamSourcesAvailable(myUser.value?.installationType))
const orgUuid = computed((): string => String(route.params.orguuid ?? ''))
const isOrgAdmin = computed((): boolean => commonFunctions.getUserPermission(orgUuid.value, myUser.value).org === 'ADMIN')

const TABS = ['follows', 'tei']
const activeTab = computed((): string => {
    const t = route.query.tab
    return typeof t === 'string' && TABS.includes(t) ? t : 'follows'
})
const canWrite = computed((): boolean => commonFunctions.isWritable(orgUuid.value, myUser.value, 'ORG'))
const sourceFilter = computed((): string | null => {
    const s = route.query.source
    return typeof s === 'string' && isUuid(s) ? s : null
})
const stateFilter = ref<string>('ACTIVE')

const sources = ref<any[]>([])
const follows = ref<any[]>([])
const state = ref<ListState>('LOADING')
const loading = ref(false)
const loadError = ref('')
const startOpen = ref(false)

const activeSources = computed((): any[] => sources.value.filter(s => s.status === 'ACTIVE'))
const sourceFilterOptions = computed(() => sources.value.map(s => ({ label: s.name, value: s.uuid })))
const sourcesTab = computed(() => ({ name: 'OrgSettings', params: { orguuid: orgUuid.value }, query: { tab: 'upstreamSources' } }))

function onTab (tab: string) {
    router.replace({ query: { ...route.query, tab } })
}

function onSourceFilter (value: string | null) {
    const query: Record<string, any> = { ...route.query }
    if (value) query.source = value
    else delete query.source
    router.replace({ query })
}

async function loadSources () {
    try {
        const resp: any = await graphqlClient.query({
            query: GET_UPSTREAM_SOURCES, variables: { org: orgUuid.value, statuses: ['ACTIVE', 'ARCHIVED'] }, fetchPolicy: 'no-cache',
        })
        sources.value = resp?.data?.upstreamSources ?? []
    } catch {
        // The names are a convenience: without them the table shows the source's uuid.
        sources.value = []
    }
}

async function loadFollows () {
    loading.value = true
    const firstRead = state.value !== 'LIST'
    try {
        const resp: any = await graphqlClient.query({
            query: GET_UPSTREAM_FOLLOWS,
            variables: {
                org: orgUuid.value,
                source: sourceFilter.value,
                statuses: stateFilter.value === ALL_STATES ? null : [stateFilter.value],
            },
            fetchPolicy: 'no-cache',
        })
        follows.value = resp?.data?.upstreamFollows ?? []
        state.value = 'LIST'
        loadError.value = ''
    } catch (err) {
        const refusal = refusalOf(err)
        if (firstRead) state.value = isForbidden(refusal) ? 'FORBIDDEN' : 'ERROR'
        else state.value = 'ERROR'
        loadError.value = refusal.text
    } finally {
        loading.value = false
    }
}

watch([available, orgUuid], ([isAvailable, org]) => {
    if (!isAvailable || !isUuid(org)) return
    state.value = 'LOADING'
    loadFollows()
    loadSources()
}, { immediate: true })

watch([sourceFilter, stateFilter], () => {
    if (available.value && isUuid(orgUuid.value)) loadFollows()
})
</script>

<style scoped>
.upstream-follows-head {
    margin-bottom: 12px;
}
.upstream-error {
    color: #d03050;
}
</style>
