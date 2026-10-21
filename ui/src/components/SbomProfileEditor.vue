<template>
    <div class="sbom-profile-editor">
        <n-spin :show="loading">
            <div v-if="loadError" data-testid="sbom-load-error" class="text-danger">{{ loadError }}</div>
            <n-alert v-if="!isWritable" type="default" class="sbom-alert">
                Read-only: changing SBOM profiles needs admin rights here.
            </n-alert>
            <n-form label-placement="left" label-width="210" :disabled="!isWritable" size="small">
                <n-form-item v-if="createMode" label-for="sbom-name">
                    <template #label><FieldLabel label="Name" :tip="SBOM_FIELD_HELP.name" /></template>
                    <div class="sbom-name-block">
                        <n-input data-testid="sbom-name" :input-props="{ id: 'sbom-name' }" v-model:value="typedName"
                            placeholder="e.g. top-level" @blur="loadTyped" @update:value="scheduleTypedLoad" />
                        <div v-if="nameError && typedName" data-testid="sbom-name-error" class="text-danger">{{ nameError }}</div>
                    </div>
                </n-form-item>

                <template v-if="view">
                    <p data-testid="sbom-source" class="text-muted">{{ sourceLine }}</p>
                    <p data-testid="sbom-effective" class="text-muted">{{ effectiveLine }}</p>
                    <n-alert v-if="view.parent?.status === 'CONFLICT'" data-testid="sbom-conflict" type="warning" class="sbom-alert">
                        {{ sourceLine }}
                    </n-alert>
                </template>

                <n-form-item v-if="showMode" label-for="sbom-mode">
                    <template #label><FieldLabel label="Mode" :tip="SBOM_FIELD_HELP.mode" /></template>
                    <n-radio-group data-testid="sbom-mode" v-model:value="form.mode">
                        <n-radio v-for="o in SBOM_MODE_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
                    </n-radio-group>
                </n-form-item>
                <n-form-item v-if="follows" label="Followed perspective" label-for="sbom-follow-perspective">
                    <n-select data-testid="sbom-follow-perspective" v-model:value="form.followedPerspective"
                        :options="perspectiveOptions" :filterable="false" placeholder="Choose one of this component's perspectives" />
                </n-form-item>

                <div v-if="!follows" data-testid="sbom-fields">
                    <n-form-item v-for="f in selectFields" :key="f.field" :label-for="f.testid">
                        <template #label><FieldLabel :label="f.label" :tip="SBOM_FIELD_HELP[f.field]" /></template>
                        <div class="sbom-field" :title="SBOM_FIELD_HELP[f.field]">
                            <n-select :data-testid="f.testid" v-model:value="(form as any)[f.field]" :options="f.options"
                                :multiple="f.multiple" :filterable="false" :disabled="f.disabled" />
                            <div v-if="f.field === 'supportMetadata' && injectionOff" data-testid="sbom-support-metadata-reason" class="text-muted">
                                The organization's support injection is off, so support metadata stays excluded.
                            </div>
                        </div>
                    </n-form-item>
                    <n-form-item label-for="sbom-description">
                        <template #label><FieldLabel label="Description" :tip="SBOM_FIELD_HELP.description" /></template>
                        <n-input data-testid="sbom-description" :input-props="{ id: 'sbom-description' }" v-model:value="form.description"
                            type="textarea" :maxlength="SBOM_DESCRIPTION_MAX" show-count :autosize="{ minRows: 1, maxRows: 4 }" />
                    </n-form-item>
                </div>
            </n-form>

            <n-space v-if="isWritable">
                <n-button data-testid="sbom-save" type="primary" :loading="saving" :disabled="saveDisabled" @click="save">Save</n-button>
                <n-button v-if="!createMode && view?.stored" data-testid="sbom-delete" type="error" @click="remove">Delete</n-button>
                <n-button v-if="scope === 'ORGANIZATION' && effectiveName === SBOM_DEFAULT_NAME" data-testid="sbom-reset" @click="resetToDefaults">
                    Reset to built-in defaults
                </n-button>
            </n-space>
        </n-spin>
    </div>
</template>

<script setup lang="ts">
// The SBOM profile editor (task TEA-30, design 3.3.2): one name at one scope, in the shape of
// TeaProfileEditor -- the stored row, the effective resolution and the parent it pre-fills from in
// one read. Every field is an enum select with its tooltip. Errors show through the SweetAlert2
// object form with the server's text in `text` (design 3.8, D18).
import { computed, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NForm, NFormItem, NInput, NRadio, NRadioGroup, NSelect, NSpace, NSpin } from 'naive-ui'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import profilesQueries from '@/utils/profilesQueries'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import FieldLabel from './FieldLabel.vue'
import {
    Option, SbomProfileForm, SbomScope, SBOM_DEFAULT_NAME, SBOM_DEPENDENCY_DEPTH_OPTIONS, SBOM_DESCRIPTION_MAX,
    SBOM_EXCLUDED_COVERAGE_OPTIONS, SBOM_FIELD_HELP, SBOM_INTERNAL_METADATA_OPTIONS, SBOM_MODE_OPTIONS,
    SBOM_OPTIONAL_DEPENDENCIES_OPTIONS, SBOM_SOURCES_OPTIONS, SBOM_STRUCTURE_OPTIONS, SBOM_SUPPORT_METADATA_OPTIONS,
    SBOM_VULNERABILITY_DOCUMENTS_OPTIONS, sbomBuiltInDefaults, sbomEffectiveLine, sbomFormFromView, sbomNameError,
    sbomProfileInput, sbomScopeWord, sbomSourceLine,
} from '@/utils/sbomProfile'
import { deleteSbomProfileConfirmed } from '@/utils/sbomProfileDelete'

const props = withDefaults(defineProps<{
    orgUuid: string
    scope: SbomScope
    objectUuid?: string | null
    objectName?: string | null
    objectIsProduct?: boolean
    name?: string | null
    isWritable?: boolean
    perspectiveOptions?: Option[]
    installationType?: string
    names?: Record<string, string>
}>(), {
    objectUuid: null,
    objectName: null,
    objectIsProduct: false,
    name: null,
    isWritable: false,
    perspectiveOptions: () => [],
    installationType: 'OSS',
    names: () => ({}),
})

const emit = defineEmits<{ (e: 'saved', profile: any): void, (e: 'removed', name: string): void, (e: 'cancel'): void }>()

const view = ref<any>(null)
const form = ref<SbomProfileForm>(sbomBuiltInDefaults())
const typedName = ref('')
const loading = ref(false)
const saving = ref(false)
const loadError = ref<string | null>(null)

const createMode = computed(() => !props.name)
const effectiveName = computed(() => props.name || typedName.value.trim())
const nameError = computed(() => createMode.value ? sbomNameError(typedName.value) : null)
const allNames = computed((): Record<string, string> => {
    const names: Record<string, string> = { ...props.names }
    for (const o of props.perspectiveOptions) names[o.value] = o.label
    for (const p of view.value?.parent?.conflictingPerspectives ?? []) names[p.uuid] = p.name
    for (const p of view.value?.effective?.conflictingPerspectives ?? []) names[p.uuid] = p.name
    return names
})
const showMode = computed(() => props.scope === 'COMPONENT' && props.installationType !== 'OSS')
const follows = computed(() => props.scope === 'COMPONENT' && form.value.mode === 'FOLLOW_PERSPECTIVE')
const injectionOff = computed(() => view.value?.supportInjection === 'DISABLED')
const sourceLine = computed(() => sbomSourceLine(view.value, allNames.value, effectiveName.value || SBOM_DEFAULT_NAME))
const effectiveLine = computed(() => sbomEffectiveLine(view.value?.effective, allNames.value))
const scopeWord = computed(() => sbomScopeWord(props.scope, props.objectIsProduct))
const saveDisabled = computed(() => Boolean(nameError.value) || !effectiveName.value)

const selectFields = computed(() => [
    { field: 'dependencyDepth', label: 'Dependency depth', testid: 'sbom-dependency-depth', options: SBOM_DEPENDENCY_DEPTH_OPTIONS, multiple: false, disabled: false },
    { field: 'optionalDependencies', label: 'Optional dependencies', testid: 'sbom-optional-dependencies', options: SBOM_OPTIONAL_DEPENDENCIES_OPTIONS, multiple: false, disabled: false },
    { field: 'structure', label: 'SBOM structure', testid: 'sbom-structure', options: SBOM_STRUCTURE_OPTIONS, multiple: false, disabled: false },
    { field: 'sources', label: 'SBOM sources', testid: 'sbom-sources', options: SBOM_SOURCES_OPTIONS, multiple: true, disabled: false },
    { field: 'excludedCoverage', label: 'Excluded coverage', testid: 'sbom-excluded-coverage', options: SBOM_EXCLUDED_COVERAGE_OPTIONS, multiple: true, disabled: false },
    { field: 'supportMetadata', label: 'Support metadata', testid: 'sbom-support-metadata', options: SBOM_SUPPORT_METADATA_OPTIONS, multiple: false, disabled: injectionOff.value },
    { field: 'internalMetadata', label: 'Internal metadata', testid: 'sbom-internal-metadata', options: SBOM_INTERNAL_METADATA_OPTIONS, multiple: false, disabled: false },
    { field: 'vulnerabilityDocuments', label: 'Vulnerability documents', testid: 'sbom-vulnerability-documents', options: SBOM_VULNERABILITY_DOCUMENTS_OPTIONS, multiple: false, disabled: true },
])

watch(injectionOff, (off) => { if (off) form.value.supportMetadata = 'EXCLUDE' })

async function load () {
    const name = effectiveName.value
    if (!name || (createMode.value && nameError.value)) {
        view.value = null
        return
    }
    loading.value = true
    loadError.value = null
    try {
        const resp: any = await graphqlClient.query({
            query: profilesQueries.SbomProfileEditorViewGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid, name },
            fetchPolicy: 'no-cache',
        })
        view.value = resp.data.sbomProfileEditorView
        form.value = sbomFormFromView(view.value, props.scope)
        if (props.scope === 'COMPONENT' && props.installationType === 'OSS') form.value.mode = 'OVERRIDE'
        if (injectionOff.value) form.value.supportMetadata = 'EXCLUDE'
    } catch (err: any) {
        console.error(err)
        loadError.value = graphqlRefusalText(err)
    } finally {
        loading.value = false
    }
}

// Create mode: the view follows the typed name, so the pre-fill and the source line do.
let typedTimer: ReturnType<typeof setTimeout> | null = null
function scheduleTypedLoad () {
    if (typedTimer) clearTimeout(typedTimer)
    typedTimer = setTimeout(loadTyped, 400)
}
function loadTyped () {
    if (typedTimer) clearTimeout(typedTimer)
    typedTimer = null
    if (createMode.value && !nameError.value) load()
}

async function save () {
    const name = effectiveName.value
    if (!name || nameError.value) return
    saving.value = true
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: profilesQueries.SaveSbomProfileGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid, name,
                profile: sbomProfileInput(form.value, props.scope) },
        })
        emit('saved', resp.data.saveSbomProfile)
        await load()
    } catch (err: any) {
        console.error(err)
        Swal.fire({ title: 'Error!', text: graphqlRefusalText(err), icon: 'error' })
    } finally {
        saving.value = false
    }
}

async function remove () {
    const name = effectiveName.value
    const outcome = await deleteSbomProfileConfirmed(graphqlClient, { orgUuid: props.orgUuid, scope: props.scope,
        objectUuid: props.objectUuid, name, scopeWord: scopeWord.value, installationType: props.installationType })
    if (outcome !== 'deleted') return
    emit('removed', name)
    await load()
}

function resetToDefaults () {
    form.value = { ...sbomBuiltInDefaults(), description: form.value.description }
    if (injectionOff.value) form.value.supportMetadata = 'EXCLUDE'
}

onMounted(load)
watch(() => [props.orgUuid, props.scope, props.objectUuid, props.name], load)

defineExpose({ load })
</script>

<style scoped>
.sbom-alert {
    margin-bottom: 0.75rem;
}
.sbom-field, .sbom-name-block {
    width: 100%;
}
</style>
