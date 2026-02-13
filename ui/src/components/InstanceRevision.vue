<template>
    <div class="container">
        <div class="instanceView">
            <h5 v-if="updatedInstance">Instance: {{ updatedInstance.uri }}, Revision: {{ (props.revision === -1) ? 'Live' : props.revision }}
                <a :href="'/api/manual/v1/instanceRevision/cyclonedxExport/' + instanceUuid + '/' + props.revision" target="_blank" rel="noopener noreferrer">
                    <n-icon class="clickable icons" title="Show as CycloneDX JSON" size="20"><Download /></n-icon>
                </a>
            </h5>
            <div v-if="updatedInstance" class="settingsBlock">
                <h6>Environment: {{ updatedInstance.environment }}</h6>
                <h6>Deployment Type: {{ updatedInstance.deploymentType }}</h6>
            </div>
            <div>Agent Data: <n-icon class="ml-1 clickable" @click="showAgentDataModal = true" title="Show Agent Data" size="20"><InfoCircle /></n-icon></div>
            <n-modal
                v-model:show="showAgentDataModal"
                preset="dialog"
                :show-icon="false"
                style="width: 90%;"
                title="Instance Agent Data"
            >
                <div><pre style="white-space: pre-wrap;"> {{ updatedInstance.agentData }} </pre></div>
            </n-modal>
            <div v-if="updatedInstance" class="matchedProductBlock">
                <h5 class="mt-2">Product Releases:</h5>
                <div :class="[updatedInstance.deploymentType !== 'INDIVIDUAL' ? 'productHeader productList' : 'productHeader productListI']">
                    <div>Product</div>
                    <div>{{ featureSetLabel }}</div>
                    <div>Actual</div>
                    <div v-if="updatedInstance.deploymentType !== 'INDIVIDUAL'">Target</div>
                    <div>Namespace</div>
                    <div>Integrate?</div>
                </div>
                <div v-if="updatedInstance && updatedInstance.products && !updatedInstance.products.length">No products mapped.</div>
                <div v-for="prl in diffedProducts"
                    :class="[updatedInstance.deploymentType !== 'INDIVIDUAL' ? 'productList' : 'productListI', (prl.diff) ? 'releaseDiff' : '']"
                    :key="updatedInstance.uuid + revision + prl.uuid">
                    <div>
                        <router-link :to="{ name: 'ProductsOfOrg',
                            params: {orguuid: updatedInstance.org, compuuid: prl.featureSetDetails.componentDetails.uuid }}">
                            {{ prl.featureSetDetails.componentDetails.name }}
                        </router-link>
                    </div>
                    <div>
                        <router-link :to="{ name: 'ProductsOfOrg', params: {orguuid: updatedInstance.org,
                            compuuid: prl.featureSetDetails.componentDetails.uuid, branchuuid: prl.featureSet }}">
                            {{ prl.featureSetDetails.name }}
                        </router-link>
                    </div>
                    <div>
                        <span v-if="prl.matchedRelease">
                            <a href="#" @click="$event => {$event.preventDefault(); selectedReleaseUuid = prl.matchedRelease; showReleaseViewModal = true; }" class="clickable">{{ prl.matchedReleaseDetails.version }}</a>
                        </span>
                        <span v-else>Not matched</span>
                    </div>
                    <div v-if="updatedInstance.deploymentType !== 'INDIVIDUAL'">
                        <span v-if="prl.type !== 'INTEGRATE'">
                            <span v-if="prl.targetRelease && prl.targetReleaseDetails.version">
                                <a href="#" @click="$event => {$event.preventDefault(); selectedReleaseUuid = prl.targetRelease; showReleaseViewModal = true; }" class="clickable">{{ prl.targetReleaseDetails.version }}</a>
                            </span>
                            <span v-else>Not Set</span>
                        </span>
                        <span v-else>Not Applicable</span>
                    </div>
                    <div>{{ prl.namespace }}</div>
                    <div>{{ prl.type }}</div>
                </div>
            </div>
            <div v-if="updatedInstance" class="instanceReleaseBlock">
                <h5 class="mt-2">Deployed Component Releases:
                    <n-dropdown title="Select Namespace" trigger="hover" :options="namespacesForDropdown" @select="$key => {selectedNamespace = $key}">
                    <span>
                            <span>{{ selectedNamespace ? selectedNamespace : 'Filter By Namespace' }}</span>
                            <Icon><CaretDownFilled/></Icon>
                        </span>
                    </n-dropdown>
                </h5>
                <div class="releaseHeader releaseList">
                    <div>Component</div>
                    <div>Version</div>
                    <div>Artifact</div>
                    <div>Namespace</div>
                    <div>State</div>
                </div>
                <div v-if="updatedInstance && updatedInstance.releases && !updatedInstance.releases.length">No releases found for this instance.</div>
                <div v-for="drl in deployedReleases"
                    :class="(drl.diff) ? 'releaseList releaseDiff' : 'releaseList'"
                    :key="drl.release.uuid">
                    <div>
                        <router-link :to="{ name: 'ComponentsOfOrg', params: {orguuid: updatedInstance.org, compuuid: drl.componentUuid }}">{{ drl.component }}</router-link>
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
                                        @click="copyToClipboard(drl.artifact.identifier + (drl.artifact.digests.length ?  '@' + drl.artifact.digests[0] : ''))"
                                        size="20"><Box /></n-icon>
                                </template>
                                {{ drl.artifact.identifier + (drl.artifact.digests.length ?  '@' + drl.artifact.digests[0] : '') }}
                            </n-tooltip>
                        </span>
                        <span v-else>Not Set</span>
                    </div>
                    <div>{{ drl.namespace }}</div>
                    <div>{{ drl.state }}</div>
                </div>
            </div>
            <div v-if="updatedInstance" class="instancePropertiesBlock">
                <h5 class="mt-2">Instance Properties:</h5>
                <div class="propertyHeader propertyList">
                    <div>Property Key</div>
                    <div>Data Type</div>
                    <div>Namespace</div>
                    <div>Product</div>
                    <div>Value</div>
                </div>
                <div v-if="updatedInstance && updatedInstance.properties && !updatedInstance.properties.length">No properties set for this instance.</div>
                <div v-for="prop in updatedInstance.properties"
                    class="propertyList"
                    :key="prop.uuid + prop.namespace + updatedInstance.revision">
                    <div>{{ prop.property ? prop.property.name : ''}}</div>
                    <div>{{ prop.property ? prop.property.dataType : '' }}</div>
                    <div>{{ prop.namespace }}</div>
                    <div>{{ prop.productDetails && prop.productDetails.name ? prop.productDetails.name : '' }}</div>
                    <div>
                        <n-ellipsis style="max-width: 240px">
                            <span>{{ prop.value }}</span>
                        </n-ellipsis>
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
                                v-if="updatedInstance"
                                :uuidprop="selectedReleaseUuid" @closeRelease="showReleaseViewModal=false"/>
            </n-modal>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'InstanceRevision'
}
</script>
<script lang="ts" setup>
import { ref, Ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { NDropdown, NModal, NTooltip, NEllipsis, NIcon, useNotification, NotificationType } from 'naive-ui'
import { Download, InfoCircle, GitCommit, ExternalLink, Box } from '@vicons/tabler'
import { CaretDownFilled } from '@vicons/antd'
import { Icon } from '@vicons/utils'
import ReleaseView from '@/components/ReleaseView.vue'

import commonFunctions from '@/utils/commonFunctions'

const store = useStore()
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const featureSetLabel = computed(() => myorg.value?.terminology?.featureSetLabel || 'Feature Set')

const props = defineProps<{
    instanceUuid: String,
    revision: Number,
    otherInstanceUuid: String,
    otherRevision: Number,
    otherRevisionType: String,
    namespace: String
}>()
const notification = useNotification()

const updatedInstance: Ref<any> = ref({})
const showAgentDataModal = ref(false)
const showReleaseViewModal = ref(false)

const selectedNamespace = ref('')
if (props.namespace) selectedNamespace.value = props.namespace
const selectedReleaseUuid = ref('')

const mapping: Ref<any> = ref({})

const deployedReleases: ComputedRef<any> = computed((): any => {
    let deployedRls: any[] = []
    if (updatedInstance.value && updatedInstance.value.releases && updatedInstance.value.releases.length) {
        updatedInstance.value.releases.forEach((rl: any, index: number) => {
            if (!selectedNamespace.value || selectedNamespace.value === 'ALL' || selectedNamespace.value === rl.namespace) {
                let deployedRl = store.getters.releaseById(rl.release)
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
                        let otherInstance = store.getters.instanceById(props.otherInstanceUuid, props.otherRevision)
                        if (otherInstance && otherInstance.uuid) {
                            if (!otherInstance.releases.length) {
                                dRlObj.diff = true
                            } else {
                                let matchingRelease = otherInstance.releases.filter((x: any) => x.release === rl.release &&
                                    x.artifact === rl.artifact && x.namespace === rl.namespace && x.state === rl.state)
                                if (!matchingRelease || !matchingRelease.length) dRlObj.diff = true
                            }
                        }
                    } else if (props.otherRevisionType === 'release') {
                        const otherRelease = store.getters.releaseById(props.otherInstanceUuid)
                        if (otherRelease && otherRelease.uuid) {
                            if (!otherRelease.parentReleases || !otherRelease.parentReleases.length) {
                                dRlObj.diff = true
                            } else {
                                const matchingRelease = otherRelease.parentReleases.filter((x: any) => x.release === rl.release)
                                if (!matchingRelease || !matchingRelease.length) dRlObj.diff = true
                            }
                        }
                    }

                    deployedRls.push(dRlObj)
                }
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
    if (updatedInstance.value.products && updatedInstance.value.products.length) {
        diffedProducts = updatedInstance.value.products.slice()
        // add diff - check if other revision has this release
        const otherInstance = store.getters.instanceById(props.otherInstanceUuid, props.otherRevision)
        if (otherInstance && otherInstance.uuid) {
            if (!otherInstance.products || !otherInstance.products.length) {
                diffedProducts.forEach((pr: any) => {
                    pr.diff = true
                })
            } else {
                diffedProducts.forEach((pr: any) => {
                    let matchingRelease = otherInstance.products.filter((x: any) => x.matchedRelease === pr.matchedRelease &&
                        x.targetRelease === pr.targetRelease && x.namespace === pr.namespace)
                    if (!matchingRelease || !matchingRelease.length) pr.diff = true
                })
            }
        }
    }
    return diffedProducts
})

const namespacesForDropdown: ComputedRef<any[]> = computed((): any => {
    let retNs: any[] = []
    const namespaces = new Set()
    namespaces.add('ALL')
    namespaces.add('default')
    if (updatedInstance.value && updatedInstance.value.releases && updatedInstance.value.releases.length) {
        updatedInstance.value.releases.forEach((dr: any) => {
            namespaces.add(dr.namespace)
        })
    }
    if (namespaces.size) {
        retNs = Array.from(namespaces).map(n => {return {key: n, label: n}})
    }
    return retNs
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
    const storeResp = await store.dispatch('fetchInstance', { id: props.instanceUuid, revision: props.revision })
    updatedInstance.value = commonFunctions.deepCopy(storeResp)
    const fetchRlzParams = {
        org: updatedInstance.value.org,
        releases: updatedInstance.value.releases.map((rl: any) => rl.release)
    }
    const rlzWithMapping = await store.dispatch('fetchReleasesByOrgUuids', fetchRlzParams)
    mapping.value = rlzWithMapping.mapping
}

await onCreate()

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.instanceView {
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
    grid-template-columns: repeat(2, 1fr) 90px 100px 185px;
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
    grid-template-columns: 0.6fr 95px 135px 100px 1fr;
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
    grid-template-columns: 1fr 0.7fr repeat(2, 0.6fr) 100px;
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