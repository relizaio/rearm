<template>
    <div v-if="supported" class="permission-templates-panel">
        <n-space justify="end" style="margin-bottom: 8px;">
            <!-- The plus icon of the other add controls, with its title as the tooltip; a native button for the keyboard. -->
            <button type="button" class="new-template" :class="orgUuid ? 'clickable' : ''" :disabled="!orgUuid" title="New template"
                    aria-label="New template" data-testid="new-permission-template" @click="openNew">
                <n-icon class="icons" size="24"><CirclePlus /></n-icon>
            </button>
        </n-space>
        <n-data-table
            data-testid="permission-templates-table"
            :columns="columns"
            :data="sortedTemplates"
            class="table-hover"
        />
        <n-modal
            preset="dialog"
            :show-icon="false"
            style="width: 90%;"
            :show="showEditor"
            :title="editing ? 'Permission template ' + (editing.name || '') : 'New permission template'"
            @update:show="(v: boolean) => { showEditor = v }"
        >
            <div data-testid="permission-template-editor" style="max-height: 700px; overflow-y: auto; padding-right: 8px;">
                <n-form-item label="Name">
                    <n-input v-model:value="form.name" maxlength="128" data-testid="permission-template-name" />
                </n-form-item>
                <n-form-item label="Description">
                    <n-input v-model:value="form.description" maxlength="512" :count-graphemes="countCodePoints" placeholder="Optional" data-testid="permission-template-description" />
                </n-form-item>
                <n-spin :show="loadingLists">
                    <ScopedPermissions
                        v-if="orgUuid"
                        v-model="scoped"
                        :org-uuid="orgUuid"
                        :approval-roles="approvalRoles"
                        :perspectives="perspectives"
                        :products="orgProducts"
                        :components="orgComponents"
                        :instances="orgInstances"
                        :clusters="orgClusters"
                        :boards="boards"
                        :show-sbom-probing="true"
                    />
                </n-spin>
                <n-space style="margin-top: 20px;">
                    <n-button v-if="editing" type="success" :disabled="!form.name.trim() || !dirty || saving" data-testid="permission-template-save" @click="save">Save changes</n-button>
                    <n-button v-else type="success" :disabled="!form.name.trim() || saving || loadingLists" data-testid="permission-template-create" @click="save">Save template</n-button>
                    <n-button @click="showEditor = false">Cancel</n-button>
                </n-space>
            </div>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
/**
 * TPF-113 (TPF-87 part 2, architecture 4.3 item 3, 4.4): the profile's list of the user's own
 * permission templates in one organization, with New template, edit and delete. New template opens
 * the same editor empty and saves through createPermissionTemplate, the mutation and bounds of the
 * picker's Save as template (name required, 128 / 512, the description counted in code points);
 * templates are also saved from the device-login page or the key request (PermissionTemplatePicker).
 * Renders nothing on a server without templates (CE); every refusal goes to notify with the server's
 * text. The editor's object lists are loaded here as ApiKeyPermissionsModal loads its own, since the
 * profile page has none.
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NDataTable, NFormItem, NIcon, NInput, NModal, NSpace, NSpin, NTooltip, useDialog } from 'naive-ui'
import { CirclePlus, Edit as EditIcon, Trash } from '@vicons/tabler'
import { Info20Regular } from '@vicons/fluent'
import { useStore } from 'vuex'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import constants from '@/utils/constants'
import commonFunctions from '@/utils/commonFunctions'
import { extractError } from '@/utils/notificationsCommon'
import { boardNameOf } from '@/utils/boardPermissions'
import { scopedModelFromPermissions } from '@/utils/apiKeyGroupInput'
import {
    CREATE_PERMISSION_TEMPLATE_MUTATION, DELETE_PERMISSION_TEMPLATE_MUTATION, UPDATE_PERMISSION_TEMPLATE_MUTATION, countCodePoints,
    grantsText, loadMyPermissionTemplates, templateGrantCount, templateInputFromModel, templateOrgType, templateUpdateInput
} from '@/utils/permissionTemplates'
import ScopedPermissions from './ScopedPermissions.vue'

const props = defineProps<{
    orgUuid: string | null
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
}>()
const store = useStore()
const dialog = useDialog()

const templates = ref<any[]>([])
// null until the first answer: nothing renders before the server says it has templates
const supported = ref<boolean | null>(null)
const sortedTemplates = computed(() => [...templates.value].sort((a: any, b: any) =>
    (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' })))

async function reload () {
    const org = props.orgUuid
    if (!org) { templates.value = []; return }
    try {
        const r = await loadMyPermissionTemplates(graphqlClient, org)
        if (org !== props.orgUuid) return
        templates.value = r.templates
        supported.value = r.supported
    } catch (e: any) {
        console.error('permission templates unavailable', e)
        props.notify('error', 'Error', extractError(e))
    }
}
onMounted(reload)
watch(() => props.orgUuid, () => { showEditor.value = false; reload() })
defineExpose({ reload, supported })

// ---- the editor's object lists, loaded when the editor opens ----
const loadingLists = ref(false)
const perspectives = ref<any[]>([])
const boards = ref<any[]>([])
const approvalRoles = computed(() => store.getters.orgById(props.orgUuid)?.approvalRoles || [])
const orgComponents = computed(() => store.getters.componentsOfOrg(props.orgUuid) || [])
const orgProducts = computed(() => store.getters.productsOfOrg(props.orgUuid) || [])
const orgInstancesAndClusters = computed(() => {
    if (store.getters.myuser?.installationType === 'OSS') return []
    return (store.getters.instancesOfOrg(props.orgUuid) || []).filter((x: any) => x.revision === -1 && (x.status === 'ACTIVE' || !x.status))
})
const orgInstances = computed(() => orgInstancesAndClusters.value
    .filter((x: any) => x.instanceType === constants.InstanceType.STANDALONE_INSTANCE || x.instanceType === constants.InstanceType.CLUSTER_INSTANCE))
const orgClusters = computed(() => orgInstancesAndClusters.value.filter((x: any) => x.instanceType === constants.InstanceType.CLUSTER))

async function loadOrgObjects (org: string) {
    const loads: Promise<any>[] = [
        graphqlClient.query({ query: gql`query perspectives($org: ID!) { perspectives(org: $org) { uuid name org type } }`, variables: { org }, fetchPolicy: 'no-cache' })
            .then((r: any) => { perspectives.value = r?.data?.perspectives || [] }),
        Promise.resolve(store.dispatch('fetchAgentBoardNamesOfOrg', org)).then((b: any) => { boards.value = b ?? [] }),
        store.dispatch('fetchComponents', org), store.dispatch('fetchProducts', org)
    ]
    if (store.getters.myuser?.installationType !== 'OSS') loads.push(store.dispatch('fetchInstances', org))
    const results = await Promise.allSettled(loads)
    for (const r of results) if (r.status === 'rejected') console.warn('permission templates: an org object list is unavailable', r.reason?.message || r.reason)
}

/** A grant's object name for the editor (the key modal's resolver over the lists loaded here). */
async function resolveName (scope: string, objectId: string): Promise<string> {
    if (scope === 'BOARD') return boardNameOf(boards.value, objectId) ?? objectId
    if (scope === 'PERSPECTIVE') {
        const p = perspectives.value.find((x: any) => x.uuid === objectId)
        return p ? p.name : objectId
    }
    if (scope === 'INSTANCE') {
        const obj = orgInstancesAndClusters.value.find((x: any) => x.uuid === objectId)
        if (!obj) return objectId
        if (obj.instanceType === constants.InstanceType.CLUSTER_INSTANCE) {
            const parent = orgClusters.value.find((c: any) => Array.isArray(c.instances) && c.instances.includes(obj.uuid))
            return `${parent ? parent.name : '(cluster)'} / ${obj.namespace || obj.uuid}`
        }
        if (obj.instanceType === constants.InstanceType.CLUSTER) return obj.name || obj.uri || obj.uuid
        return obj.uri || obj.name || obj.uuid
    }
    const known = [...orgComponents.value, ...orgProducts.value].find((c: any) => c.uuid === objectId)
    if (known) return known.name
    try { const fetched = await store.dispatch('fetchComponent', objectId); return fetched?.name || objectId } catch { return objectId }
}

// ---- the editor ----
const emptyScoped = () => ({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] as any[] })
const deepCopy = (v: any) => JSON.parse(JSON.stringify(v))
const showEditor = ref(false)
const saving = ref(false)
const editing = ref<any | null>(null)
// New template: the editor was opened empty by New template (editing stays null)
const creating = ref(false)
const form = ref<{ name: string, description: string }>({ name: '', description: '' })
const scoped = ref<any>(emptyScoped())
const original = ref<string>('')
const snapshot = () => commonFunctions.stableStringify({ form: form.value, scoped: scoped.value })
const dirty = computed(() => !!original.value && snapshot() !== original.value)

async function openEdit (template: any) {
    const org = props.orgUuid
    if (!org) return
    editing.value = template
    creating.value = false
    form.value = { name: template.name || '', description: template.description || '' }
    scoped.value = emptyScoped()
    original.value = ''
    showEditor.value = true
    loadingLists.value = true
    try {
        await loadOrgObjects(org)
        scoped.value = await scopedModelFromPermissions(template.permissions?.permissions, org, resolveName)
        original.value = snapshot()
    } finally { loadingLists.value = false }
}

/** New template: the same editor, empty, for the selected organization (editing stays null). */
async function openNew () {
    const org = props.orgUuid
    if (!org) return
    editing.value = null
    creating.value = true
    form.value = { name: '', description: '' }
    scoped.value = emptyScoped()
    original.value = ''
    showEditor.value = true
    loadingLists.value = true
    try {
        await loadOrgObjects(org)
    } finally { loadingLists.value = false }
}

/** Saves the editor: an update of the template being edited, or a new one through createPermissionTemplate. */
async function save () {
    const org = props.orgUuid
    const name = form.value.name.trim()
    const template = editing.value
    // an update needs the template being edited; a create needs the editor opened by New template
    if (!org || !name || (!template && !creating.value)) return
    saving.value = true
    try {
        if (template) {
            await graphqlClient.mutate({
                mutation: UPDATE_PERMISSION_TEMPLATE_MUTATION,
                variables: { template: templateUpdateInput(template.uuid, name, form.value.description, deepCopy(scoped.value), org) }
            })
        } else {
            await graphqlClient.mutate({
                mutation: CREATE_PERMISSION_TEMPLATE_MUTATION,
                variables: { template: templateInputFromModel(name, form.value.description, org, deepCopy(scoped.value)) }
            })
        }
        props.notify('success', 'Saved', `Saved template "${name}"`)
        showEditor.value = false
        creating.value = false
        await reload()
    } catch (e: any) {
        console.error(template ? 'updating a permission template failed' : 'creating a permission template failed', e)
        props.notify('error', 'Error', extractError(e))
    } finally { saving.value = false }
}

function remove (template: any) {
    dialog.warning({
        title: 'Delete permission template',
        content: `Delete the template "${template.name}"? Keys and sessions approved from it keep their permissions.`,
        positiveText: 'Delete',
        negativeText: 'Cancel',
        onPositiveClick: async () => {
            try {
                await graphqlClient.mutate({ mutation: DELETE_PERMISSION_TEMPLATE_MUTATION, variables: { uuid: template.uuid } })
                props.notify('success', 'Deleted', `Deleted template "${template.name}"`)
                await reload()
            } catch (e: any) {
                console.error('deleting a permission template failed', e)
                props.notify('error', 'Error', extractError(e))
            }
        }
    })
}

const columns = computed(() => [
    {
        key: 'name',
        title: 'Name',
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
        key: 'orgWide',
        title: 'Organization-wide',
        render: (row: any) => {
            const t = templateOrgType(row, props.orgUuid || '')
            return h('span', t === 'NONE' ? 'none' : t)
        }
    },
    {
        key: 'perObject',
        title: 'Per object',
        render: (row: any) => h('span', grantsText(templateGrantCount(row, props.orgUuid || '')))
    },
    {
        key: 'controls',
        title: 'Manage',
        render: (row: any) => h('div', [
            h(NIcon, { title: 'Edit template', class: 'icons clickable', size: 25, 'data-testid': 'edit-permission-template', onClick: () => openEdit(row) }, { default: () => h(EditIcon) }),
            h(NIcon, { title: 'Delete template', class: 'icons clickable', size: 25, 'data-testid': 'delete-permission-template', onClick: () => remove(row) }, { default: () => h(Trash) })
        ])
    }
])
</script>

<style scoped lang="scss">
.new-template { background: none; border: none; padding: 0; color: inherit; line-height: 0; }
.new-template:disabled { opacity: 0.5; }
</style>
