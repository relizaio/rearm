<template>
    <div class="subscriptions-pane">
        <div class="tab-toolbar">
            <div class="tab-toolbar-info">
                Rules that pick which events fire on which channels. One subscription matches a set of event types, optionally narrows via a filter, and fans out across one or more severity-gated routes.
            </div>
            <n-button
                v-if="canWrite"
                type="primary"
                size="small"
                @click="openCreateSubscription"
            >
                <template #icon><n-icon><CirclePlus /></n-icon></template>
                Add subscription
            </n-button>
        </div>

        <n-data-table
            :data="subscriptions"
            :columns="subscriptionColumns"
            :loading="subscriptionsLoading"
            :single-line="false"
            :bordered="false"
        />

        <!-- Subscription create/edit modal -->
        <n-modal v-model:show="showSubscriptionModal" preset="dialog" :show-icon="false">
            <n-card
                style="width: 760px"
                size="huge"
                :title="subForm.uuid ? `Edit subscription — ${subForm.name || ''}` : 'Add subscription'"
                :bordered="false"
                role="dialog"
                aria-modal="true"
            >
                <n-form :model="subForm">
                    <n-space vertical size="large">
                        <n-grid :cols="2" :x-gap="12">
                            <n-gi>
                                <n-form-item label="Name">
                                    <n-input v-model:value="subForm.name" placeholder="e.g. critical-vuln-oncall" />
                                </n-form-item>
                            </n-gi>
                            <n-gi>
                                <n-form-item label="Status">
                                    <n-select v-model:value="subForm.status" :options="subscriptionStatusOptions" />
                                </n-form-item>
                            </n-gi>
                        </n-grid>

                        <n-form-item label="Event types">
                            <n-select
                                v-model:value="subForm.eventTypes"
                                :options="eventTypeOptions"
                                multiple
                                placeholder="Pick one or more event types"
                            />
                        </n-form-item>

                        <n-form-item label="Filter mode">
                            <n-radio-group v-model:value="subForm.filterMode">
                                <n-radio-button value="PRESET">Preset (match all selected event types)</n-radio-button>
                                <n-radio-button value="ADVANCED">Advanced (CEL)</n-radio-button>
                            </n-radio-group>
                        </n-form-item>
                        <n-form-item v-if="subForm.filterMode === 'ADVANCED'" label="CEL expression">
                            <n-input
                                v-model:value="subForm.celExpression"
                                type="textarea"
                                :autosize="{ minRows: 3, maxRows: 8 }"
                                style="font-family: monospace; font-size: 12px;"
                                placeholder='e.g. event.severity == "CRITICAL" && size(affectedReleases) > 0'
                            />
                        </n-form-item>

                        <div class="routes-section">
                            <div class="routes-header">
                                <div class="routes-title">Routes</div>
                                <n-button size="tiny" @click="addRoute">
                                    <template #icon><n-icon><CirclePlus /></n-icon></template>
                                    Add route
                                </n-button>
                            </div>
                            <div class="routes-hint">
                                A route's Perspectives filter gates which events this channel actually <strong>delivers</strong> — it does not affect the in-app inbox/bell visibility. Leave empty for no restriction.
                            </div>
                            <div v-for="(r, i) in subForm.routes" :key="i" class="route-row">
                                <n-grid :cols="24" :x-gap="8" item-responsive>
                                    <n-gi :span="8">
                                        <n-form-item :label="i === 0 ? 'Minimum severity' : ''" :show-feedback="false">
                                            <n-select
                                                v-model:value="r.whenSeverityAtLeast"
                                                :options="severityOptions"
                                                placeholder="Any"
                                                clearable
                                            />
                                        </n-form-item>
                                    </n-gi>
                                    <n-gi :span="14">
                                        <n-form-item :label="i === 0 ? 'Channels' : ''" :show-feedback="false">
                                            <n-select
                                                v-model:value="r.channels"
                                                :options="channelOptions"
                                                multiple
                                                placeholder="Pick channels (and/or groups below)"
                                            />
                                        </n-form-item>
                                    </n-gi>
                                    <n-gi :span="2" class="route-remove-cell">
                                        <n-form-item :label="i === 0 ? ' ' : ''" :show-feedback="false">
                                            <n-button
                                                size="small"
                                                secondary
                                                type="error"
                                                :disabled="subForm.routes.length <= 1"
                                                @click="removeRoute(i)"
                                                :title="subForm.routes.length <= 1 ? 'Subscription must have at least one route' : 'Remove route'"
                                            >
                                                <template #icon><n-icon><Trash /></n-icon></template>
                                            </n-button>
                                        </n-form-item>
                                    </n-gi>
                                    <n-gi :span="22" :offset="0">
                                        <n-form-item :label="i === 0 ? 'Channel groups (optional)' : ''" :show-feedback="false">
                                            <n-select
                                                v-model:value="r.channelGroups"
                                                :options="channelGroupOptions"
                                                multiple
                                                placeholder="(none)"
                                                clearable
                                            />
                                        </n-form-item>
                                    </n-gi>
                                    <n-gi :span="22" :offset="0">
                                        <n-form-item :label="i === 0 ? 'Perspectives (delivery filter)' : ''" :show-feedback="false">
                                            <n-select
                                                v-model:value="r.perspectives"
                                                :options="perspectiveOptions"
                                                multiple
                                                clearable
                                                placeholder="All perspectives (no restriction)"
                                            />
                                        </n-form-item>
                                    </n-gi>
                                </n-grid>
                            </div>
                        </div>

                        <n-grid :cols="2" :x-gap="12">
                            <n-gi>
                                <n-form-item label="Dedup window (minutes, optional)">
                                    <n-input-number
                                        v-model:value="subForm.dedupWindowMinutes"
                                        :min="0"
                                        clearable
                                        placeholder="None"
                                        style="width: 100%;"
                                    />
                                </n-form-item>
                            </n-gi>
                            <n-gi>
                                <n-space>
                                    <n-form-item label="Rate limit max">
                                        <n-input-number
                                            v-model:value="subForm.rateLimitMaxPerWindow"
                                            :min="1"
                                            clearable
                                            placeholder="None"
                                            style="width: 100px;"
                                        />
                                    </n-form-item>
                                    <n-form-item label="per (min)">
                                        <n-input-number
                                            v-model:value="subForm.rateLimitWindowMinutes"
                                            :min="1"
                                            clearable
                                            placeholder="None"
                                            style="width: 100px;"
                                        />
                                    </n-form-item>
                                </n-space>
                            </n-gi>
                        </n-grid>

                        <n-alert v-if="subModalError" type="error" :show-icon="false">
                            {{ subModalError }}
                            <template v-if="isConflictError(subModalError)" #action>
                                <n-button size="small" type="primary" @click="loadSubscriptions">
                                    Reload from server
                                </n-button>
                            </template>
                        </n-alert>

                        <n-space>
                            <n-button
                                @click="saveSubscription"
                                type="primary"
                                :loading="savingSubscription"
                                :disabled="!subForm.name.trim() || subForm.eventTypes.length === 0 || subForm.routes.length === 0"
                            >
                                Save
                            </n-button>
                            <n-button @click="showSubscriptionModal = false">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
import { ref, computed, h, onMounted } from 'vue'
import { useStore } from 'vuex'
import {
    NDataTable, NButton, NIcon, NModal, NCard, NForm, NFormItem, NInput,
    NInputNumber, NSelect, NSpace, NAlert, NGrid, NGi, NTag,
    NRadioGroup, NRadioButton, useDialog, useMessage
} from 'naive-ui'
import { CirclePlus, Trash, Edit as EditIcon } from '@vicons/tabler'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import {
    ChannelRow, ChannelGroupRow, SubscriptionRow, TYPE_LABELS,
    subscriptionStatusOptions, eventTypeOptions, severityOptions,
    LIST_CHANNELS_QUERY, LIST_GROUPS_QUERY, LIST_SUBSCRIPTIONS_QUERY,
    extractError, isConflictError
} from '@/utils/notificationsCommon'

const props = defineProps<{
    orguuid: string
    isWritable: boolean
}>()

const dialog = useDialog()
const message = useMessage()
const store = useStore()

const orgUuid = computed<string>(() => props.orguuid)
const canWrite = computed<boolean>(() => props.isWritable)

interface SubscriptionRoute {
    whenSeverityAtLeast: string | null
    channels: string[]
    channelGroups: string[]
    // Delivery filter: restricts which events this route's channels deliver
    // to the listed perspectives. NOT the inbox/bell visibility gate. Empty
    // = no restriction (all perspectives).
    perspectives: string[]
    // Carries the as-loaded route object on edit so fields the UI still
    // doesn't model (andEnvIn, andLifecycleIn) survive an Edit → Save
    // round-trip instead of being silently stripped. channels +
    // channelGroups + perspectives overlay this last and win. Empty on Create.
    _raw?: Record<string, any>
}

interface SubscriptionForm {
    uuid: string | null
    expectedRevision: number | null
    name: string
    status: string
    eventTypes: string[]
    filterMode: string
    celExpression: string
    routes: SubscriptionRoute[]
    dedupWindowMinutes: number | null
    rateLimitMaxPerWindow: number | null
    rateLimitWindowMinutes: number | null
    // Carries the as-loaded filter object on edit so `presetConfigJson`
    // (and any other field the UI doesn't model yet) survives an
    // Edit → Save round-trip. Empty on Create.
    _rawFilter?: Record<string, any>
}

function freshRoute (): SubscriptionRoute {
    return { whenSeverityAtLeast: null, channels: [], channelGroups: [], perspectives: [] }
}

function freshSubscriptionForm (): SubscriptionForm {
    return {
        uuid: null,
        expectedRevision: null,
        name: '',
        status: 'ACTIVE',
        eventTypes: [],
        filterMode: 'PRESET',
        celExpression: '',
        routes: [freshRoute()],
        dedupWindowMinutes: null,
        rateLimitMaxPerWindow: null,
        rateLimitWindowMinutes: null,
    }
}

const channels = ref<ChannelRow[]>([])
const channelGroups = ref<ChannelGroupRow[]>([])
const subscriptions = ref<SubscriptionRow[]>([])
const subscriptionsLoading = ref<boolean>(false)
const showSubscriptionModal = ref<boolean>(false)
const savingSubscription = ref<boolean>(false)
const subModalError = ref<string>('')
const subForm = ref<SubscriptionForm>(freshSubscriptionForm())

const channelOptions = computed(() =>
    channels.value
        .filter(c => c.status === 'ENABLED')
        .map(c => ({ label: `${c.name} (${TYPE_LABELS[c.type] || c.type})`, value: c.uuid }))
)

const channelGroupOptions = computed(() =>
    channelGroups.value.map(g => ({
        label: `${g.name} (${g.channels.length} ch)`,
        value: g.uuid,
    }))
)

const perspectiveOptions = computed(() =>
    (store.getters.perspectivesOfOrg(orgUuid.value) || []).map((p: any) => ({
        label: p.name,
        value: p.uuid,
    }))
)

const UPSERT_SUBSCRIPTION_MUTATION = gql`
    mutation upsertNotificationSubscription($input: NotificationSubscriptionInput!) {
        upsertNotificationSubscription(input: $input) {
            uuid org resourceGroup name status eventTypes
            filter routes dedupWindowMinutes rateLimit revision
        }
    }
`

const SET_SUBSCRIPTION_STATUS_MUTATION = gql`
    mutation setNotificationSubscriptionStatus($uuid: ID!, $status: NotificationSubscriptionStatusEnum!) {
        setNotificationSubscriptionStatus(uuid: $uuid, status: $status) {
            uuid status
        }
    }
`

const DELETE_SUBSCRIPTION_MUTATION = gql`
    mutation deleteNotificationSubscription($uuid: ID!) {
        deleteNotificationSubscription(uuid: $uuid)
    }
`

async function loadChannels (): Promise<void> {
    try {
        const res = await graphqlClient.query({
            query: LIST_CHANNELS_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        channels.value = res.data?.notificationChannels || []
    } catch (e: any) {
        message.error(`Failed to load channels: ${extractError(e)}`)
    }
}

async function loadChannelGroups (): Promise<void> {
    try {
        const res = await graphqlClient.query({
            query: LIST_GROUPS_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        channelGroups.value = res.data?.notificationChannelGroups || []
    } catch (e: any) {
        message.error(`Failed to load channel groups: ${extractError(e)}`)
    }
}

async function loadSubscriptions (): Promise<void> {
    subscriptionsLoading.value = true
    try {
        const res = await graphqlClient.query({
            query: LIST_SUBSCRIPTIONS_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        subscriptions.value = res.data?.notificationSubscriptions || []
    } catch (e: any) {
        message.error(`Failed to load subscriptions: ${extractError(e)}`)
    } finally {
        subscriptionsLoading.value = false
    }
}

function openCreateSubscription (): void {
    subForm.value = freshSubscriptionForm()
    subModalError.value = ''
    showSubscriptionModal.value = true
}

function openEditSubscription (row: SubscriptionRow): void {
    const f = freshSubscriptionForm()
    f.uuid = row.uuid
    f.expectedRevision = row.revision
    f.name = row.name
    f.status = row.status
    f.eventTypes = [...(row.eventTypes || [])]
    f.dedupWindowMinutes = row.dedupWindowMinutes ?? null
    // Filter / routes / rateLimit ride as JSON-stringified blobs over the
    // wire. Parse them back into the structured form shape AND stash the
    // original blob on _raw / _rawFilter so unmodelled fields survive an
    // Edit → Save round-trip.
    try {
        const filter = row.filter ? JSON.parse(row.filter) : null
        if (filter) {
            f.filterMode = filter.mode || 'PRESET'
            f.celExpression = filter.celExpression || ''
            f._rawFilter = filter
        }
    } catch { /* fall back to PRESET defaults */ }
    try {
        const routes = row.routes ? JSON.parse(row.routes) : []
        if (Array.isArray(routes) && routes.length > 0) {
            f.routes = routes.map((r: any) => ({
                whenSeverityAtLeast: r.whenSeverityAtLeast || null,
                channels: Array.isArray(r.channels) ? [...r.channels] : [],
                channelGroups: Array.isArray(r.channelGroups) ? [...r.channelGroups] : [],
                perspectives: Array.isArray(r.perspectives) ? [...r.perspectives] : [],
                _raw: r,
            }))
        }
    } catch { /* fall back to one empty route */ }
    try {
        const rl = row.rateLimit ? JSON.parse(row.rateLimit) : null
        if (rl) {
            f.rateLimitMaxPerWindow = rl.maxPerWindow ?? null
            f.rateLimitWindowMinutes = rl.windowMinutes ?? null
        }
    } catch { /* skip */ }
    subForm.value = f
    subModalError.value = ''
    showSubscriptionModal.value = true
}

function addRoute (): void {
    subForm.value.routes.push(freshRoute())
}

function removeRoute (i: number): void {
    if (subForm.value.routes.length > 1) {
        subForm.value.routes.splice(i, 1)
    }
}

async function saveSubscription (): Promise<void> {
    subModalError.value = ''
    const f = subForm.value
    if (!f.name.trim() || f.eventTypes.length === 0 || f.routes.length === 0) {
        subModalError.value = 'Name, at least one event type, and at least one route are required.'
        return
    }
    // Every route must have at least one channel or one group; backend
    // rejects empty {channels, channelGroups} anyway, but catch it
    // client-side for a cleaner error path.
    const emptyRouteIdx = f.routes.findIndex(r =>
        (r.channels || []).length === 0 && (r.channelGroups || []).length === 0
    )
    if (emptyRouteIdx >= 0) {
        subModalError.value = `Route ${emptyRouteIdx + 1} has no channels or groups — pick at least one.`
        return
    }
    // Overlay the modeled filter fields on top of the original blob so
    // `presetConfigJson` survives instead of being nulled on every edit.
    const filterInput: any = {
        ...(f._rawFilter || {}),
        mode: f.filterMode,
        celExpression: f.filterMode === 'ADVANCED' ? f.celExpression : null,
    }
    const input: any = {
        uuid: f.uuid || undefined,
        expectedRevision: f.expectedRevision,
        org: orgUuid.value,
        name: f.name.trim(),
        status: f.status,
        eventTypes: f.eventTypes,
        filter: filterInput,
        // Spread the original route's still-unmodelled fields (andEnvIn,
        // andLifecycleIn) so an Edit → Save round-trip doesn't silently
        // strip them. The modeled fields overlay last and win.
        routes: f.routes.map(r => ({
            ...(r._raw || {}),
            whenSeverityAtLeast: r.whenSeverityAtLeast,
            channels: r.channels,
            channelGroups: r.channelGroups,
            perspectives: r.perspectives,
        })),
        dedupWindowMinutes: f.dedupWindowMinutes,
    }
    if (f.rateLimitMaxPerWindow && f.rateLimitWindowMinutes) {
        input.rateLimit = {
            maxPerWindow: f.rateLimitMaxPerWindow,
            windowMinutes: f.rateLimitWindowMinutes,
        }
    }
    savingSubscription.value = true
    try {
        await graphqlClient.mutate({
            mutation: UPSERT_SUBSCRIPTION_MUTATION,
            variables: { input },
        })
        showSubscriptionModal.value = false
        message.success(f.uuid ? 'Subscription updated' : 'Subscription created')
        await loadSubscriptions()
    } catch (e: any) {
        subModalError.value = extractError(e)
    } finally {
        savingSubscription.value = false
    }
}

async function toggleSubscriptionStatus (row: SubscriptionRow): Promise<void> {
    const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    try {
        await graphqlClient.mutate({
            mutation: SET_SUBSCRIPTION_STATUS_MUTATION,
            variables: { uuid: row.uuid, status: next },
        })
        message.success(`Subscription ${next.toLowerCase()}`)
        await loadSubscriptions()
    } catch (e: any) {
        message.error(`Status change failed: ${extractError(e)}`)
    }
}

function confirmDeleteSubscription (row: SubscriptionRow): void {
    dialog.warning({
        title: `Delete subscription "${row.name}"?`,
        content: 'This is permanent. The matching events stop dispatching immediately; History rows for past deliveries are retained.',
        positiveText: 'Delete',
        negativeText: 'Cancel',
        onPositiveClick: async () => {
            try {
                await graphqlClient.mutate({
                    mutation: DELETE_SUBSCRIPTION_MUTATION,
                    variables: { uuid: row.uuid },
                })
                message.success('Subscription deleted')
                await loadSubscriptions()
            } catch (e: any) {
                message.error(`Delete failed: ${extractError(e)}`)
            }
        },
    })
}

function subscriptionRouteCount (row: SubscriptionRow): number {
    try { return row.routes ? (JSON.parse(row.routes) || []).length : 0 }
    catch { return 0 }
}

const subscriptionColumns = computed(() => [
    { title: 'Name', key: 'name' },
    {
        title: 'Status', key: 'status',
        render: (row: SubscriptionRow) => h(
            NTag,
            {
                type: row.status === 'ACTIVE' ? 'success' : (row.status === 'PREVIEW' ? 'info' : 'warning'),
                size: 'small',
            },
            { default: () => row.status },
        ),
    },
    {
        title: 'Event types', key: 'eventTypes',
        render: (row: SubscriptionRow) => `${(row.eventTypes || []).length} type(s)`,
    },
    {
        title: 'Routes', key: 'routes',
        render: (row: SubscriptionRow) => `${subscriptionRouteCount(row)} route(s)`,
    },
    {
        title: 'Actions', key: 'actions',
        render: (row: SubscriptionRow) => h(NSpace, { size: 'small' }, {
            default: () => [
                h(NButton, {
                    size: 'tiny', secondary: true,
                    onClick: () => openEditSubscription(row),
                    disabled: !canWrite.value,
                }, { icon: () => h(NIcon, null, { default: () => h(EditIcon) }) }),
                h(NButton, {
                    size: 'tiny', secondary: true,
                    onClick: () => toggleSubscriptionStatus(row),
                    disabled: !canWrite.value,
                }, { default: () => row.status === 'ACTIVE' ? 'Disable' : 'Enable' }),
                h(NButton, {
                    size: 'tiny', secondary: true, type: 'error',
                    onClick: () => confirmDeleteSubscription(row),
                    disabled: !canWrite.value,
                }, { icon: () => h(NIcon, null, { default: () => h(Trash) }) }),
            ],
        }),
    },
])

onMounted(async () => {
    await Promise.all([
        loadChannels(),
        loadChannelGroups(),
        loadSubscriptions(),
        store.dispatch('fetchPerspectives', orgUuid.value),
    ])
})
</script>

<style scoped>
.tab-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 0 16px;
}
.tab-toolbar-info { font-size: 12.5px; color: var(--n-text-color-3, #888); }
.routes-section {
    border: 1px solid var(--n-border-color, #eee);
    border-radius: 6px;
    padding: 12px 14px;
    background: var(--n-color-embedded, transparent);
}
.routes-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
}
.routes-title { font-size: 13px; font-weight: 600; }
.routes-hint { font-size: 12px; color: var(--n-text-color-3, #888); margin-bottom: 10px; }
.route-row + .route-row { margin-top: 6px; }
.route-remove-cell { display: flex; align-items: flex-end; }
</style>
