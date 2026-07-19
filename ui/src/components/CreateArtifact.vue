<template>
    <div class="createArtifact">
        <h2>Create Artifact</h2>
        <n-form ref="createArtifactForm" :model="artifact" :rules="rules">
            <n-form-item v-if="!isUpdateExistingBom"
                        path="storedIn"
                        label="Select Storage Type">
                <n-radio-group  v-model:value="artifact.storedIn" >
                    <n-tooltip trigger="hover">
                        <template #trigger>
                            <n-radio-button
                                key='REARM'
                                value='REARM'
                                label='ReARM'
                            />
                        </template>
                        Artifact is to be uploaded and stored on ReARM Internally.
                        
                    </n-tooltip>
                    <n-tooltip trigger="hover">
                        <template #trigger>
                            <n-radio-button
                                key='EXTERNALLY'
                                value='EXTERNALLY'
                                label='External'
                            />
                        </template>
                        Artifact is stored externally and it's Downloadlinks are stored.
                    </n-tooltip>
                </n-radio-group>
            </n-form-item>
            <n-form-item label="Select Artifact: " v-if="artifact.storedIn==='REARM'">
                <n-upload v-model:value="fileList" :max="1" @change="onFileChange">
                    <n-button>
                    Upload File
                    </n-button>
                </n-upload>
            </n-form-item>
            <n-form-item
                        path="displayIdentifier"
                        label="Display Identifier">
                <n-input
                        v-model:value="artifact.displayIdentifier"
                        required
                        placeholder="Enter artifact display identifier, i.e. this can be URI" />
            </n-form-item>
            <n-form-item
                        path="type"
                        label="Artifact Type">
                <n-select
                        v-model:value="artifact.type"
                        filterable
                        :options="artifactTypes" />
            </n-form-item>
            <n-card v-if="artifact.type === 'VEX'"
                title="VEX import settings"
                size="small"
                :bordered="true"
                style="margin: 8px 0 16px 0;">
                <n-text depth="3" style="font-size: 12px; display: block; margin-bottom: 12px;">
                    Defaults work for most uploads — leave them alone unless the heuristics don't match your situation. Hover ⓘ for details.
                </n-text>

                <n-form-item>
                    <template #label>
                        <n-tooltip trigger="hover" placement="top" :style="{ maxWidth: '360px' }">
                            <template #trigger>
                                <span>Scope <n-icon :component="QuestionCircle20Regular" size="14" style="vertical-align: middle;" /></span>
                            </template>
                            How broadly this VEX claim applies. Organization is the safe default — most VEX statements apply across every release containing the affected component. Pick a narrower scope only when the claim is build-specific.
                        </n-tooltip>
                    </template>
                    <n-select v-model:value="artifact.vexScope" :options="vexScopeOptions" />
                </n-form-item>

                <n-form-item>
                    <template #label>
                        <n-tooltip trigger="hover" placement="top" :style="{ maxWidth: '360px' }">
                            <template #trigger>
                                <span>Import mode <n-icon :component="QuestionCircle20Regular" size="14" style="vertical-align: middle;" /></span>
                            </template>
                            What to do with each parsed statement. Hover each option for details.
                        </n-tooltip>
                    </template>
                    <n-radio-group v-model:value="artifact.vexImportMode">
                        <n-radio value="AUTO_ACCEPT">
                            <n-tooltip trigger="hover" placement="bottom" :style="{ maxWidth: '360px' }">
                                <template #trigger><span>Auto-accept</span></template>
                                Each statement is applied as a finding-analysis decision immediately. The trust gate may still stage some statements (e.g., a vendor's "not affected" claim) — those land in the VEX inbox for manual review.
                            </n-tooltip>
                        </n-radio>
                        <n-radio value="STAGE">
                            <n-tooltip trigger="hover" placement="bottom" :style="{ maxWidth: '360px' }">
                                <template #trigger><span>Stage all for review</span></template>
                                Every statement lands in the VEX Proposals inbox as PENDING, regardless of issuer. Use this when reviewing a new vendor for the first time, or when you want full control over which decisions apply.
                            </n-tooltip>
                        </n-radio>
                        <n-radio value="REJECT">
                            <n-tooltip trigger="hover" placement="bottom" :style="{ maxWidth: '360px' }">
                                <template #trigger><span>Reject all</span></template>
                                Records the upload as an audit artifact but discards every parsed statement. Use this for malformed-batch testing or when you want to keep the file on record without applying any of its decisions.
                            </n-tooltip>
                        </n-radio>
                    </n-radio-group>
                </n-form-item>

                <n-form-item>
                    <template #label>
                        <n-tooltip trigger="hover" placement="top" :style="{ maxWidth: '420px' }">
                            <template #trigger>
                                <span>Issuer class <n-icon :component="QuestionCircle20Regular" size="14" style="vertical-align: middle;" /></span>
                            </template>
                            Who is making this VEX claim. <strong>Vendor</strong> is the cautious default (quiet "not affected" claims get staged for review, loud "exploitable" claims auto-accept). Pick <strong>Self</strong> when you wrote this VEX about your own software. Pick <strong>Third party</strong> for fully untrusted sources — everything stages.
                        </n-tooltip>
                    </template>
                    <n-select
                        v-model:value="artifact.userIssuerClassOverride"
                        :options="issuerClassOptions"
                        clearable
                        placeholder="Auto-detect from binding" />
                </n-form-item>
            </n-card>
            <n-form-item
                        path="version"
                        label="Artifact Version">
                <n-input
                        v-model:value="artifact.version"
                        required
                        placeholder="Enter artifact version (Positive integer, optional, auto-generated otherwise)" />
            </n-form-item>
            <n-form-item
                        label="Artifact Coverage Type">
                <n-select
                    v-model:value="selectedCoverageTypes"
                    multiple
                    :options="constants.ArtifactCoverageTypes"
                    placeholder="Select coverage types (optional)" />
            </n-form-item>
            <n-form-item
                        label="Artifact Lifecycle">
                <n-select
                    v-model:value="selectedLifecycleTypes"
                    multiple
                    filterable
                    tag
                    :options="constants.ArtifactLifecycleTypes"
                    placeholder="Select lifecycle phases or type custom (optional)" />
            </n-form-item>
            <n-form-item
                        label="Artifact Tags">
                <n-dynamic-input
                    preset="pair"
                    v-model:value="artifactTags"
                    key-placeholder="Enter tag key, i.e. 'deploymentType'"
                    value-placeholder="Enter tag value, i.e. 'primary'" />
            </n-form-item>
            <n-form-item
                        label="Artifact DownloadLinks" v-if="artifact.storedIn==='EXTERNALLY'">
                <n-dynamic-input
                    v-model:value="downloadLinks"
                    :on-create="onCreateDownloadLinks">
                    <template #default="{ value }">
                        <n-input v-model:value="value.uri" type="text" placeholder='uri'/>
                        <n-select :options="constants.ContentTypes" v-model:value="value.content" placeholder='select content type'/>
                    </template>
                </n-dynamic-input>
            </n-form-item>
            <n-form-item
                        path ="digestRecords"
                        label="Artifact Digests">
                        <n-dynamic-input
                    v-model:value="artifact.digestRecords"
                    :on-create="onCreateDigests"
                 >
                    <template #default="{ value }">
                        <n-select style="width: 200px;" :options="constants.TeaArtifactChecksumTypes" v-model:value="value.algo"  placeholder='Select Algo'/>
                        <n-input v-model:value="value.digest" type="text" placeholder='digest'/>
                    </template>
                    </n-dynamic-input>
            </n-form-item>
            <n-button type="success" @click="onSubmit" :disabled="isUploading" :loading="isUploading">
                {{ isUploading ? 'Uploading...' : 'Add Artifact' }}
            </n-button>
            <n-button type="warning" @click="onReset" :disabled="isUploading">Reset Artifact Input</n-button>
        </n-form>

    </div>
</template>
<script lang="ts">
export default {
    name: 'CreateArtifact'
}
</script>
<script lang="ts" setup>
import graphqlClient from '@/utils/graphql'
import gql from 'graphql-tag'
import { FormInst, NButton, NCard, NDynamicInput, NForm, NFormItem, NIcon, NInput, NInputNumber, NRadio, NRadioButton, NRadioGroup, NSelect, NText, NTooltip, NUpload } from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'
import { computed, ComputedRef, ref, Ref } from 'vue'
import { useStore } from 'vuex'
import { Tag, DownloadLink } from '@/utils/commonTypes'
import Swal from 'sweetalert2'
import commonFunctions from '../utils/commonFunctions'
import constants from '@/utils/constants'

const isUploading = ref(false)


const props = defineProps<{
    inputOrgUuid: string,
    inputRelease: string,
    inputDeliverarble?: string,
    inputSce?: string,
    inputBelongsTo?: string
    isUpdateExistingBom?: boolean
    updateArtifact?: any
}>()

const emit = defineEmits(['addArtifact'])

const store = useStore()

const orguuid = ref(props.inputOrgUuid ? props.inputOrgUuid : '')
const compuuid = ref('')

const createArtifactForm = ref<FormInst | null>(null)

const artifactTags: Ref<Tag[]> = ref([])
const selectedCoverageTypes: Ref<string[]> = ref(
    props.isUpdateExistingBom && props.updateArtifact?.tags
        ? props.updateArtifact.tags.filter((t: Tag) => t.key === 'COVERAGE_TYPE').map((t: Tag) => t.value)
        : []
)
const selectedLifecycleTypes: Ref<string[]> = ref(
    props.isUpdateExistingBom && props.updateArtifact?.tags
        ? props.updateArtifact.tags.filter((t: Tag) => t.key === 'LIFECYCLE').map((t: Tag) => t.value)
        : []
)

type DigestRecord = {
    algo: string,
    digest: string,
    scope: 'ORIGINAL_FILE'
}


interface Artifact {
    displayIdentifier: string,
    tags: Tag[],
    type: string,
    downloadLinks: DownloadLink[],
    inventoryTypes: string[],
    digestRecords: DigestRecord[],
    bomFormat: string,
    storedIn: string,
    status: string,
    version: string,
}
const artifact: Ref<any>= ref({
    displayIdentifier: props.isUpdateExistingBom ? props.updateArtifact.displayIdentifier : '',
    tags: props.isUpdateExistingBom ? props.updateArtifact.tags :[],
    type:  props.isUpdateExistingBom ? props.updateArtifact.type : '',
    downloadLinks: [],
    inventoryTypes: props.isUpdateExistingBom ? props.updateArtifact.inventoryTypes : [],
    digestRecords: [],
    bomFormat: props.isUpdateExistingBom ? props.updateArtifact.bomFormat : null,
    storedIn: props.isUpdateExistingBom ? 'REARM' : '',
    status: 'ANY',
    version: '',
    vexScope: 'COMPONENT',
    vexImportMode: 'AUTO_ACCEPT',
    userIssuerClassOverride: 'VENDOR',
})

const vexScopeOptions = [
    { label: 'Component (default — this release\'s component, all branches)', value: 'COMPONENT' },
    { label: 'Organization (every release in the org)', value: 'ORG' },
    { label: 'Branch (this release\'s branch only)', value: 'BRANCH' },
    { label: 'Release (only this specific release)', value: 'RELEASE' },
]
const issuerClassOptions = [
    { label: 'Vendor — vendor-supplied (default)', value: 'VENDOR' },
    { label: 'Self — my own software', value: 'SELF' },
    { label: 'Third party — external/untrusted', value: 'THIRD_PARTY' },
]
if(props.isUpdateExistingBom && props.updateArtifact){
    artifact.value.storedIn = 'REARM'
}
const downloadLinks: Ref<DownloadLink[]> = ref([])

const rules = {
    storedIn: {
        required: true,
        message: 'Storage Type is required'
    },
    displayIdentifier: {
        required: true,
        message: 'Identifier is required'
    },
    type: {
        required: true,
        message: 'Type is required'
    },
    version: {
        validator: (rule: any, value: string) => {
            if (!value) return true // Allow empty values (not required)
            const num = parseInt(value, 10)
            if (isNaN(num) || num <= 0 || !Number.isInteger(num) || num.toString() !== value.trim()) {
                return new Error('Version must be a positive integer')
            }
            return true
        },
        trigger: ['input', 'blur']
    }
}


const showUploadArtifactModal: Ref<boolean> = ref(false)
// const artifactUploadData = ref({
//     file: null,
//     tag: '',
//     uuid: '',
//     artifactType: ''
// })
const fileList: Ref<any> = ref([])
const artifactType: Ref<any> = ref(null)
const fileTag = ref('')
function onFileChange(newFileList: any) {
    fileList.value = newFileList
}

const onSubmit = async () => {
    try {
        await createArtifactForm.value?.validate()
    } catch (validationErrors) {
        return
    }

    if (!bomRequiredTypes.includes(artifact.value.type)) {
        artifact.value.bomFormat = null
    }

    const manualCoverageValues = artifactTags.value
        .filter((t: Tag) => t.key === 'COVERAGE_TYPE')
        .map((t: Tag) => t.value)
    const allCoverageValues = [...new Set([...selectedCoverageTypes.value, ...manualCoverageValues])]
    const coverageTypeTags: Tag[] = allCoverageValues.map((ct: string) => ({ key: 'COVERAGE_TYPE', value: ct }))
    const manualLifecycleValues = artifactTags.value
        .filter((t: Tag) => t.key === 'LIFECYCLE')
        .map((t: Tag) => t.value)
    const allLifecycleValues = [...new Set([...selectedLifecycleTypes.value, ...manualLifecycleValues])]
    const lifecycleTags: Tag[] = allLifecycleValues.map((lc: string) => ({ key: 'LIFECYCLE', value: lc }))
    const otherTags = artifactTags.value.filter((t: Tag) => t.key !== 'COVERAGE_TYPE' && t.key !== 'LIFECYCLE')
    artifact.value.tags = [...coverageTypeTags, ...lifecycleTags, ...otherTags]
    artifact.value.downloadLinks = downloadLinks.value
    artifact.value.file = fileList.value?.file?.file
    const createArtifactInput: any = {
        release: props.inputRelease,
        artifact: artifact.value,
        belongsTo: props.inputBelongsTo,
    }

    if(props.inputBelongsTo === 'DELIVERABLE'){
        createArtifactInput.deliverable = props.inputDeliverarble
    }

    if(props.inputBelongsTo === 'SCE'){
        createArtifactInput.sce = props.inputSce
    }

    // Capture the display id we submitted so we can locate the resulting VEX
    // artifact (and its import summary) in the mutation response below.
    const submittedDisplayId = artifact.value.displayIdentifier
    const isVex = artifact.value.type === 'VEX'
    // For a VEX upload, ask the mutation for the resulting artifacts + their
    // import summary so we can report matched / unmatched counts.
    const releaseBody = isVex
        ? `uuid artifactDetails { uuid displayIdentifier type vexImportSummary { statementsTotal proposalsCreated proposalsAutoAccepted proposalsAutoRejected statementsUnmatched statementsErrored errorMessages } }`
        : `uuid`

    isUploading.value = true
    try{
        let mutationResult: any
        if(props.isUpdateExistingBom){
            mutationResult = await graphqlClient.mutate({
                mutation: gql`
                    mutation addArtifactManual($artifactInput: CreateArtifactInput, $artifactUuid: ID!) {
                        addArtifactManual(artifactInput: $artifactInput, artifactUuid: $artifactUuid) {
                            ${releaseBody}
                        }
                    }`,
                variables: {
                    'artifactInput': createArtifactInput,
                    'artifactUuid': props.updateArtifact.uuid
                },
                fetchPolicy: 'no-cache'
            })
            emit('addArtifact')
        }else {
            mutationResult = await graphqlClient.mutate({
                mutation: gql`
                    mutation addArtifactManual($artifactInput: CreateArtifactInput) {
                        addArtifactManual(artifactInput: $artifactInput) {
                            ${releaseBody}
                        }
                    }`,
                variables: {
                    'artifactInput': createArtifactInput
                },
                fetchPolicy: 'no-cache'
            })
            emit('addArtifact')
        }

        if (isVex) {
            showVexOutcome(mutationResult?.data?.addArtifactManual, submittedDisplayId)
        }

    }   catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    } finally {
        isUploading.value = false
    }
};

// Report the VEX import outcome. VEX import is synchronous, so the counts are
// final by the time the mutation returns -- if nothing matched the release's
// SBOM inventory, say so instead of pointing the user at an empty VEX tab.
function showVexOutcome(release: any, displayId: string) {
    const artifacts = release?.artifactDetails ?? []
    const vexArtifact = artifacts
        .filter((a: any) => a?.type === 'VEX' && a?.displayIdentifier === displayId)
        .find((a: any) => a?.vexImportSummary)
    const s = vexArtifact?.vexImportSummary

    if (!s) {
        // No summary available (older backend, or nothing to summarize): keep it
        // truthful -- processing is already done, direct the user to the tab.
        Swal.fire({
            icon: 'success', title: 'VEX uploaded',
            text: 'Open the VEX tab on this release (or the org-wide VEX Proposals inbox) to review proposals.',
            timer: 6000, showConfirmButton: true,
        })
        return
    }

    const total = s.statementsTotal ?? 0
    const staged = s.proposalsCreated ?? 0
    const accepted = s.proposalsAutoAccepted ?? 0
    const rejected = s.proposalsAutoRejected ?? 0
    const unmatched = s.statementsUnmatched ?? 0
    const errored = s.statementsErrored ?? 0
    const matched = staged + accepted + rejected
    // First few backend messages (e.g. "N entries skipped: no analysis block",
    // doc parse errors) -- the concrete "why", not just counts.
    const details = (s.errorMessages ?? []).slice(0, 3).join(' ')

    if (total === 0) {
        // Nothing to import at all: doc-level parse failure, or the document
        // contains no VEX statements ReARM can read.
        const text = details
            ? `No VEX statements could be imported from this document. ${details}`
            : 'No VEX statements found in this document. For CycloneDX VEX, ReARM imports vulnerabilities[] entries that carry an analysis block (state / justification); entries without analysis are plain vulnerability reports, not VEX statements.'
        Swal.fire({ icon: 'warning', title: 'No VEX statements imported', text, showConfirmButton: true })
        return
    }

    if (matched === 0) {
        const parts = [`${total} VEX statement${total === 1 ? '' : 's'} uploaded, but none produced a proposal, so nothing was added to the VEX tab.`]
        if (unmatched > 0) parts.push(`${unmatched} unmatched: the VEX product PURLs / versions do not correspond to any component in this release's SBOM -- check that the VEX targets the same PURLs (including version) that appear in the SBOM.`)
        if (errored > 0) parts.push(`${errored} could not be processed.`)
        if (details) parts.push(details)
        Swal.fire({ icon: 'warning', title: 'No VEX statements matched', text: parts.join(' '), showConfirmButton: true })
        return
    }

    const parts = []
    if (staged > 0) parts.push(`${staged} staged for review`)
    if (accepted > 0) parts.push(`${accepted} auto-accepted`)
    if (rejected > 0) parts.push(`${rejected} auto-rejected`)
    if (unmatched > 0) parts.push(`${unmatched} unmatched`)
    if (errored > 0) parts.push(`${errored} could not be processed`)
    const breakdown = parts.length ? `: ${parts.join(', ')}` : ' processed'
    const suffix = details && errored > 0 ? ` ${details}` : ''
    Swal.fire({
        icon: 'success', title: 'VEX processed',
        text: `${total} statement${total === 1 ? '' : 's'}${breakdown}. Open the VEX tab to review.${suffix}`,
        timer: 7000, showConfirmButton: true,
    })
}

const onCreateDownloadLinks = () => {
    return {
        uri: '',
        content: ''
    }
}

const onCreateDigests = () => {
    return {
        algo: '',
        digest: '',
        scope: 'ORIGINAL_FILE'
    } as DigestRecord
}

// Array of artifact types that require BOM format selection
const bomRequiredTypes = ['BOM', 'VEX', 'VDR', 'BOV']
const branches: ComputedRef<any> = computed((): any => {
    let branches = []
    if (compuuid.value) {
        const storeBranches = store.getters.branchesOfComponent(compuuid.value)
        branches = storeBranches.sort((a: any, b: any) => {
            if (a.name === "master" || a.name === "main") {
                return -1
            } else if (b.name === "master" || b.name === "main") {
                return 1
            } else if (a.name < b.name) {
                return -1
            } else if (a.name > b.name) {
                return 1
            } else {
                return 0
            }
        }).map((br: any) => {
            let brObj = {
                label: br.name,
                value: br.uuid
            }
            return brObj
        })
    }
    return branches
})

const orgs: ComputedRef<any> = computed((): any => {
    const storeOrgs = store.getters.allOrganizations
    return storeOrgs.map((so: any) => {
        const orgObj = {
            label: so.name,
            value: so.uuid
        }
        return orgObj
    })
})


const components: ComputedRef<any> = computed((): any => {
    let projs = []
    if (orguuid.value) {
        const storeComponents = store.getters.componentsOfOrg(orguuid.value)
        projs = storeComponents.map((proj: any) => {
            const projObj = {
                label: proj.name,
                value: proj.uuid
            }
            return projObj
        })
    }
    return projs
})

const onOrgChange = function (orgId: string) {
    store.dispatch('fetchComponents', orgId)
}

const onReset = function () {
    artifact.value = {
        displayIdentifier: '',
        tags: [],
        downloadLinks: [],
        inventoryTypes: [],
        bomFormat: null,
        storedIn: null,
        status: null,
        version: null,
        type:null,
    }
    selectedCoverageTypes.value = []
    selectedLifecycleTypes.value = []
}


await store.dispatch('fetchMyOrganizations')
if (props.inputOrgUuid) {
    onOrgChange(props.inputOrgUuid)
}
const artTypesResp = await graphqlClient.query({
    query: gql`
        query artifactTypes {
            artifactTypes
        }`
})

const artifactTypes = artTypesResp.data.artifactTypes.filter((t: string) => t !== 'SARIF').map((t: string) => {
    return {
        label: t,
        value: t
    }
}).sort((a: {label: string}, b: {label: string}) => a.label.localeCompare(b.label))





</script>

<style scoped lang="scss">
.createArtifact {
    width: 95%;
    margin-left: 20px;
}
Input.digestInput {
    display: inline;
    width: 90%;
}
.digestEntry {
    display:inline;

}
.removeDigest {
    display: inline;
    margin-left:2px;
    cursor: pointer;
}
</style>