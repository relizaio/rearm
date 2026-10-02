<template>
    <div class="tea-profile-editor">
        <n-alert v-if="banner" data-testid="tea-public-banner" type="error" :closable="false" class="tea-alert">
            {{ banner }}
        </n-alert>
        <n-spin :show="loading">
            <div v-if="loadError" data-testid="tea-load-error" class="text-danger">{{ loadError }}</div>
            <template v-else-if="view">
                <p data-testid="tea-source" class="text-muted">{{ sourceLine }}</p>
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
                            <n-radio v-for="o in TEA_MODE_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
                        </n-radio-group>
                    </n-form-item>
                    <n-form-item v-if="follows" label="Followed perspective">
                        <n-select data-testid="tea-follow-perspective" v-model:value="form.followedPerspective"
                            :options="perspectiveOptions" placeholder="Choose one of this component's perspectives" />
                    </n-form-item>

                    <div v-if="!follows" data-testid="tea-fields">
                        <n-form-item label="Publishing">
                            <n-select data-testid="tea-publishing" v-model:value="form.publishing" :options="TEA_PUBLISHING_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.publishing }}</template>
                        </n-form-item>
                        <n-form-item label="Visibility">
                            <n-select data-testid="tea-visibility" v-model:value="form.visibility"
                                :options="TEA_VISIBILITY_OPTIONS" :disabled="!isOrgAdmin" />
                            <template #feedback>
                                <span v-if="!isOrgAdmin" data-testid="tea-visibility-reason">Only an organization admin can make a profile public.</span>
                                <span v-else>{{ TEA_FIELD_HELP.visibility }}</span>
                            </template>
                        </n-form-item>
                        <n-form-item label="Dependency depth">
                            <n-select data-testid="tea-dependency-depth" v-model:value="form.dependencyDepth" :options="TEA_DEPENDENCY_DEPTH_OPTIONS" />
                        </n-form-item>
                        <n-form-item label="Optional dependencies">
                            <n-select data-testid="tea-optional-dependencies" v-model:value="form.optionalDependencies" :options="TEA_OPTIONAL_DEPENDENCIES_OPTIONS" />
                        </n-form-item>
                        <n-form-item label="SBOM structure">
                            <n-select data-testid="tea-structure" v-model:value="form.structure" :options="TEA_STRUCTURE_OPTIONS" />
                        </n-form-item>
                        <n-form-item label="SBOM sources">
                            <n-select data-testid="tea-sources" v-model:value="form.sources" multiple :options="TEA_SOURCES_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.sources }}</template>
                        </n-form-item>
                        <n-form-item label="Excluded coverage">
                            <n-select data-testid="tea-excluded-coverage" v-model:value="form.excludedCoverage" multiple :options="TEA_EXCLUDED_COVERAGE_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.excludedCoverage }}</template>
                        </n-form-item>
                        <n-form-item label="Support metadata">
                            <n-select data-testid="tea-support-metadata" v-model:value="form.supportMetadata"
                                :options="TEA_SUPPORT_METADATA_OPTIONS" :disabled="injectionOff" />
                            <template #feedback>
                                <span v-if="injectionOff" data-testid="tea-support-metadata-reason">
                                    The organization's support injection is off, so support metadata stays excluded.
                                </span>
                                <span v-else>{{ TEA_FIELD_HELP.supportMetadata }}</span>
                            </template>
                        </n-form-item>
                        <n-form-item label="Internal metadata">
                            <n-select data-testid="tea-internal-metadata" v-model:value="form.internalMetadata" :options="TEA_INTERNAL_METADATA_OPTIONS" />
                        </n-form-item>
                        <n-form-item label="Raw artifacts">
                            <n-select data-testid="tea-raw-artifacts" v-model:value="form.rawArtifacts" :options="TEA_RAW_ARTIFACTS_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.rawArtifacts }}</template>
                        </n-form-item>
                        <n-form-item v-if="showProductComponents" label="Product components">
                            <n-select data-testid="tea-product-components" v-model:value="form.productComponents" :options="TEA_PRODUCT_COMPONENTS_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.productComponents }}</template>
                        </n-form-item>
                        <n-form-item label="Minimum lifecycle">
                            <n-select data-testid="tea-minimum-lifecycle" v-model:value="form.minimumLifecycle" :options="TEA_MINIMUM_LIFECYCLE_OPTIONS" />
                            <template #feedback>{{ TEA_FIELD_HELP.minimumLifecycle }}</template>
                        </n-form-item>
                        <n-form-item label="TEI">
                            <n-select data-testid="tea-tei" v-model:value="form.tei" :options="TEA_TEI_OPTIONS" />
                        </n-form-item>
                        <n-form-item v-if="form.tei === 'UUID'" label="TEI domain">
                            <n-input data-testid="tea-tei-domain" v-model:value="form.teiDomain" placeholder="products.example.com" />
                        </n-form-item>
                        <n-form-item label="Vulnerability documents">
                            <span data-testid="tea-vulnerability-documents">NONE (reserved)</span>
                        </n-form-item>
                    </div>
                </n-form>

                <n-space v-if="isWritable">
                    <n-button data-testid="tea-save" type="primary" :loading="saving" @click="save">Save</n-button>
                    <n-button v-if="scope !== 'ORGANIZATION' && view.stored" data-testid="tea-remove" type="error" @click="remove">
                        Remove override
                    </n-button>
                    <n-button v-if="scope === 'ORGANIZATION'" data-testid="tea-reset" @click="resetToDefaults">Reset to defaults</n-button>
                </n-space>
            </template>
        </n-spin>
    </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NForm, NFormItem, NInput, NRadio, NRadioGroup, NSelect, NSpace, NSpin } from 'naive-ui'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import graphqlQueries from '@/utils/graphqlQueries'
import commonFunctions from '@/utils/commonFunctions'
import {
    Option, TeaProfileForm, TeaScope, TEA_DEPENDENCY_DEPTH_OPTIONS, TEA_EXCLUDED_COVERAGE_OPTIONS, TEA_FIELD_HELP,
    TEA_INTERNAL_METADATA_OPTIONS, TEA_MINIMUM_LIFECYCLE_OPTIONS, TEA_MODE_OPTIONS, TEA_OPTIONAL_DEPENDENCIES_OPTIONS,
    TEA_PRODUCT_COMPONENTS_OPTIONS, TEA_PUBLISHING_OPTIONS, TEA_RAW_ARTIFACTS_OPTIONS, TEA_SOURCES_OPTIONS,
    TEA_STRUCTURE_OPTIONS, TEA_SUPPORT_METADATA_OPTIONS, TEA_TEI_OPTIONS, TEA_VISIBILITY_OPTIONS, teaBuiltInDefaults,
    teaFormFromView, teaNeedsPublicConfirm, teaProfileInput, teaPublicBanner, teaPublicConfirmText, teaSourceLine,
} from '@/utils/teaProfile'

const props = withDefaults(defineProps<{
    orgUuid: string
    scope: TeaScope
    objectUuid?: string | null
    objectName?: string | null
    objectIsProduct?: boolean
    isWritable?: boolean
    isOrgAdmin?: boolean
    perspectiveOptions?: Option[]
    installationType?: string
}>(), {
    objectUuid: null,
    objectName: null,
    objectIsProduct: false,
    isWritable: false,
    isOrgAdmin: false,
    perspectiveOptions: () => [],
    installationType: 'OSS',
})

const emit = defineEmits<{ (e: 'saved', profile: any): void, (e: 'removed'): void }>()

const view = ref<any>(null)
const form = ref<TeaProfileForm>(teaBuiltInDefaults())
const loading = ref(false)
const saving = ref(false)
const loadError = ref<string | null>(null)

const perspectiveNames = computed((): Record<string, string> => {
    const names: Record<string, string> = {}
    for (const o of props.perspectiveOptions) names[o.value] = o.label
    for (const p of view.value?.parent?.conflictingPerspectives ?? []) names[p.uuid] = p.name
    for (const p of view.value?.effective?.conflictingPerspectives ?? []) names[p.uuid] = p.name
    return names
})
const showMode = computed(() => props.scope === 'COMPONENT' && props.installationType !== 'OSS')
const follows = computed(() => props.scope === 'COMPONENT' && form.value.mode === 'FOLLOW_PERSPECTIVE')
const showProductComponents = computed(() => props.scope !== 'COMPONENT' || props.objectIsProduct)
const injectionOff = computed(() => view.value?.supportInjection === 'DISABLED')
const sourceLine = computed(() => teaSourceLine(view.value, perspectiveNames.value))
const banner = computed(() => teaPublicBanner(view.value, perspectiveNames.value))
const conflictNames = computed(() => (view.value?.parent?.conflictingPerspectives ?? []).map((p: any) => p.name).join(', '))
const scopeWord = computed(() => props.objectIsProduct ? 'product' : props.scope.toLowerCase())

watch(injectionOff, (off) => { if (off) form.value.supportMetadata = 'EXCLUDE' })

async function load () {
    loading.value = true
    loadError.value = null
    try {
        const resp: any = await graphqlClient.query({
            query: graphqlQueries.TeaProfileEditorViewGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid },
            fetchPolicy: 'no-cache',
        })
        view.value = resp.data.teaProfileEditorView
        form.value = teaFormFromView(view.value, props.scope)
        if (props.scope === 'COMPONENT' && props.installationType === 'OSS') form.value.mode = 'OVERRIDE'
        if (injectionOff.value) form.value.supportMetadata = 'EXCLUDE'
    } catch (err: any) {
        console.error(err)
        loadError.value = commonFunctions.parseGraphQLError(err.message)
    } finally {
        loading.value = false
    }
}

async function save () {
    if (teaNeedsPublicConfirm(form.value, props.scope, view.value)) {
        const answer: any = await Swal.fire({
            title: 'Make this TEA profile public?',
            text: teaPublicConfirmText(view.value?.publishedReleases ?? 0),
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: 'Make it public',
            cancelButtonText: 'Cancel',
        })
        if (!answer?.isConfirmed) return
    }
    saving.value = true
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: graphqlQueries.SaveTeaProfileGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid,
                profile: teaProfileInput(form.value, props.scope) },
        })
        emit('saved', resp.data.saveTeaProfile)
        await load()
    } catch (err: any) {
        Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
    } finally {
        saving.value = false
    }
}

async function remove () {
    const answer: any = await Swal.fire({
        title: 'Remove this TEA profile override?',
        text: 'The ' + scopeWord.value + ' then resolves its TEA profile from its parent again.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Remove',
        cancelButtonText: 'Cancel',
    })
    if (!answer?.isConfirmed) return
    try {
        await graphqlClient.mutate({
            mutation: graphqlQueries.DeleteTeaProfileGql,
            variables: { org: props.orgUuid, scope: props.scope, object: props.objectUuid },
        })
        emit('removed')
        await load()
    } catch (err: any) {
        Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
    }
}

function resetToDefaults () {
    form.value = teaBuiltInDefaults()
}

onMounted(load)
watch(() => [props.orgUuid, props.scope, props.objectUuid], load)

defineExpose({ load })
</script>

<style scoped>
.tea-alert {
    margin-bottom: 0.75rem;
}
</style>
