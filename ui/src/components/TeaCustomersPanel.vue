<template>
    <div class="tea-customers-panel">
        <h5 class="listHead mt-4" style="display: flex; align-items: center; gap: 8px;">
            <span>Customers</span>
            <n-icon v-if="isOrgAdmin && supported" class="clickable" size="20" title="Create customer"
                data-testid="customer-create" @click="openCreate"><CirclePlus /></n-icon>
        </h5>
        <p class="subtle" data-testid="customers-lead">
            A customer is a group of external keys that reads the same products and components on TEA. Grants are read-only
            and TEA-only; deactivating a customer stops every key in it at once.
        </p>

        <n-alert v-if="!supported" type="info" :show-icon="false" data-testid="customers-unsupported">
            This server does not serve customer groups yet.
        </n-alert>

        <template v-else>
            <n-space align="center" style="margin-bottom: 12px;">
                <n-switch v-model:value="showInactive" data-testid="customers-show-inactive" />
                <span>Show inactive customers</span>
            </n-space>
            <n-spin :show="loading">
                <n-data-table data-testid="customers-table" :columns="columns" :data="shownGroups"
                    :row-key="(r: any) => r.uuid" :row-class-name="rowClassName" class="table-hover" />
            </n-spin>
        </template>

        <n-modal preset="dialog" :show-icon="false" style="width: 80%;" :show="showEditor" :title="editorTitle"
            @update:show="onEditorClose">
            <div data-testid="customer-editor" style="max-height: 700px; overflow-y: auto; padding-right: 8px;">
                <n-form-item label="Name">
                    <div style="width: 100%;">
                        <n-input v-model:value="form.name" :maxlength="NAME_MAX" show-count placeholder="e.g. Acme Hospital"
                            data-testid="customer-name" />
                        <n-text depth="3" style="font-size: 12px;" data-testid="customer-name-hint">
                            Customer names and API key group names are one list in this organization, inactive ones included:
                            a name already used on the API Key Groups tab is refused.
                        </n-text>
                    </div>
                </n-form-item>
                <n-form-item label="Description">
                    <n-input v-model:value="form.description" type="textarea" :maxlength="DESCRIPTION_MAX" show-count
                        placeholder="Optional" data-testid="customer-description" />
                </n-form-item>
                <n-form-item label="Keys">
                    <div data-testid="customer-keys" style="width: 100%;">
                        <n-text v-if="keyRows.length === 0" depth="3">No external keys in this organization yet</n-text>
                        <div v-for="r in keyRows" :key="r.uuid" style="margin: 4px 0;">
                            <n-checkbox :checked="form.apiKeys.includes(r.uuid)" :disabled="saving"
                                :data-testid="'customer-key-' + r.uuid" @update:checked="(v: boolean) => toggleKey(r.uuid, v)">
                                {{ r.label }}
                                <n-tag v-if="r.status && r.status !== 'ACTIVE'" size="tiny" style="margin-left: 6px;">{{ r.status }}</n-tag>
                            </n-checkbox>
                        </div>
                    </div>
                </n-form-item>
                <n-form-item label="Grants">
                    <div style="width: 100%;">
                        <ScopedPermissions
                            v-model="scoped"
                            :org-uuid="orgUuid"
                            :approval-roles="[]"
                            :perspectives="perspectives"
                            :products="products"
                            :components="components"
                            :instances="[]"
                            :clusters="[]"
                            :boards="[]"
                            :external-key="true"
                        />
                    </div>
                </n-form-item>
                <n-space style="margin-top: 20px;">
                    <n-button type="success" :disabled="!canSave" :loading="saving" data-testid="customer-save" @click="save">Save</n-button>
                    <n-button :disabled="saving" data-testid="customer-cancel" @click="onEditorClose(false)">Cancel</n-button>
                </n-space>
            </div>
        </n-modal>
    </div>
</template>

<script setup lang="ts">
/**
 * FDAW-25 3.12 (FDAW-40 3.5): the Customers sub-tab of Programmatic Access. A customer is an API key
 * group of kind CUSTOMER: the organization's external keys of one outside party, and the TEA grants
 * every key in it reads. The panel loads its own CUSTOMER list and the external keys, never the
 * INTERNAL list OrgSettings holds. The server is the control (organization ADMIN on every verb,
 * membership by kind, TEA-only grants, the bounds and the name rule); every refusal is shown with the
 * server's text and every server value renders as text.
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import {
    NAlert, NButton, NCheckbox, NDataTable, NFormItem, NIcon, NInput, NModal, NSpace, NSpin, NSwitch, NTag, NText,
    useDialog
} from 'naive-ui'
import { CirclePlus, Edit as EditIcon, Trash } from '@vicons/tabler'
import graphqlClient from '@/utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import { extractError } from '@/utils/notificationsCommon'
import { externalGrantsSummary } from '@/utils/apiKeyControls'
import { kindOf, loadApiKeyGroups } from '@/utils/apiKeyGroupsQuery'
import { byText, permissionsFromScopedModel, scopedModelFromPermissions } from '@/utils/apiKeyGroupInput'
import {
    CREATE_CUSTOMER_GROUP, UPDATE_CUSTOMER_GROUP, DEACTIVATE_CUSTOMER_GROUP, customerCreateSteps, customerKeyRows,
    customerKeysTitle, customerUpdateInput, type CustomerForm
} from '@/utils/teaCustomers'
import ScopedPermissions from './ScopedPermissions.vue'

const props = withDefaults(defineProps<{
    orgUuid: string
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
    isOrgAdmin?: boolean
    products?: any[]
    components?: any[]
    perspectives?: any[]
}>(), {
    isOrgAdmin: false,
    products: () => [],
    components: () => [],
    perspectives: () => [],
})

const NAME_MAX = 512
const DESCRIPTION_MAX = 2048

const dialog = useDialog()
const loading = ref(false)
const supported = ref(true)
const groups = ref<any[]>([])
const externalKeys = ref<any[]>([])
const showInactive = ref(false)

const emptyScoped = () => ({ orgPermission: { type: 'NONE', functions: [], approvals: [] }, scopedPermissions: [] as any[] })
const showEditor = ref(false)
/** the stored customer the editor edits; null while it creates one */
const stored = ref<any | null>(null)
const form = ref<CustomerForm>({ name: '', description: '', apiKeys: [] })
const scoped = ref<any>(emptyScoped())
const saving = ref(false)

const productUuids = computed(() => new Set<string>((props.products || []).map((p: any) => p.uuid)))
const shownGroups = computed(() => groups.value
    .filter((g: any) => kindOf(g) === 'CUSTOMER' && (showInactive.value || g.status === 'ACTIVE'))
    .sort((a: any, b: any) => byText(a.name, b.name)))
const keyRows = computed(() => customerKeyRows(externalKeys.value, stored.value))
const editorTitle = computed(() => stored.value ? `Edit customer ${stored.value.name}` : 'Create customer')
const permissions = computed(() => permissionsFromScopedModel(scoped.value, props.orgUuid))
const updateInput = computed(() => stored.value ? customerUpdateInput(stored.value, form.value, permissions.value) : null)
const canSave = computed(() => !saving.value && !!form.value.name.trim() && (!stored.value || !!updateInput.value))

function fail (e: any) {
    console.error('Customer operation failed:', e)
    props.notify('error', 'Error', extractError(e))
}

async function reload () {
    if (!props.orgUuid) return
    loading.value = true
    try {
        const [r, keys]: any[] = await Promise.all([
            loadApiKeyGroups(graphqlClient, props.orgUuid, 'CUSTOMER'),
            graphqlClient.query({ query: graphqlQueries.ExternalApiKeysGql, variables: { orgUuid: props.orgUuid }, fetchPolicy: 'no-cache' }),
        ])
        groups.value = r.groups
        supported.value = r.supported
        externalKeys.value = keys?.data?.externalApiKeys ?? []
    } catch (e: any) {
        fail(e)
    } finally {
        loading.value = false
    }
}

/** A grant's object name from the lists the panel is given; the uuid when it is not among them. */
async function nameOf (scope: string, objectId: string): Promise<string> {
    const list = scope === 'PERSPECTIVE' ? props.perspectives : [...(props.products || []), ...(props.components || [])]
    const known = (list || []).find((x: any) => x.uuid === objectId)
    return known?.name || objectId
}

function openCreate () {
    if (saving.value) return
    stored.value = null
    form.value = { name: '', description: '', apiKeys: [] }
    scoped.value = emptyScoped()
    showEditor.value = true
}

async function openEdit (group: any) {
    if (saving.value) return
    stored.value = group
    form.value = { name: group.name || '', description: group.description || '', apiKeys: [...(group.apiKeys || [])] }
    scoped.value = await scopedModelFromPermissions(group.permissions?.permissions, props.orgUuid, nameOf)
    showEditor.value = true
}

function toggleKey (uuid: string, on: boolean) {
    if (saving.value) return
    const rest = form.value.apiKeys.filter(u => u !== uuid)
    form.value.apiKeys = on ? [...rest, uuid] : rest
}

function onEditorClose (show: boolean) {
    if (saving.value) return
    showEditor.value = show
}

async function save () {
    if (!canSave.value) return
    saving.value = true
    try {
        if (stored.value) await saveEdit()
        else await saveCreate()
    } finally {
        saving.value = false
    }
}

async function saveCreate () {
    const steps = customerCreateSteps(form.value, form.value.apiKeys, permissions.value, props.orgUuid)
    let created: any
    try {
        const resp: any = await graphqlClient.mutate({ mutation: CREATE_CUSTOMER_GROUP, variables: { apiKeyGroup: steps.create } })
        created = resp?.data?.createApiKeyGroup
    } catch (e: any) {
        // nothing exists yet: the editor stays in create mode with the form kept
        fail(e)
        return
    }
    if (steps.follow && created) {
        try {
            await graphqlClient.mutate({
                mutation: UPDATE_CUSTOMER_GROUP,
                variables: { apiKeyGroup: { groupId: created.uuid, apiKeys: form.value.apiKeys, permissions: permissions.value } }
            })
        } catch (e: any) {
            // the customer exists without its members or grants: edit it from here, the form as the admin left it
            fail(e)
            stored.value = created
            await reload()
            return
        }
    }
    props.notify('success', 'Created', `Created customer "${steps.create.name}"`)
    showEditor.value = false
    await reload()
}

async function saveEdit () {
    const input = updateInput.value
    if (!input) return
    const name = form.value.name.trim()
    try {
        await graphqlClient.mutate({ mutation: UPDATE_CUSTOMER_GROUP, variables: { apiKeyGroup: input } })
    } catch (e: any) {
        fail(e)
        return
    }
    props.notify('success', 'Updated', `Updated customer "${name}"`)
    showEditor.value = false
    await reload()
}

function deactivate (group: any) {
    if (saving.value) return
    dialog.warning({
        title: 'Deactivate customer',
        content: `Keys of "${group.name}" stop reading what it grants on TEA at their next request. The customer can be restored later.`,
        positiveText: 'Deactivate',
        negativeText: 'Cancel',
        onPositiveClick: async () => {
            if (saving.value) return
            saving.value = true
            try {
                await graphqlClient.mutate({ mutation: DEACTIVATE_CUSTOMER_GROUP, variables: { groupUuid: group.uuid } })
                props.notify('success', 'Deactivated', `Deactivated customer "${group.name}"`)
                await reload()
            } catch (e: any) {
                fail(e)
            } finally {
                saving.value = false
            }
        }
    })
}

async function restore (group: any) {
    if (saving.value) return
    saving.value = true
    try {
        await graphqlClient.mutate({ mutation: UPDATE_CUSTOMER_GROUP, variables: { apiKeyGroup: { groupId: group.uuid, status: 'ACTIVE' } } })
        props.notify('success', 'Restored', `Restored customer "${group.name}"`)
        await reload()
    } catch (e: any) {
        fail(e)
    } finally {
        saving.value = false
    }
}

function rowClassName (row: any): string {
    return row.status === 'INACTIVE' ? 'inactive-row' : ''
}

const columns = computed(() => [
    { key: 'name', title: 'Name', render: (row: any) => h('span', { 'data-testid': 'customers-name' }, row.name || '') },
    { key: 'description', title: 'Description', render: (row: any) => h('span', row.description || '') },
    {
        key: 'apiKeys', title: 'Keys',
        render: (row: any) => h('span', { 'data-testid': 'customers-keys-count', title: customerKeysTitle(externalKeys.value, row) },
            String((row.apiKeys || []).length))
    },
    {
        key: 'grants', title: 'Grants',
        render: (row: any) => h('span', { 'data-testid': 'customers-grants' },
            externalGrantsSummary(row.permissions?.permissions, props.orgUuid, productUuids.value))
    },
    {
        key: 'status', title: 'Status',
        render: (row: any) => h(NTag, { size: 'small', type: row.status === 'ACTIVE' ? 'success' : 'default' }, { default: () => row.status })
    },
    {
        key: 'controls', title: 'Manage',
        render: (row: any) => {
            if (!props.isOrgAdmin) return h('div')
            if (row.status === 'INACTIVE') {
                return h('div', [h(NButton, { type: 'success', size: 'small', 'data-testid': 'customer-restore', onClick: () => restore(row) },
                    { default: () => 'Restore' })])
            }
            return h('div', [
                h(NIcon, { title: 'Edit customer', class: 'icons clickable', size: 25, 'data-testid': 'customer-edit', onClick: () => openEdit(row) },
                    { default: () => h(EditIcon) }),
                h(NIcon, { title: 'Deactivate customer', class: 'icons clickable', size: 25, 'data-testid': 'customer-deactivate', onClick: () => deactivate(row) },
                    { default: () => h(Trash) })
            ])
        }
    }
])

onMounted(reload)
watch(() => props.orgUuid, reload)
</script>

<style scoped>
:deep(.inactive-row td) {
    opacity: 0.55;
    background-color: #f5f5f5 !important;
}
</style>
