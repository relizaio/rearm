<template>
    <div class="branchView" v-if="modifiedBranch && branchData && modifiedBranch.name && branchData.name && branchData.componentDetails">
        <h5>{{ words.branchFirstUpper }}: {{ branchData.name }}</h5>
        <div class="branchControls">
            <div class="mainControls">
                <vue-feather v-if="isWritable" @click="showCreateReleaseModal = true" class="clickable" type="plus-circle" title="Add Release" />
                <vue-feather type="tool" @click="openBranchSettings" class="icons clickable" :title="words.branchFirstUpper + ' Settings'" />
                <vue-feather type="arrow-right-circle" @click="openNextVersionModal" class="icons clickable" title='Set Next Version' />
            </div>
            <div class="dangerControls">
                <vue-feather v-if="isWritable && branchData.type !== 'BASE'" @click="archiveBranch" class="clickable" type="trash-2" :title="'Archive ' + words.branchFirstUpper" />
            </div>
        </div>
        <n-modal
            v-model:show="showCreateReleaseModal"
            title="Add New Release"
            preset="dialog"
            :show-icon="false"
            style="width: 90%"
        >
            <create-release
                            class="addRelease"
                            :orgProp="branchData.org"
                            :inputBranch="branchData.uuid"
                            :inputType="branchData.componentDetails.type"
                            :disallowPlaceholder="true"
                            @createdRelease="showCreateReleaseModal = false" />
        </n-modal>
        <n-modal
            v-model:show="showBranchSettingsModal"
            preset="dialog"
            :show-icon="false"
            style="width: 90%"
            :on-after-leave="closeBranchSettings"
        >
            <h3>{{ words.branchFirstUpper }} Settings for {{ branchData.name }} of {{ branchData.componentDetails.name }}</h3>
            <div class="branchNameBlock">
                <label id="branchNameLabel" for="branchName">{{ words.branchFirstUpper }} Name</label>
                <n-input  v-if="isWritable" v-model:value="modifiedBranch.name" />
                <n-input v-if="!isWritable" type="text" :value="branchData.name" readonly/>
            </div>
            <div class="versionSchemaBlock">
                <label id="branchVersionSchemaLabel" for="branchVersionSchema">{{ words.branchFirstUpper }} Version Schema</label>
                <n-select
                    v-if="isWritable"
                    v-model:value="modifiedBranch.versionSchema"
                    tag
                    filterable
                    :placeholder="'Select version schema for ' + words.branchFirstUpper"
                    :options="branchData.type === 'BASE' ? constants.VersionTypes : constants.BranchVersionTypes" />
                <n-input
                    v-if="isWritable && modifiedBranch.versionSchema === 'custom_version'"
                    v-model:value="customBranchVersionSchema"
                    placeholder="Custom Version Schema" />
                <n-input v-if="!isWritable" type="text" name="versionSchema" :value="modifiedBranch.versionSchema" readonly/>
            </div>
            <div class="versionSchemaBlock" v-if="marketingVersionEnabled">
                <label id="branchVersionSchemaLabel" for="branchVersionSchema">{{ words.branchFirstUpper }} Marketing Version Schema</label>
                <n-input v-if="isWritable" v-model:value="modifiedBranch.marketingVersionSchema" />
                <n-input v-else type="text" name="marketingVersionSchema" :value="modifiedBranch.marketingVersionSchema" readonly/>
            </div>
            <div class="versionMetadataBlock" v-if="false && branchData.componentDetails.type === 'PRODUCT'">
                <label id="branchVersionMetadataLabel" for="branchVersionMetadata">Generated Version Metadata</label>
                <n-input v-if="isWritable" id="branchVersionMetadata" v-model:value="modifiedBranch.metadata" />
                <n-input v-else type="text" id="versionMetadata" name="versionMetadata" :value="modifiedBranch.metadata" readonly/>
            </div>
            <div class="branchTypeBlock" v-if="branchData.componentDetails.type === 'COMPONENT'">
                <label id="branchTypeMetadataLabel">Branch Type</label>
                <n-select
                    v-if="isWritable && branchData.type !== 'BASE'"
                    id="branchType"
                    v-model:value="modifiedBranch.type"
                    :options="branchSelectOptions" 
                />
                <n-input v-else class="w-25" type="text" :value="branchData.type" readonly/>
            </div>
            <div class="linkedVcsRepoBlock" v-if="branchData.componentDetails.type === 'COMPONENT'">
                <label>Linked VCS Repository</label>
                <n-select v-if="isWritable" :options="vcsRepos" v-model:value="modifiedBranch.vcs" />
                <span v-if="!isWritable && modifiedBranch.vcsRepositoryDetails && modifiedBranch.vcsRepositoryDetails.uuid">{{ (modifiedBranch.vcsRepositoryDetails.name === modifiedBranch.vcsRepositoryDetails.uri) ? modifiedBranch.vcsRepositoryDetails.name : modifiedBranch.vcsRepositoryDetails.name + ' - ' + modifiedBranch.vcsRepositoryDetails.uri }}</span>
                <span v-if="!isWritable && !modifiedBranch.vcsRepositoryDetails">Not Set</span>
            </div>
            <div class="vcsBranchBlock" v-if="branchData.componentDetails.type === 'COMPONENT' && modifiedBranch.vcsRepositoryDetails && modifiedBranch.vcsRepositoryDetails.uuid">
                <label for="vcsBranch">VCS Branch</label>
                <n-input v-if="isWritable" id="vcsBranch" v-model:value="modifiedBranch.vcsBranch" />
                <n-input v-else type="text" id="vcsBranch" name="vcsBranch" :value="modifiedBranch.vcsBranch" readonly/>
            </div>
            <n-button
                v-if="isWritable && modifiedBranch && !modifiedBranch.vcs && !isLinkVcsRepo && branchData.componentDetails === 'COMPONENT'"
                @click="isLinkVcsRepo = true">
                Link VCS Repository
            </n-button>
                <link-vcs
                                v-if="isLinkVcsRepo"
                                id="branch_link_vcs_to_branch"
                                :branchUuid="branchData.uuid"
                                :orgprop="modifiedBranch.org"
                                @linkVcsRepo="linkVcsRepo"

                />
            <div v-if="branchData.componentDetails.type === 'PRODUCT'" class="autoIntegrateBlock">
                <label id="autoIntegrateLabel">Auto Integrate </label>
                <n-select v-if="isWritable" :options="[{label: 'ENABLED', value: 'ENABLED'}, {label: 'DISABLED', value: 'DISABLED'}]" v-model:value="modifiedBranch.autoIntegrate" />
                <n-input v-else type="text" :value="modifiedBranch.autoIntegrate" readonly/>
            </div>
            <div class="dependencyPatternsBlock mt-3" v-if="branchData.componentDetails.type === 'PRODUCT'">
                <p>
                    <strong>Dependency Patterns </strong>
                    <n-tooltip trigger="hover">
                        <template #trigger>
                            <n-icon size="16" style="cursor: help;">
                                <QuestionMark />
                            </n-icon>
                        </template>
                        Java regex patterns to automatically match components as dependencies. E.g., ^myapp-.* matches all components starting with "myapp-"
                    </n-tooltip>
                    <vue-feather v-if="isWritable" class="clickable" type="plus-circle"
                        @click="addDependencyPattern" title="Add Dependency Pattern" />
                </p>

                <div v-if="modifiedBranch.dependencyPatterns && modifiedBranch.dependencyPatterns.length">
                    <n-data-table :data="modifiedBranch.dependencyPatterns" :columns="patternTableFields" :row-key="(row: any) => row.uuid" />
                </div>
                <div v-else class="text-muted" style="font-size: 0.9em; color: #666;">
                    No dependency patterns configured. Add patterns to automatically include matching components.
                </div>
            </div>
            <div class="effectiveDependenciesBlock mt-3" v-if="branchData.componentDetails.type === 'PRODUCT'">
                <p>
                    <strong>Dependencies </strong>
                    <n-tooltip trigger="hover">
                        <template #trigger>
                            <n-icon size="16" style="cursor: help;">
                                <QuestionMark />
                            </n-icon>
                        </template>
                        Shows all dependencies including pattern-matched and manual ones. Use the buttons below to add dependencies or configure patterns above.
                    </n-tooltip>
                    <vue-feather v-if="isWritable" class="clickable" type="plus-circle"
                        @click="showAddComponentModal = true" title="Add Component Dependency" />
                    <vue-feather v-if="isWritable" class="clickable" type="folder-plus"
                        @click="showAddComponentProductModal = true" title="Add Product Dependency" />
                    <vue-feather v-if="isWritable && modifiedBranch.autoIntegrate === 'ENABLED'" class="clickable" type="trending-up"
                        @click="triggerAutoIntegrate" title="Trigger Auto Integrate" />
                </p>
                <div v-if="modifiedBranch.effectiveDependencies && modifiedBranch.effectiveDependencies.length">
                    <n-data-table :data="modifiedBranch.effectiveDependencies" :columns="effectiveDepTableFields" :row-key="(row: any) => row.component?.uuid" :row-class-name="getRowClassName" />
                </div>
                <div v-else class="empty-state">
                    <p style="text-align: center; padding: 40px; color: #999;">
                        <vue-feather type="package" size="48" style="opacity: 0.3; margin-bottom: 10px;" />
                        <br />
                        <strong>No dependencies configured</strong>
                        <br />
                        <span style="font-size: 0.9em;">
                            Add dependencies using the buttons above, or configure dependency patterns to automatically match components.
                        </span>
                    </p>
                </div>
            </div>
            <n-modal
                v-model:show="showAddComponentModal"
                preset="dialog"
                :show-icon="false"
                style="width: 70%"
            >
                <add-component
                                :orgProp="branchData.org"
                                :addExtOrg=true
                                :requireBranch=true
                                @addedComponent="addedComponent" />
            </n-modal>
            <n-modal
                v-model:show="showAddComponentProductModal"
                preset="dialog"
                :show-icon="false"
                style="width: 70%"
            >
                <add-component
                                :orgProp="branchData.org"
                                :addExtOrg=true
                                :requireBranch=true
                                inputType='PRODUCT'
                                @addedComponent="addedComponent" />
            </n-modal>
            <n-modal
                v-model:show="showEditComponentModal"
                preset="dialog"
                :show-icon="false"
                style="width: 90%"
            >
                <add-component
                                :orgProp="branchData.org"
                                :addExtOrg=true
                                :inputBranch=editableDependency.editCompBranch
                                :inputProj=editableDependency.editCompProj
                                :inputStatus=editableDependency.editCompStatus
                                :inputRelease=editableDependency.editCompRelease
                                @addedComponent="editedComponent" />
            </n-modal>
            <n-modal
                v-model:show="showAddOssArtifactModal"
                preset="dialog"
                :show-icon="false"
                style="width: 90%"
            >
                <n-form>
                    <n-form-item
                                label="Artifact">
                        <n-input v-model:value="ossArtifact"/>
                    </n-form-item>
                    <n-button @click="registerOssArtifact" type="success" variant="primary">Submit</n-button>
                    <n-button @click="ossArtifact = ''" type="warning" variant="danger">Reset</n-button>
                </n-form>
            </n-modal>
            <div class="branchSettingsActions" v-if="hasBranchSettingsChanges && isWritable" style="margin-top: 20px;">
                <n-space>
                    <n-button type="success" @click="saveModifiedBranch">
                        <template #icon>
                            <vue-feather type="check" />
                        </template>
                        Save Changes
                    </n-button>
                    <n-button type="warning" @click="resetBranchSettings">
                        <template #icon>
                            <vue-feather type="x" />
                        </template>
                        Reset Changes
                    </n-button>
                </n-space>
            </div>
        </n-modal>
        
        <n-data-table :data="itemsForList" :columns="releaseFields" :row-key="releaseRowkey"  @update:checked-row-keys="handleComparison" v-model:checked-row-keys="releasesToCompare"/>
        <div class="branchReleaseBlock">
            <n-pagination
                v-if="rows > perPage"
                v-model:page="currentPage"
                v-model:page-size="perPage"
                show-size-picker
                :page-sizes="[25, 50, 100]"
                :page-count="Math.ceil(rows / perPage)"
            ></n-pagination>
            <n-modal 
                style="min-height: 95vh; background-color: white;" 
                v-model:show="showReleaseModal"
                preset="dialog"
                :show-icon="false"
                :on-after-leave="closeReleaseModal">
                <release-view :uuidprop="showReleaseUuid" @closeRelease="closeReleaseModal" />
            </n-modal>
        </div>
        <n-modal
            v-model:show="showReleaseComparisonModal" 
            :on-after-leave="resetComparison"
            title="Side-By-Side Release Comparison"
            preset="dialog"
            :show-icon="false" >
            <side-by-side
                comparisonTypeLeftIn="release"
                :instanceLeft="releasesToCompare[0]"
                comparisonTypeRightIn="release"
                :instanceRight="releasesToCompare[1]"/>
        </n-modal>
        <n-modal
            v-model:show="cloneReleaseToFsObj.showModal"
            :title="'Create ' + words.branchFirstUpper + ' From Release - ' + cloneReleaseToFsObj.version"
            preset="dialog"
            :show-icon="false" >
            <n-form>
                <n-input
                    v-model:value="cloneReleaseToFsObj.fsName"
                    required
                    :placeholder="'Enter New ' + words.branchFirstUpper + ' Name'" 
                />
                <n-button type="success" @click="createFsFromRelease">Create</n-button>
            </n-form>
        </n-modal>
        <n-modal
            v-model:show="showSetNextVersionModal"
            :title="'Set Next Version For ' + words.branchFirstUpper + ': ' + branchData.name"
            preset="dialog"
            :show-icon="false" >
            <n-form>
                <n-form-item :label=" currentNextVersion == null ? 'Set Next Version' : 'Current Next Version is: ' + currentNextVersion">
                    <n-input
                        v-model:value="nextVersion"
                        required
                        placeholder="Enter Next Version" 
                    />
                </n-form-item>
                <n-button type="success" @click="setNextVersion">Create</n-button>
            </n-form>
        </n-modal>
        
        <!-- Pattern Preview Modal -->
        <n-modal
            v-model:show="showPatternPreviewModal"
            title="Pattern Preview"
            preset="dialog"
            style="width: 70%"
        >
            <div class="pattern-preview">
                <p>Components matching the pattern:</p>
                <n-data-table 
                    :data="patternPreviewData" 
                    :columns="previewTableFields" 
                    :row-key="(row: any) => row.component?.uuid" 
                />
            </div>
        </n-modal>
        
        <vulnerability-modal
            v-model:show="showDetailedVulnerabilitiesModal"
            :component-name="selectedReleaseForModal?.componentDetails?.name || branchData.componentDetails?.name || ''"
            :version="selectedReleaseForModal?.version || ''"
            :data="detailedVulnerabilitiesData"
            :loading="loadingVulnerabilities"
            :artifacts="currentReleaseArtifacts"
            :org-uuid="currentReleaseOrgUuid"
            :dtrack-project-uuids="currentDtrackProjectUuids"
            :release-uuid="selectedReleaseForModal?.uuid || ''"
            :branch-uuid="branchData.uuid || ''"
            :component-uuid="branchData.componentDetails?.uuid || ''"
            :component-type="branchData.componentDetails?.type || ''"
            :artifact-view-only="false"
            :initial-severity-filter="currentSeverityFilter"
            :initial-type-filter="currentTypeFilter"
            @refresh-data="handleRefreshVulnerabilityData"
        />
    </div>
</template>

<script lang="ts">
export default {
    name: 'BranchView'
}
</script>
<script lang="ts" setup>
import { ComputedRef, computed, Ref, ref, h, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { NButton, NCheckbox, NForm, NFormItem, NInput, NModal, NPagination, NPopover, NSelect, NotificationType, useNotification, SelectOption, NDataTable, NIcon, NSpace, NSpin, NTag, NTooltip, DataTableColumns, NSelect as NSelectComponent, NDropdown} from 'naive-ui'
import AddComponent from './AddComponent.vue'
import CreateRelease from './CreateRelease.vue'
import ReleaseView from './ReleaseView.vue'
import SideBySide from './SideBySide.vue'
import LinkVcs from './LinkVcs.vue'
import commonFunctions from '../utils/commonFunctions'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import GqlQueries from '../utils/graphqlQueries'
import { Edit24Regular } from '@vicons/fluent'
import { Edit, Eye, X, QuestionMark } from '@vicons/tabler'
import constants from '@/utils/constants'
import { ReleaseVulnerabilityService } from '@/utils/releaseVulnerabilityService'
import VulnerabilityModal from '@/components/VulnerabilityModal.vue'
import Swal from 'sweetalert2'
import { SwalData } from '@/utils/commonFunctions'
import { LayoutColumns, Filter, Copy, Trash, Check } from '@vicons/tabler'

async function loadApprovalMatrix (org: string, resourceGroup: string) {
    let amxResponse = await graphqlClient.query({
        query: gql`
            query getApprovalMatrix($orgUuid: ID!, $appUuid: ID) {
                getApprovalMatrix(orgUuid: $orgUuid, appUuid: $appUuid) {
                    matrix {
                        matrix
                    }
                }
            }
            `,
        variables: {
            orgUuid: org,
            appUuid: resourceGroup
        }
    })
    return amxResponse.data.getApprovalMatrix.matrix.matrix
}

async function loadApprovalTypes (org: string, resourceGroup: string) {
    const atypesResponse = await graphqlClient.query({
        query: gql`
            query activeApprovalTypes($orgUuid: ID!, $appUuid: ID) {
                activeApprovalTypes(orgUuid: $orgUuid, appUuid: $appUuid) {
                    approvalTypes
                }
            }
            `,
        variables: {
            orgUuid: org,
            appUuid: resourceGroup
        }
    })
    return [''].concat(Object.keys(atypesResponse.data.activeApprovalTypes.approvalTypes))
        .map((x: string) => {return {label: x, value: x}})
}

async function loadEnvironmentTypes (org: string) {
    let etypesResponse = await graphqlClient.query({
        query: gql`
            query environmentTypes($orgUuid: ID!) {
                environmentTypes(orgUuid: $orgUuid, includeBuiltIn: false)
            }
            `,
        variables: { orgUuid: org }
    })
    return [''].concat(etypesResponse.data.environmentTypes)
        .map((x: string) => {return {label: x, value: x}})
}
const props = defineProps<{
    branchUuidProp: String,
    prnumberprop: String
}>()
const emit = defineEmits(['addedComponentBranch'])


const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

const notify = async function (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

const orguuid = route.params.orguuid.toString()
const myUser = store.getters.myuser
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)

const branchUuid: Ref<string> = ref(props.branchUuidProp ? props.branchUuidProp.toString() : route.params.branchuuid ? route.params.branchuuid.toString() : '')
const prNumber: Ref<string> = ref(props.prnumberprop ? props.prnumberprop.toString() : route.params.prnumber ? route.params.prnumber.toString() : '')

const isLinkVcsRepo = ref(false)

const showBranchSettingsModal: Ref<boolean> = ref(false)

// Initialize branch settings modal from URL query parameter
if (route.query.branchSettingsView === 'true') {
    showBranchSettingsModal.value = true
}
const showSetNextVersionModal: Ref<boolean> = ref(false)
const showAddComponentModal: Ref<boolean> = ref(false)
const showAddComponentProductModal: Ref<boolean> = ref(false)
const showEditComponentModal: Ref<boolean> = ref(false)
const showAddOssArtifactModal: Ref<boolean> = ref(false)
const showCreateReleaseModal: Ref<boolean> = ref(false)
const showPatternPreviewModal: Ref<boolean> = ref(false)
const patternPreviewData: Ref<any[]> = ref([])
const selectNewVcsRepo = ref(false)


const compareMode = ref(false)
const comparisonCheckboxes: Ref<any> = ref({})
const showReleaseComparisonModal = ref(false)

const releasesToCompare: Ref<string[]> = ref([])

const branchData: ComputedRef<any> = computed((): any => {
    return store.getters.branchById(branchUuid.value)
})
const marketingVersionEnabled: ComputedRef<boolean> = computed((): any => {
    return branchData.value.componentDetails.versionType === 'MARKETING'
})
const modifiedBranch: Ref<any> = ref({})
const customBranchVersionSchema = ref('')

const isWritable : boolean = commonFunctions.isWritable(orguuid, myUser, 'BRANCH')

const archiveBranch = async function() {
    const componentUuid = modifiedBranch.value.component
    const isProduct = branchData.value.componentDetails?.type === 'PRODUCT'
    const onSwalConfirm = async function () {
        const archiveBranchParams = {
            branchUuid: branchData.value.uuid,
            componentUuid: componentUuid
        }
        try {
            await store.dispatch('archiveBranch', archiveBranchParams)
            // Navigate to the component/product page after successful archive
            router.push({
                name: isProduct ? 'ProductsOfOrg' : 'ComponentsOfOrg',
                params: {
                    orguuid: orguuid,
                    compuuid: componentUuid
                }
            })
        } catch (err: any) {
            Swal.fire(
                'Error!',
                commonFunctions.parseGraphQLError(err.message),
                'error'
            )
        }
    }
    const swalData: SwalData = {
        questionText: `Are you sure you want to archive the ${modifiedBranch.value.name} ${words.value.branch}?`,
        successTitle: 'Archived!',
        successText: `The ${words.value.branch} ${modifiedBranch.value.name} has been archived.`,
        dismissText: 'Archiving has been cancelled.'
    }
    await commonFunctions.swalWrapper(onSwalConfirm, swalData, notify)
}

const showReleaseModal: Ref<boolean> = ref(false)
const showReleaseUuid: Ref<string> = ref('')

// Initialize release modal from URL query parameter
if (route.query.release) {
    showReleaseUuid.value = route.query.release as string
    showReleaseModal.value = true
}
const showRelease = async function(uuid: string) {
    showReleaseUuid.value = uuid
    showReleaseModal.value = true
    
    // Update router query parameter
    await router.push({
        query: { ...route.query, release: uuid }
    })
}

const closeReleaseModal = async function() {
    showReleaseModal.value = false
    showReleaseUuid.value = ''
    
    // Remove release query parameter from URL
    const { release, ...queryWithoutRelease } = route.query
    await router.push({
        query: queryWithoutRelease
    })
}

const cloneReleaseToFsObj = ref({
    showModal: false,
    releaseUuid: '',
    version: '',
    fsName: ''
})

const cloneReleaseToFs = async function(uuid: string, version: string) {
    cloneReleaseToFsObj.value.releaseUuid = uuid
    cloneReleaseToFsObj.value.version = version
    cloneReleaseToFsObj.value.showModal = true
}
const createFsFromRelease = async function(){
    const gqlResp: any = await graphqlClient.mutate({
        mutation: gql`
            mutation createFeatureSetFromRelease($featureSetName: String!, $releaseUuid: ID!) {
                createFeatureSetFromRelease(featureSetName: $featureSetName, releaseUuid: $releaseUuid)
                {
                    ${graphqlQueries.BranchGql}
                }
            }
        `,
        variables: {
            featureSetName: cloneReleaseToFsObj.value.fsName,
            releaseUuid: cloneReleaseToFsObj.value.releaseUuid
        },
        fetchPolicy: 'no-cache'
    })
    cloneReleaseToFsObj.value.showModal = false
    cloneReleaseToFsObj.value.releaseUuid = ''
    cloneReleaseToFsObj.value.fsName = ''
    cloneReleaseToFsObj.value.version = ''
    notify('success', 'Success', 'Redirecting to new ' + words.value.branchFirstUpper)
    router.push({
        name: 'ProductsOfOrg',
        params: {
            orguuid: gqlResp.data.createFeatureSetFromRelease.org,
            compuuid: gqlResp.data.createFeatureSetFromRelease.component,
            branchuuid: gqlResp.data.createFeatureSetFromRelease.uuid
        }
    })
    
    //redirect to created fs
}

async function triggerAutoIntegrate () {
    await graphqlClient.mutate({
        mutation: gql`
            mutation autoIntegrateFeatureSet($branchUuid: ID!) {
                autoIntegrateFeatureSet(branchUuid: $branchUuid)
            }`,
        variables: { branchUuid: branchUuid.value },
        fetchPolicy: 'no-cache'
    })
    notify('success', 'Success', 'Auto Integrate Triggered')
    await onCreated()
    showBranchSettingsModal.value = false
}

const openBranchSettings = async function() {
    showBranchSettingsModal.value = true
    
    // Update router query parameter
    await router.push({
        query: { ...route.query, branchSettingsView: 'true' }
    })
}

const closeBranchSettings = async function() {
    showBranchSettingsModal.value = false
    
    // Remove branchSettingsView query parameter from URL
    const { branchSettingsView, ...queryWithoutSettings } = route.query
    await router.push({
        query: queryWithoutSettings
    })
}

const approvalMatrix = ref({})
if (false && myUser.installationType !== 'OSS') approvalMatrix.value = await loadApprovalMatrix(orguuid, modifiedBranch.value.resourceGroup)

const releaseFilter = ref({
    lifecycle: '',
    tagKey: '',
})

const releases: ComputedRef<any> = computed((): any => {
    let releases = null
    if (prNumber.value) {
        const foundPr = branchData.value.pullRequests.find((pr: any) => {
            return pr.number == prNumber.value
        })
        const sces = foundPr && foundPr.commits ? foundPr.commits : null
        releases = store.getters.releasesOfBranchPr(branchUuid.value, sces)
    } 
    if(!releases){
        releases = store.getters.releasesOfBranch(branchUuid.value)
    }
    return releases
})

// Initialize pagination from route query parameters
const currentPage: Ref<number> = ref(parseInt(route.query.branchReleasePage as string) || 1)
const perPage: Ref<number> = ref(parseInt(route.query.branchReleasePerPage as string) || 25)

const filteredReleases: ComputedRef<any> = computed((): any => {
    let filteredReleases = releases.value && releases.value.length ? releases.value.slice(0) : []
    if (releaseFilter.value.lifecycle) {
        filteredReleases = filteredReleases.filter((a: any) => a.lifecycle === releaseFilter.value.lifecycle)
    }
    if (releaseFilter.value.tagKey) {
        filteredReleases = filteredReleases.filter((a: any) => a.tags.find((t: any) => t.key === releaseFilter.value.tagKey))
    }
    return filteredReleases
})

const rows: ComputedRef<any> = computed((): any => {
    return filteredReleases.value.length
})

const itemsForList: ComputedRef<any> = computed((): any => {
    return filteredReleases.value.slice((currentPage.value - 1) * perPage.value, currentPage.value * perPage.value)
})

const isReleaseFilterActivated: ComputedRef<boolean>  = computed((): boolean => {
    let isActivated: boolean = false
    if (releaseFilter.value.lifecycle || releaseFilter.value.tagKey) {
        isActivated = true
    }
    return isActivated
})

const words: Ref<any> = ref({})

const branchSelectOptions = [
    {label: 'REGULAR', value: 'REGULAR'}, 
    {label: 'BASE', value: 'BASE'}, 
    {label: 'FEATURE', value: 'FEATURE'},
    {label: 'RELEASE', value: 'RELEASE'}
]
async function updateBranchDependecyStatus (uuid: string, status: string) {
    const objIndex = modifiedBranch.value.dependencies.findIndex((d: any) => d.uuid === uuid)
    modifiedBranch.value.dependencies[objIndex].status = status
    saveModifiedBranch()
}

async function setDependencyAsFollowVersion (uuid: string) {
    const objIndex = modifiedBranch.value.dependencies.findIndex((d: any) => d.uuid === uuid)
    if (modifiedBranch.value.dependencies[objIndex].isFollowVersion) {
        modifiedBranch.value.dependencies[objIndex].isFollowVersion = false
    } else {
        modifiedBranch.value.dependencies.forEach((d: any) => d.isFollowVersion = false)
        modifiedBranch.value.dependencies[objIndex].isFollowVersion = true
    }
    saveModifiedBranch()
}

const saveModifiedBranch = async function () {
    try {
        // Check if branch type is being changed to BASE
        if (modifiedBranch.value.type === 'BASE' && branchData.value.type !== 'BASE') {
            const result = await Swal.fire({
                title: 'Change Branch Type to Base?',
                text: 'Changing this branch to Base will automatically convert the current Base branch to Regular. Only one Base branch can exist per component.',
                icon: 'warning',
                showCancelButton: true,
                confirmButtonColor: '#3085d6',
                cancelButtonColor: '#d33',
                confirmButtonText: 'Yes, change to Base',
                cancelButtonText: 'Cancel'
            })
            
            if (!result.isConfirmed) {
                return
            }
        }
        
        // If there's an active editing session, apply those changes first
        if (editingRow.value) {
            const editedRow = modifiedBranch.value.effectiveDependencies?.find(
                (dep: any) => dep.component?.uuid === editingRow.value
            )
            if (editedRow) {
                applyEditSilent(editedRow)
            }
            // Clear editing state after applying
            cancelEdit()
        }
        
        // Validate dependency patterns before saving
        if (modifiedBranch.value.dependencyPatterns) {
            for (const pattern of modifiedBranch.value.dependencyPatterns) {
                // Check if pattern is empty or whitespace-only
                if (!pattern.pattern || pattern.pattern.trim() === '') {
                    notify('error', 'Invalid Pattern', 'Pattern cannot be empty')
                    return
                }
                try {
                    new RegExp(pattern.pattern)
                } catch (e: any) {
                    notify('error', 'Invalid Pattern', `Invalid regex pattern: ${pattern.pattern} - ${e.message}`)
                    return
                }
            }
        }
        
        // Handle custom version schema
        if (modifiedBranch.value.versionSchema === 'custom_version') {
            modifiedBranch.value.versionSchema = customBranchVersionSchema.value
        }
        
        // Remove dependencies marked for deletion before saving
        if (dependenciesMarkedForDeletion.value.size > 0) {
            modifiedBranch.value.dependencies = modifiedBranch.value.dependencies.filter(
                (d: any) => !dependenciesMarkedForDeletion.value.has(d.uuid)
            )
        }
        
        // Track if branch type was changed to BASE
        const wasChangedToBase = modifiedBranch.value.type === 'BASE' && branchData.value.type !== 'BASE'
        
        const storeResp = await store.dispatch('updateBranch', modifiedBranch.value)
        modifiedBranch.value = commonFunctions.deepCopy(storeResp)
        customBranchVersionSchema.value = ''
        selectNewVcsRepo.value = false
        // Clear deletion marks and newly added marks after successful save
        dependenciesMarkedForDeletion.value.clear()
        newlyAddedDependencies.value.clear()
        
        // If branch type was changed to BASE, refresh all branches of the component
        // to update the old Base branch (now Regular) in the UI
        if (wasChangedToBase && branchData.value.component) {
            await store.dispatch('fetchBranches', branchData.value.component)
        }
    } catch (err) {
        notify('error', 'Error Saving Branch', String(err))
    }
}

const hasBranchSettingsChanges: ComputedRef<boolean> = computed((): boolean => {
    if (!modifiedBranch.value || !branchData.value) return false
    
    // Check if there's an active editing session with unsaved changes
    const hasActiveEdit = editingRow.value !== null
    
    // Check dependency patterns changes
    const patternsChanged = JSON.stringify(modifiedBranch.value.dependencyPatterns || []) !== 
        JSON.stringify(branchData.value.dependencyPatterns || [])
    
    // Check dependencies changes
    const dependenciesChanged = JSON.stringify(modifiedBranch.value.dependencies || []) !== 
        JSON.stringify(branchData.value.dependencies || [])
    
    // Check if any dependencies are marked for deletion
    const hasDeletionMarks = dependenciesMarkedForDeletion.value.size > 0
    
    return modifiedBranch.value.name !== branchData.value.name ||
        modifiedBranch.value.versionSchema !== branchData.value.versionSchema ||
        modifiedBranch.value.marketingVersionSchema !== branchData.value.marketingVersionSchema ||
        modifiedBranch.value.metadata !== branchData.value.metadata ||
        modifiedBranch.value.type !== branchData.value.type ||
        modifiedBranch.value.vcs !== branchData.value.vcs ||
        modifiedBranch.value.vcsBranch !== branchData.value.vcsBranch ||
        modifiedBranch.value.autoIntegrate !== branchData.value.autoIntegrate ||
        patternsChanged ||
        dependenciesChanged ||
        hasDeletionMarks ||
        hasActiveEdit
})

function resetBranchSettings() {
    if (!branchData.value) return
    modifiedBranch.value.name = branchData.value.name
    modifiedBranch.value.versionSchema = branchData.value.versionSchema
    modifiedBranch.value.marketingVersionSchema = branchData.value.marketingVersionSchema
    modifiedBranch.value.metadata = branchData.value.metadata
    modifiedBranch.value.type = branchData.value.type
    modifiedBranch.value.vcs = branchData.value.vcs
    modifiedBranch.value.vcsBranch = branchData.value.vcsBranch
    modifiedBranch.value.autoIntegrate = branchData.value.autoIntegrate
    modifiedBranch.value.dependencyPatterns = commonFunctions.deepCopy(branchData.value.dependencyPatterns || [])
    modifiedBranch.value.dependencies = commonFunctions.deepCopy(branchData.value.dependencies || [])
    modifiedBranch.value.effectiveDependencies = commonFunctions.deepCopy(branchData.value.effectiveDependencies || [])
    selectNewVcsRepo.value = false
    // Clear deletion marks and newly added marks
    dependenciesMarkedForDeletion.value.clear()
    newlyAddedDependencies.value.clear()
    // Exit edit mode after resetting
    cancelEdit()
}

const vcsRepos: Ref<any[]> = ref([])
const approvalTypes: Ref<any[]> = ref([])
const environmentTypes: Ref<any[]> = ref([])
const releaseTagKeys: Ref<any[]> = ref([])
const editingRow: Ref<string | null> = ref(null)
const editingStatus: Ref<string> = ref('')
const editingBranch: Ref<string> = ref('')
const editingRelease: Ref<string> = ref('')
const editingFollowVersion: Ref<boolean> = ref(false)
const componentBranches: Ref<any[]> = ref([])
const branchReleases: Ref<any[]> = ref([])
const dependenciesMarkedForDeletion: Ref<Set<string>> = ref(new Set())
const newlyAddedDependencies: Ref<Set<string>> = ref(new Set())

const fetchVcsRepos = async function () : Promise<any[]> {
    let fetchedRepos = store.getters.vcsReposOfOrg(branchData.value.org)
    if (!fetchedRepos || !fetchedRepos.length) {
        fetchedRepos = await store.dispatch('fetchVcsRepos', branchData.value.org)
    }
    vcsRepos.value = fetchedRepos.map((repo: any) => {
        let repoObj = {
            label: repo.name + ' - ' + repo.uri,
            value: repo.uuid,
            uri: repo.uri
        }
        return repoObj
    })
    return vcsRepos.value
}

const fetchComponentBranches = async function (componentUuid: string) {
    try {
        // Check if branches are already in store
        let branches = store.getters.branchesOfComponent(componentUuid)
        if (!branches || !branches.length) {
            // Fetch branches from server
            await store.dispatch('fetchBranches', componentUuid)
            branches = store.getters.branchesOfComponent(componentUuid)
        }
        // Filter out archived branches and sort
        componentBranches.value = branches
            .filter((b: any) => b.status !== 'ARCHIVED')
            .sort((a: any, b: any) => {
                if (a.name === 'master' || a.name === 'main') return -1
                if (b.name === 'master' || b.name === 'main') return 1
                return a.name.localeCompare(b.name)
            })
    } catch (error) {
        console.error('Error fetching component branches:', error)
        notify('error', 'Error', 'Failed to fetch component branches')
        componentBranches.value = []
    }
}

const fetchBranchReleases = async function (branchUuid: string) {
    // Guard against null or empty branchUuid
    if (!branchUuid) {
        branchReleases.value = []
        return
    }
    
    try {
        // Check if releases are already in store
        let releases = store.getters.releasesOfBranch(branchUuid)
        if (!releases || !releases.length) {
            // Fetch releases from server - pass as object with branch property
            await store.dispatch('fetchReleases', { branch: branchUuid })
            releases = store.getters.releasesOfBranch(branchUuid)
        }
        // Sort releases by creation date (newest first)
        branchReleases.value = releases
            .filter((r: any) => r.status !== 'ARCHIVED')
            .sort((a: any, b: any) => {
                return new Date(b.createdDate).getTime() - new Date(a.createdDate).getTime()
            })
    } catch (error) {
        console.error('Error fetching branch releases:', error)
        notify('error', 'Error', 'Failed to fetch branch releases')
        branchReleases.value = []
    }
}

const addedComponent = function (component: any) {
    console.log('addedComponent called with:', component)
    
    try {
        // check if component already exists
        let exists = false
        if (component.branch) {
            const exdep = modifiedBranch.value.dependencies.filter((d: any) => (d.branch === component.branch))
            exists = exdep.length
        } else {
            const exdep = modifiedBranch.value.dependencies.filter((d: any) => (d.uuid === component.uuid))
            exists = exdep.length
        }
        if (exists) {
            notify('warning', 'Cannot Add Component', 'Component Already Exists')
            showAddComponentModal.value = false
            showAddComponentProductModal.value = false
            return
        }
        
        // Stage the new dependency instead of immediately saving
        modifiedBranch.value.dependencies.push(component)
        
        // Mark as newly added for visual indication
        newlyAddedDependencies.value.add(component.uuid)
        
        // Also add to effectiveDependencies for immediate display
        if (!modifiedBranch.value.effectiveDependencies) {
            modifiedBranch.value.effectiveDependencies = []
        }
        
        // Get component and branch details from store
        // The AddComponent modal dispatches fetchBranches and fetchReleases, so data should be in store
        // Access components directly from state since there's no componentByUuid getter
        let componentDetails = store.state.components.find((c: any) => c.uuid === component.uuid)
        let branchDetails = component.branch ? store.getters.branchById(component.branch) : null
        let releaseDetails = component.release ? store.state.releases.find((r: any) => r.uuid === component.release) : null
        
        console.log('Component details from store:', componentDetails)
        console.log('Branch details from store:', branchDetails)
        console.log('Release details from store:', releaseDetails)
        
        // If component not in store, we'll need to fetch it or use minimal info
        if (!componentDetails) {
            console.warn('Component not found in store, will fetch after save:', component.uuid)
            // Create minimal component object - will be properly populated after save/refresh
            componentDetails = {
                uuid: component.uuid,
                name: 'Loading...',
                type: 'COMPONENT'
            }
        }
        
        // If branch not in store, create minimal object
        if (component.branch && !branchDetails) {
            console.warn('Branch not found in store:', component.branch)
            branchDetails = {
                uuid: component.branch,
                name: 'Loading...',
                type: 'BRANCH'
            }
        }
        
        modifiedBranch.value.effectiveDependencies.push({
            component: componentDetails,
            branch: branchDetails,
            status: component.status || 'REQUIRED',
            release: component.release || null,
            releaseDetails: releaseDetails,
            isFollowVersion: component.isFollowVersion || false,
            source: 'MANUAL'
        })
        
        console.log('Added to effectiveDependencies:', modifiedBranch.value.effectiveDependencies[modifiedBranch.value.effectiveDependencies.length - 1])
        
        notify('success', 'Dependency Added', 'Click Save Changes to persist this dependency')
    } catch (error) {
        console.error('Error in addedComponent:', error)
        notify('error', 'Error', 'Failed to add dependency: ' + String(error))
    } finally {
        // Always close modals
        showAddComponentModal.value = false
        showAddComponentProductModal.value = false
    }
}

const editableDependency = {
    editCompProj: '',
    editCompBranch: '',
    editCompStatus: '',
    editCompRelease: ''    
}

const editDependency = function (component: string, branch: string, status: string, release: string) {
    editableDependency.editCompProj = component
    editableDependency.editCompBranch = branch
    editableDependency.editCompStatus = status
    editableDependency.editCompRelease = release
    showEditComponentModal.value = true
}

const deleteDependency = function (component: string, branch: string) {
    // Mark dependency for deletion instead of immediately deleting
    if (component) {
        dependenciesMarkedForDeletion.value.add(component)
    }
}

// Dependency Pattern functions
const addDependencyPattern = function () {
    if (!modifiedBranch.value.dependencyPatterns) {
        modifiedBranch.value.dependencyPatterns = []
    }
    modifiedBranch.value.dependencyPatterns.push({
        uuid: crypto.randomUUID(),
        pattern: '',
        targetBranchName: null,
        defaultStatus: 'REQUIRED',
        isNew: true  // Mark as new (not saved to backend yet)
    })
}

const removeDependencyPattern = function (uuid: string) {
    // Remove pattern from UI without saving (for new patterns)
    modifiedBranch.value.dependencyPatterns = modifiedBranch.value.dependencyPatterns.filter((p: any) => p.uuid !== uuid)
}

const deleteDependencyPattern = function (uuid: string) {
    // Delete pattern and save to backend (for existing patterns)
    modifiedBranch.value.dependencyPatterns = modifiedBranch.value.dependencyPatterns.filter((p: any) => p.uuid !== uuid)
    saveModifiedBranch()
}

const isPatternNew = function (row: any): boolean {
    // Check if pattern exists in the original branchData (saved to backend)
    if (!branchData.value.dependencyPatterns) return true
    return !branchData.value.dependencyPatterns.some((p: any) => p.uuid === row.uuid)
}

const patternTableFields: DataTableColumns<any> = [
    {
        title: 'Pattern',
        key: 'pattern',
        width: 200,
        render: (row: any) => {
            return h(NInput, {
                value: row.pattern,
                placeholder: 'e.g., ^myapp-.*',
                onUpdateValue: (val: string) => {
                    row.pattern = val
                }
            })
        }
    },
    {
        title: 'Target Branch',
        key: 'targetBranchName',
        width: 200,
        render: (row: any) => {
            return h(NInput, {
                value: row.targetBranchName || '',
                placeholder: 'Leave empty for BASE branch',
                onUpdateValue: (val: string) => {
                    row.targetBranchName = val || null
                }
            })
        }
    },
    {
        title: 'Status',
        key: 'defaultStatus',
        width: 130,
        render: (row: any) => {
            return h(NSelect, {
                value: row.defaultStatus,
                options: [
                    { label: 'REQUIRED', value: 'REQUIRED' },
                    { label: 'IGNORED', value: 'IGNORED' },
                    { label: 'TRANSIENT', value: 'TRANSIENT' }
                ],
                onUpdateValue: (val: string) => {
                    row.defaultStatus = val
                }
            })
        }
    },
    {
        title: 'Actions',
        key: 'actions',
        width: 100,
        render: (row: any) => {
            const isNew = isPatternNew(row)
            const buttons = [
                h(NButton, {
                    size: 'small',
                    type: 'info',
                    onClick: () => previewPattern(row),
                    title: 'Preview pattern matches'
                }, { default: () => h(NIcon, null, { default: () => h(Eye) }) })
            ]
            
            if (isNew) {
                // Show X button for new (unsaved) patterns - just removes from UI
                buttons.push(h(NButton, {
                    size: 'small',
                    quaternary: true,
                    onClick: () => removeDependencyPattern(row.uuid),
                    title: 'Remove pattern (not saved)'
                }, { default: () => h(NIcon, null, { default: () => h(X) }) }))
            } else {
                // Show delete button for existing (saved) patterns - deletes from backend
                buttons.push(h(NButton, {
                    size: 'small',
                    type: 'error',
                    onClick: () => deleteDependencyPattern(row.uuid),
                    title: 'Delete pattern'
                }, { default: () => h(NIcon, null, { default: () => h(Trash) }) }))
            }
            
            return h('div', { class: 'flex gap-1' }, buttons)
        }
    }
]

const previewTableFields: DataTableColumns<any> = [
    {
        title: 'Component',
        key: 'component',
        render: (row: any) => row.component?.name || 'Unknown'
    },
    {
        title: 'Branch',
        key: 'branch',
        render: (row: any) => row.branch?.name || 'Unknown'
    },
    {
        title: 'Type',
        key: 'branchType',
        render: (row: any) => h(NTag, { type: 'info', size: 'small' }, { default: () => row.branch?.type || 'Unknown' })
    },
    {
        title: 'Status',
        key: 'status',
        render: (row: any) => h(NTag, { type: 'success', size: 'small' }, { default: () => row.status || 'REQUIRED' })
    }
]

const getRowClassName = (row: any) => {
    const isMarkedForDeletion = dependenciesMarkedForDeletion.value.has(row.component?.uuid)
    const isNewlyAdded = newlyAddedDependencies.value.has(row.component?.uuid)
    
    if (isMarkedForDeletion) return 'marked-for-deletion'
    if (isNewlyAdded) return 'newly-added'
    return ''
}

const effectiveDepTableFields: DataTableColumns<any> = [
    {
        title: 'Component / Product',
        key: 'component',
        render: (row: any) => {
            const isNewlyAdded = newlyAddedDependencies.value.has(row.component?.uuid)
            const name = row.component?.name || 'Unknown'
            
            if (isNewlyAdded) {
                return h('div', { style: 'display: flex; align-items: center; gap: 8px;' }, [
                    h('span', name),
                    h(NTag, { 
                        type: 'success', 
                        size: 'small',
                        style: 'font-weight: bold;'
                    }, { default: () => 'NEW' })
                ])
            }
            
            return name
        }
    },
    {
        title: 'Branch / ' + (myorg.value?.terminology?.featureSetLabel ? myorg.value?.terminology?.featureSetLabel : 'Feature Set'),
        key: 'branch',
        render: (row: any) => {
            const isEditing = editingRow.value === row.component?.uuid
            
            if (isEditing) {
                // Show branch selection dropdown
                const branchOptions = componentBranches.value.map((b: any) => ({
                    label: b.name,
                    value: b.uuid
                }))
                
                return h(NSelectComponent, {
                    value: editingBranch.value,
                    'onUpdate:value': async (value: string) => { 
                        editingBranch.value = value
                        // Fetch releases for the newly selected branch
                        if (value) {
                            await fetchBranchReleases(value)
                        } else {
                            branchReleases.value = []
                        }
                        // Reset release selection when branch changes
                        editingRelease.value = ''
                    },
                    options: branchOptions,
                    size: 'small',
                    placeholder: 'Select branch'
                })
            }
            
            if (!row.branch) return 'Unknown'
            
            const isExcluded = row.status === 'IGNORED'
            
            return h('span', { 
                style: isExcluded ? 'opacity: 0.5' : ''
            }, row.branch.name)
        }
    },
    {
        title: 'Release',
        key: 'release',
        render: (row: any) => {
            const isEditing = editingRow.value === row.component?.uuid
            const isExcluded = row.status === 'IGNORED'
            
            if (isEditing) {
                // Show release selection dropdown with "None" option
                const releaseOptions = [
                    { label: 'None', value: '' },
                    ...branchReleases.value.map((r: any) => ({
                        label: r.version,
                        value: r.uuid
                    }))
                ]
                
                return h(NSelectComponent, {
                    value: editingRelease.value,
                    'onUpdate:value': (value: string) => { 
                        editingRelease.value = value
                    },
                    options: releaseOptions,
                    size: 'small',
                    placeholder: 'Select release',
                    clearable: true
                })
            }
            
            // Get version directly from the row data
            const version = row.releaseDetails?.version || 'Not Set'
            
            return h('span', { 
                style: isExcluded ? 'opacity: 0.5' : ''
            }, version)
        }
    },
    {
        title: 'Type',
        key: 'type',
        render: (row: any) => {
            const isExcluded = row.status === 'IGNORED'
            
            // Try to get type from row data first
            let type = row.component?.type
            
            // If not available, look up from store
            if (!type && row.component?.uuid) {
                const componentFromStore = store.state.components.find((c: any) => c.uuid === row.component.uuid)
                type = componentFromStore?.type
            }
            
            // Final fallback
            if (!type) {
                type = 'COMPONENT'
            }
            
            const color = type === 'PRODUCT' ? 'success' : 'info'
            
            return h(NTag, { 
                type: color, 
                size: 'small',
                style: isExcluded ? 'opacity: 0.5' : ''
            }, () => type)
        }
    },
    {
        title: 'Follow Version',
        key: 'isFollowVersion',
        render: (row: any) => {
            const isEditing = editingRow.value === row.component?.uuid
            const isExcluded = row.status === 'IGNORED'
            
            if (isEditing) {
                return h(NCheckbox, {
                    checked: editingFollowVersion.value,
                    'onUpdate:checked': (value: boolean) => { 
                        editingFollowVersion.value = value
                        
                        // If setting Follow Version to true, uncheck it on all other rows
                        if (value && modifiedBranch.value.effectiveDependencies) {
                            modifiedBranch.value.effectiveDependencies.forEach((d: any) => {
                                if (d.component?.uuid !== row.component?.uuid) {
                                    d.isFollowVersion = false
                                }
                            })
                            // Also clear from dependencies array
                            modifiedBranch.value.dependencies.forEach((d: any) => {
                                if (d.uuid !== row.component.uuid) {
                                    d.isFollowVersion = false
                                }
                            })
                            // Force reactivity update
                            modifiedBranch.value.effectiveDependencies = [...modifiedBranch.value.effectiveDependencies]
                        }
                    },
                    title: 'Following Dependency Version If Checked',
                    size: 'large'
                })
            }
            
            return h(NCheckbox, {
                checked: row.isFollowVersion || false,
                disabled: true,
                title: 'Following Dependency Version If Checked. Click Edit in the Actions column to Change.',
                size: 'large',
                style: isExcluded ? 'opacity: 0.5' : ''
            })
        }
    },
    {
        title: 'Status',
        key: 'status',
        render: (row: any) => {
            const isEditing = editingRow.value === row.component?.uuid
            const isExcluded = row.status === 'IGNORED'
            
            if (isEditing) {
                return h(NSelectComponent, {
                    value: editingStatus.value,
                    'onUpdate:value': (value: string) => { 
                        editingStatus.value = value
                    },
                    options: [
                        { label: 'Required', value: 'REQUIRED' },
                        { label: 'Ignored', value: 'IGNORED' },
                        { label: 'Transient', value: 'TRANSIENT' }
                    ],
                    size: 'small'
                })
            }
            
            let color = 'default'
            let label = row.status || 'UNKNOWN'
            
            switch(row.status) {
                case 'REQUIRED':
                    color = 'error'
                    break
                case 'OPTIONAL':
                    color = 'warning'
                    break
                case 'TRANSIENT':
                    color = 'info'
                    break
                case 'IGNORED':
                    color = 'default'
                    break
            }
            
            return h(NTag, { 
                type: color, 
                size: 'small',
                style: isExcluded ? 'opacity: 0.5' : ''
            }, () => label)
        }
    },
    {
        title: 'Source',
        key: 'source',
        render: (row: any) => {
            const color = row.source === 'MANUAL' ? 'info' : 'success'
            return h(NTag, { type: color, size: 'small' }, () => row.source)
        }
    },
    {
        title: 'Actions',
        key: 'actions',
        width: 120,
        render: (row: any) => {
            // Check if this row is being edited
            const isEditing = editingRow.value === row.component?.uuid
            const isExcluded = row.status === 'IGNORED'
            
            if (isEditing) {
                return h('div', { class: 'flex gap-1 items-center' }, [
                    h(NButton, {
                        size: 'small',
                        quaternary: true,
                        onClick: () => applyEdit(row),
                        title: 'Confirm Dependency Changes and Close its editing',
                    }, { default: () => h(NIcon, { component: Check }) }),
                    h(NButton, {
                        size: 'small',
                        quaternary: true,
                        onClick: () => cancelEdit(),
                        title: 'Cancel editing',
                    }, { default: () => h(NIcon, { component: X }) })
                ])
            }
            
            const buttons = [
                // Edit button
                h(NButton, {
                    size: 'small',
                    quaternary: true,
                    onClick: () => startEdit(row),
                    title: 'Edit dependency',
                }, { default: () => h(NIcon, { component: Edit }) })
            ]
            
            // Remove/Delete button - only for manual dependencies
            if (row.source === 'MANUAL') {
                const isNewlyAdded = newlyAddedDependencies.value.has(row.component?.uuid)
                const isMarkedForDeletion = dependenciesMarkedForDeletion.value.has(row.component?.uuid)
                
                if (isNewlyAdded) {
                    // Show X icon for newly added (not yet persisted) - removes immediately
                    buttons.push(
                        h(NButton, {
                            size: 'small',
                            quaternary: true,
                            onClick: () => {
                                // Remove from dependencies
                                modifiedBranch.value.dependencies = modifiedBranch.value.dependencies.filter(
                                    (d: any) => d.uuid !== row.component?.uuid
                                )
                                // Remove from effectiveDependencies
                                modifiedBranch.value.effectiveDependencies = modifiedBranch.value.effectiveDependencies.filter(
                                    (d: any) => d.component?.uuid !== row.component?.uuid
                                )
                                // Remove from newly added set
                                newlyAddedDependencies.value.delete(row.component?.uuid)
                            },
                            title: 'Remove unsaved dependency'
                        }, { default: () => h(NIcon, { component: X }) })
                    )
                } else if (isMarkedForDeletion) {
                    // Show undo button if marked for deletion
                    buttons.push(
                        h(NButton, {
                            size: 'small',
                            quaternary: true,
                            onClick: () => dependenciesMarkedForDeletion.value.delete(row.component?.uuid),
                            title: 'Undo deletion'
                        }, { default: () => 'Undo' })
                    )
                } else {
                    // Show delete button for persisted dependencies
                    buttons.push(
                        h(NButton, {
                            size: 'small',
                            quaternary: true,
                            onClick: () => deleteDependency(row.component?.uuid, row.branch?.uuid),
                            title: 'Mark dependency for deletion. Then click on Save Changes to confirm.'
                        }, { default: () => h(NIcon, { component: Trash }) })
                    )
                }
            }
            
            return h('div', { class: 'flex gap-1 items-center' }, buttons)
        }
    }
]

const startEdit = async function(row: any) {
    // If already editing another row, apply those changes first
    if (editingRow.value && editingRow.value !== row.component?.uuid) {
        // Find the row being edited and apply changes
        const editedRow = modifiedBranch.value.effectiveDependencies?.find(
            (dep: any) => dep.component?.uuid === editingRow.value
        )
        if (editedRow) {
            applyEdit(editedRow)
        }
    }
    
    editingRow.value = row.component?.uuid
    editingStatus.value = row.status
    editingBranch.value = row.branch?.uuid || ''
    editingRelease.value = row.releaseDetails?.uuid || row.release?.uuid || ''
    editingFollowVersion.value = row.isFollowVersion || false
    
    // Fetch branches for this component
    if (row.component?.uuid) {
        await fetchComponentBranches(row.component.uuid)
    }
    
    // Fetch releases for the selected branch only if branch is set
    if (editingBranch.value) {
        await fetchBranchReleases(editingBranch.value)
    }
}

const cancelEdit = function() {
    editingRow.value = null
    editingStatus.value = ''
    editingBranch.value = ''
    editingRelease.value = ''
    editingFollowVersion.value = false
    componentBranches.value = []
    branchReleases.value = []
}

const applyEditSilent = function(row: any) {
    // Apply changes without exiting edit mode
    // Check if this dependency already exists in manual dependencies
    const existingIndex = modifiedBranch.value.dependencies.findIndex(
        (d: any) => d.uuid === row.component.uuid
    )
    
    // Enforce rule: only one dependency can have isFollowVersion=true
    if (editingFollowVersion.value) {
        // Clear isFollowVersion from all other dependencies
        modifiedBranch.value.dependencies.forEach((d: any) => {
            d.isFollowVersion = false
        })
        
        // Also clear from effectiveDependencies for visual consistency
        if (modifiedBranch.value.effectiveDependencies) {
            modifiedBranch.value.effectiveDependencies.forEach((d: any) => {
                if (d.component?.uuid !== row.component.uuid) {
                    d.isFollowVersion = false
                }
            })
            // Force reactivity update
            modifiedBranch.value.effectiveDependencies = [...modifiedBranch.value.effectiveDependencies]
        }
    }
    
    const manualDep: any = {
        uuid: row.component.uuid,
        branch: editingBranch.value || row.branch?.uuid,
        status: editingStatus.value,
        release: editingRelease.value || null,
        isFollowVersion: editingFollowVersion.value
    }
    
    if (existingIndex >= 0) {
        // Update existing manual dependency
        modifiedBranch.value.dependencies[existingIndex] = manualDep
    } else {
        // Add as new manual dependency (will override pattern match)
        modifiedBranch.value.dependencies.push(manualDep)
    }
    
    // Also update the effectiveDependencies row so the table reflects changes immediately
    const effectiveDepIndex = modifiedBranch.value.effectiveDependencies?.findIndex(
        (d: any) => d.component?.uuid === row.component.uuid
    )
    if (effectiveDepIndex >= 0 && modifiedBranch.value.effectiveDependencies) {
        // Update the effective dependency with the new values
        modifiedBranch.value.effectiveDependencies[effectiveDepIndex].status = editingStatus.value
        modifiedBranch.value.effectiveDependencies[effectiveDepIndex].isFollowVersion = editingFollowVersion.value
        
        // Update branch reference if changed
        if (editingBranch.value && editingBranch.value !== row.branch?.uuid) {
            const newBranch = componentBranches.value.find((b: any) => b.uuid === editingBranch.value)
            if (newBranch) {
                modifiedBranch.value.effectiveDependencies[effectiveDepIndex].branch = newBranch
            }
        }
        
        // Update release reference if changed
        if (editingRelease.value) {
            const newRelease = branchReleases.value.find((r: any) => r.uuid === editingRelease.value)
            if (newRelease) {
                modifiedBranch.value.effectiveDependencies[effectiveDepIndex].releaseDetails = newRelease
            }
        } else {
            modifiedBranch.value.effectiveDependencies[effectiveDepIndex].releaseDetails = null
        }
        
        // Update source to MANUAL since it's now a manual override
        modifiedBranch.value.effectiveDependencies[effectiveDepIndex].source = 'MANUAL'
    }
    
    // Don't clear editing state - stay in edit mode
}

const applyEdit = function(row: any) {
    // Apply changes and exit edit mode
    applyEditSilent(row)
    
    // Clear editing state
    editingRow.value = null
    editingStatus.value = ''
    editingBranch.value = ''
    editingRelease.value = ''
    editingFollowVersion.value = false
    componentBranches.value = []
    branchReleases.value = []
}

const previewPattern = async function(pattern: any) {
    try {
        // Validate pattern first
        if (!pattern.pattern) {
            notify('error', 'Error', 'Pattern is required')
            return
        }
        
        // Call GraphQL to preview pattern matches
        const response = await graphqlClient.query({
            query: gql`
                query previewPattern($orgUuid: ID!, $pattern: String!, $targetBranchName: String, $defaultStatus: Status) {
                    previewPattern(orgUuid: $orgUuid, pattern: $pattern, targetBranchName: $targetBranchName, defaultStatus: $defaultStatus) {
                        component {
                            uuid
                            name
                        }
                        branch {
                            uuid
                            name
                            type
                        }
                        status
                    }
                }
            `,
            variables: {
                orgUuid: orguuid,
                pattern: pattern.pattern,
                targetBranchName: pattern.targetBranchName,
                defaultStatus: pattern.defaultStatus
            }
        })
        
        patternPreviewData.value = response.data.previewPattern
        showPatternPreviewModal.value = true
    } catch (error: any) {
        notify('error', 'Error', error.message || 'Failed to preview pattern')
    }
}

const editedComponent = function (component: any) {
    // locate component via supplied proj and branch and replace it
    let branchExists = false
    if (editableDependency.editCompBranch) {
        let exdep = modifiedBranch.value.dependencies.filter((d: any) => (d.branch === editableDependency.editCompBranch))
        branchExists = exdep.length
    }
    let replaceIndex = -1
    for (let i = 0; i < modifiedBranch.value.dependencies.length && replaceIndex < 0; i++) {
        if (branchExists && modifiedBranch.value.dependencies[i].branch === editableDependency.editCompBranch) {
            replaceIndex = i
        } else if (!branchExists && modifiedBranch.value.dependencies[i].uuid === editableDependency.editCompProj) {
            replaceIndex = i
        }
    }

    if (replaceIndex < 0) {
        notify('error', 'Error on updating requirement', 'Cannot Determine Component to Update')
    } else {
        modifiedBranch.value.dependencies[replaceIndex] = component
        saveModifiedBranch()
        showEditComponentModal.value = false
    }
}

const ossArtifact = ref('')

const registerOssArtifact = async function () {
    await graphqlClient.mutate({
        mutation: gql`
            mutation registerOssArtifact($artifact: String!, $org: ID!) {
                registerOssArtifact(artifact: $artifact, org: $org) {
                    uuid
                    displayIdentifier
                }
            }`,
        variables: { artifact: ossArtifact.value, org: orguuid }
    })
    ossArtifact.value = ''
    showAddOssArtifactModal.value = false
}

const linkVcsRepo = function (value: any) {
    modifiedBranch.value.vcs = value.repo
    modifiedBranch.value.vcsBranch = value.branch
    isLinkVcsRepo.value = false
    saveModifiedBranch()
}

const resetReleaseFilter = function () {
    releaseFilter.value = {
        lifecycle: '',
        tagKey: '',
    }
}

const handleComparison = function (rowKeys: any[]) {
    // check if release to compare already has this release
    if (rowKeys.length > 2) rowKeys = []
    releasesToCompare.value = rowKeys
    if (releasesToCompare.value.length > 1) {
        showReleaseComparisonModal.value = true
    }
    if (rowKeys.length > 1) releasesToCompare.value = []
}

const resetComparison = function () {
    releasesToCompare.value = []
    // uncheck checkboxes
    Object.keys(comparisonCheckboxes.value).forEach(checkbox => {
        comparisonCheckboxes.value[checkbox] = false
    })
}
const getNextVersion = async function (branch: string){
    let resp = await graphqlClient.query({
        query: gql`
            query getNextVersion($branchUuid: ID!) {
                getNextVersion(branchUuid: $branchUuid)
            }
            `,
        variables: { branchUuid: branch },
        fetchPolicy: 'no-cache'
    })
    currentNextVersion.value = resp.data.getNextVersion
}
const nextVersion: Ref<any> = ref("")
const currentNextVersion: Ref<any> = ref("")
async function onCreated () {
    if (!branchData.value || !branchData.value.uuid) {
        if(branchUuid.value!=''){
            modifiedBranch.value = commonFunctions.deepCopy((await store.dispatch('fetchBranch', branchUuid.value)))
        }
    } else {
        modifiedBranch.value = commonFunctions.deepCopy(branchData.value)
    }
    if (false && myUser.installationType !== 'OSS') approvalTypes.value = await loadApprovalTypes (branchData.value.org, branchData.value.componentDetails.resourceGroup)
    if (false && myUser.installationType !== 'OSS')  environmentTypes.value = await loadEnvironmentTypes (branchData.value.org)
    const rlz = await store.dispatch('fetchReleases', {branch: branchUuid.value})
    // compute list of tag keys
    const tagKeyMap: any = {}
    rlz.forEach((r: any) => {
        if (r.tags && r.tags.length) {
            const tagKeyArr = r.tags.map((t: any) => t.key)
            if (tagKeyArr && tagKeyArr.length) {
                tagKeyArr.forEach((tk: string) => {tagKeyMap[tk] = true})
            }
        }
    })
    releaseTagKeys.value = Object.keys(tagKeyMap)
        .map((x: string) => {return {label: x, value: x}})
    fetchVcsRepos()
    words.value = commonFunctions.resolveWords(branchData.value.componentDetails.type === 'COMPONENT', myorg.value?.terminology)
}


const setNextVersion = async function(){
    try{
        await graphqlClient.mutate({
            mutation: gql`
                mutation setNextVersion($branchUuid: ID!, $versionString: String!) {
                    setNextVersion(branchUuid: $branchUuid, versionString: $versionString)
                }`,
            variables: {
                branchUuid: branchUuid.value,
                versionString: nextVersion.value
            }
        })
    }   catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
    
    showSetNextVersionModal.value = false
    await getNextVersion(branchUuid.value)
    nextVersion.value = ''

}

const openNextVersionModal = async function(){
    await getNextVersion(branchUuid.value)
    showSetNextVersionModal.value = true
}

const releaseFields: ComputedRef<any[]>  = computed((): any[] => {

    const fields: any[] = []

    if(compareMode.value){
        fields.push({type: 'selection'})
    }
    fields.push(
        {
            key: 'version',
            title: (column: any) => {
                let els = []
                els.push('Version')
                if (branchData.value.componentDetails.type === 'PRODUCT') {
                    els.push(
                        h(
                            NIcon,
                            {
                                title: 'Compare Release Components',
                                class: 'icons clickable',
                                size: 25,
                                onClick: () => compareMode.value = !compareMode.value
                            },
                            () => h(LayoutColumns)
                        )
                    )
                }
                const lifecycleEl = h('div', [
                                'Lifecycle:',
                                h(NSelect, {
                                    options: [{value: '', label: ''}].concat(constants.LifecycleOptions.map((x: any) => ({ value: x.key, label: x.label }))),
                                    defaultValue: releaseFilter.value.lifecycle,
                                    'on-update:value': (value: string) => releaseFilter.value.lifecycle = value
                                })
                            ])
                const tagEl = h('div', [
                                'Tag key:',
                                h(NSelect, {
                                    options: [{value: '', label: ''}].concat(releaseTagKeys.value),
                                    defaultValue: releaseFilter.value.tagKey,
                                    'on-update:value': (value: string) => releaseFilter.value.tagKey = value
                                })
                            ])
                els.push(h(
                    NPopover, {
                        trigger: 'hover',
                        style: 'width: 300px;'
                    }, {
                        trigger: () => h(
                            NIcon,
                            {
                                title: 'Filter',
                                class: 'icons clickable',
                                size: 25,
                                color: isReleaseFilterActivated.value ? 'red' : ''
                            },
                            () => h(Filter)
                        ),
                        default: () =>  [
                            lifecycleEl,
                            tagEl,
                            h(NButton, {
                                type: 'warning',
                                onClick: () => resetReleaseFilter()
                            },
                            () => 'Reset'),
                        ]
                    }

                ))
                return h('div', els)
            },
            render: (row: any) => {
                let els: any[] = []
                els.push(
                    h('a', {
                        onClick: (e: Event) => {
                            e.preventDefault()
                            showRelease(row.uuid)
                        },
                        href: '#'
                    }, [h('span', row.version)]
                    )
                )
                if(isWritable && branchData.value.componentDetails.type === 'PRODUCT'){
                    els.push(
                        h(
                            NIcon,
                            {
                                title: 'Create ' + words.value.branchFirstUpper + ' From Release',
                                class: 'icons clickable',
                                size: 25,
                                onClick: () => cloneReleaseToFs(row.uuid, row.version)
                            },
                            () => h(Copy)
                        )
                    )
                }
                return h('div', els)
            }
        }
    )
    if (marketingVersionEnabled.value) fields.push(
        {
            key: 'marketingVersion',
            title: 'Marketing Version'
        }
    )
    fields.push(
        {
            key: 'createdDate',
            title: 'Created',
            render: (row: any) => (new Date(row.createdDate)).toLocaleString('en-CA', {hour12: false})
        },
        {
            key: 'lifecycle',
            title: 'Lifecycle',
            render: (row: any) => (constants.LifecycleOptions.find(lo => lo.key === row.lifecycle)?.label)
        }
    )
    fields.push({
        key: 'vulnerabilities',
        title: 'Vulnerabilities',
        render: (row: any) => {
            let els: any[] = []
            if (row.metrics && row.metrics.lastScanned) {
                const criticalEl = h('div', {title: 'Criticial Severity Vulnerabilities', class: 'circle', style: 'background: #f86c6b; cursor: pointer;', onClick: () => viewDetailedVulnerabilitiesForRelease(row, 'CRITICAL', 'Vulnerability')}, row.metrics.critical)
                const highEl = h('div', {title: 'High Severity Vulnerabilities', class: 'circle', style: 'background: #fd8c00; cursor: pointer;', onClick: () => viewDetailedVulnerabilitiesForRelease(row, 'HIGH', 'Vulnerability')}, row.metrics.high)
                const medEl = h('div', {title: 'Medium Severity Vulnerabilities', class: 'circle', style: 'background: #ffc107; cursor: pointer;', onClick: () => viewDetailedVulnerabilitiesForRelease(row, 'MEDIUM', 'Vulnerability')}, row.metrics.medium)
                const lowEl = h('div', {title: 'Low Severity Vulnerabilities', class: 'circle', style: 'background: #4dbd74; cursor: pointer;', onClick: () => viewDetailedVulnerabilitiesForRelease(row, 'LOW', 'Vulnerability')}, row.metrics.low)
                const unassignedEl = h('div', {title: 'Vulnerabilities with Unassigned Severity', class: 'circle', style: 'background: #777; cursor: pointer;', onClick: () => viewDetailedVulnerabilitiesForRelease(row, 'UNASSIGNED', 'Vulnerability')}, row.metrics.unassigned)
                els = [h(NSpace, {size: 1}, () => [criticalEl, highEl, medEl, lowEl, unassignedEl])]
            }
            if (!els.length) els = [h('div'), 'N/A']
            return els
        }
    })
    fields.push({
        key: 'violations',
        title: 'Violations',
        render: (row: any) => {
            let els: any[] = []
            if (row.metrics && row.metrics.lastScanned) {
                const licenseEl = h('div', {title: 'Licensing Policy Violations', class: 'circle', style: 'background: blue; cursor: pointer;', onClick: () => viewDetailedVulnerabilitiesForRelease(row, '', 'Violation')}, row.metrics.policyViolationsLicenseTotal)
                const securityEl = h('div', {title: 'Security Policy Violations', class: 'circle', style: 'background: red; cursor: pointer;', onClick: () => viewDetailedVulnerabilitiesForRelease(row, '', 'Violation')}, row.metrics.policyViolationsSecurityTotal)
                const operationalEl = h('div', {title: 'Operational Policy Violations', class: 'circle', style: 'background: grey; cursor: pointer;', onClick: () => viewDetailedVulnerabilitiesForRelease(row, '', 'Violation')}, row.metrics.policyViolationsOperationalTotal)
                els = [h(NSpace, {size: 1}, () => [licenseEl, securityEl, operationalEl])]
            }
            if (!els.length) els = [h('div'), 'N/A']
            return els
        }
    })

    return fields
})

// Detailed vulnerability modal state and logic (mirrors ReleaseView)
const showDetailedVulnerabilitiesModal = ref(false)
const detailedVulnerabilitiesData: Ref<any[]> = ref([])
const loadingVulnerabilities: Ref<boolean> = ref(false)
const selectedReleaseForModal: Ref<any> = ref(null)

// Per-modal context for Dependency-Track linking
const currentReleaseArtifacts: Ref<any[]> = ref([])
const currentReleaseOrgUuid: Ref<string> = ref('')
const currentDtrackProjectUuids: Ref<string[]> = ref([])
const currentSeverityFilter: Ref<string> = ref('')
const currentTypeFilter: Ref<string> = ref('')

async function viewDetailedVulnerabilitiesForRelease(releaseRow: any, severityFilter: string = '', typeFilter: string = '') {
    loadingVulnerabilities.value = true
    showDetailedVulnerabilitiesModal.value = true
    selectedReleaseForModal.value = releaseRow
    currentSeverityFilter.value = severityFilter
    currentTypeFilter.value = typeFilter
    try {
        const releaseData = await ReleaseVulnerabilityService.fetchReleaseVulnerabilityData(
            releaseRow.uuid,
            branchData.value.org
        )
        
        // Update reactive values with the processed data
        currentReleaseArtifacts.value = releaseData.artifacts
        currentReleaseOrgUuid.value = releaseData.orgUuid
        currentDtrackProjectUuids.value = releaseData.dtrackProjectUuids
        detailedVulnerabilitiesData.value = releaseData.vulnerabilityData
    } catch (error) {
        console.error('Error fetching release details:', error)
        notify('error', 'Error', 'Failed to load vulnerability details for release')
    } finally {
        loadingVulnerabilities.value = false
    }
}

async function handleRefreshVulnerabilityData() {
    if (selectedReleaseForModal.value) {
        loadingVulnerabilities.value = true
        // Add a 5 second delay to allow backend to process changes
        setTimeout(async () => {
            await viewDetailedVulnerabilitiesForRelease(selectedReleaseForModal.value)
        }, 3000)
    }
}

const releaseRowkey = (row: any) => row.uuid

// Watch pagination changes and sync to route query parameters
watch(currentPage, (newPage) => {
    router.push({
        query: { ...route.query, branchReleasePage: newPage.toString() }
    })
})

watch(perPage, (newPerPage) => {
    router.push({
        query: { ...route.query, branchReleasePerPage: newPerPage.toString(), branchReleasePage: '1' }
    })
    currentPage.value = 1
})

onCreated()

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.branchSettingsActions {
    padding: 15px;
    background-color: #f5f5f5;
    border-radius: 8px;
}

:deep(.marked-for-deletion) {
    text-decoration: line-through;
    opacity: 0.5;
    color: #d03050 !important;
}

:deep(.newly-added) {
    background-color: #e8f5e9 !important;
    font-style: italic;
    border-left: 4px solid #4caf50 !important;
    box-shadow: inset 0 0 0 1px #c8e6c9 !important;
}
.versionSchemaBlock, .versionMetadataBlock, .branchNameBlock, .branchTypeBlock, .linkedVcsRepoBlock, .vcsBranchBlock, .autoIntegrateBlock {
    padding-top: 15px;
    padding-bottom: 15px;
    display: flex;
    flex-wrap: nowrap;
    align-items: center;
    gap: 12px;
    
    label {
        font-weight: bold;
        min-width: 250px;
        flex-shrink: 0;
    }
    input {
        flex: 1;
        min-width: 0;
    }
    .n-input {
        flex: 1;
        min-width: 200px;
        
        :deep(.n-input-wrapper) {
            padding-left: 12px;
            padding-right: 12px;
        }
        :deep(.n-input__input-el) {
            padding: 0;
        }
    }
    .n-select {
        flex: 1;
        min-width: 200px;
    }
    select {
        flex: 1;
        min-width: 200px;
    }
    span {
        flex: 1;
    }
    .versionIcon {
        flex-shrink: 0;
    }
    .accept {
        color: green;
    }
    .accept:hover {
        color: darkgreen;
    }
    .reject {
        color: red;
    }
    .reject:hover {
        color: darkred;
    }
}
.linkedVcsRepoBlock {
    margin-bottom: 10px;
}
.releaseList {
    display: grid;
    grid-template-columns: 1fr 200px 100px;
    border-radius: 6px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
    .releaseDetails {
        grid-column: 1/5;
        overflow-wrap: break-word;
        .releaseDetailContent div {
           border: none;
        }
        .editReleaseIcon {
            float: right;
        }
        .releaseDetailsInnerHeader {
            margin-top: 7px;
            font-weight: bold;
        }
    }
}
.releaseList:hover {
    background-color: #d9eef3;
}
.releaseHeader {
    background-color: #f9dddd;
    font-weight: bold;
}
.branchControls {
    display: grid;
    grid-template-columns: 1fr 30px;
}
.componentRequirements {
    display: grid;
    grid-template-columns: 500px 120px 70px;
}

.componentRequirements:hover {
    background-color: #d9eef3;
}
:deep(.n-data-table-th--selection) {
    .n-checkbox-box-wrapper{
        visibility: hidden;
    }
}
.charts {
    display: grid;
}
</style>