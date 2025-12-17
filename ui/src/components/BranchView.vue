<template>
    <div class="branchView" v-if="modifiedBranch && branchData && modifiedBranch.name && branchData.name && branchData.componentDetails">
        <h5>{{ words.branchFirstUpper }}: {{ branchData.name }}</h5>
        <div class="branchControls">
            <div class="mainControls">
                <vue-feather v-if="isWritable" @click="showCreateReleaseModal = true" class="clickable" type="plus-circle" title="Add Release" />
                <vue-feather type="tool" @click="showBranchSettingsModal = true" class="icons clickable" :title="words.branchFirstUpper + ' Settings'" />
                <vue-feather type="arrow-right-circle" @click="openNextVersionModal" class="icons clickable" title='Set Next Version' />
            </div>
            <div class="dangerControls">
                <vue-feather v-if="isWritable" @click="archiveBranch" class="clickable" type="trash-2" :title="'Archive ' + words.branchFirstUpper" />
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
        >
            <div class="branchNameBlock">
                <label id="branchNameLabel" for="branchName">{{ words.branchFirstUpper }} Name</label>
                <n-input  v-if="isWritable" v-model:value="modifiedBranch.name" />
                <n-input v-if="!isWritable" type="text" :value="branchData.name" readonly/>
            </div>
            <div class="versionSchemaBlock">
                <label id="branchVersionSchemaLabel" for="branchVersionSchema">{{ words.branchFirstUpper }} Version Schema</label>
                <n-input v-if="isWritable" v-model:value="modifiedBranch.versionSchema" />
                <n-input v-else type="text" name="versionSchema" :value="modifiedBranch.versionSchema" readonly/>
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
                <h6><strong>Linked VCS Repository:</strong></h6>
                <div>
                    <n-select v-if="isWritable" :options="vcsRepos" v-model:value="modifiedBranch.vcs" @focus="fetchVcsRepos" />
                    <span v-if="!isWritable && modifiedBranch.vcsRepositoryDetails && modifiedBranch.vcsRepositoryDetails.uuid">{{ (modifiedBranch.vcsRepositoryDetails.name === modifiedBranch.vcsRepositoryDetails.uri) ? modifiedBranch.vcsRepositoryDetails.name : modifiedBranch.vcsRepositoryDetails.name + ' - ' + modifiedBranch.vcsRepositoryDetails.uri }}</span>
                    <span v-if="!isWritable && !modifiedBranch.vcsRepositoryDetails">Not Set</span>
                </div>
                <div class="vcsBranchBlock" v-if="modifiedBranch.vcsRepositoryDetails && modifiedBranch.vcsRepositoryDetails.uuid">
                    <label for="vcsBranch">VCS Branch</label>
                    <n-input v-if="isWritable" id="vcsBranch" v-model:value="modifiedBranch.vcsBranch" />
                    <n-input v-else type="text" id="vcsBranch" name="vcsBranch" :value="modifiedBranch.vcsBranch" readonly/>
                </div>
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
                <label id="autoIntegrateLabel" style="margin-right:10px;">Auto Integrate </label>
                <n-select v-if="isWritable" :options="[{label: 'ENABLED', value: 'ENABLED'}, {label: 'DISABLED', value: 'DISABLED'}]" v-model:value="modifiedBranch.autoIntegrate" />
                <n-input v-else type="text" :value="modifiedBranch.autoIntegrate" readonly/>
            </div>
            <div class="componentComponentsBlock mt-3" v-if="branchData.componentDetails.type === 'PRODUCT'">
                <div>
                    <p>
                        <strong>Dependency Requirements </strong>
                        <vue-feather v-if="isWritable" class="clickable" type="plus-circle"
                            @click="showAddComponentModal = true" title="Add Component Dependency Requirement" />
                        <vue-feather v-if="isWritable" class="clickable" type="folder-plus"
                            @click="showAddComponentProductModal = true" title="Add Product Dependency Requirement" />
                        <vue-feather v-if="false && isWritable" class="clickable" type="file-plus"
                            @click="showAddOssArtifactModal = true" title="Register open source artifact" />
                        <vue-feather v-if="isWritable && modifiedBranch.autoIntegrate === 'ENABLED'" class="clickable" type="trending-up"
                            @click="triggerAutoIntegrate" title="Trigger Auto Integrate" />
                    </p>
                    <n-data-table :data="modifiedBranch.dependencies" :columns="depTableFields" :row-key="releaseRowkey" />
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
            </div>
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
            :title="'Create Feature Set From Release - ' + cloneReleaseToFsObj.version"
            preset="dialog"
            :show-icon="false" >
            <n-form>
                <n-input
                    v-model:value="cloneReleaseToFsObj.fsName"
                    required
                    placeholder="Enter New Feature Set Name" 
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
import { ComputedRef, computed, Ref, ref, h } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { NButton, NCheckbox, NForm, NFormItem, NInput, NModal, NPagination, NPopover, NSelect, NotificationType, useNotification, SelectOption, NDataTable, NIcon, NSpace, NSpin, NTag, NTooltip, DataTableColumns} from 'naive-ui'
import AddComponent from './AddComponent.vue'
import CreateRelease from './CreateRelease.vue'
import ReleaseView from './ReleaseView.vue'
import SideBySide from './SideBySide.vue'
import LinkVcs from './LinkVcs.vue'
import VulnerabilityModal from './VulnerabilityModal.vue'
import commonFunctions from '../utils/commonFunctions'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import Swal from 'sweetalert2'
import { SwalData } from '@/utils/commonFunctions'
import graphqlQueries  from '@/utils/graphqlQueries'
import { Copy, LayoutColumns, Filter, Trash } from '@vicons/tabler'
import { Edit24Regular } from '@vicons/fluent'
import constants from '@/utils/constants'
import { ReleaseVulnerabilityService } from '@/utils/releaseVulnerabilityService'

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

const branchUuid: Ref<string> = ref(props.branchUuidProp ? props.branchUuidProp.toString() : route.params.branchuuid ? route.params.branchuuid.toString() : '')
const prNumber: Ref<string> = ref(props.prnumberprop ? props.prnumberprop.toString() : route.params.prnumber ? route.params.prnumber.toString() : '')

const isLinkVcsRepo = ref(false)

const showBranchSettingsModal: Ref<boolean> = ref(false)
const showSetNextVersionModal: Ref<boolean> = ref(false)
const showAddComponentModal: Ref<boolean> = ref(false)
const showAddComponentProductModal: Ref<boolean> = ref(false)
const showEditComponentModal: Ref<boolean> = ref(false)
const showAddOssArtifactModal: Ref<boolean> = ref(false)
const showCreateReleaseModal: Ref<boolean> = ref(false)
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
    await commonFunctions.swalWrapper(onSwalConfirm, swalData)
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
    notify('success', 'Success', 'Redirecting to new Feature Set')
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

const currentPage: Ref<number> = ref(1)
const perPage: Ref<number> = ref(25)

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

const permissionOptions = [
    {label: 'Required', value: 'REQUIRED'},
    {label: 'Transient', value: 'TRANSIENT'},
    {label: 'Ignored', value: 'IGNORED'}
]
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
        const storeResp = await store.dispatch('updateBranch', modifiedBranch.value)
        modifiedBranch.value = commonFunctions.deepCopy(storeResp)
        selectNewVcsRepo.value = false
    } catch (err) {
        notify('error', 'Error Saving Branch', String(err))
    }
}

const hasBranchSettingsChanges: ComputedRef<boolean> = computed((): boolean => {
    if (!modifiedBranch.value || !branchData.value) return false
    
    return modifiedBranch.value.name !== branchData.value.name ||
        modifiedBranch.value.versionSchema !== branchData.value.versionSchema ||
        modifiedBranch.value.marketingVersionSchema !== branchData.value.marketingVersionSchema ||
        modifiedBranch.value.metadata !== branchData.value.metadata ||
        modifiedBranch.value.type !== branchData.value.type ||
        modifiedBranch.value.vcs !== branchData.value.vcs ||
        modifiedBranch.value.vcsBranch !== branchData.value.vcsBranch ||
        modifiedBranch.value.autoIntegrate !== branchData.value.autoIntegrate
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
    selectNewVcsRepo.value = false
}

const vcsRepos: Ref<any[]> = ref([])
const approvalTypes: Ref<any[]> = ref([])
const environmentTypes: Ref<any[]> = ref([])
const releaseTagKeys: Ref<any[]> = ref([])

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

const addedComponent = function (component: any) {
    // validate that branch is selected
    if (!component.branch) {
        notify('error', 'Branch Required', 'Please select a branch for the dependency. Branch is required for auto-integrate functionality.')
        return
    }
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
    } else {
        modifiedBranch.value.dependencies.push(component)
        saveModifiedBranch()
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
    if (branch) {
        modifiedBranch.value.dependencies = modifiedBranch.value.dependencies.filter((p: any) => (p.branch !== branch))
        saveModifiedBranch()
    } else if (component) {
        modifiedBranch.value.dependencies = modifiedBranch.value.dependencies.filter((p: any) => (p.uuid !== component))
        saveModifiedBranch()
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

    words.value = commonFunctions.resolveWords(branchData.value.componentDetails.type === 'COMPONENT')
    await getNextVersion(branchUuid.value)
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
                                title: 'Create Feature Set From Release',
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

const depTableFields = [
    {
        key: "componentDetails.name",
        title: "Name"
    },
    {
        key: "branchDetails.name",
        title: "Branch"
    },
    {
        key: "releaseDetails.version",
        title: "Release"
    },
    {
        key: "status",
        title: "Requirement Status",
        render: (row: any) => {
            let els: any[] = []
            if (isWritable) {
                const selectEl = h(NSelect, {
                    options: permissionOptions,
                    defaultValue: row.status,
                    style: "width: 160px;",
                    'on-update:value': (value: string) => updateBranchDependecyStatus(row.uuid, value)
                })
                els.push(selectEl)
            }
            if (!els.length) els = [h('div'), row.status]
            return els
        }
    },
    {
        key: "isFollowVersion",
        title: "Follow Version?",
        render: (row: any) => {
            return h(NCheckbox, {
                checked: row.isFollowVersion,
                disabled: !isWritable,
                title: 'Following Dependency Version If Checked',
                size: 'large',
                onClick: (e: any, i: any) => {
                    e.preventDefault()
                    setDependencyAsFollowVersion(row.uuid)
                }
            })
        }
    },
    {
        key: 'actions',
        title: 'Actions',
        render: (row: any) => {
            let els: any[] = []
            if (isWritable) {
                const editEl = h(NIcon, {
                    title: 'Edit Dependency',
                    class: 'icons clickable',
                    size: 20,
                    onClick: () => {
                        editDependency(row.uuid, row.branch, row.status, row.release)
                    }
                }, 
                { 
                    default: () => h(Edit24Regular) 
                }
                )
                els.push(editEl)
                const deleteEl = h(NIcon, {
                    title: 'Delete Dependency',
                    class: 'icons clickable',
                    size: 20,
                    onClick: () => {
                        deleteDependency(row.uuid, row.branch)
                    }
                }, 
                { 
                    default: () => h(Trash) 
                }
                )
                els.push(deleteEl)
            }
            if (!els.length) els = [h('div'), row.status]
            return els
        }
    }
]

const releaseRowkey = (row: any) => row.uuid

onCreated()

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.branchSettingsActions {
    padding: 15px;
    background-color: #f5f5f5;
    border-radius: 8px;
}
.versionSchemaBlock, .versionMetadataBlock, .branchNameBlock, .branchTypeBlock {
    padding-bottom: 25px;
    label {
        display: block;
        font-weight: bold;
    }
    input {
        display: inline-block;
        width: 85%;
    }
    .versionIcon {
        float: right;
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
    .n-input {
        display: inline-block;
        width: 60%;
    }
    .n-select {
        display: inline-block;
        width: 60%;
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
</style>