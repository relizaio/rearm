<template>
    <n-modal
        :show="show"
        preset="dialog"
        :show-icon="false"
        style="width: 720px;"
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

            <n-divider title-placement="left">Route</n-divider>
            <n-form-item label="Severity gate">
                <n-select v-model:value="form.routeMinSeverity"
                          :options="severityOptions"
                          clearable
                          placeholder="No gate (any severity)"
                          :disabled="saving"/>
            </n-form-item>
            <n-form-item label="Channels" required>
                <n-select v-model:value="form.routeChannels"
                          :options="channelOptions"
                          multiple
                          placeholder="Pick one or more channels"
                          :disabled="saving || loadingChannels"
                          :loading="loadingChannels"/>
            </n-form-item>
            <p class="hint">
                v1 surfaces one route per subscription. Multi-route configs (different severity gates →
                different channels) are still authorable through the GraphQL <code>upsertNotificationSubscription</code> mutation directly.
            </p>

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
    status: 'ACTIVE' as 'ACTIVE' | 'DISABLED' | 'PREVIEW',
    eventTypes: [] as string[],
    celExpression: '',
    routeMinSeverity: null as string | null,
    routeChannels: [] as string[],
    dedupWindowMinutes: null as number | null,
})

const statusOptions = [
    { label: 'Active', value: 'ACTIVE' },
    { label: 'Preview (observe-only)', value: 'PREVIEW' },
    { label: 'Disabled', value: 'DISABLED' },
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
    availableChannels.value.map((c: any) => ({
        label: `${c.name} (${c.type}${c.status === 'DISABLED' ? ', DISABLED' : ''})`,
        value: c.uuid,
        disabled: c.status === 'DISABLED',
    }))
)

const canSave = computed(() => {
    if (!form.name.trim()) return false
    if (!form.eventTypes.length) return false
    if (!form.routeChannels.length) return false
    return true
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
        // filter / routes come back as JSON strings from the read API.
        // Best-effort hydrate; users can edit even if the parse fails.
        try {
            const f = props.original.filter ? JSON.parse(props.original.filter) : null
            form.celExpression = f?.celExpression ?? ''
        } catch (e) { form.celExpression = '' }
        try {
            const rs = props.original.routes ? JSON.parse(props.original.routes) : []
            const first = rs[0] ?? null
            form.routeMinSeverity = first?.whenSeverityAtLeast ?? null
            form.routeChannels = first?.channels ?? []
        } catch (e) {
            form.routeMinSeverity = null
            form.routeChannels = []
        }
    } else {
        form.name = ''
        form.status = 'ACTIVE'
        form.eventTypes = []
        form.celExpression = ''
        form.routeMinSeverity = null
        form.routeChannels = []
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

async function save () {
    if (!canSave.value || saving.value) return
    saving.value = true
    try {
        const input: any = {
            org: props.orgUuid,
            name: form.name.trim(),
            status: form.status,
            eventTypes: form.eventTypes,
            routes: [
                {
                    whenSeverityAtLeast: form.routeMinSeverity,
                    andEnvIn: null,
                    andLifecycleIn: null,
                    channels: form.routeChannels,
                },
            ],
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
</style>
