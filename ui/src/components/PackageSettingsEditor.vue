<template>
    <div class="package-settings-editor">
        <n-spin :show="loading">
            <div v-if="loadError" class="text-danger">
                <span data-testid="package-load-error">{{ loadError }}</span>
                <n-button size="tiny" style="margin-left: 8px;" data-testid="package-retry" @click="init">Retry</n-button>
            </div>
            <n-alert v-if="!isWritable" type="default" class="package-alert">
                Read-only: changing regulatory package settings needs admin rights here.
            </n-alert>
            <n-form label-placement="left" label-width="210" :disabled="!isWritable" size="small">
                <n-form-item label-for="package-kind">
                    <template #label><FieldLabel label="Package kind" :tip="PACKAGE_FIELD_HELP.kind" /></template>
                    <n-select data-testid="package-kind" :value="kind" :options="kindOptions" :filterable="false"
                        :disabled="false" @update:value="selectKind" />
                </n-form-item>

                <template v-if="view">
                    <p data-testid="package-source" class="text-muted">{{ sourceLine }}</p>
                    <p data-testid="package-effective" class="text-muted">{{ effectiveLine }}</p>
                    <n-alert v-if="view.parent?.status === 'CONFLICT'" data-testid="package-conflict" type="warning" class="package-alert">
                        {{ sourceLine }}
                    </n-alert>

                    <n-form-item v-if="showMode" label-for="package-mode">
                        <template #label><FieldLabel label="Mode" :tip="PACKAGE_FIELD_HELP.mode" /></template>
                        <n-radio-group data-testid="package-mode" v-model:value="form.mode">
                            <n-radio v-for="o in PACKAGE_MODE_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
                        </n-radio-group>
                    </n-form-item>
                    <n-form-item v-if="follows" label="Followed perspective" label-for="package-follow-perspective">
                        <n-select data-testid="package-follow-perspective" v-model:value="form.followedPerspective"
                            :options="perspectiveOptions" :filterable="false" placeholder="Choose one of this component's perspectives" />
                    </n-form-item>

                    <div v-if="!follows" data-testid="package-fields">
                        <n-form-item label-for="package-sbom-profile">
                            <template #label><FieldLabel label="SBOM profile" :tip="PACKAGE_FIELD_HELP.sbomProfile" /></template>
                            <SbomProfilePicker data-testid="package-sbom-profile" :model-value="profileValue" single
                                :entries="catalog" :installation-type="installationType" :names="allNames"
                                :disabled="!isWritable" @update:model-value="setProfile" />
                        </n-form-item>
                        <div v-if="catalogError" data-testid="package-profiles-error" class="text-danger">{{ catalogError }}</div>

                        <table data-testid="package-parts" class="package-parts">
                            <thead>
                                <tr><th>Part</th><th>Requirement</th><th>Selection</th><th>Options</th></tr>
                            </thead>
                            <tbody>
                                <tr v-for="p in form.parts" :key="p.part" :data-testid="'package-part-' + p.part">
                                    <td>
                                        <FieldLabel :label="packagePartLabel(p.part)" :tip="PACKAGE_FIELD_HELP.selection" />
                                        <n-tag v-if="!p.generatorPresent" size="small" type="warning" data-testid="package-no-generator"
                                            title="No generator is registered for this part on this server; a submission including it is refused">
                                            no generator
                                        </n-tag>
                                    </td>
                                    <td>
                                        <n-tag size="small" :type="p.requirement === 'REQUIRED' ? 'error' : 'default'">{{ p.requirement }}</n-tag>
                                    </td>
                                    <td :title="PACKAGE_FIELD_HELP.selection">
                                        <n-select :data-testid="'package-selection-' + p.part" v-model:value="p.selection"
                                            :options="PACKAGE_SELECTION_OPTIONS" :filterable="false" :disabled="p.requirement === 'REQUIRED'" />
                                        <div v-if="p.requirement === 'REQUIRED'" :data-testid="'package-selection-reason-' + p.part" class="text-muted">
                                            REQUIRED by the kind
                                        </div>
                                    </td>
                                    <td>
                                        <div v-for="f in optionFields(p.part)" :key="f.field" class="package-option" :title="PACKAGE_FIELD_HELP[f.field]">
                                            <FieldLabel :label="f.label" :tip="PACKAGE_FIELD_HELP[f.field]" />
                                            <n-select :data-testid="f.testid + p.part" v-model:value="(p as any)[f.field]" :options="f.options"
                                                :multiple="f.multiple" :filterable="false" :disabled="p.selection !== 'INCLUDE'" />
                                        </div>
                                    </td>
                                </tr>
                            </tbody>
                        </table>

                        <n-form-item label-for="package-description">
                            <template #label><FieldLabel label="Description" :tip="PACKAGE_FIELD_HELP.description" /></template>
                            <n-input data-testid="package-description" :input-props="{ id: 'package-description' }" v-model:value="form.description"
                                type="textarea" :maxlength="1024" show-count :autosize="{ minRows: 1, maxRows: 4 }" />
                        </n-form-item>
                    </div>
                </template>
            </n-form>

            <div v-if="view && formError" data-testid="package-form-error" class="text-danger">{{ formError }}</div>
            <n-space v-if="view && isWritable">
                <n-button data-testid="package-save" type="primary" :loading="saving" :disabled="Boolean(formError)" @click="save">Save</n-button>
                <n-button v-if="view.stored" data-testid="package-remove" type="error" @click="remove">Remove</n-button>
            </n-space>
        </n-spin>
    </div>
</template>

<script setup lang="ts">
// The regulatory package settings editor (task TEA-30, design 3.4.2), Pro only: one kind at one
// scope, generic over the kinds packageKinds answers -- nothing here names a kind. The parts, their
// requirement, their defaults and whether a generator is registered come from the server's
// descriptor; the SBOM profile is the picker in single mode. Errors show through the SweetAlert2
// object form with the server's text in `text` (design 3.8, D18).
import { computed, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NForm, NFormItem, NInput, NRadio, NRadioGroup, NSelect, NSpace, NSpin, NTag } from 'naive-ui'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import profilesQueries from '@/utils/profilesQueries'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import FieldLabel from './FieldLabel.vue'
import SbomProfilePicker from './SbomProfilePicker.vue'
import {
    Option, PackageScope, PackageSettingsForm, PACKAGE_ARTIFACT_TYPE_OPTIONS, PACKAGE_FIELD_HELP,
    PACKAGE_IN_TRIAGE_FINDINGS_OPTIONS, PACKAGE_MODE_OPTIONS, PACKAGE_SELECTION_OPTIONS,
    PACKAGE_SUPPRESSED_FINDINGS_OPTIONS, PACKAGE_VEX_FORMAT_OPTIONS, packageEffectiveLine, packageFormError,
    packageFormFromView, packageKindLabel, packagePartFields, packagePartLabel, packageRemoveConfirm,
    packageSettingsInput, packageSourceLine,
} from '@/utils/packageSettings'
import { sbomScopeWord } from '@/utils/sbomProfile'

const props = withDefaults(defineProps<{
    orgUuid: string
    scope: PackageScope
    objectUuid?: string | null
    objectName?: string | null
    objectIsProduct?: boolean
    isWritable?: boolean
    perspectiveOptions?: Option[]
    installationType?: string
    names?: Record<string, string>
}>(), {
    objectUuid: null,
    objectName: null,
    objectIsProduct: false,
    isWritable: false,
    perspectiveOptions: () => [],
    installationType: 'OSS',
    names: () => ({}),
})

const emit = defineEmits<{ (e: 'saved', settings: any): void, (e: 'removed'): void }>()

const kinds = ref<any[]>([])
const kind = ref<string | null>(null)
const view = ref<any>(null)
const form = ref<PackageSettingsForm>({ mode: null, followedPerspective: null, sbomProfile: 'default', description: '', parts: [] })
const catalog = ref<any[]>([])
const catalogError = ref<string | null>(null)
const loading = ref(false)
const saving = ref(false)
const loadError = ref<string | null>(null)

const OPTION_FIELDS: Record<string, { label: string, testid: string, options: Option[], multiple: boolean }> = {
    suppressedFindings: { label: 'Suppressed findings', testid: 'package-suppressed-', options: PACKAGE_SUPPRESSED_FINDINGS_OPTIONS, multiple: false },
    inTriageFindings: { label: 'Findings in triage', testid: 'package-in-triage-', options: PACKAGE_IN_TRIAGE_FINDINGS_OPTIONS, multiple: false },
    vexFormat: { label: 'VEX format', testid: 'package-vex-format-', options: PACKAGE_VEX_FORMAT_OPTIONS, multiple: false },
    artifactTypes: { label: 'Artifact types', testid: 'package-artifact-types-', options: PACKAGE_ARTIFACT_TYPE_OPTIONS, multiple: true },
}

function optionFields (part: string) {
    return packagePartFields(part).filter(f => f !== 'selection').map(f => ({ field: f, ...OPTION_FIELDS[f] }))
}

const kindOptions = computed(() => kinds.value.map((k: any) => ({ label: packageKindLabel(k.kind), value: k.kind })))
const allNames = computed((): Record<string, string> => {
    const out: Record<string, string> = { ...props.names }
    for (const o of props.perspectiveOptions) out[o.value] = o.label
    for (const p of view.value?.parent?.conflictingPerspectives ?? []) out[p.uuid] = p.name
    for (const p of view.value?.effective?.conflictingPerspectives ?? []) out[p.uuid] = p.name
    return out
})
const showMode = computed(() => props.scope === 'COMPONENT' && props.installationType !== 'OSS')
const follows = computed(() => props.scope === 'COMPONENT' && form.value.mode === 'FOLLOW_PERSPECTIVE')
const sourceLine = computed(() => packageSourceLine(view.value, allNames.value))
const effectiveLine = computed(() => packageEffectiveLine(view.value?.effective, allNames.value))
const formError = computed(() => view.value ? packageFormError(form.value) : null)
const profileValue = computed(() => form.value.sbomProfile ? [form.value.sbomProfile] : [])
const scopeWord = computed(() => sbomScopeWord(props.scope, props.objectIsProduct))

function setProfile (v: string[]) {
    form.value.sbomProfile = v?.[0] ?? ''
}

/** The kinds once, then the first kind's view. */
async function init () {
    loading.value = true
    loadError.value = null
    try {
        const resp: any = await graphqlClient.query({ query: profilesQueries.PackageKindsGql, fetchPolicy: 'no-cache' })
        kinds.value = resp.data.packageKinds ?? []
    } catch (err: any) {
        console.error(err)
        loadError.value = graphqlRefusalText(err)
        loading.value = false
        return
    }
    loading.value = false
    if (!kind.value || !kinds.value.some((k: any) => k.kind === kind.value)) kind.value = kinds.value[0]?.kind ?? null
    await load()
}

async function selectKind (k: string) {
    kind.value = k
    await load()
}

/** The selected kind's editor view and the SBOM profile catalog at this scope; resets the form. */
async function load () {
    if (!kind.value) {
        view.value = null
        return
    }
    loading.value = true
    loadError.value = null
    try {
        const [resp]: any[] = await Promise.all([
            graphqlClient.query({
                query: profilesQueries.PackageSettingsEditorViewGql,
                variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid, kind: kind.value },
                fetchPolicy: 'no-cache',
            }),
            reloadCatalog(),
        ])
        view.value = resp.data.packageSettingsEditorView
        form.value = packageFormFromView(view.value, props.scope)
        if (props.scope === 'COMPONENT' && props.installationType === 'OSS') form.value.mode = 'OVERRIDE'
    } catch (err: any) {
        console.error(err)
        view.value = null
        loadError.value = graphqlRefusalText(err)
    } finally {
        loading.value = false
    }
}

/** The SBOM profile catalog alone, for the picker; the form is untouched (design 3.4.2, D15). */
async function reloadCatalog () {
    try {
        const resp: any = await graphqlClient.query({
            query: profilesQueries.SbomProfileCatalogGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid },
            fetchPolicy: 'no-cache',
        })
        catalog.value = resp.data.sbomProfileCatalog ?? []
        catalogError.value = null
    } catch (err: any) {
        console.error(err)
        catalog.value = []
        catalogError.value = graphqlRefusalText(err)
    }
}

async function save () {
    if (!kind.value || formError.value) return
    saving.value = true
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: profilesQueries.SavePackageSettingsGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid, kind: kind.value,
                settings: packageSettingsInput(form.value, props.scope) },
        })
        emit('saved', resp.data.savePackageSettings)
        await load()
    } catch (err: any) {
        console.error(err)
        Swal.fire({ title: 'Error!', text: graphqlRefusalText(err), icon: 'error' })
    } finally {
        saving.value = false
    }
}

async function remove () {
    if (!kind.value) return
    const answer: any = await Swal.fire({ ...packageRemoveConfirm(kind.value, scopeWord.value), icon: 'warning',
        showCancelButton: true, cancelButtonText: 'Cancel' })
    if (!answer?.isConfirmed) return
    try {
        await graphqlClient.mutate({
            mutation: profilesQueries.DeletePackageSettingsGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid, kind: kind.value },
        })
        emit('removed')
        await load()
    } catch (err: any) {
        console.error(err)
        Swal.fire({ title: 'Error!', text: graphqlRefusalText(err), icon: 'error' })
    }
}

onMounted(init)
watch(() => [props.orgUuid, props.scope, props.objectUuid], init)

defineExpose({ load, reloadCatalog })
</script>

<style scoped>
.package-alert {
    margin-bottom: 0.75rem;
}
.package-parts {
    width: 100%;
    margin-bottom: 1rem;
}
.package-parts td, .package-parts th {
    padding: 4px 8px;
    vertical-align: top;
}
.package-option {
    margin-bottom: 4px;
}
</style>
