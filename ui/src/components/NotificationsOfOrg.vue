<template>
    <div class="notifications-page">
        <div class="page-header">
            <h2 class="page-title">Notifications</h2>
            <div class="page-subtitle">Security and operational events routed to your destinations of choice.</div>
        </div>

        <n-tabs v-model:value="activeTab" type="segment" animated @update:value="onTabChange">
            <n-tab-pane name="channels" tab="Channels">
                <div class="tab-toolbar">
                    <div class="tab-toolbar-info">
                        Destinations that receive notification deliveries. One channel = one webhook / recipient list / Sentinel DCR.
                    </div>
                    <n-button
                        v-if="canWrite"
                        type="primary"
                        size="small"
                        @click="openCreateChannel"
                    >
                        <template #icon><n-icon><CirclePlus /></n-icon></template>
                        Add channel
                    </n-button>
                </div>

                <n-data-table
                    :data="channels"
                    :columns="channelColumns"
                    :loading="channelsLoading"
                    :single-line="false"
                    :bordered="false"
                />
            </n-tab-pane>

            <n-tab-pane name="subscriptions" tab="Subscriptions">
                <n-empty description="Subscriptions UI lands in the next Phase 7 slice." />
            </n-tab-pane>

            <n-tab-pane name="history" tab="History">
                <n-empty description="Delivery history UI lands in a later Phase 7 slice." />
            </n-tab-pane>
        </n-tabs>

        <!-- Create / Edit channel modal -->
        <n-modal v-model:show="showChannelModal" preset="dialog" :show-icon="false">
            <n-card
                style="width: 640px"
                size="huge"
                :title="channelForm.uuid ? `Edit channel — ${channelForm.name || ''}` : 'Add channel'"
                :bordered="false"
                role="dialog"
                aria-modal="true"
            >
                <n-form :model="channelForm">
                    <n-space vertical size="large">
                        <n-form-item label="Name">
                            <n-input v-model:value="channelForm.name" placeholder="e.g. sec-oncall-slack" />
                        </n-form-item>

                        <n-form-item label="Type">
                            <n-select
                                v-model:value="channelForm.type"
                                :options="typeOptions"
                                :disabled="!!channelForm.uuid"
                                placeholder="Pick a channel type"
                            />
                            <template #feedback>
                                <span v-if="channelForm.uuid" class="muted-12">
                                    Type cannot be changed after creation.
                                </span>
                            </template>
                        </n-form-item>

                        <!-- Slack -->
                        <template v-if="channelForm.type === 'SLACK'">
                            <n-form-item label="Slack incoming webhook URL">
                                <n-input
                                    v-model:value="channelForm.slack.webhookUrl"
                                    :placeholder="channelForm.uuid ? 'Re-enter to update; leave blank to keep existing' : 'https://hooks.slack.com/services/...'"
                                />
                            </n-form-item>
                        </template>

                        <!-- MS Teams -->
                        <template v-if="channelForm.type === 'MS_TEAMS'">
                            <n-form-item label="Teams Workflows webhook URL">
                                <n-input
                                    v-model:value="channelForm.teams.webhookUrl"
                                    :placeholder="channelForm.uuid ? 'Re-enter to update; leave blank to keep existing' : 'https://...powerplatform.com/... or .../logic.azure.com/...'"
                                />
                            </n-form-item>
                        </template>

                        <!-- Generic webhook -->
                        <template v-if="channelForm.type === 'WEBHOOK'">
                            <n-form-item label="Webhook URL">
                                <n-input
                                    v-model:value="channelForm.webhook.url"
                                    :placeholder="channelForm.uuid ? 'Re-enter to update; leave blank to keep existing' : 'https://your-receiver.example.com/path'"
                                />
                            </n-form-item>
                            <n-form-item label="Auth">
                                <n-select v-model:value="channelForm.webhook.authScheme" :options="webhookAuthOptions" />
                            </n-form-item>
                            <n-form-item
                                v-if="channelForm.webhook.authScheme === 'BEARER'"
                                label="Bearer token"
                            >
                                <n-input
                                    v-model:value="channelForm.webhook.secret"
                                    type="password"
                                    show-password-on="click"
                                    :placeholder="channelForm.uuid ? 'Re-enter to update; leave blank to keep existing' : 'Secret bearer token'"
                                />
                            </n-form-item>
                            <n-form-item
                                v-if="channelForm.webhook.authScheme === 'HMAC_SHA256'"
                                label="HMAC shared secret"
                            >
                                <n-input
                                    v-model:value="channelForm.webhook.secret"
                                    type="password"
                                    show-password-on="click"
                                    :placeholder="channelForm.uuid ? 'Re-enter to update; leave blank to keep existing' : 'Shared HMAC secret'"
                                />
                            </n-form-item>
                        </template>

                        <!-- Sentinel -->
                        <template v-if="channelForm.type === 'SENTINEL'">
                            <n-grid :cols="2" :x-gap="12">
                                <n-gi>
                                    <n-form-item label="Tenant ID">
                                        <n-input v-model:value="channelForm.sentinel.tenantId" :placeholder="sentinelPlaceholder" />
                                    </n-form-item>
                                </n-gi>
                                <n-gi>
                                    <n-form-item label="Client ID">
                                        <n-input v-model:value="channelForm.sentinel.clientId" :placeholder="sentinelPlaceholder" />
                                    </n-form-item>
                                </n-gi>
                            </n-grid>
                            <n-form-item label="Client Secret">
                                <n-input
                                    v-model:value="channelForm.sentinel.clientSecret"
                                    type="password"
                                    show-password-on="click"
                                    :placeholder="sentinelPlaceholder"
                                />
                            </n-form-item>
                            <n-form-item label="DCR endpoint">
                                <n-input v-model:value="channelForm.sentinel.dcrEndpoint" :placeholder="sentinelPlaceholder || 'https://dce-...ingest.monitor.azure.com'" />
                            </n-form-item>
                            <n-grid :cols="2" :x-gap="12">
                                <n-gi>
                                    <n-form-item label="DCR immutable ID">
                                        <n-input v-model:value="channelForm.sentinel.dcrImmutableId" :placeholder="sentinelPlaceholder" />
                                    </n-form-item>
                                </n-gi>
                                <n-gi>
                                    <n-form-item label="Stream name">
                                        <n-input v-model:value="channelForm.sentinel.streamName" :placeholder="sentinelPlaceholder || 'Custom-ReARMNotifications_CL'" />
                                    </n-form-item>
                                </n-gi>
                            </n-grid>
                        </template>

                        <n-alert v-if="modalError" type="error" :show-icon="false">
                            {{ modalError }}
                        </n-alert>

                        <n-space>
                            <n-button @click="saveChannel" type="primary" :loading="savingChannel" :disabled="!channelForm.name || !channelForm.type">
                                Save
                            </n-button>
                            <n-button @click="showChannelModal = false">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

        <!-- Test result modal -->
        <n-modal v-model:show="showTestModal" preset="dialog" :show-icon="false">
            <n-card style="width: 480px" size="huge" :title="`Test channel — ${testState.channelName}`" :bordered="false" role="dialog" aria-modal="true">
                <n-space vertical>
                    <n-alert v-if="testState.status === 'PENDING'" type="info" :show-icon="false">
                        Sending a synthetic event… polling for delivery status.
                    </n-alert>
                    <n-alert v-else-if="testState.status === 'SENT'" type="success" :show-icon="false">
                        Delivered successfully in {{ testState.attempts }} attempt(s).
                        Now verify the message reached the destination tool.
                    </n-alert>
                    <n-alert v-else-if="testState.status === 'FAILED'" type="error" :show-icon="false">
                        Delivery failed: {{ testState.lastError || 'no error message returned' }}
                    </n-alert>
                    <n-alert v-else-if="testState.status === 'ERROR'" type="error" :show-icon="false">
                        Test could not be started: {{ testState.lastError }}
                    </n-alert>
                    <n-alert v-else-if="testState.status === 'TIMEOUT'" type="warning" :show-icon="false">
                        Delivery did not reach a terminal state within the poll window. Check History for the latest state.
                    </n-alert>
                    <div v-if="testState.eventUuid" class="muted-12">
                        Event UUID: <code>{{ testState.eventUuid }}</code>
                    </div>
                    <n-button @click="showTestModal = false">Close</n-button>
                </n-space>
            </n-card>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
import { ref, computed, h, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import {
    NTabs, NTabPane, NDataTable, NButton, NIcon, NEmpty, NModal, NCard, NForm,
    NFormItem, NInput, NSelect, NSpace, NAlert, NGrid, NGi, NTag, useDialog, useMessage
} from 'naive-ui'
import { CirclePlus, Trash } from '@vicons/tabler'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import { Edit as EditIcon } from '@vicons/tabler'
import commonFunctions from '@/utils/commonFunctions'

interface ChannelRow {
    uuid: string
    org: string
    resourceGroup: string | null
    name: string
    type: string
    status: string
}

const route = useRoute()
const router = useRouter()
const store = useStore()
const dialog = useDialog()
const message = useMessage()

const orgUuid = computed<string>(() => route.params.orguuid as string)
const myuser = computed<any>(() => store.getters.myuser)
const userPermission = ref<string>('')

const canWrite = computed<boolean>(() =>
    userPermission.value === 'ADMIN' || userPermission.value === 'READ_WRITE'
)

const activeTab = ref<string>((route.query.tab as string) || 'channels')

function onTabChange (tab: string): void {
    router.replace({ query: { ...route.query, tab } })
}

const channels = ref<ChannelRow[]>([])
const channelsLoading = ref<boolean>(false)

const TYPE_LABELS: Record<string, string> = {
    SLACK: 'Slack',
    WEBHOOK: 'Webhook',
    MS_TEAMS: 'MS Teams',
    SENTINEL: 'Sentinel',
    EMAIL: 'Email',
}

// Email omitted until the Phase 9 dispatcher lands in rearm-core.
const typeOptions = [
    { label: 'Slack', value: 'SLACK' },
    { label: 'Microsoft Teams', value: 'MS_TEAMS' },
    { label: 'Generic Webhook', value: 'WEBHOOK' },
    { label: 'Azure Sentinel', value: 'SENTINEL' },
]

const webhookAuthOptions = [
    { label: 'None (URL secrecy + TLS only)', value: 'NONE' },
    { label: 'Bearer token', value: 'BEARER' },
    { label: 'HMAC-SHA256', value: 'HMAC_SHA256' },
]

const showChannelModal = ref<boolean>(false)
const savingChannel = ref<boolean>(false)
const modalError = ref<string>('')

interface ChannelForm {
    uuid: string | null
    name: string
    type: string | null
    status: string
    slack: { webhookUrl: string }
    teams: { webhookUrl: string }
    webhook: { url: string; authScheme: string; secret: string }
    sentinel: {
        tenantId: string
        clientId: string
        clientSecret: string
        dcrEndpoint: string
        dcrImmutableId: string
        streamName: string
    }
}

function freshForm (): ChannelForm {
    return {
        uuid: null,
        name: '',
        type: null,
        status: 'ENABLED',
        slack: { webhookUrl: '' },
        teams: { webhookUrl: '' },
        webhook: { url: '', authScheme: 'NONE', secret: '' },
        sentinel: { tenantId: '', clientId: '', clientSecret: '', dcrEndpoint: '', dcrImmutableId: '', streamName: '' },
    }
}

const channelForm = ref<ChannelForm>(freshForm())

const sentinelPlaceholder = computed<string>(() =>
    channelForm.value.uuid ? 'Re-enter to update; leave blank to keep existing' : ''
)

// Test channel state
interface TestState {
    channelUuid: string | null
    channelName: string
    eventUuid: string | null
    status: 'IDLE' | 'PENDING' | 'SENT' | 'FAILED' | 'ERROR' | 'TIMEOUT'
    attempts: number
    lastError: string
}

const testState = ref<TestState>({
    channelUuid: null, channelName: '', eventUuid: null,
    status: 'IDLE', attempts: 0, lastError: '',
})
const showTestModal = ref<boolean>(false)

// ---- GraphQL queries / mutations -----------------------------------------

const LIST_CHANNELS_QUERY = gql`
    query notificationChannels($orgUuid: ID!) {
        notificationChannels(orgUuid: $orgUuid) {
            uuid org resourceGroup name type status
        }
    }
`

const UPSERT_CHANNEL_MUTATION = gql`
    mutation upsertNotificationChannel($input: NotificationChannelInput!) {
        upsertNotificationChannel(input: $input) {
            uuid org resourceGroup name type status
        }
    }
`

const SET_STATUS_MUTATION = gql`
    mutation setNotificationChannelStatus($uuid: ID!, $status: NotificationChannelStatusEnum!) {
        setNotificationChannelStatus(uuid: $uuid, status: $status) {
            uuid status
        }
    }
`

const DELETE_CHANNEL_MUTATION = gql`
    mutation deleteNotificationChannel($uuid: ID!) {
        deleteNotificationChannel(uuid: $uuid)
    }
`

const TEST_CHANNEL_MUTATION = gql`
    mutation testNotificationChannel($channelUuid: ID!) {
        testNotificationChannel(channelUuid: $channelUuid) {
            uuid status
        }
    }
`

const LIST_DELIVERIES_QUERY = gql`
    query notificationDeliveries($orgUuid: ID!, $eventUuid: ID, $limit: Int) {
        notificationDeliveries(orgUuid: $orgUuid, eventUuid: $eventUuid, limit: $limit) {
            items { uuid status attemptCount lastError sentAt }
        }
    }
`

// ---- Data loading --------------------------------------------------------

async function loadChannels (): Promise<void> {
    channelsLoading.value = true
    try {
        const res = await graphqlClient.query({
            query: LIST_CHANNELS_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        channels.value = res.data?.notificationChannels || []
    } catch (e: any) {
        message.error(`Failed to load channels: ${extractError(e)}`)
    } finally {
        channelsLoading.value = false
    }
}

// ---- Create / Edit -------------------------------------------------------

function openCreateChannel (): void {
    channelForm.value = freshForm()
    modalError.value = ''
    showChannelModal.value = true
}

function openEditChannel (row: ChannelRow): void {
    // Secrets aren't returned by the read surface — edit form is metadata
    // only; per-type config fields stay blank as placeholders prompt the
    // user to re-enter only if they want to update the credential.
    const f = freshForm()
    f.uuid = row.uuid
    f.name = row.name
    f.type = row.type
    f.status = row.status
    channelForm.value = f
    modalError.value = ''
    showChannelModal.value = true
}

async function saveChannel (): Promise<void> {
    modalError.value = ''
    const f = channelForm.value
    if (!f.type || !f.name.trim()) {
        modalError.value = 'Name and type are required.'
        return
    }
    const input: any = {
        uuid: f.uuid || undefined,
        org: orgUuid.value,
        name: f.name.trim(),
        type: f.type,
        status: f.status,
    }
    if (f.type === 'SLACK' && f.slack.webhookUrl) {
        input.slackConfig = { webhookUrl: f.slack.webhookUrl }
    }
    if (f.type === 'MS_TEAMS' && f.teams.webhookUrl) {
        input.teamsConfig = { webhookUrl: f.teams.webhookUrl }
    }
    if (f.type === 'WEBHOOK') {
        const cfg: any = { authScheme: f.webhook.authScheme }
        if (f.webhook.url) cfg.url = f.webhook.url
        if (f.webhook.secret) cfg.authToken = f.webhook.secret
        input.webhookConfig = cfg
    }
    if (f.type === 'SENTINEL') {
        const s = f.sentinel
        const cfg: any = {}
        if (s.tenantId) cfg.tenantId = s.tenantId
        if (s.clientId) cfg.clientId = s.clientId
        if (s.clientSecret) cfg.clientSecret = s.clientSecret
        if (s.dcrEndpoint) cfg.dcrEndpoint = s.dcrEndpoint
        if (s.dcrImmutableId) cfg.dcrImmutableId = s.dcrImmutableId
        if (s.streamName) cfg.streamName = s.streamName
        input.sentinelConfig = cfg
    }
    savingChannel.value = true
    try {
        await graphqlClient.mutate({
            mutation: UPSERT_CHANNEL_MUTATION,
            variables: { input },
        })
        showChannelModal.value = false
        message.success(f.uuid ? 'Channel updated' : 'Channel created')
        await loadChannels()
    } catch (e: any) {
        modalError.value = extractError(e)
    } finally {
        savingChannel.value = false
    }
}

// ---- Toggle / Delete -----------------------------------------------------

async function toggleStatus (row: ChannelRow): Promise<void> {
    const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
    try {
        await graphqlClient.mutate({
            mutation: SET_STATUS_MUTATION,
            variables: { uuid: row.uuid, status: next },
        })
        message.success(`Channel ${next.toLowerCase()}`)
        await loadChannels()
    } catch (e: any) {
        message.error(`Status change failed: ${extractError(e)}`)
    }
}

function confirmDelete (row: ChannelRow): void {
    dialog.warning({
        title: `Delete channel "${row.name}"?`,
        content: 'This is permanent. Subscriptions that route only to this channel will silently stop dispatching.',
        positiveText: 'Delete',
        negativeText: 'Cancel',
        onPositiveClick: async () => {
            try {
                await graphqlClient.mutate({
                    mutation: DELETE_CHANNEL_MUTATION,
                    variables: { uuid: row.uuid },
                })
                message.success('Channel deleted')
                await loadChannels()
            } catch (e: any) {
                message.error(`Delete failed: ${extractError(e)}`)
            }
        },
    })
}

// ---- Test channel --------------------------------------------------------

async function testChannel (row: ChannelRow): Promise<void> {
    testState.value = {
        channelUuid: row.uuid, channelName: row.name, eventUuid: null,
        status: 'PENDING', attempts: 0, lastError: '',
    }
    showTestModal.value = true
    try {
        const res = await graphqlClient.mutate({
            mutation: TEST_CHANNEL_MUTATION,
            variables: { channelUuid: row.uuid },
        })
        const eventUuid = res.data?.testNotificationChannel?.uuid
        if (!eventUuid) {
            testState.value.status = 'ERROR'
            testState.value.lastError = 'No outbox event returned'
            return
        }
        testState.value.eventUuid = eventUuid
        await pollForDelivery(eventUuid)
    } catch (e: any) {
        testState.value.status = 'ERROR'
        testState.value.lastError = extractError(e)
    }
}

async function pollForDelivery (eventUuid: string): Promise<void> {
    // Backend dispatcher loop ticks every few seconds; usually first poll
    // (1.5s in) already finds the SENT row. Cap at ~30s to keep the modal
    // honest under a backlogged worker.
    const deadline = Date.now() + 30000
    while (Date.now() < deadline) {
        await new Promise(r => setTimeout(r, 1500))
        try {
            const res = await graphqlClient.query({
                query: LIST_DELIVERIES_QUERY,
                variables: { orgUuid: orgUuid.value, eventUuid, limit: 5 },
                fetchPolicy: 'network-only',
            })
            const items = res.data?.notificationDeliveries?.items || []
            if (items.length > 0) {
                const d = items[0]
                testState.value.attempts = d.attemptCount || 0
                testState.value.lastError = d.lastError || ''
                if (d.status === 'SENT') {
                    testState.value.status = 'SENT'
                    return
                }
                if (d.status === 'FAILED' || d.status === 'PERMANENT_FAILURE') {
                    testState.value.status = 'FAILED'
                    return
                }
            }
        } catch {
            // Transient — keep polling within budget.
        }
    }
    testState.value.status = 'TIMEOUT'
}

// ---- Column defs ---------------------------------------------------------

const channelColumns = computed(() => [
    { title: 'Name', key: 'name' },
    {
        title: 'Type', key: 'type',
        render: (row: ChannelRow) => h(NTag, { type: 'default', size: 'small' }, { default: () => TYPE_LABELS[row.type] || row.type }),
    },
    {
        title: 'Status', key: 'status',
        render: (row: ChannelRow) => h(
            NTag,
            { type: row.status === 'ENABLED' ? 'success' : 'warning', size: 'small' },
            { default: () => row.status },
        ),
    },
    {
        title: 'Actions', key: 'actions',
        render: (row: ChannelRow) => h(NSpace, { size: 'small' }, {
            default: () => [
                h(NButton, {
                    size: 'tiny', secondary: true,
                    onClick: () => testChannel(row),
                    disabled: row.status !== 'ENABLED',
                    title: row.status === 'ENABLED' ? 'Send synthetic event to this channel' : 'Enable channel to test',
                }, { default: () => 'Test' }),
                h(NButton, {
                    size: 'tiny', secondary: true,
                    onClick: () => openEditChannel(row),
                    disabled: !canWrite.value,
                }, { icon: () => h(NIcon, null, { default: () => h(EditIcon) }) }),
                h(NButton, {
                    size: 'tiny', secondary: true,
                    onClick: () => toggleStatus(row),
                    disabled: !canWrite.value,
                }, { default: () => row.status === 'ENABLED' ? 'Disable' : 'Enable' }),
                h(NButton, {
                    size: 'tiny', secondary: true, type: 'error',
                    onClick: () => confirmDelete(row),
                    disabled: !canWrite.value,
                }, { icon: () => h(NIcon, null, { default: () => h(Trash) }) }),
            ],
        }),
    },
])

// ---- helpers -------------------------------------------------------------

function extractError (e: any): string {
    const gql = e?.graphQLErrors?.[0]?.message
    if (gql) return gql
    return e?.message || 'Unknown error'
}

onMounted(async () => {
    userPermission.value = commonFunctions.getUserPermission(orgUuid.value, myuser.value)?.org || ''
    await loadChannels()
})
</script>

<style scoped>
.notifications-page { padding: 24px; }
.page-header { margin-bottom: 16px; }
.page-title { margin: 0 0 4px; font-size: 18px; font-weight: 600; }
.page-subtitle { font-size: 13px; color: var(--n-text-color-3, #888); }

.tab-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 0 16px;
}
.tab-toolbar-info { font-size: 12.5px; color: var(--n-text-color-3, #888); }
.muted-12 { font-size: 12px; color: var(--n-text-color-3, #888); }
</style>
