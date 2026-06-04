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
            </n-tab-pane>

            <n-tab-pane name="groups" tab="Channel groups">
                <div class="tab-toolbar">
                    <div class="tab-toolbar-info">
                        Named, cross-type collections of channels. Reference one group instead of repeating a channel list across multiple subscription routes — e.g. "Security oncall" = Slack #sec + Teams #leadership + Email security@.
                    </div>
                    <n-button
                        v-if="canWrite"
                        type="primary"
                        size="small"
                        @click="openCreateGroup"
                    >
                        <template #icon><n-icon><CirclePlus /></n-icon></template>
                        Add group
                    </n-button>
                </div>

                <n-data-table
                    :data="channelGroups"
                    :columns="channelGroupColumns"
                    :loading="channelGroupsLoading"
                    :single-line="false"
                    :bordered="false"
                />
            </n-tab-pane>

            <n-tab-pane name="history" tab="History">
                <div class="tab-toolbar">
                    <div class="tab-toolbar-info">
                        Audit log of every delivery row: when, to whom, status, attempts. Test rows (channel-test button) and PREVIEW rows (subscriptions in preview mode) are included.
                    </div>
                    <n-button size="small" @click="loadDeliveries">
                        <template #icon><n-icon><Refresh /></n-icon></template>
                        Refresh
                    </n-button>
                </div>

                <n-grid :cols="3" :x-gap="12" class="history-filters">
                    <n-gi>
                        <n-form-item label="Status" :show-feedback="false">
                            <n-select
                                v-model:value="historyFilters.status"
                                :options="deliveryStatusOptions"
                                placeholder="Any"
                                clearable
                                @update:value="applyHistoryFilters"
                            />
                        </n-form-item>
                    </n-gi>
                    <n-gi>
                        <n-form-item label="Origin" :show-feedback="false">
                            <n-select
                                v-model:value="historyFilters.origin"
                                :options="deliveryOriginOptions"
                                placeholder="Any"
                                clearable
                                @update:value="applyHistoryFilters"
                            />
                        </n-form-item>
                    </n-gi>
                    <n-gi>
                        <n-form-item label="Channel" :show-feedback="false">
                            <n-select
                                v-model:value="historyFilters.channelUuid"
                                :options="channelFilterOptions"
                                placeholder="Any"
                                clearable
                                @update:value="applyHistoryFilters"
                            />
                        </n-form-item>
                    </n-gi>
                </n-grid>

                <n-data-table
                    :data="deliveries"
                    :columns="deliveryColumns"
                    :loading="deliveriesLoading"
                    :single-line="false"
                    :bordered="false"
                />

                <div v-if="historyTotalCount > historyPageSize" class="history-pagination">
                    <n-pagination
                        v-model:page="historyPage"
                        :page-count="historyPageCount"
                        :page-size="historyPageSize"
                        :item-count="historyTotalCount"
                        @update:page="loadDeliveries"
                    />
                </div>
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

        <!-- Channel group create/edit modal -->
        <n-modal v-model:show="showGroupModal" preset="dialog" :show-icon="false">
            <n-card
                style="width: 600px"
                size="huge"
                :title="groupForm.uuid ? `Edit group — ${groupForm.name || ''}` : 'Add channel group'"
                :bordered="false"
                role="dialog"
                aria-modal="true"
            >
                <n-form :model="groupForm">
                    <n-space vertical size="large">
                        <n-form-item label="Name">
                            <n-input v-model:value="groupForm.name" placeholder="e.g. security-oncall" />
                        </n-form-item>
                        <n-form-item label="Channels">
                            <n-select
                                v-model:value="groupForm.channels"
                                :options="channelOptions"
                                multiple
                                placeholder="Pick the channels in this group"
                            />
                        </n-form-item>

                        <n-alert v-if="groupModalError" type="error" :show-icon="false">
                            {{ groupModalError }}
                        </n-alert>

                        <n-space>
                            <n-button
                                @click="saveGroup"
                                type="primary"
                                :loading="savingGroup"
                                :disabled="!groupForm.name.trim() || groupForm.channels.length === 0"
                            >
                                Save
                            </n-button>
                            <n-button @click="showGroupModal = false">Cancel</n-button>
                        </n-space>
                    </n-space>
                </n-form>
            </n-card>
        </n-modal>

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
    NFormItem, NInput, NInputNumber, NSelect, NSpace, NAlert, NGrid, NGi, NTag,
    NRadioGroup, NRadioButton, NPagination, useDialog, useMessage
} from 'naive-ui'
import { CirclePlus, Trash, Edit as EditIcon, Refresh } from '@vicons/tabler'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'

interface ChannelRow {
    uuid: string
    org: string
    resourceGroup: string | null
    name: string
    type: string
    status: string
}

interface SubscriptionRoute {
    whenSeverityAtLeast: string | null
    channels: string[]
    channelGroups: string[]
    // Carries the as-loaded route object on edit so fields the slice-4
    // UI still doesn't model (andEnvIn, andLifecycleIn, perspectives)
    // survive an Edit → Save round-trip instead of being silently
    // stripped. channels + channelGroups overlay this last and win.
    // Empty on Create.
    _raw?: Record<string, any>
}

interface ChannelGroupRow {
    uuid: string
    org: string
    resourceGroup: string | null
    name: string
    channels: string[]
    revision: number
    createdDate: string | null
    lastUpdatedDate: string | null
}

interface ChannelGroupForm {
    uuid: string | null
    name: string
    channels: string[]
}

interface DeliveryRow {
    uuid: string
    org: string
    outboxEventUuid: string
    subscriptionUuid: string | null
    channelUuid: string
    status: string
    origin: string
    dedupKey: string | null
    attemptCount: number
    nextAttemptAt: string | null
    sentAt: string | null
    lastError: string | null
    createdDate: string
}

interface SubscriptionRow {
    uuid: string
    org: string
    resourceGroup: string | null
    name: string
    status: string
    eventTypes: string[]
    filter: string | null         // JSON-stringified server-side
    routes: string | null         // JSON-stringified server-side
    dedupWindowMinutes: number | null
    rateLimit: string | null      // JSON-stringified server-side
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

const subscriptionStatusOptions = [
    { label: 'Active (deliveries go out)', value: 'ACTIVE' },
    { label: 'Disabled (no dispatch)', value: 'DISABLED' },
    { label: 'Preview (rows land but no dispatch)', value: 'PREVIEW' },
]

const eventTypeOptions = [
    { label: 'New vuln affects releases', value: 'NEW_VULN_AFFECTS_RELEASES' },
    { label: 'Vulnerability record updated', value: 'VULNERABILITY_RECORD_UPDATED' },
    { label: 'VEX state changed', value: 'VEX_STATE_CHANGED' },
]

const severityOptions = [
    { label: 'CRITICAL', value: 'CRITICAL' },
    { label: 'HIGH', value: 'HIGH' },
    { label: 'MEDIUM', value: 'MEDIUM' },
    { label: 'LOW', value: 'LOW' },
    { label: 'INFO', value: 'INFO' },
]

const channelOptions = computed(() =>
    channels.value
        .filter(c => c.status === 'ENABLED')
        .map(c => ({ label: `${c.name} (${TYPE_LABELS[c.type] || c.type})`, value: c.uuid }))
)

// History tab — channel filter spans ALL channels (incl. disabled) so a
// user investigating why nothing landed on a disabled channel can still
// see past deliveries.
const channelFilterOptions = computed(() =>
    channels.value.map(c => ({ label: `${c.name} (${TYPE_LABELS[c.type] || c.type})`, value: c.uuid }))
)

const deliveryStatusOptions = [
    { label: 'PENDING', value: 'PENDING' },
    { label: 'SENT', value: 'SENT' },
    { label: 'ACKED', value: 'ACKED' },
    { label: 'FAILED', value: 'FAILED' },
    { label: 'RATE_LIMITED', value: 'RATE_LIMITED' },
    { label: 'EVAL_TIMEOUT', value: 'EVAL_TIMEOUT' },
    { label: 'TEST', value: 'TEST' },
    { label: 'PREVIEW', value: 'PREVIEW' },
]

const deliveryOriginOptions = [
    { label: 'REAL', value: 'REAL' },
    { label: 'SYNTHETIC (test / Quick Start)', value: 'SYNTHETIC' },
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

// Optimistic-locking note. The three upsert paths in this file
// (upsertNotificationChannel, upsertNotificationSubscription,
// upsertNotificationChannelGroup) do NOT forward the `revision` value
// from the loaded row. The backend entities use @Version, but the
// GraphQL Input shapes don't currently expose an `expectedRevision`
// field, so the UI can't gate save on it. Accepted as-is across all
// three surfaces for consistency; a stale concurrent edit on the same
// row is last-writer-wins. If multi-admin races become a real customer
// concern, the fix is a coordinated schema change (add expectedRevision
// to all three Input shapes) + a UI guard that surfaces the conflict.
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

// Subscription form state
interface SubscriptionForm {
    uuid: string | null
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
    // (and any other field the slice-2 UI doesn't model yet) survives an
    // Edit → Save round-trip. Empty on Create. Same pattern as
    // SubscriptionRoute._raw.
    _rawFilter?: Record<string, any>
}

function freshRoute (): SubscriptionRoute {
    return { whenSeverityAtLeast: null, channels: [], channelGroups: [] }
}

function freshGroupForm (): ChannelGroupForm {
    return { uuid: null, name: '', channels: [] }
}

function freshSubscriptionForm (): SubscriptionForm {
    return {
        uuid: null,
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

const subscriptions = ref<SubscriptionRow[]>([])
const subscriptionsLoading = ref<boolean>(false)
const showSubscriptionModal = ref<boolean>(false)
const savingSubscription = ref<boolean>(false)
const subModalError = ref<string>('')
const subForm = ref<SubscriptionForm>(freshSubscriptionForm())

// Channel groups state
const channelGroups = ref<ChannelGroupRow[]>([])
const channelGroupsLoading = ref<boolean>(false)
const showGroupModal = ref<boolean>(false)
const savingGroup = ref<boolean>(false)
const groupModalError = ref<string>('')
const groupForm = ref<ChannelGroupForm>(freshGroupForm())

const channelGroupOptions = computed(() =>
    channelGroups.value.map(g => ({
        label: `${g.name} (${g.channels.length} ch)`,
        value: g.uuid,
    }))
)

// Delivery history state
const deliveries = ref<DeliveryRow[]>([])
const deliveriesLoading = ref<boolean>(false)
const historyPage = ref<number>(1)
const historyPageSize = ref<number>(25)
const historyTotalCount = ref<number>(0)
const historyPageCount = computed<number>(() =>
    Math.max(1, Math.ceil(historyTotalCount.value / historyPageSize.value))
)
const historyFilters = ref<{ status: string | null, origin: string | null, channelUuid: string | null }>({
    status: null,
    origin: null,
    channelUuid: null,
})

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

// Narrow projection used by the test-channel poll loop. Keeps the SLA
// path independent of the wider history projection (which fetches more
// fields).
const LIST_DELIVERIES_QUERY = gql`
    query notificationDeliveries($orgUuid: ID!, $eventUuid: ID, $limit: Int) {
        notificationDeliveries(orgUuid: $orgUuid, eventUuid: $eventUuid, limit: $limit) {
            items { uuid status attemptCount lastError sentAt }
        }
    }
`

const HISTORY_DELIVERIES_QUERY = gql`
    query notificationDeliveriesPage(
        $orgUuid: ID!,
        $channelUuid: ID,
        $status: NotificationDeliveryStatusEnum,
        $origin: NotificationDeliveryOriginEnum,
        $limit: Int,
        $offset: Int
    ) {
        notificationDeliveries(
            orgUuid: $orgUuid,
            channelUuid: $channelUuid,
            status: $status,
            origin: $origin,
            limit: $limit,
            offset: $offset
        ) {
            items {
                uuid org outboxEventUuid subscriptionUuid channelUuid
                status origin dedupKey attemptCount nextAttemptAt sentAt
                lastError createdDate
            }
            totalCount limit offset
        }
    }
`

const LIST_GROUPS_QUERY = gql`
    query notificationChannelGroups($orgUuid: ID!) {
        notificationChannelGroups(orgUuid: $orgUuid) {
            uuid org resourceGroup name channels revision createdDate lastUpdatedDate
        }
    }
`

const UPSERT_GROUP_MUTATION = gql`
    mutation upsertNotificationChannelGroup($input: NotificationChannelGroupInput!) {
        upsertNotificationChannelGroup(input: $input) {
            uuid org resourceGroup name channels revision createdDate lastUpdatedDate
        }
    }
`

const DELETE_GROUP_MUTATION = gql`
    mutation deleteNotificationChannelGroup($uuid: ID!) {
        deleteNotificationChannelGroup(uuid: $uuid)
    }
`

const LIST_SUBSCRIPTIONS_QUERY = gql`
    query notificationSubscriptions($orgUuid: ID!) {
        notificationSubscriptions(orgUuid: $orgUuid) {
            uuid org resourceGroup name status eventTypes
            filter routes dedupWindowMinutes rateLimit
        }
    }
`

const UPSERT_SUBSCRIPTION_MUTATION = gql`
    mutation upsertNotificationSubscription($input: NotificationSubscriptionInput!) {
        upsertNotificationSubscription(input: $input) {
            uuid org resourceGroup name status eventTypes
            filter routes dedupWindowMinutes rateLimit
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

// Monotonic in-flight token. ApolloClient with `fetchPolicy: network-only`
// does NOT dedup or cancel concurrent identical queries, so rapidly
// toggling a filter can race the older response in last and overwrite
// the newer state. The token guards apply-time so a stale response is
// dropped silently.
let historyInflightToken = 0

async function loadChannelGroups (): Promise<void> {
    channelGroupsLoading.value = true
    try {
        const res = await graphqlClient.query({
            query: LIST_GROUPS_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        channelGroups.value = res.data?.notificationChannelGroups || []
    } catch (e: any) {
        message.error(`Failed to load channel groups: ${extractError(e)}`)
    } finally {
        channelGroupsLoading.value = false
    }
}

// ---- Channel group create / edit -----------------------------------------

function openCreateGroup (): void {
    groupForm.value = freshGroupForm()
    groupModalError.value = ''
    showGroupModal.value = true
}

function openEditGroup (row: ChannelGroupRow): void {
    groupForm.value = {
        uuid: row.uuid,
        name: row.name,
        channels: [...(row.channels || [])],
    }
    groupModalError.value = ''
    showGroupModal.value = true
}

async function saveGroup (): Promise<void> {
    groupModalError.value = ''
    const f = groupForm.value
    if (!f.name.trim() || f.channels.length === 0) {
        groupModalError.value = 'Name and at least one channel are required.'
        return
    }
    const input: any = {
        uuid: f.uuid || undefined,
        org: orgUuid.value,
        name: f.name.trim(),
        channels: f.channels,
    }
    savingGroup.value = true
    try {
        await graphqlClient.mutate({
            mutation: UPSERT_GROUP_MUTATION,
            variables: { input },
        })
        showGroupModal.value = false
        message.success(f.uuid ? 'Group updated' : 'Group created')
        await loadChannelGroups()
    } catch (e: any) {
        groupModalError.value = extractError(e)
    } finally {
        savingGroup.value = false
    }
}

function confirmDeleteGroup (row: ChannelGroupRow): void {
    dialog.warning({
        title: `Delete channel group "${row.name}"?`,
        content: 'Subscriptions that reference this group will silently lose this hop. Channels in the group are not affected.',
        positiveText: 'Delete',
        negativeText: 'Cancel',
        onPositiveClick: async () => {
            try {
                await graphqlClient.mutate({
                    mutation: DELETE_GROUP_MUTATION,
                    variables: { uuid: row.uuid },
                })
                message.success('Group deleted')
                await loadChannelGroups()
            } catch (e: any) {
                message.error(`Delete failed: ${extractError(e)}`)
            }
        },
    })
}

async function loadDeliveries (): Promise<void> {
    const myToken = ++historyInflightToken
    deliveriesLoading.value = true
    try {
        const offset = (historyPage.value - 1) * historyPageSize.value
        const res = await graphqlClient.query({
            query: HISTORY_DELIVERIES_QUERY,
            variables: {
                orgUuid: orgUuid.value,
                channelUuid: historyFilters.value.channelUuid,
                status: historyFilters.value.status,
                origin: historyFilters.value.origin,
                limit: historyPageSize.value,
                offset,
            },
            fetchPolicy: 'network-only',
        })
        if (myToken !== historyInflightToken) return  // stale response
        const page = res.data?.notificationDeliveries
        deliveries.value = page?.items || []
        historyTotalCount.value = page?.totalCount || 0
    } catch (e: any) {
        if (myToken !== historyInflightToken) return  // stale failure
        message.error(`Failed to load history: ${extractError(e)}`)
    } finally {
        if (myToken === historyInflightToken) deliveriesLoading.value = false
    }
}

// Filter change must reset to page 1 — otherwise a user on page 3 of
// an unfiltered list who applies a narrowing filter would request an
// out-of-range offset and land on a phantom empty page.
function applyHistoryFilters (): void {
    historyPage.value = 1
    loadDeliveries()
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
    if (f.type === 'WEBHOOK' && (f.webhook.url || f.webhook.secret)) {
        // Mirror the Slack/Teams gating: only send webhookConfig when
        // the user actually filled something in. Otherwise the backend
        // preserves both URL + auth scheme + secret as a unit. This
        // protects against a future backend change that trusts a
        // non-null authScheme independent of the URL — without it,
        // every metadata-only edit would silently flip the channel
        // to NONE auth.
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
                if (d.status === 'SENT' || d.status === 'ACKED') {
                    testState.value.status = 'SENT'
                    return
                }
                // Terminal failure states per NotificationDeliveryStatusEnum.
                // RATE_LIMITED + EVAL_TIMEOUT are terminal for the dispatch
                // path even though they have different operator semantics;
                // surface them with the failure alert and lastError will
                // carry the disambiguation (the worker writes a descriptive
                // lastError when it short-circuits on these).
                if (d.status === 'FAILED'
                    || d.status === 'RATE_LIMITED'
                    || d.status === 'EVAL_TIMEOUT') {
                    testState.value.status = 'FAILED'
                    if (!testState.value.lastError) {
                        testState.value.lastError = `Delivery ${d.status.toLowerCase().replace(/_/g, ' ')}`
                    }
                    return
                }
            }
        } catch {
            // Transient — keep polling within budget.
        }
    }
    testState.value.status = 'TIMEOUT'
}

// ---- Subscription create / edit ------------------------------------------

function openCreateSubscription (): void {
    subForm.value = freshSubscriptionForm()
    subModalError.value = ''
    showSubscriptionModal.value = true
}

function openEditSubscription (row: SubscriptionRow): void {
    const f = freshSubscriptionForm()
    f.uuid = row.uuid
    f.name = row.name
    f.status = row.status
    f.eventTypes = [...(row.eventTypes || [])]
    f.dedupWindowMinutes = row.dedupWindowMinutes ?? null
    // Filter / routes / rateLimit ride as JSON-stringified blobs over the
    // wire (NotificationSubscriptionResult). Parse them back into the
    // structured form shape AND stash the original blob on _raw / _rawFilter
    // so unmodelled fields (channelGroups, andEnvIn, andLifecycleIn on
    // routes; presetConfigJson on filter) survive an Edit → Save round-trip.
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
    // Build the filter input by overlaying the slice-2-modeled fields on
    // top of the original blob. This preserves `presetConfigJson` (set via
    // the future preset-toggle UI or directly via API) instead of nulling
    // it on every edit.
    const filterInput: any = {
        ...(f._rawFilter || {}),
        mode: f.filterMode,
        celExpression: f.filterMode === 'ADVANCED' ? f.celExpression : null,
    }
    const input: any = {
        uuid: f.uuid || undefined,
        org: orgUuid.value,
        name: f.name.trim(),
        status: f.status,
        eventTypes: f.eventTypes,
        filter: filterInput,
        // Spread the original route's still-unmodelled fields
        // (andEnvIn, andLifecycleIn, perspectives) so an Edit → Save
        // round-trip doesn't silently strip them. The slice-2 +
        // slice-4 modelled fields (severity, channels, channelGroups)
        // overlay last and win.
        routes: f.routes.map(r => ({
            ...(r._raw || {}),
            whenSeverityAtLeast: r.whenSeverityAtLeast,
            channels: r.channels,
            channelGroups: r.channelGroups,
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

const channelGroupColumns = computed(() => [
    { title: 'Name', key: 'name' },
    {
        title: 'Channels', key: 'channels',
        render: (row: ChannelGroupRow) => `${(row.channels || []).length} channel(s)`,
    },
    {
        title: 'Last updated', key: 'lastUpdatedDate',
        render: (row: ChannelGroupRow) => formatHistoryTimestamp(row.lastUpdatedDate),
    },
    {
        title: 'Actions', key: 'actions',
        render: (row: ChannelGroupRow) => h(NSpace, { size: 'small' }, {
            default: () => [
                h(NButton, {
                    size: 'tiny', secondary: true,
                    onClick: () => openEditGroup(row),
                    disabled: !canWrite.value,
                }, { icon: () => h(NIcon, null, { default: () => h(EditIcon) }) }),
                h(NButton, {
                    size: 'tiny', secondary: true, type: 'error',
                    onClick: () => confirmDeleteGroup(row),
                    disabled: !canWrite.value,
                }, { icon: () => h(NIcon, null, { default: () => h(Trash) }) }),
            ],
        }),
    },
])

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

// Cross-ref helpers — history rows carry uuids; resolve to names from
// the already-loaded channels + subscriptions lists.
const channelNameById = computed<Record<string, string>>(() => {
    const m: Record<string, string> = {}
    for (const c of channels.value) m[c.uuid] = c.name
    return m
})
const subscriptionNameById = computed<Record<string, string>>(() => {
    const m: Record<string, string> = {}
    for (const s of subscriptions.value) m[s.uuid] = s.name
    return m
})

function deliveryStatusTagType (status: string): 'success' | 'warning' | 'error' | 'info' | 'default' {
    if (status === 'SENT' || status === 'ACKED') return 'success'
    if (status === 'FAILED' || status === 'EVAL_TIMEOUT' || status === 'RATE_LIMITED') return 'error'
    if (status === 'PREVIEW' || status === 'TEST') return 'info'
    return 'default'
}

// Route through commonFunctions.dateDisplay so the History timestamps
// stay in the en-CA locale convention used elsewhere in the UI
// (ReleaseView etc.). The helper doesn't itself guard null, so we do.
function formatHistoryTimestamp (s: string | null): string {
    if (!s) return '—'
    try { return commonFunctions.dateDisplay(s) } catch { return s }
}

function truncate (s: string | null, n: number): string {
    if (!s) return ''
    return s.length > n ? `${s.slice(0, n - 1)}…` : s
}

const deliveryColumns = computed(() => [
    {
        title: 'When', key: 'createdDate',
        render: (row: DeliveryRow) => formatHistoryTimestamp(row.sentAt || row.createdDate),
    },
    {
        title: 'Status', key: 'status',
        render: (row: DeliveryRow) => h(
            NTag,
            { type: deliveryStatusTagType(row.status), size: 'small' },
            { default: () => row.status },
        ),
    },
    {
        title: 'Origin', key: 'origin',
        render: (row: DeliveryRow) => h(
            NTag,
            { type: row.origin === 'SYNTHETIC' ? 'info' : 'default', size: 'small' },
            { default: () => row.origin },
        ),
    },
    {
        title: 'Channel', key: 'channelUuid',
        render: (row: DeliveryRow) => channelNameById.value[row.channelUuid]
            || h('span', { class: 'muted-12', title: row.channelUuid }, '(deleted channel)'),
    },
    {
        title: 'Subscription', key: 'subscriptionUuid',
        render: (row: DeliveryRow) => {
            if (!row.subscriptionUuid) {
                return h('span', { class: 'muted-12' }, '(channel test)')
            }
            return subscriptionNameById.value[row.subscriptionUuid]
                || h('span', { class: 'muted-12', title: row.subscriptionUuid }, '(deleted subscription)')
        },
    },
    {
        title: 'Attempts', key: 'attemptCount',
        render: (row: DeliveryRow) => `${row.attemptCount}`,
    },
    {
        title: 'Last error', key: 'lastError',
        render: (row: DeliveryRow) => row.lastError
            ? h('span', { title: row.lastError, class: 'muted-12' }, truncate(row.lastError, 60))
            : '',
    },
])

// ---- helpers -------------------------------------------------------------

function extractError (e: any): string {
    return commonFunctions.parseGraphQLError(commonFunctions.extractGraphQLErrorMessage(e))
}

onMounted(async () => {
    userPermission.value = commonFunctions.getUserPermission(orgUuid.value, myuser.value)?.org || ''
    // Load channels, channel groups, subscriptions, and the first
    // page of delivery history in parallel. Subscription edit modal's
    // pickers degrade to empty placeholders if opened before channels
    // / groups resolve — acceptable given the typical few-hundred-ms
    // load.
    await Promise.all([
        loadChannels(),
        loadChannelGroups(),
        loadSubscriptions(),
        loadDeliveries(),
    ])
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

.history-filters { margin-bottom: 12px; }
.history-pagination { margin-top: 12px; display: flex; justify-content: flex-end; }

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
.route-row + .route-row { margin-top: 6px; }
.route-remove-cell { display: flex; align-items: flex-end; }
</style>
