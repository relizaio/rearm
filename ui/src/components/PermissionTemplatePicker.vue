<template>
    <div v-if="supported" class="permission-template-picker" style="margin-bottom: 8px;">
        <n-space align="center">
            <n-select
                data-testid="permission-template-picker"
                :value="selected"
                :options="options"
                :placeholder="options.length ? 'Start from a template' : 'No templates yet'"
                :disabled="disabled"
                style="min-width: 260px;"
                @update:value="pick"
            />
            <n-button
                data-testid="save-permission-template"
                :disabled="disabled || !orgUuid"
                @click="openSaveAs"
            >Save as template</n-button>
        </n-space>
        <n-modal
            preset="dialog"
            :show-icon="false"
            title="Save as template"
            :show="showSaveAs"
            @update:show="(v: boolean) => { showSaveAs = v }"
        >
            <n-form-item label="Name">
                <n-input v-model:value="saveName" maxlength="128" placeholder="e.g. CI read-write" data-testid="save-template-name" />
            </n-form-item>
            <n-form-item label="Description">
                <n-input v-model:value="saveDescription" maxlength="512" :count-graphemes="countCodePoints" placeholder="Optional" data-testid="save-template-description" />
            </n-form-item>
            <n-space>
                <n-button type="primary" :disabled="!saveName.trim() || saving" data-testid="save-template-confirm" @click="saveAs">Save template</n-button>
                <n-button @click="showSaveAs = false">Cancel</n-button>
            </n-space>
        </n-modal>
    </div>
</template>

<script lang="ts" setup>
/**
 * TPF-113 (TPF-87 part 2, architecture 4.3 items 1-2, 4.4): "Start from a template" plus "Save as
 * template" over a ScopedPermissions model. A pick only fills the host's editor: it emits
 * apply(model, reductions) with the template's permissions, clipped to the bounds the host passes
 * (display only, decision 3), and the host still approves or sends with its own button. Renders
 * nothing on a server without templates (CE); every other failure goes to notify with the
 * server's text.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { NButton, NFormItem, NInput, NModal, NSelect, NSpace } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import { extractError } from '@/utils/notificationsCommon'
import { scopedModelFromPermissions } from '@/utils/apiKeyGroupInput'
import {
    CREATE_PERMISSION_TEMPLATE_MUTATION, clipModelToBounds, countCodePoints, loadMyPermissionTemplates,
    templateInputFromModel, templateOptions
} from '@/utils/permissionTemplates'

const props = defineProps<{
    orgUuid: string | null
    /** the host editor's current model; "Save as template" saves it */
    model: { orgPermission: any, scopedPermissions: any[] }
    /** the bounds of the clip; each left out clips nothing (the request modal passes none) */
    maxOrgType?: string
    allowedFunctions?: string[]
    orgFunctions?: string[]
    /** while the host loads: the select and the button are disabled and a pick emits nothing */
    disabled?: boolean
    nameOf: (scope: string, objectId: string) => Promise<string>
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
}>()
const emit = defineEmits<{ (e: 'apply', model: { orgPermission: any, scopedPermissions: any[] }, reductions: string[]): void }>()

const templates = ref<any[]>([])
// null until the first answer: nothing renders before the server says it has templates
const supported = ref<boolean | null>(null)
const selected = ref<string | null>(null)
const options = computed(() => templateOptions(templates.value))

const showSaveAs = ref(false)
const saveName = ref('')
const saveDescription = ref('')
const saving = ref(false)

async function load () {
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
onMounted(load)
watch(() => props.orgUuid, () => { selected.value = null; load() })

async function pick (uuid: string | null) {
    if (props.disabled || !uuid || !props.orgUuid) return
    const org = props.orgUuid
    const t = templates.value.find((x: any) => x.uuid === uuid)
    if (!t) return
    selected.value = uuid
    const model = await scopedModelFromPermissions(t.permissions?.permissions, org, props.nameOf)
    if (org !== props.orgUuid || props.disabled) return
    const clipped = clipModelToBounds(model, props.maxOrgType, props.allowedFunctions, props.orgFunctions)
    emit('apply', clipped.model, clipped.reductions)
}

function openSaveAs () {
    if (props.disabled || !props.orgUuid) return
    saveName.value = ''
    saveDescription.value = ''
    showSaveAs.value = true
}

async function saveAs () {
    const org = props.orgUuid
    const name = saveName.value.trim()
    if (!org || !name || props.disabled) return
    saving.value = true
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: CREATE_PERMISSION_TEMPLATE_MUTATION,
            variables: { template: templateInputFromModel(name, saveDescription.value, org, props.model) }
        })
        const created = resp?.data?.createPermissionTemplate
        showSaveAs.value = false
        props.notify('success', 'Saved', `Saved template "${name}"`)
        await load()
        if (created?.uuid) selected.value = created.uuid
    } catch (e: any) {
        console.error('saving a permission template failed', e)
        props.notify('error', 'Error', extractError(e))
    } finally { saving.value = false }
}
</script>
