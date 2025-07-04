<template>
    <div>
        <n-tabs
            class="card-tabs"
            default-value="maincreatereleasetab"
            size="large"
            animated
            style="margin: 0 -4px"
            :tab-style="props.disallowCreateRelease ? 'display: none;' : ''"
            pane-style="padding-left: 4px; padding-right: 4px; box-sizing: border-box;"
            @update:value="handleTabSwitch"
        >
            <n-tab-pane name="maincreatereleasetab" tab="General">
                <n-form>
                    <n-form-item
                                v-if="!props.orgProp"
                                label="Parent Organization">
                        <n-select
                            v-on:update:value="value => {onOrgChange(value)}"
                            v-model:value="release.org"
                            :options="orgs" />
                    </n-form-item>
                    <n-form-item
                                v-if="release.org && !props.inputType"
                                label="Select Component Type">
                        <n-radio-group v-model:value="componentProduct">
                            <n-radio label="Component" value="COMPONENT" />
                            <n-radio label="Product" value="PRODUCT" />
                        </n-radio-group>
                    </n-form-item>
                    <n-form-item
                        v-if="!props.inputComponent && !props.inputBranch && componentProduct"
                        :label="(componentProduct === 'PRODUCT') ? 'Parent Product' : 'Parent Component'">
                        <n-select
                            v-on:update:value="value=>{onComponentChange(value)}"
                            filterable
                            v-model:value="compuuid"
                            :options="(componentProduct === 'PRODUCT') ? products : components" />
                    </n-form-item>
                    <n-form-item
                        v-if="componentProduct && compuuid && !inputBranch"
                        :label="'Parent ' + branchName"
                        :description="'Enter ' + branchName + ' of your release'">
                        <n-select
                                id ="release_create_branch"
                                v-model:value="release.branch"
                                filterable
                                v-on:update:value="value => onBranchChange(value)"
                                :options="branches" />
                    </n-form-item>
                    <n-form-item
                                v-if="props.attemptPickRelease && release.branch"
                                label="Pick release">
                        <n-spin v-if="isReleasesLoading" size="small" />
                        <n-select
                                v-if="releaseCandidates && releaseCandidates.length"
                                v-model="release.uuid"
                                v-on:update:value="value => checkIfCreateNewRelease(value)"
                                :options="releaseCandidates" />
                    </n-form-item>
                    <n-form-item
                                v-if="isCreateNewRelease && release.branch"
                                :label="'Version' + (generatedReleaseVersion ? ' (auto-assigned version = ' + generatedReleaseVersion + ')' : '')" >
                        <n-input
                                v-model:value="release.version"
                                required
                                placeholder="Enter release version" />
                    </n-form-item>
                </n-form>
            </n-tab-pane>
            <n-tab-pane v-if="isCreateNewRelease && componentProduct === 'COMPONENT'" name="sourcecodecreatereleasetab" tab="Source Code">
                <div v-if="releaseBranch && !releaseBranch.vcs && !release.sourceCodeEntry">
                    <p>Please link VCS repository to this branch first</p>
                    <link-vcs
                        :branchUuid="release.branch"
                        @linkVcsRepo="linkVcsRepo"

                    />
                </div>
                <div
                    v-if="isSetSce && releaseBranch && releaseBranch.vcs">
                            <p> VCS repo: {{ vcs }}
                            <a :href="'http://' + vcs" target="_blank" rel="noopener noreferrer">
                                <vue-feather type="external-link" title="Open repository In New Tab" class="clickable icons" />
                            </a></p>
                            <p> VCS branch: {{ releaseBranch.vcsBranch }}</p>
                        </div>
                <create-source-code-entry v-if="isSetSce && release.branch && releaseBranch.vcs && !release.sourceCodeEntry"
                    @updateSce="updateSce"
                    :inputOrgUuid="release.org"
                    :inputBranch="release.branch" />
                <div v-if="release.sourceCodeEntry"> <b> To be added: </b> </div>
                    <div v-if="release.sourceCodeEntry">
                        <p> Source Code Entry : {{ release.sourceCodeEntry }} <a :href="linkifiedCommit" target="_blank" rel="noopener noreferrer">
                            <vue-feather type="external-link" title="Open Commit In New Tab" class="clickable icons" />
                        </a></p>
                    </div>
                <div v-show="release.sourceCodeEntry">
                    <p @click="clearSourceCodeEntry">x</p>
                </div>
            </n-tab-pane>
            <n-tab-pane v-if="isCreateNewRelease" name="artifactsreleasetab" tab="Artifacts">
                <h4>Create artifacts</h4>
                <vue-feather @click="isCreateArtifact = true" class="clickable" type="plus-circle" title="Add artifact" />
                <create-artifact
                                            v-if="isCreateArtifact"
                                            @addArtifact="addArtifact"
                                            :inputOrgUuid="release.org"
                                            :inputBranch="release.branch"/>
                <div v-if="release.artifacts.length > 0">
                    <p><b> Artifacts to be added: </b></p>
                    <li v-for="i in release.artifacts" :key="i"> {{ getArtifact(i) }} </li>
                </div>
            </n-tab-pane>
        </n-tabs>
        <div v-if="!isSetSce && !isCreateArtifact"> 
            <n-button type="success" @click="onSubmit" variant="primary">
                <span v-if="props.createButtonText">{{ props.createButtonText }}</span>
                <span v-else>Create Release</span>
            </n-button>
            <n-button v-if="!props.isHideReset" type="warning" @click="onReset" variant="danger">Reset Release Input</n-button>
        </div>
    </div>
</template>

<script lang="ts">
export default {
    name: 'CreateRelease'
}
</script>
<script lang="ts" setup>
import { Ref, ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { NForm, NFormItem, NInput, NSelect, NRadio, NRadioGroup, NSpin, NTabs, NTabPane, NButton } from 'naive-ui'
import constants from '../utils/constants'
import CreateArtifact from './CreateArtifact.vue'
import CreateSourceCodeEntry from '@/components/CreateSourceCodeEntry.vue'
import LinkVcs from './LinkVcs.vue'
import commonFunctions from '@/utils/commonFunctions'
import gql from 'graphql-tag'
import graphqlClient from '@/utils/graphql'


async function getGeneratedVersion (branchUuid: string): Promise<string> {
    const response = await graphqlClient.mutate({
        mutation: gql`
                mutation getNewVersionManual($branchUuid: ID!) {
                    getNewVersionManual(branchUuid: $branchUuid) {
                        version
                    }
                }`,
        variables: {branchUuid},
        fetchPolicy: 'no-cache'
    })
    return response.data.getNewVersionManual.version
}

const props = defineProps<{
    orgProp: String,
    inputBranch: String,
    inputType: String,
    inputFeatureSet?: String,
    inputComponent?: String,
    updateMode?: Boolean,
    attemptPickRelease?: Boolean,
    disallowPlaceholder: Boolean,
    disallowCreateRelease?: Boolean,
    isChooseNamespace?: Boolean,
    isHideReset?: Boolean,
    createButtonText?: String
}>()

const emit = defineEmits(['createdRelease'])


const store = useStore()

const componentProduct = ref(props.inputType)
const isCreateNewRelease = ref(!props.attemptPickRelease)
const generatedReleaseVersion = ref('')
const isReleasesLoading = ref(false)
const isSetSce = ref(false)
const isCreateArtifact = ref(false)

const compuuid = ref(props.inputComponent ? props.inputComponent : '')

const release = ref({
    version: '',
    branch: props.inputBranch ? props.inputBranch : '',
    artifacts: new Array<string>(),
    sourceCodeEntry: '',
    org: props.orgProp,
    uuid: '',
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

const products: ComputedRef<any> = computed((): any => {
    let products = []
    if (release.value.org) {
        const storeProducts = store.getters.productsOfOrg(release.value.org)
        products = storeProducts.map((sb: any) => {
            const productObj = {
                label: sb.name,
                value: sb.uuid
            }
            return productObj
        })
    }
    if (products) {
        products.sort((a: any, b: any) => {
            if (a.label < b.label) {
                return -1
            } else if (a.label > b.label) {
                return 1
            } else {
                return 0
            }
        })
    }
    return products
})

const components: ComputedRef<any> = computed((): any => {
    let projs = []
    if (release.value.org) {
        const storeComponents = store.getters.componentsOfOrg(release.value.org)
        projs = storeComponents.map((sp: any) => {
            const projObj = {
                label: sp.name,
                value: sp.uuid
            }
            return projObj
        })
    }
    if (projs) {
        projs.sort((a: any, b: any) => {
            if (a.label < b.label) {
                return -1
            } else if (a.label > b.label) {
                return 1
            } else {
                return 0
            }
        })
    }
    return projs
})

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

const releaseCandidates:Ref<any> = ref({})

const releaseBranch: ComputedRef<any> = computed((): any => {
    let rb = {}
    if (release.value.branch) {
        rb = store.getters.branchById(release.value.branch)
    }
    return rb
})

const branchName: ComputedRef<string> = computed((): any => {
    let branchName
    if (componentProduct.value === 'PRODUCT') {
        branchName = 'feature set'
    } else {
        branchName = 'branch'
    }
    return branchName
})

const sce: ComputedRef<any> = computed((): any => {
    let sceRet = {}
    if (release.value.sourceCodeEntry) {
        sceRet = store.getters.sourceCodeEntryById(release.value.sourceCodeEntry)
    }
    return sceRet
})

const vcs: ComputedRef<string> = computed((): any => {
    if (props.inputType !== 'PRODUCT' && typeof store.getters.vcsRepoById(releaseBranch.value.vcs) !== 'undefined') {
        return store.getters.vcsRepoById(releaseBranch.value.vcs)['uri']
    } else {
        return ''
    }
})

const linkifiedCommit: ComputedRef<string> = computed((): any => {
    let lc = ''
    if (vcs.value && sce.value && sce.value.commit) {
        lc = commonFunctions.linkifyCommit(vcs.value, sce.value.commit)
    }
    return lc
})


const addArtifact = function (artId: any) {
    release.value.artifacts.push(artId.uuid)
    isCreateArtifact.value = false
}

const getArtifact = function (uuid: string) {
    return store.getters.artifactById(uuid)['displayIdentifier']
}

const handleTabSwitch = function (tabName: string) {
    if (tabName === 'sourcecodecreatereleasetab') {
        isSetSce.value = true
    }
}

const linkVcsRepo = function (value: any) {
    const modifiedBranch = commonFunctions.deepCopy(releaseBranch.value)
    modifiedBranch.vcs = value.repo
    modifiedBranch.vcsBranch = value.branch
    store.dispatch('updateBranch', modifiedBranch)
}

const onOrgChange = function (changedOrg: string) {
    if (changedOrg !== constants.ExternalPublicComponentsOrg) {
        store.dispatch('fetchProducts', changedOrg)
        store.dispatch('fetchComponents', changedOrg)
        store.dispatch('fetchVcsRepos', changedOrg)
    }
    release.value.version = ''
    release.value.branch = ''
    release.value.artifacts = []
    release.value.sourceCodeEntry = ''
    release.value.uuid = ''
}

const onComponentChange = function (componentId: string) {
    store.dispatch('fetchBranches', componentId)
    release.value.version = ''
    release.value.branch = ''
    release.value.uuid = ''
}

const onBranchChange = async function (branchId: string) {
    isReleasesLoading.value = true
    // need to fetch releases here as store releases are unreliable in this case and also too bulky
    const branchRlzResponse = await graphqlClient.query({
        query: gql`
            query FetchReleases($branchID: ID!, $numRecords: Int) {
                releases(branchFilter: $branchID, numRecords: $numRecords) {
                    createdDate
                    uuid
                    version
                }
            }`,
        variables: { branchID: branchId, numRecords: 10000 },
        fetchPolicy: 'no-cache'
    })

    let srMap: any[] = []
    srMap = branchRlzResponse.data.releases.map((r: any) => {
        const rObj = {
            label: r.version + " - " + (new Date(r.createdDate)).toLocaleString('en-CA'),
            value: r.uuid
        }
        return rObj
    })
    if (!props.disallowCreateRelease) {
        srMap.push({
            label: 'Add new release',
            code: 'add_new_rlz'
        })
    }

    release.value.version = ''
    release.value.uuid = ''
    releaseCandidates.value = srMap
    isReleasesLoading.value = false
}

const checkIfCreateNewRelease = async function (value: string) {
    if (value === 'add_new_rlz') {
        isCreateNewRelease.value = true
        const generatedVersion = await getGeneratedVersion(release.value.branch)
        generatedReleaseVersion.value = generatedVersion
        release.value.version = generatedVersion
    } else {
        release.value.uuid = value
    }
}

const updateSce = function (sceId: string) {
    release.value.sourceCodeEntry = sceId
    isSetSce.value = false
}

const clearSourceCodeEntry = function () {
    release.value.sourceCodeEntry = ''
    isSetSce.value = false
}

const onReset = function () {
    release.value = {
        version: '',
        branch: props.inputBranch ? props.inputBranch : '',
        artifacts: new Array<string>(),
        sourceCodeEntry: '',
        org: props.orgProp,
        uuid: ''
    }
}

const onSubmit = async function () {
    let retRlz: any = {}
    if (isCreateNewRelease.value) {
        const createRlzResp = await store.dispatch('createRelease', release.value)
        if (createRlzResp.uuid) {
            retRlz = createRlzResp
        }
    } else if (release.value.uuid) {
        retRlz = release.value
    }
    emit('createdRelease', retRlz)
}


const onCreate = async function () {
    store.dispatch('fetchComponents', constants.ExternalPublicComponentsOrg)
    if (!release.value.org) {
        store.dispatch('fetchMyOrganizations')
    } else {
        store.dispatch('fetchComponents', release.value.org)
        store.dispatch('fetchProducts', release.value.org)
        store.dispatch('fetchVcsRepos', release.value.org)
    }

    if (props.inputComponent) {
        store.dispatch('fetchBranches', props.inputComponent)
    }
    if (props.inputBranch) {
        await onBranchChange(props.inputBranch)
        if (releaseBranch.value && releaseBranch.value.versionSchema && isCreateNewRelease.value) {
            generatedReleaseVersion.value = await getGeneratedVersion(releaseBranch.value.uuid)
            release.value.version = generatedReleaseVersion.value
        }
    }
}

onCreate()

</script>

<style scoped lang="scss">
.createReleaseGlobal {
    width: 500px;
    margin-left: 20px;
}
</style>