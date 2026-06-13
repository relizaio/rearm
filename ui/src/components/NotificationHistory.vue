<template>
    <div class="notification-history-pane">
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
    </div>
</template>

<script lang="ts" setup>
import { ref, computed, h, onMounted } from 'vue'
import {
    NDataTable, NButton, NIcon, NFormItem, NSelect, NGrid, NGi, NTag,
    NPagination, useMessage
} from 'naive-ui'
import { Refresh } from '@vicons/tabler'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import {
    ChannelRow, SubscriptionRow, DeliveryRow, TYPE_LABELS,
    deliveryStatusOptions, deliveryOriginOptions,
    LIST_CHANNELS_QUERY, LIST_SUBSCRIPTIONS_QUERY,
    deliveryStatusTagType, formatHistoryTimestamp, truncate,
    buildNameMap, extractError
} from '@/utils/notificationsCommon'

const props = defineProps<{
    orguuid: string
}>()

const message = useMessage()
const orgUuid = computed<string>(() => props.orguuid)

const channels = ref<ChannelRow[]>([])
const subscriptions = ref<SubscriptionRow[]>([])
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

// History channel filter spans ALL channels (incl. disabled) so a user
// investigating why nothing landed on a disabled channel can still see
// past deliveries.
const channelFilterOptions = computed(() =>
    channels.value.map(c => ({ label: `${c.name} (${TYPE_LABELS[c.type] || c.type})`, value: c.uuid }))
)

// Cross-ref helpers — history rows carry uuids; resolve to names from
// the loaded channels + subscriptions lists.
const channelNameById = computed<Record<string, string>>(() => buildNameMap(channels.value))
const subscriptionNameById = computed<Record<string, string>>(() => buildNameMap(subscriptions.value))

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

async function loadSubscriptions (): Promise<void> {
    try {
        const res = await graphqlClient.query({
            query: LIST_SUBSCRIPTIONS_QUERY,
            variables: { orgUuid: orgUuid.value },
            fetchPolicy: 'network-only',
        })
        subscriptions.value = res.data?.notificationSubscriptions || []
    } catch (e: any) {
        message.error(`Failed to load subscriptions: ${extractError(e)}`)
    }
}

// Monotonic in-flight token. ApolloClient with `fetchPolicy: network-only`
// does NOT dedup or cancel concurrent identical queries, so rapidly
// toggling a filter can race the older response in last and overwrite
// the newer state. The token guards apply-time so a stale response is
// dropped silently.
let historyInflightToken = 0

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

// Filter change must reset to page 1 — otherwise a user on page 3 of an
// unfiltered list who applies a narrowing filter would request an
// out-of-range offset and land on a phantom empty page.
function applyHistoryFilters (): void {
    historyPage.value = 1
    loadDeliveries()
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
        // Null channel = Phase 4a targeted delivery (personal inbox copy,
        // no transmission channel) — not a deleted channel.
        render: (row: DeliveryRow) => (row.channelUuid
            ? (channelNameById.value[row.channelUuid]
                || h('span', { class: 'muted-12', title: row.channelUuid }, '(deleted channel)'))
            : h('span', { class: 'muted-12' }, 'Direct')),
    },
    {
        title: 'Subscription', key: 'subscriptionUuid',
        render: (row: DeliveryRow) => {
            if (!row.subscriptionUuid) {
                // Channel tests have a channel but no subscription; targeted
                // approval deliveries have neither.
                return h('span', { class: 'muted-12' }, row.channelUuid ? '(channel test)' : 'Direct')
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

onMounted(async () => {
    // Channels + subscriptions feed the name-resolution maps; deliveries
    // is the actual table. Load all three in parallel.
    await Promise.all([loadChannels(), loadSubscriptions(), loadDeliveries()])
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
.muted-12 { font-size: 12px; color: var(--n-text-color-3, #888); }
.history-filters { margin-bottom: 12px; }
.history-pagination { margin-top: 12px; display: flex; justify-content: flex-end; }
</style>
