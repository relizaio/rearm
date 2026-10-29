<template>
    <n-modal preset="dialog" :show-icon="false" style="width: 60%;" :show="show" :title="title" @update:show="onClose">
        <div data-testid="api-key-groups-of-key-editor">
            <n-text v-if="!isFreeform" depth="3" data-testid="api-key-groups-of-key-not-freeform">
                Only Free Form keys can be members of API key groups.
            </n-text>
            <template v-else>
                <p class="subtle">Checked groups add their permissions to this key's on every call, including calls made through a federated trust rule bound to this key. A key cannot be added to an inactive group; it can be removed from one.</p>
                <n-empty v-if="rows.length === 0" description="No API key groups in this organization yet" data-testid="api-key-groups-of-key-empty" />
                <div v-else data-testid="api-key-groups-of-key-list">
                    <div v-for="r in rows" :key="r.uuid" style="margin: 4px 0;">
                        <n-checkbox :checked="checked.includes(r.uuid)" :disabled="r.disabled || saving" :data-testid="'api-key-group-of-key-' + r.uuid" @update:checked="(v: boolean) => toggle(r.uuid, v)">
                            {{ r.name }}
                            <n-tag v-if="r.status === 'INACTIVE'" size="tiny" style="margin-left: 6px;">INACTIVE</n-tag>
                        </n-checkbox>
                    </div>
                </div>
                <n-space style="margin-top: 20px;">
                    <n-button type="success" :disabled="!dirty || saving" :loading="saving" data-testid="api-key-groups-of-key-save" @click="save">Save</n-button>
                    <n-button :disabled="saving" data-testid="api-key-groups-of-key-cancel" @click="onClose(false)">Cancel</n-button>
                </n-space>
            </template>
        </div>
    </n-modal>
</template>

<script setup lang="ts">
/**
 * TPF-124: the API key groups of one Free Form key, edited from the key's row. The modal holds the
 * desired set (every checked, enabled group) and saves it whole through setApiKeyGroupsOfKey; the
 * server computes the delta. A refusal reaches notify with the server's text, the modal stays open and
 * nothing is emitted.
 */
import { computed, ref, watch } from 'vue'
import { NModal, NCheckbox, NTag, NButton, NSpace, NEmpty, NText } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import { extractError } from '@/utils/notificationsCommon'
import { apiKeyLabel } from '@/utils/apiKeyLabel'
import { membershipRows, desiredGroupUuids, membershipDiff, setApiKeyGroupsOfKey } from '@/utils/apiKeyGroupMembership'

const props = defineProps<{
    show: boolean
    apiKey: any
    groups: any[]
    siblingKeys?: any[]
    orgUuid: string
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
}>()
const emit = defineEmits<{
    (e: 'update:show', value: boolean): void
    (e: 'saved', result: { added: string[], removed: string[] }): void
}>()

const isFreeform = computed(() => props.apiKey?.type === 'FREEFORM')
const rows = computed(() => membershipRows(props.groups, props.apiKey?.uuid))
const checked = ref<string[]>([])
const saving = ref(false)
const label = computed(() => props.apiKey ? apiKeyLabel(props.apiKey, { withType: true, siblings: props.siblingKeys || [] }) : '')
const title = computed(() => `API key groups of ${label.value}`)
const diff = computed(() => membershipDiff(rows.value, checked.value))
const dirty = computed(() => diff.value.added.length + diff.value.removed.length > 0)

function reset () {
    checked.value = rows.value.filter(r => r.member).map(r => r.uuid)
}
watch(() => [props.show, props.apiKey?.uuid], ([shown]) => {
    if (shown) reset()
}, { immediate: true })

function toggle (uuid: string, on: boolean) {
    const r = rows.value.find(x => x.uuid === uuid)
    if (!r || r.disabled || saving.value) return
    checked.value = on ? [...checked.value.filter(u => u !== uuid), uuid] : checked.value.filter(u => u !== uuid)
}

function onClose (value: boolean) {
    if (saving.value) return
    emit('update:show', value)
}

async function save () {
    if (saving.value || !dirty.value || !props.apiKey) return
    saving.value = true
    const d = diff.value
    try {
        await setApiKeyGroupsOfKey(graphqlClient, props.orgUuid, props.apiKey.uuid, desiredGroupUuids(rows.value, checked.value))
        props.notify('success', 'Saved', `Key ${label.value}: added to ${d.added.length} group(s), removed from ${d.removed.length}`)
        emit('saved', d)
        emit('update:show', false)
    } catch (e: any) {
        props.notify('error', 'Error', extractError(e))
    } finally {
        saving.value = false
    }
}
</script>
