<template>
    <div class="container">
        <div class="instanceView" v-if="release && release.version">
            <h5 v-if="release">Release: {{release.componentDetails.name}}, version {{release.version}}
                <a :href="'/api/manual/v1/release/exportAsBom/' + releaseUuid" target="_blank" rel="noopener noreferrer">
                    <n-icon class="clickable icons" title="Show as CycloneDX JSON" size="20"><Download /></n-icon>
                </a>
            </h5>
            <div v-if="release" class="mb-3 settingsBlock">
                <h6>Parent Type: {{ release.componentDetails.type }}</h6>
                <h6>Release Type: {{ release.type }}</h6>
                <h6><span v-if="release.componentDetails.type === 'PRODUCT'">{{ featureSetLabel }}: </span><span v-else>Branch: </span> {{ release.branchDetails.name }}</h6>
            </div>
            <div v-if="release" class="matchedProductBlock">
                <h5 class="mt-2">Product For Comparison:</h5>
                <div class="productHeader productList">
                    <div>Product</div>
                    <div>{{ featureSetLabel }}</div>
                </div>
                <div v-if="release.componentDetails.type !== 'PRODUCT'">Not a product release.</div>
                <div v-for="prl in diffedProducts"
                    class="productList"
                    :key="releaseUuid + prl.uuid">
                    <div>
                        <router-link :to="{ name: 'ProductsOfOrg',
                            params: {orguuid: release.org, compuuid: prl.componentDetails.uuid }}">
                            {{ prl.componentDetails.name }}
                        </router-link>
                    </div>
                    <div>
                        <router-link :to="{ name: 'ProductsOfOrg', params: {orguuid: release.org,
                            compuuid: prl.componentDetails.uuid, branchuuid: prl.branchDetails.uuid }}">
                            {{ prl.branchDetails.name }}
                        </router-link>
                    </div>
                </div>
            </div>
            <div v-if="release" class="instanceReleaseBlock">
                <h5 class="mt-2">Component Components:</h5>
                <div class="releaseHeader releaseList">
                    <div>Component</div>
                    <div>Version</div>
                    <div>Artifact</div>
                </div>
                <div v-if="release && !deployedReleases.length">No components found in this release.</div>
                <div v-for="drl in deployedReleases"
                    :class="(drl.diff) ? 'releaseList releaseDiff' : 'releaseList'"
                    :key="drl.release.uuid">
                    <div>
                        <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: release.org, compuuid: drl.componentUuid }}">{{ drl.component }}</router-link>
                    </div>
                    <div>
                        <a href="#" @click="$event => {$event.preventDefault(); selectedReleaseUuid = drl.release.uuid; showReleaseViewModal = true; }" class="clickable">{{ drl.release.version }}</a>
                    </div>
                    <div>
                        <span v-if="drl.release.sourceCodeEntryDetails && drl.release.sourceCodeEntryDetails.commit">
                            <n-tooltip trigger="hover" class="artifactTooltip">
                                <template #trigger>
                                    <a v-if="linkifyCommit(drl.release.sourceCodeEntryDetails.vcsRepository.uri, drl.release.sourceCodeEntryDetails.commit)"
                                    :href="linkifyCommit(drl.release.sourceCodeEntryDetails.vcsRepository.uri, drl.release.sourceCodeEntryDetails.commit)"
                                    target="_blank" rel="noopener noreferrer">
                                        <n-icon :id="'sce' + drl.index" size="20"><GitCommit /></n-icon>
                                    </a>
                                </template>
                                <span>{{ linkifyCommit(drl.release.sourceCodeEntryDetails.vcsRepository.uri, drl.release.sourceCodeEntryDetails.commit) }}</span>
                                <a v-if="linkifyCommit(drl.release.sourceCodeEntryDetails.vcsRepository.uri, drl.release.sourceCodeEntryDetails.commit)"
                                    :href="linkifyCommit(drl.release.sourceCodeEntryDetails.vcsRepository.uri, drl.release.sourceCodeEntryDetails.commit)"
                                    target="_blank" rel="noopener noreferrer">
                                    <n-icon title="Open Commit In New Tab" class="clickable icons" size="20"><ExternalLink /></n-icon>
                                </a>
                            </n-tooltip>
                        </span>
                        <span v-if="drl.artifact !== 'Not Set'">
                            <n-tooltip trigger="hover" class="artifactTooltip">
                                <template #trigger>
                                    <n-icon
                                        class="clickable icons"
                                        @click="copyToClipboard(drl.artifact.displayIdentifier + (drl.artifact.digests.length ?  '@' + drl.artifact.digests[0] : ''))"
                                        size="20"><Box /></n-icon>
                                </template>
                                {{ drl.artifact.displayIdentifier + (drl.artifact.digests.length ?  '@' + drl.artifact.digests[0] : '') }}
                            </n-tooltip>
                        </span>
                        <span v-else>Not Set</span>
                    </div>
                </div>
            </div>
            <n-modal
                v-model:show="showReleaseViewModal"
                preset="dialog"
                :show-icon="false"
                style="width: 90%; min-height: 95vh;"
            >
                <release-view
                    v-if="release"
                    :uuidprop="selectedReleaseUuid" @closeRelease="showReleaseViewModal=false"/>
            </n-modal>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'ReleaseRevision'
}
</script>
<script lang="ts" setup>

import { ref, Ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { NModal, NTooltip, NIcon, useNotification, NotificationType } from 'naive-ui'
import { Download, GitCommit, ExternalLink, Box } from '@vicons/tabler'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'
import ReleaseView from '@/components/ReleaseView.vue'

import commonFunctions from '@/utils/commonFunctions'

// Slim queries used by the diff view. The full SingleReleaseGql /
// MultiReleaseGqlData fetches pulled in artifactDetails (with metrics/tags/
// enrichment), inboundDeliverableDetails, commitsDetails, inProducts,
// approvalEvents, updateEvents, variantDetails, metrics, releaseCollection,
// intermediateFailedReleases, and recursive parent payloads — none of which
// this template renders. For a product release with many components those
// payloads dominate the right-pane load time.
const RELEASE_DIFF_QUERY = gql`
    query FetchReleaseForDiff($releaseID: ID!, $orgID: ID) {
        release(releaseUuid: $releaseID, orgUuid: $orgID) {
            uuid
            version
            org
            type
            componentDetails { uuid name type resourceGroup }
            branchDetails { uuid name }
            parentReleases {
                release
                artifact
                type
                namespace
                state
            }
        }
    }
`

const PARENT_RELEASES_DIFF_QUERY = gql`
    query FetchParentReleasesForDiff($orgId: ID!, $releaseIds: [ID]) {
        releases(orgFilter: $orgId, releaseFilter: $releaseIds) {
            uuid
            version
            type
            componentDetails { uuid name type resourceGroup }
            branchDetails { uuid name }
            sourceCodeEntryDetails {
                commit
                vcsRepository { uri }
            }
            artifactDetails {
                uuid
                displayIdentifier
                digestRecords { algo digest }
            }
        }
    }
`

const props = defineProps<{
    releaseUuid: String,
    otherInstanceUuid: String,
    otherRevision: Number,
    otherRevisionType: String,
    orgprop?: String
}>()

const store = useStore()
const notification = useNotification()
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const featureSetLabel = computed(() => myorg.value?.terminology?.featureSetLabel || 'Feature Set')

const release: any = ref({})

// Parent releases for this diff view, keyed by release UUID. Kept local so the
// slim payload doesn't overwrite full release rows in the Vuex store that
// other components (ReleaseView, ReleaseEl, etc.) depend on.
const parentReleasesMap: Ref<Map<string, any>> = ref(new Map())

const showReleaseViewModal = ref(false)

const releaseUuid = ref('')
const selectedReleaseUuid = ref('')

const parentRlzLoaded = ref(false)

const deployedReleases: ComputedRef<any> = computed((): any => {
    let deployedRls: any[] = []
    if (release.value && release.value.parentReleases && release.value.parentReleases.length) {
        release.value.parentReleases.forEach((rl: any, index: number) => {
            let deployedRl = parentReleasesMap.value.get(rl.release)
            if(!deployedRl){
                deployedRl = store.getters.getProxyRelease(rl.release)
            }
            if (deployedRl) {
                let deployedArt
                if (deployedRl.artifactDetails && deployedRl.artifactDetails) {
                    let artArr = deployedRl.artifactDetails.filter((ad: any) => (ad.uuid === rl.artifact))
                    if (artArr && artArr.length) deployedArt = artArr[0]
                }
                let dRlObj = {
                    release: deployedRl,
                    artifact: deployedArt,
                    type: rl.type,
                    component: deployedRl.componentDetails.name,
                    componentUuid: deployedRl.componentDetails.uuid,
                    componentType: deployedRl.componentDetails.type,
                    index: index,
                    namespace: rl.namespace,
                    state: rl.state,
                    branch: (deployedRl.type !== 'PLACEHOLDER') ? deployedRl.branchDetails.name : undefined,
                    branchUuid: (deployedRl.type !== 'PLACEHOLDER') ? deployedRl.branchDetails.uuid : undefined,
                    diff: false
                }
                if (!dRlObj.artifact) {
                    dRlObj.artifact = 'Not Set'
                }
                // check if other revision has this release
                if (props.otherRevisionType === 'instance') {
                    const otherInstance = store.getters.instanceById(props.otherInstanceUuid, props.otherRevision)
                    if (otherInstance && otherInstance.uuid) {
                        if (!otherInstance.releases.length) {
                            dRlObj.diff = true
                        } else {
                            let matchingRelease = otherInstance.releases.filter((x: any) => x.release === rl.release)
                            if (!matchingRelease || !matchingRelease.length) dRlObj.diff = true
                        }
                    }
                } else if (props.otherRevisionType === 'release') {
                    const otherRelease = store.getters.releaseById(props.otherInstanceUuid)
                    if (otherRelease && otherRelease.uuid) {
                        if (!otherRelease.parentReleases || !otherRelease.parentReleases.length) {
                            dRlObj.diff = true
                        } else {
                            let matchingRelease = otherRelease.parentReleases.filter((x: any) => x.release === rl.release)
                            if (!matchingRelease || !matchingRelease.length) dRlObj.diff = true
                        }
                    }
                }

                deployedRls.push(dRlObj)
            }
        })
    }
    if (deployedRls) {
        deployedRls.sort((a, b) => {
            if (a.component < b.component) {
                return -1
            } else if (a.component > b.component) {
                return 1
            } else {
                return 0
            }
        })
    }
    return deployedRls
})

const diffedProducts: ComputedRef<any[]> = computed((): any => {
    let diffedProducts = []
    if (release.value && release.value.componentDetails.type === 'PRODUCT') {
        diffedProducts.push(Object.assign({}, release.value))
        // add diff - check if other revision has this release
        const otherInstance = store.getters.instanceById(props.otherInstanceUuid, props.otherRevision)
        if (otherInstance && otherInstance.uuid) {
            if (!otherInstance.productPlans || !otherInstance.productPlans.length) {
                diffedProducts.forEach(pr => {
                    pr.diff = true
                })
            } else {
                diffedProducts.forEach(pr => {
                    let matchingRelease = otherInstance.productPlans.filter((x: any) => x.targetRelease === pr.uuid)
                    if (!matchingRelease || !matchingRelease.length) pr.diff = true
                })
            }
        }
    }
    return diffedProducts
})

const copyToClipboard = async function (text: string) {
    try {
        navigator.clipboard.writeText(text);
        notify('info', 'Copied', `Copied: ${text}`)
    } catch (error) {
        console.error(error)
    }
}

const linkifyCommit = function (uri: string, commit: string) {
    return commonFunctions.linkifyCommit(uri, commit)
}

const notify = async function (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}

const onCreate = async function () {
    const variables: any = { releaseID: props.releaseUuid }
    if (props.orgprop) variables.orgID = props.orgprop
    const resp = await graphqlClient.query({
        query: RELEASE_DIFF_QUERY,
        variables,
        fetchPolicy: 'no-cache'
    })
    release.value = (resp.data as any)?.release || {}
    if (release.value && release.value.uuid) releaseUuid.value = release.value.uuid
    if (!release.value.type) release.value.type = 'REGULAR'

    if (release.value.parentReleases && release.value.parentReleases.length) {
        const releaseIds = release.value.parentReleases.map((rl: any) => rl.release)
        const parentsResp = await graphqlClient.query({
            query: PARENT_RELEASES_DIFF_QUERY,
            variables: { orgId: release.value.org, releaseIds },
            fetchPolicy: 'no-cache'
        })
        const map = new Map<string, any>()
        const rows = ((parentsResp.data as any)?.releases) || []
        rows.forEach((r: any) => map.set(r.uuid, r))
        parentReleasesMap.value = map
    }
    parentRlzLoaded.value = true
}

await onCreate()

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">.instanceView {
    margin-left: 2px;
}
.envBlock {
    margin-bottom: 10px;
    h6 {
        display: inline;
        margin-right: 10px;
    }
}
.releaseList {
    display: grid;
    grid-template-columns: repeat(2, 1fr) 90px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
    button {
        margin-right: 10px;
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
.propertyList {
    display: grid;
    grid-template-columns: 0.6fr 95px 135px 1fr 55px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}
.alert {
   display:inline-block;
}
.historyList {
    display: grid;
    grid-template-columns: 100px repeat(3, 1fr) 55px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}
.productList {
    display: grid;
    grid-template-columns: 1fr 0.7fr 0.6fr 0.6fr 100px 120px;
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}

.productListI {
    display: grid;
    grid-template-columns: 1fr 0.7fr repeat(2, 0.6fr);
    border-radius: 9px;
    div {
        border-style: solid;
        border-width: thin;
        border-color: #edf2f3;
        padding-left: 2px;
    }
}
.releaseList:hover, .propertyList:hover, .historyList:hover, .productList:hover {
    background-color: #d9eef3;
}
.releaseHeader, .propertyHeader, .historyHeader, .productHeader {
    background-color: #f9dddd;
    font-weight: bold;
}
.releaseDiff {
    background-color: #ff6c6c;
}
</style>