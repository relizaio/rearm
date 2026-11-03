<template>
    <div class="api-key-groups-pane">
        <!-- The create icon right of the heading, as the component view's Branch heading has it. -->
        <h5 class="listHead mt-4" style="display: flex; align-items: center; gap: 8px;">
            <span>API Key Groups</span>
            <n-icon v-if="isWritable && supported" class="clickable" size="20" title="Add group"
                data-testid="add-api-key-group" @click="openCreate"><CirclePlus /></n-icon>
        </h5>
        <div class="tab-toolbar">
            <div class="tab-toolbar-info">
                An API key group is a permission set that Free Form keys of this organization join. A member key is
                granted the group's permissions in addition to its own on every call, including calls made through a
                federated trust rule bound to the key; nothing is copied onto the key.
            </div>
        </div>

        <n-alert
            v-if="!supported"
            type="info"
            :show-icon="false"
            data-testid="api-key-groups-unsupported"
        >
            API key groups are not available on this server version. Everything else on this page still works.
        </n-alert>

        <template v-if="supported">
            <n-space align="center" style="margin-bottom: 12px;">
                <n-switch v-model:value="showInactive" data-testid="api-key-groups-show-inactive" />
                <span>Show inactive groups</span>
            </n-space>
            <n-data-table
                data-testid="api-key-groups-table"
                :columns="columns"
                :data="shownGroups"
                :row-class-name="rowClassName"
                class="table-hover"
            />
        </template>

        <n-modal
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            :show="showEditor"
            :title="editorTitle"
            @update:show="onEditorClose"
        >
            <div data-testid="api-key-group-editor" style="max-height: 700px; overflow-y: auto; padding-right: 8px;">
                <n-form-item label="Name">
                    <n-input v-model:value="form.name" placeholder="e.g. CI keys" data-testid="api-key-group-name" />
                </n-form-item>
                <n-form-item label="Description">
                    <n-input v-model:value="form.description" placeholder="Optional" data-testid="api-key-group-description" />
                </n-form-item>
                <template v-if="form.uuid">
                    <n-form-item label="Member keys">
                        <div data-testid="api-key-group-keys" style="width: 100%;">
                            <n-select
                                v-model:value="form.apiKeys"
                                multiple
                                filterable
                                :options="keyOptions"
                                :render-label="renderKeyOption"
                                placeholder="Free Form keys that gain this group's permissions"
                            />
                        </div>
                    </n-form-item>
                    <ScopedPermissions
                        v-model="scoped"
                        :org-uuid="orguuid"
                        :approval-roles="approvalRoles"
                        :perspectives="perspectives"
                        :products="products"
                        :components="components"
                        :instances="instances"
                        :clusters="clusters"
                        :boards="boards"
                        :show-sbom-probing="true"
                    />
                </template>
                <n-text v-else depth="3" style="font-size: 12px;">
                    Member keys and permissions can be set once the group exists.
                </n-text>
                <n-space style="margin-top: 20px;">
                    <template v-if="!form.uuid">
                        <n-button type="primary" :disabled="!form.name.trim()" data-testid="api-key-group-create" @click="create">Create group</n-button>
                        <n-button @click="showEditor = false">Cancel</n-button>
                    </template>
                    <template v-else-if="dirty">
                        <n-button type="success" :disabled="!form.name.trim()" data-testid="api-key-group-save" @click="save">Save changes</n-button>
                        <n-button type="warning" data-testid="api-key-group-reset" @click="reset">Reset changes</n-button>
                    </template>
                </n-space>
            </div>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
/**
 * TPF-92 (architecture 4.4): the API Key Groups sub-tab of Organization Settings' Programmatic Access, after Free Form Keys.
 * OrgSettings owns the group list (decision 1) and passes it in; this pane creates, edits,
 * deactivates and restores groups, then emits changed so OrgSettings reloads. Every refusal goes
 * to notify with the server's text, and nothing is emitted on a failure.
 */
import { computed, h, ref, Ref } from 'vue'
import {
    NAlert, NButton, NDataTable, NFormItem, NIcon, NInput, NModal, NSelect, NSpace, NSwitch, NTag, NText, NTooltip,
    useDialog
} from 'naive-ui'
import { CirclePlus, Edit as EditIcon, Trash } from '@vicons/tabler'
import { Info20Regular } from '@vicons/fluent'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import constants from '@/utils/constants'
import { extractError } from '@/utils/notificationsCommon'
import { boardNameOf } from '@/utils/boardPermissions'
import { apiKeyLabel } from '@/utils/apiKeyLabel'
import {
    buildApiKeyGroupUpdateInput, freeformKeyOptions, permissionsFromScopedModel, scopedModelFromPermissions
} from '@/utils/apiKeyGroupInput'
import ScopedPermissions from './ScopedPermissions.vue'

const props = defineProps<{
    orguuid: string
    isWritable: boolean
    groups: any[]
    supported: boolean
    /** the organization's keys as OrgSettings loads them; the member picker keeps the FREEFORM ones */
    keys: any[]
    approvalRoles: any[]
    perspectives: any[]
    products: any[]
    components: any[]
    instances: any[]
    clusters: any[]
    boards: any[]
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
}>()
const emit = defineEmits(['changed'])
const store = useStore()
const dialog = useDialog()

// The selection is repeated in each document on purpose: validate-graphql.mjs skips a template
// containing ${...}, and these operations are Pro-ahead of the CE mirror.
const CREATE_API_KEY_GROUP = gql`
    mutation createApiKeyGroup($apiKeyGroup: CreateApiKeyGroupInput!) {
        createApiKeyGroup(apiKeyGroup: $apiKeyGroup) {
            uuid name description org status apiKeys
            apiKeyDetails { uuid keyOrder type status notes declaredName }
            permissions { permissions { org scope object type meta approvals functions } }
            createdDate lastUpdatedBy
        }
    }`
const UPDATE_API_KEY_GROUP = gql`
    mutation updateApiKeyGroup($apiKeyGroup: UpdateApiKeyGroupInput!) {
        updateApiKeyGroup(apiKeyGroup: $apiKeyGroup) {
            uuid name description org status apiKeys
            apiKeyDetails { uuid keyOrder type status notes declaredName }
            permissions { permissions { org scope object type meta approvals functions } }
            createdDate lastUpdatedBy
        }
    }`
const DELETE_API_KEY_GROUP = gql`
    mutation deleteApiKeyGroup($groupUuid: ID!) {
        deleteApiKeyGroup(groupUuid: $groupUuid) {
            uuid name description org status apiKeys
            apiKeyDetails { uuid keyOrder type status notes declaredName }
            permissions { permissions { org scope object type meta approvals functions } }
            createdDate lastUpdatedBy
        }
    }`

interface GroupForm { uuid: string | null, name: string, description: string, apiKeys: string[] }
const emptyScoped = () => ({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] as any[] })
const deepCopy = (v: any) => JSON.parse(JSON.stringify(v))

const showInactive = ref(false)
const showEditor = ref(false)
const editing: Ref<any | null> = ref(null)
const form: Ref<GroupForm> = ref({ uuid: null, name: '', description: '', apiKeys: [] })
const scoped: Ref<any> = ref(emptyScoped())
const formOriginal: Ref<GroupForm | null> = ref(null)
const scopedOriginal: Ref<any> = ref(null)

const shownGroups = computed(() => {
    const groups = showInactive.value ? (props.groups || []) : (props.groups || []).filter((g: any) => g.status === 'ACTIVE')
    return [...groups].sort((a: any, b: any) => (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' }))
})
const editorTitle = computed(() => form.value.uuid ? 'API Key Group Settings for ' + (editing.value?.name || form.value.name) : 'Add API Key Group')
const keyOptions = computed(() => freeformKeyOptions(props.keys, props.keys, form.value.apiKeys, editing.value?.apiKeyDetails))
const dirty = computed(() => !!formOriginal.value && (
    commonFunctions.stableStringify(form.value) !== commonFunctions.stableStringify(formOriginal.value)
    || commonFunctions.stableStringify(scoped.value) !== commonFunctions.stableStringify(scopedOriginal.value)))

function rowClassName (row: any): string {
    return row.status === 'INACTIVE' ? 'inactive-row' : ''
}

function renderKeyOption (option: any) {
    const kids: any[] = [h('span', option.label)]
    if (option.status && option.status !== 'ACTIVE') {
        kids.push(h(NTag, { size: 'tiny', style: 'margin-left: 6px;' }, { default: () => option.status }))
    }
    return h('span', kids)
}

/** A grant's object name for the editor, from the lists OrgSettings passes (the key modal's resolver). */
async function resolveName (scope: string, objectId: string): Promise<string> {
    if (scope === 'BOARD') return boardNameOf(props.boards, objectId) ?? objectId
    if (scope === 'PERSPECTIVE') {
        const p = (props.perspectives || []).find((x: any) => x.uuid === objectId)
        return p ? p.name : objectId
    }
    if (scope === 'INSTANCE') {
        const all = [...(props.instances || []), ...(props.clusters || [])]
        const obj = all.find((x: any) => x.uuid === objectId)
        if (!obj) return objectId
        if (obj.instanceType === constants.InstanceType.CLUSTER_INSTANCE) {
            const parent = (props.clusters || []).find((c: any) => Array.isArray(c.instances) && c.instances.includes(obj.uuid))
            return `${parent ? parent.name : '(cluster)'} / ${obj.namespace || obj.uuid}`
        }
        if (obj.instanceType === constants.InstanceType.CLUSTER) return obj.name || obj.uri || obj.uuid
        return obj.uri || obj.name || obj.uuid
    }
    const known = [...(props.components || []), ...(props.products || [])].find((c: any) => c.uuid === objectId)
    if (known) return known.name
    try { const fetched = await store.dispatch('fetchComponent', objectId); return fetched?.name || objectId } catch { return objectId }
}

function openCreate () {
    editing.value = null
    form.value = { uuid: null, name: '', description: '', apiKeys: [] }
    scoped.value = emptyScoped()
    formOriginal.value = null
    scopedOriginal.value = null
    showEditor.value = true
}

async function openEdit (group: any) {
    editing.value = group
    form.value = { uuid: group.uuid, name: group.name || '', description: group.description || '', apiKeys: [...(group.apiKeys || [])] }
    scoped.value = await scopedModelFromPermissions(group.permissions?.permissions, props.orguuid, resolveName)
    formOriginal.value = deepCopy(form.value)
    scopedOriginal.value = deepCopy(scoped.value)
    showEditor.value = true
}

function reset () {
    if (!formOriginal.value) return
    form.value = deepCopy(formOriginal.value)
    scoped.value = deepCopy(scopedOriginal.value)
}

function onEditorClose (show: boolean) {
    if (!show && dirty.value) {
        dialog.warning({
            title: 'Discard unsaved changes?',
            content: 'The changes to this group are not saved.',
            positiveText: 'Discard',
            negativeText: 'Keep editing',
            onPositiveClick: () => { showEditor.value = false }
        })
        return
    }
    showEditor.value = show
}

function fail (e: any) {
    console.error('API key group operation failed:', e)
    props.notify('error', 'Error', extractError(e))
}

async function create () {
    const name = form.value.name.trim()
    if (!name) return
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: CREATE_API_KEY_GROUP,
            variables: { apiKeyGroup: { name, description: form.value.description, org: props.orguuid } }
        })
        const created = resp?.data?.createApiKeyGroup
        props.notify('success', 'Created', `Created API key group "${name}"`)
        emit('changed')
        if (created) await openEdit(created)
    } catch (e: any) { fail(e) }
}

async function save () {
    const name = form.value.name.trim()
    if (!name || !form.value.uuid) return
    try {
        await graphqlClient.mutate({
            mutation: UPDATE_API_KEY_GROUP,
            variables: {
                apiKeyGroup: buildApiKeyGroupUpdateInput({ ...form.value, uuid: form.value.uuid, name },
                    permissionsFromScopedModel(scoped.value, props.orguuid))
            }
        })
        props.notify('success', 'Saved', `Updated API key group "${name}"`)
        emit('changed')
        formOriginal.value = null
        showEditor.value = false
    } catch (e: any) { fail(e) }
}

function deactivate (group: any) {
    dialog.warning({
        title: 'Deactivate API Key Group',
        content: `Keys in "${group.name}" lose its permissions on their next call. The group can be restored later.`,
        positiveText: 'Deactivate',
        negativeText: 'Cancel',
        onPositiveClick: async () => {
            try {
                await graphqlClient.mutate({ mutation: DELETE_API_KEY_GROUP, variables: { groupUuid: group.uuid } })
                props.notify('success', 'Deactivated', `Deactivated API key group "${group.name}"`)
                emit('changed')
            } catch (e: any) { fail(e) }
        }
    })
}

async function restore (group: any) {
    try {
        await graphqlClient.mutate({ mutation: UPDATE_API_KEY_GROUP, variables: { apiKeyGroup: { groupId: group.uuid, status: 'ACTIVE' } } })
        props.notify('success', 'Restored', `Restored API key group "${group.name}"`)
        emit('changed')
    } catch (e: any) { fail(e) }
}

const columns = computed(() => [
    {
        key: 'name',
        title: 'Group Name',
        render (row: any) {
            if (row.description && row.description.trim()) {
                return h('div', [
                    h('span', row.name),
                    h(NTooltip, { trigger: 'hover' }, {
                        trigger: () => h(NIcon, { class: 'icons', size: 20, style: 'margin-left: 8px;' }, { default: () => h(Info20Regular) }),
                        default: () => row.description
                    })
                ])
            }
            return h('div', row.name)
        }
    },
    {
        key: 'apiKeys',
        title: 'Keys',
        render (row: any) {
            const members = row.apiKeyDetails || []
            const text = `${members.length} key${members.length === 1 ? '' : 's'}`
            if (!members.length) return h('span', text)
            return h(NTooltip, { trigger: 'hover' }, {
                trigger: () => h('span', text),
                default: () => members.map((m: any) => h('div', `${apiKeyLabel(m, { siblings: props.keys })} (${m.status || 'unknown'})`))
            })
        }
    },
    {
        key: 'status',
        title: 'Status',
        render: (row: any) => h(NTag, { size: 'small', type: row.status === 'ACTIVE' ? 'success' : 'default' }, { default: () => row.status })
    },
    {
        key: 'controls',
        title: 'Manage',
        render (row: any) {
            if (!props.isWritable) return h('div')
            if (row.status === 'INACTIVE') {
                return h('div', [h(NButton, { type: 'success', size: 'small', 'data-testid': 'restore-api-key-group', onClick: () => restore(row) }, { default: () => 'Restore' })])
            }
            return h('div', [
                h(NIcon, { title: 'Edit API Key Group', class: 'icons clickable', size: 25, 'data-testid': 'edit-api-key-group', onClick: () => openEdit(row) }, { default: () => h(EditIcon) }),
                h(NIcon, { title: 'Deactivate API Key Group', class: 'icons clickable', size: 25, 'data-testid': 'deactivate-api-key-group', onClick: () => deactivate(row) }, { default: () => h(Trash) })
            ])
        }
    }
])
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
:deep(.inactive-row td) {
    opacity: 0.55;
    background-color: #f5f5f5 !important;
}
</style>
