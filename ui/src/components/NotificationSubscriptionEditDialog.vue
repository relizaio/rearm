<template>
    <n-modal
        :show="show"
        preset="dialog"
        :show-icon="false"
        style="width: 760px;"
        :title="isEdit ? `Edit subscription: ${original?.name ?? ''}` : 'New notification subscription'"
        @update:show="(v: boolean) => emit('update:show', v)"
    >
        <n-form :model="form" label-placement="left" label-width="auto" require-mark-placement="right-hanging">
            <n-form-item label="Name" required>
                <n-input v-model:value="form.name" placeholder="e.g. critical-vulns-to-slack" :disabled="saving"/>
            </n-form-item>

            <n-form-item label="Status">
                <n-select v-model:value="form.status" :options="statusOptions" :disabled="saving"/>
            </n-form-item>

            <n-form-item label="Event types" required>
                <n-select v-model:value="form.eventTypes"
                          :options="eventTypeOptions"
                          multiple
                          :disabled="saving"
                          placeholder="Pick one or more"/>
            </n-form-item>

            <n-divider title-placement="left">Filter</n-divider>
            <n-form-item label="CEL expression">
                <n-input v-model:value="form.celExpression"
                         type="textarea"
                         :rows="3"
                         placeholder="e.g. event.severity == &quot;CRITICAL&quot; — leave blank to match every event"
                         :disabled="saving"/>
            </n-form-item>
            <p class="hint">
                Validated at save. Examples: <code>event.severity in ["CRITICAL", "HIGH"]</code>,
                <code>event.kevListed == true</code>,
                <code>size(event.affectedReleases) > 0</code>.
            </p>

            <n-divider title-placement="left">Routes</n-divider>
            <p class="hint">
                Each route layers an extra gate (severity floor) on top of the CEL filter and
                fans out to its own channel set. The first matching route wins per delivery —
                order them most-specific first.
            </p>
            <div v-for="(route, idx) in form.routes" :key="idx" class="route-block">
                <div class="route-header">
                    <strong>Route #{{ idx + 1 }}</strong>
                    <n-button v-if="form.routes.length > 1"
                              size="tiny" quaternary type="error"
                              :disabled="saving"
                              @click="removeRoute(idx)">
                        Remove
                    </n-button>
                </div>
                <n-form-item label="Severity gate">
                    <n-select v-model:value="route.whenSeverityAtLeast"
                              :options="severityOptions"
                              clearable
                              placeholder="No gate (any severity)"
                              :disabled="saving"/>
                </n-form-item>
                <n-form-item label="Channels" required>
                    <n-select v-model:value="route.channels"
                              :options="channelOptions"
                              multiple
                              placeholder="Pick one or more channels"
                              :disabled="saving || loadingChannels"
                              :loading="loadingChannels"/>
                </n-form-item>
                <n-form-item label="Limit to perspectives">
                    <n-select v-model:value="route.perspectives"
                              :options="perspectiveOptions"
                              multiple
                              clearable
                              placeholder="Any perspective (no gate)"
                              :disabled="saving || perspectiveOptions.length === 0"/>
                </n-form-item>
                <p class="hint">
                    Empty = no gate. When set, this route only fires on events that touch a release in
                    one of the chosen perspectives. Events without affected releases (e.g. VEX state
                    changes scoped to a release outside these perspectives) are gated out.
                </p>
            </div>
            <n-button size="small" :disabled="saving" @click="addRoute">+ Add route</n-button>

            <n-divider title-placement="left">Dedup</n-divider>
            <n-form-item label="Dedup window (minutes)">
                <n-input-number v-model:value="form.dedupWindowMinutes"
                                :min="0"
                                placeholder="default 1440 (24h)"
                                :disabled="saving"
                                style="width: 200px;"/>
            </n-form-item>
        </n-form>

        <template #action>
            <n-space>
                <n-button :disabled="saving" @click="emit('update:show', false)">Cancel</n-button>
                <n-button type="primary" :loading="saving" :disabled="!canSave" @click="save">
                    {{ isEdit ? 'Save changes' : 'Create subscription' }}
                </n-button>
            </n-space>
        </template>
    </n-modal>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import {
    NButton, NDivider, NForm, NFormItem, NInput, NInputNumber, NModal, NSelect,
    NSpace, useNotification,
} from 'naive-ui'
import {
    NOTIFICATION_SUBSCRIPTION_STATUSES,
    NOTIFICATION_EVENT_TYPES,
    NOTIFICATION_SEVERITIES,
    type NotificationSubscriptionStatus,
    type NotificationSeverity,
    type NotificationEventType,
} from '@/utils/notification-constants'

interface RouteForm {
    whenSeverityAtLeast: NotificationSeverity | null,
    channels: string[],
    // Phase 12 — list of perspective UUIDs this route is scoped to.
    // Empty array = no gate (route fires regardless of release perspective).
    perspectives: string[],
}

const props = defineProps<{
    show: boolean,
    orgUuid: string,
    original: any | null,  // null = create, populated row = edit
}>()
const emit = defineEmits<{
    (e: 'update:show', value: boolean): void,
    (e: 'saved', sub: any): void,
}>()

const store = useStore()
const notification = useNotification()

const isEdit = computed(() => !!props.original?.uuid)
const saving = ref(false)
const loadingChannels = ref(false)
const availableChannels = ref<any[]>([])

const form = reactive({
    name: '',
    status: 'ACTIVE' as NotificationSubscriptionStatus,
    eventTypes: [] as NotificationEventType[],
    celExpression: '',
    routes: [emptyRoute()] as RouteForm[],
    dedupWindowMinutes: null as number | null,
})

function emptyRoute (): RouteForm {
    return { whenSeverityAtLeast: null, channels: [], perspectives: [] }
}

const statusOptions = NOTIFICATION_SUBSCRIPTION_STATUSES
const eventTypeOptions = NOTIFICATION_EVENT_TYPES
const severityOptions = NOTIFICATION_SEVERITIES
const channelOptions = computed(() =>
    availableChannels.value.map((c: any) => ({
        label: `${c.name} (${c.type}${c.status === 'DISABLED' ? ', DISABLED' : ''})`,
        value: c.uuid,
        disabled: c.status === 'DISABLED',
    }))
)

// Perspectives are already in the global Vuex state (loaded at app
// startup; see TopNavBar / AppHome which use the same getter). If the
// org has zero perspectives, the dropdown stays empty and routes
// continue to behave as "match anything" — same as a route with an
// empty perspectives array.
const perspectivesOfOrg = computed<any[]>(() =>
    store.getters.perspectivesOfOrg(props.orgUuid) || [])
const perspectiveOptions = computed(() =>
    perspectivesOfOrg.value.map((p: any) => ({
        label: p.name,
        value: p.uuid,
    }))
)

const canSave = computed(() => {
    if (!form.name.trim()) return false
    if (!form.eventTypes.length) return false
    // Every route must have at least one channel; an empty channel list
    // would silently no-op a route, so block save instead.
    if (!form.routes.length) return false
    return form.routes.every((r) => r.channels.length > 0)
})

onMounted(loadChannels)

watch(() => props.show, async (opening) => {
    if (!opening) return
    await loadChannels()
    if (props.original) {
        form.name = props.original.name ?? ''
        form.status = props.original.status ?? 'ACTIVE'
        form.eventTypes = [...(props.original.eventTypes ?? [])]
        form.dedupWindowMinutes = props.original.dedupWindowMinutes ?? null
        try {
            const f = props.original.filter ? JSON.parse(props.original.filter) : null
            form.celExpression = f?.celExpression ?? ''
        } catch (e) { form.celExpression = '' }
        try {
            const rs = props.original.routes ? JSON.parse(props.original.routes) : []
            form.routes = rs.length
                ? rs.map((r: any) => ({
                    whenSeverityAtLeast: r.whenSeverityAtLeast ?? null,
                    channels: r.channels ?? [],
                    // Phase 12 — pre-v12 routes have no perspectives key;
                    // fall back to an empty array (= no gate).
                    perspectives: r.perspectives ?? [],
                }))
                : [emptyRoute()]
        } catch (e) {
            form.routes = [emptyRoute()]
        }
    } else {
        form.name = ''
        form.status = 'ACTIVE'
        form.eventTypes = []
        form.celExpression = ''
        form.routes = [emptyRoute()]
        form.dedupWindowMinutes = null
    }
})

async function loadChannels () {
    if (!props.orgUuid) return
    loadingChannels.value = true
    try {
        availableChannels.value = await store.dispatch('fetchNotificationChannelsOfOrg', props.orgUuid) || []
    } catch (e: any) {
        notification.error({ content: `Failed to load channels: ${e?.message ?? e}` })
    } finally {
        loadingChannels.value = false
    }
}

function addRoute () {
    form.routes.push(emptyRoute())
}

function removeRoute (idx: number) {
    form.routes.splice(idx, 1)
    // Guard: always leave at least one route block visible. The "no
    // routes" empty state isn't authorable through the dialog —
    // a subscription with zero routes can't dispatch anything.
    if (form.routes.length === 0) form.routes.push(emptyRoute())
}

async function save () {
    if (!canSave.value || saving.value) return
    saving.value = true
    try {
        const input: any = {
            org: props.orgUuid,
            name: form.name.trim(),
            status: form.status,
            eventTypes: form.eventTypes,
            routes: form.routes.map((r) => ({
                whenSeverityAtLeast: r.whenSeverityAtLeast,
                andEnvIn: null,
                andLifecycleIn: null,
                channels: r.channels,
                // Send null (not []) when no perspectives are selected
                // so the backend's "perspectives == null/empty = no gate"
                // branch is the canonical no-op rather than a populated
                // empty-list with edge-case semantics.
                perspectives: r.perspectives.length > 0 ? r.perspectives : null,
            })),
        }
        if (isEdit.value) input.uuid = props.original.uuid
        if (form.celExpression.trim()) {
            input.filter = {
                mode: 'ADVANCED',
                celExpression: form.celExpression.trim(),
            }
        }
        if (form.dedupWindowMinutes !== null && form.dedupWindowMinutes !== undefined) {
            input.dedupWindowMinutes = form.dedupWindowMinutes
        }

        const saved = await store.dispatch('upsertNotificationSubscription', input)
        notification.success({
            content: `${isEdit.value ? 'Saved' : 'Created'} subscription "${saved.name}"`,
        })
        emit('saved', saved)
        emit('update:show', false)
    } catch (e: any) {
        notification.error({
            content: `Save failed: ${e?.message ?? e}`,
            duration: 8000,
        })
    } finally {
        saving.value = false
    }
}
</script>

<style scoped>
.hint {
    font-size: 12px;
    color: #888;
    margin: -10px 0 12px 0;
}
.hint code {
    background: rgba(0,0,0,0.04);
    padding: 1px 4px;
    border-radius: 3px;
}
.route-block {
    border: 1px solid #eee;
    border-radius: 4px;
    padding: 12px 14px 4px 14px;
    margin-bottom: 10px;
    background: rgba(0,0,0,0.015);
}
.route-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 8px;
}
</style>
