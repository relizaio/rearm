<template>
    <div class="tea-profile-editor">
        <n-alert v-if="banner" data-testid="tea-public-banner" type="error" :closable="false" class="tea-alert">
            {{ banner }}
        </n-alert>
        <n-spin :show="loading">
            <div v-if="loadError" data-testid="tea-load-error" class="text-danger">{{ loadError }}</div>
            <template v-else-if="view">
                <p data-testid="tea-source" class="text-muted">{{ sourceLine }}</p>
                <n-alert v-if="objectArchived" data-testid="tea-archived" type="warning" :closable="false" class="tea-alert">
                    This {{ scopeWord }} is archived: its TEA profile can only hide what it published (publishing
                    DISABLED, visibility PRIVATE); any other save is refused.
                </n-alert>
                <n-alert v-if="view.parent?.status === 'CONFLICT'" data-testid="tea-conflict" type="warning" class="tea-alert">
                    The perspectives {{ conflictNames }} each carry a TEA profile, so this {{ scopeWord }} cannot
                    resolve one: a publish is refused until you choose OVERRIDE or FOLLOW one of them here.
                </n-alert>
                <n-alert v-if="!isWritable" type="default" class="tea-alert">
                    Read-only: changing this TEA profile needs admin rights here.
                </n-alert>

                <n-form label-placement="left" label-width="210" :disabled="!isWritable" size="small">
                    <n-form-item v-if="showMode" label="Mode">
                        <n-radio-group data-testid="tea-mode" v-model:value="form.mode">
                            <n-radio v-for="o in modeOptions" :key="o.value" :value="o.value" :label="o.label" />
                        </n-radio-group>
                    </n-form-item>
                    <n-form-item v-if="follows" label="Followed perspective">
                        <n-select data-testid="tea-follow-perspective" v-model:value="form.followedPerspective"
                            :options="perspectiveOptions" placeholder="Choose one of this component's perspectives" />
                    </n-form-item>
                </n-form>

                <!-- INHERIT shows the parent's values read-only: its own form, so the mode radio stays enabled. -->
                <n-form v-if="!follows" label-placement="left" label-width="210" :disabled="readOnly" size="small">
                    <div data-testid="tea-fields" :data-inherited="inherits ? 'true' : undefined">
                        <n-form-item label="Publishing">
                            <n-select data-testid="tea-publishing" v-model:value="shown.publishing" :options="TEA_PUBLISHING_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.publishing }}</template>
                        </n-form-item>
                        <n-form-item label="Visibility">
                            <n-select data-testid="tea-visibility" v-model:value="shown.visibility"
                                :options="TEA_VISIBILITY_OPTIONS" :disabled="!isOrgAdmin || readOnly" />
                            <template #feedback>
                                <span v-if="!isOrgAdmin" data-testid="tea-visibility-reason">Only an organization admin can make a profile public.</span>
                                <span v-else>{{ TEA_FIELD_HELP.visibility }}</span>
                            </template>
                        </n-form-item>
                        <!-- CE serves the one `default` SBOM profile: a fixed line, and teaProfileInput sends it. -->
                        <n-form-item v-if="!sbomProfileNamesAvailable(installationType)" label="SBOM profile">
                            <span data-testid="tea-profiles-ce">SBOM profile: {{ CE_SBOM_PROFILE }}</span>
                        </n-form-item>
                        <n-form-item v-else label="SBOM profiles">
                            <SbomProfilePicker data-testid="tea-profiles" v-model="shown.profiles" :entries="catalog"
                                :installation-type="installationType" :names="perspectiveNames" :disabled="readOnly" />
                            <template #feedback>
                                <span v-if="catalogError" data-testid="tea-profiles-error" class="text-danger">{{ catalogError }}</span>
                                <span v-else>{{ TEA_FIELD_HELP.profiles }}</span>
                            </template>
                        </n-form-item>
                        <n-form-item label="Raw artifacts">
                            <n-select data-testid="tea-raw-artifacts" v-model:value="shown.rawArtifacts" :options="TEA_RAW_ARTIFACTS_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.rawArtifacts }}</template>
                        </n-form-item>
                        <n-form-item v-if="showProductComponents" label="Product components">
                            <n-select data-testid="tea-product-components" v-model:value="shown.productComponents" :options="TEA_PRODUCT_COMPONENTS_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.productComponents }}</template>
                        </n-form-item>
                        <n-form-item label="Minimum lifecycle">
                            <n-select data-testid="tea-minimum-lifecycle" v-model:value="shown.minimumLifecycle" :options="TEA_MINIMUM_LIFECYCLE_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.minimumLifecycle }}</template>
                        </n-form-item>
                        <n-form-item label="TEI">
                            <n-select data-testid="tea-tei" v-model:value="shown.tei" :options="TEA_TEI_OPTIONS" />
                        </n-form-item>
                        <n-form-item v-if="shown.tei === 'UUID'" label="TEI domain">
                            <n-input data-testid="tea-tei-domain" v-model:value="shown.teiDomain" placeholder="products.example.com" />
                        </n-form-item>
                    </div>
                </n-form>

                <!-- INHERIT with no row stores nothing: no Save. With a row, Save asks and removes it. -->
                <n-space v-if="isWritable && !(inherits && !view.stored)">
                    <n-button data-testid="tea-save" type="primary" :loading="saving" @click="inherits ? inheritAgain() : save()">Save</n-button>
                    <n-button v-if="scope === 'ORGANIZATION'" data-testid="tea-reset" @click="resetToDefaults">Reset to defaults</n-button>
                </n-space>
            </template>
        </n-spin>
    </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NForm, NFormItem, NInput, NRadio, NRadioGroup, NSelect, NSpace, NSpin, useDialog } from 'naive-ui'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import profilesQueries from '@/utils/profilesQueries'
import {
    CE_SBOM_PROFILE, Option, TeaProfileForm, TeaScope, TEA_FIELD_HELP, TEA_INHERIT, TEA_MINIMUM_LIFECYCLE_OPTIONS,
    TEA_PRODUCT_COMPONENTS_OPTIONS, TEA_PUBLISHING_OPTIONS, TEA_RAW_ARTIFACTS_OPTIONS, TEA_TEI_OPTIONS,
    TEA_VISIBILITY_OPTIONS, teaBuiltInDefaults, teaConfirmBeforeRemoval, teaConfirmBeforeSave, teaFormFromView,
    teaInheritConfirm, teaInheritedForm, teaModeOptions, teaNeedsPublicConfirm, teaProfileInput, teaPublicBanner,
    teaSourceLine, TeaConfirm, TeaExposureChange,
} from '@/utils/teaProfile'
import { sbomProfileNamesAvailable } from '@/utils/sbomProfile'
import SbomProfilePicker from './SbomProfilePicker.vue'

const props = withDefaults(defineProps<{
    orgUuid: string
    scope: TeaScope
    objectUuid?: string | null
    objectName?: string | null
    objectIsProduct?: boolean
    objectArchived?: boolean
    isWritable?: boolean
    isOrgAdmin?: boolean
    perspectiveOptions?: Option[]
    installationType?: string
}>(), {
    objectUuid: null,
    objectName: null,
    objectIsProduct: false,
    objectArchived: false,
    isWritable: false,
    isOrgAdmin: false,
    perspectiveOptions: () => [],
    installationType: 'OSS',
})

const emit = defineEmits<{ (e: 'saved', profile: any): void, (e: 'removed'): void }>()

const dialog = useDialog()

const view = ref<any>(null)
const form = ref<TeaProfileForm>(teaBuiltInDefaults())
const loading = ref(false)
const saving = ref(false)
const loadError = ref<string | null>(null)
// The SBOM profile catalog at this scope, for the picker (TEA-30); its own error line under the picker.
const catalog = ref<any[]>([])
const catalogError = ref<string | null>(null)

const perspectiveNames = computed((): Record<string, string> => {
    const names: Record<string, string> = {}
    for (const o of props.perspectiveOptions) names[o.value] = o.label
    for (const p of view.value?.parent?.conflictingPerspectives ?? []) names[p.uuid] = p.name
    for (const p of view.value?.effective?.conflictingPerspectives ?? []) names[p.uuid] = p.name
    return names
})
const modeOptions = computed(() => teaModeOptions(props.scope, props.installationType))
const showMode = computed(() => modeOptions.value.length > 0)
const follows = computed(() => props.scope === 'COMPONENT' && form.value.mode === 'FOLLOW_PERSPECTIVE')
// INHERIT (UI only, never sent): the fields show the parent's values read-only; the form keeps the
// edit, so switching back to Override shows it again.
const inherits = computed(() => form.value.mode === TEA_INHERIT)
const readOnly = computed(() => !props.isWritable || inherits.value)
const shown = computed((): TeaProfileForm => inherits.value ? teaInheritedForm(view.value) : form.value)
const showProductComponents = computed(() => props.scope !== 'COMPONENT' || props.objectIsProduct)
const sourceLine = computed(() => teaSourceLine(view.value, perspectiveNames.value, form.value.mode))
const banner = computed(() => teaPublicBanner(view.value, perspectiveNames.value))
const conflictNames = computed(() => (view.value?.parent?.conflictingPerspectives ?? []).map((p: any) => p.name).join(', '))
const scopeWord = computed(() => props.objectIsProduct ? 'product' : props.scope.toLowerCase())

async function load () {
    loading.value = true
    loadError.value = null
    try {
        const [resp]: any[] = await Promise.all([
            graphqlClient.query({
                query: graphqlQueries.TeaProfileEditorViewGql,
                variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid },
                fetchPolicy: 'no-cache',
            }),
            reloadCatalog(),
        ])
        view.value = resp.data.teaProfileEditorView
        form.value = teaFormFromView(view.value, props.scope)
        // CE follows no perspective: a FOLLOW row (none is stored there) edits as an override.
        if (props.installationType === 'OSS' && form.value.mode === 'FOLLOW_PERSPECTIVE') form.value.mode = 'OVERRIDE'
    } catch (err: any) {
        console.error(err)
        loadError.value = graphqlRefusalText(err)
    } finally {
        loading.value = false
    }
}

/**
 * The SBOM profile catalog at this scope alone (TEA-30, design 3.3.6): the picker's entries. It
 * touches neither the view nor the form, so an unsaved edit survives; the hosts call it when the
 * overrides beside the editor change. A failure leaves the picker with the stored names only.
 */
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

/**
 * The server's dry-run of a save (profile given) or a removal (profile null): what it does to the
 * effective visibility here. An error (a validation message, an archived component's refusal) shows
 * at once and answers null, so nothing is confirmed and nothing is sent.
 */
async function exposureOf (profile: Record<string, any> | null): Promise<TeaExposureChange | null> {
    try {
        const resp: any = await graphqlClient.query({
            query: graphqlQueries.TeaProfileExposureChangeGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid, profile },
            fetchPolicy: 'no-cache',
        })
        return resp.data.teaProfileExposureChange
    } catch (err: any) {
        console.error(err)
        Swal.fire({ title: 'Error!', text: graphqlRefusalText(err), icon: 'error' })
        return null
    }
}

async function confirmed (c: TeaConfirm | null): Promise<boolean> {
    if (!c) return true
    const answer: any = await Swal.fire({ ...c, icon: 'warning', showCancelButton: true, cancelButtonText: 'Cancel' })
    return Boolean(answer?.isConfirmed)
}

async function save () {
    if (inherits.value) return
    const profile = teaProfileInput(form.value, props.scope, props.installationType)
    const exposure = await exposureOf(profile)
    if (!exposure) return
    if (!await confirmed(teaConfirmBeforeSave(exposure, scopeWord.value, perspectiveNames.value,
        follows.value ? form.value.followedPerspective : null))) return
    saving.value = true
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: graphqlQueries.SaveTeaProfileGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid, profile },
        })
        emit('saved', resp.data.saveTeaProfile)
        await load()
    } catch (err: any) {
        console.error(err)
        Swal.fire({ title: 'Error!', text: graphqlRefusalText(err), icon: 'error' })
    } finally {
        saving.value = false
    }
}

/**
 * Removes the stored row after the server's dry-run. `askedToInherit`: the user already confirmed
 * going back to INHERIT, so only the PUBLIC confirmation is still asked.
 */
async function remove (askedToInherit = false) {
    const exposure = await exposureOf(null)
    if (!exposure) return
    if (!(askedToInherit && !teaNeedsPublicConfirm(exposure))
        && !await confirmed(teaConfirmBeforeRemoval(exposure, scopeWord.value, perspectiveNames.value))) return
    try {
        await graphqlClient.mutate({
            mutation: graphqlQueries.DeleteTeaProfileGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid },
        })
        emit('removed')
        await load()
    } catch (err: any) {
        console.error(err)
        Swal.fire({ title: 'Error!', text: graphqlRefusalText(err), icon: 'error' })
    }
}

/** Save in INHERIT over a stored row: confirm, then remove the row; the reload shows INHERIT with no row. */
function inheritAgain () {
    dialog.warning({
        ...teaInheritConfirm(props.scope, scopeWord.value),
        positiveText: 'Inherit',
        negativeText: 'Cancel',
        onPositiveClick: () => { remove(true) },
    })
}

function resetToDefaults () {
    form.value = teaBuiltInDefaults()
}

onMounted(load)
watch(() => [props.orgUuid, props.scope, props.objectUuid], load)

defineExpose({ load, reloadCatalog })
</script>

<style scoped>
.tea-alert {
    margin-bottom: 0.75rem;
}
</style>
