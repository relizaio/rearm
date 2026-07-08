<template>
    <div class="channel-groups-pane">
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

        <n-alert
            v-if="channelGroupsDegraded"
            type="warning"
            :show-icon="false"
            style="margin-bottom: 12px;"
            data-testid="groups-degraded-alert"
        >
            Some group details are unavailable on this server version, so a few columns (such as created/updated dates) may be missing. Your channel groups are shown below.
        </n-alert>

        <n-data-table
            :data="channelGroups"
            :columns="channelGroupColumns"
            :loading="channelGroupsLoading"
            :single-line="false"
            :bordered="false"
        />

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
                            <template v-if="isConflictError(groupModalError)" #action>
                                <n-button size="small" type="primary" @click="loadChannelGroups">
                                    Reload from server
                                </n-button>
                            </template>
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
    </div>
</template>

<script lang="ts" setup>
import { ref, computed, h, onMounted } from 'vue'
import {
    NDataTable, NButton, NIcon, NModal, NCard, NForm, NFormItem, NInput,
    NSelect, NSpace, NAlert, useDialog, useMessage
} from 'naive-ui'
import { CirclePlus, Trash, Edit as EditIcon } from '@vicons/tabler'
import graphqlClient from '@/utils/graphql'
import {
    ChannelRow, ChannelGroupRow, TYPE_LABELS, LIST_CHANNELS_QUERY,
    LIST_GROUPS_QUERY, LIST_GROUPS_CORE_QUERY, extractError, isConflictError, formatHistoryTimestamp
} from '@/utils/notificationsCommon'
import { loadWithSchemaDriftFallback } from '@/utils/graphqlDriftFallback'
import gql from 'graphql-tag'

const props = defineProps<{
    orguuid: string
    isWritable: boolean
}>()

const dialog = useDialog()
const message = useMessage()

const orgUuid = computed<string>(() => props.orguuid)
const canWrite = computed<boolean>(() => props.isWritable)

interface ChannelGroupForm {
    uuid: string | null
    expectedRevision: number | null
    name: string
    channels: string[]
}

function freshGroupForm (): ChannelGroupForm {
    return { uuid: null, expectedRevision: null, name: '', channels: [] }
}

const channels = ref<ChannelRow[]>([])
const channelGroups = ref<ChannelGroupRow[]>([])
const channelGroupsLoading = ref<boolean>(false)
// True when the backend rejected the full group selection and we fell back to
// core fields (created/updated columns may be absent) -- see PR #169 pattern.
const channelGroupsDegraded = ref<boolean>(false)
const showGroupModal = ref<boolean>(false)
const savingGroup = ref<boolean>(false)
const groupModalError = ref<string>('')
const groupForm = ref<ChannelGroupForm>(freshGroupForm())

// Group-modal picker lists ENABLED channels by name + type label.
const channelOptions = computed(() =>
    channels.value
        .filter(c => c.status === 'ENABLED')
        .map(c => ({ label: `${c.name} (${TYPE_LABELS[c.type] || c.type})`, value: c.uuid }))
)

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
    channelGroupsLoading.value = true
    try {
        const { data, degraded } = await loadWithSchemaDriftFallback(graphqlClient, {
            fullQuery: LIST_GROUPS_QUERY,
            coreQuery: LIST_GROUPS_CORE_QUERY,
            variables: { orgUuid: orgUuid.value },
            extractPath: (d: any) => d?.notificationChannelGroups,
        })
        channelGroups.value = data || []
        channelGroupsDegraded.value = degraded
    } catch (e: any) {
        channelGroupsDegraded.value = false
        message.error(`Failed to load channel groups: ${extractError(e)}`)
    } finally {
        channelGroupsLoading.value = false
    }
}

function openCreateGroup (): void {
    groupForm.value = freshGroupForm()
    groupModalError.value = ''
    showGroupModal.value = true
}

function openEditGroup (row: ChannelGroupRow): void {
    groupForm.value = {
        uuid: row.uuid,
        expectedRevision: row.revision,
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
        expectedRevision: f.expectedRevision,
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

onMounted(async () => {
    await Promise.all([loadChannels(), loadChannelGroups()])
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
</style>
