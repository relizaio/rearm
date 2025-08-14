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
                <n-upload v-model:value="fileList" @change="onFileChange">
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
                        :options="artifactTypes" />
            </n-form-item>
            <n-form-item 
                        v-if="artifact.type === 'BOM' || artifact.type === 'VEX' || artifact.type === 'VDR' || artifact.type === 'ATTESTATION'" 
                        path='bomFormat'
                        label='Bom Format'>
                        <n-select
                        v-model:value="artifact.bomFormat"
                        :options="bomFormats" />
            </n-form-item>
            <n-form-item
                        v-if="!commonFunctions.isCycloneDXBomArtifact(artifact)"
                        path="version"
                        label="Artifact Version">
                <n-input
                        v-model:value="artifact.version"
                        required
                        placeholder="Enter artifact version (Positive integer, optional, auto-generated otherwise)" />
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
                        <n-select :options="contentTypes" v-model:value="value.content" placeholder='select content type'/>
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
            <n-button type="success" @click="onSubmit">Add Artifact</n-button>
            <n-button type="warning" @click="onReset">Reset Artifact Input</n-button>
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
import { FormInst, NButton, NDynamicInput, NForm, NFormItem, NInput, NInputNumber, NRadioButton, NRadioGroup, NSelect, NTooltip, NUpload } from 'naive-ui'
import { computed, ComputedRef, ref, Ref } from 'vue'
import { useStore } from 'vuex'
import { Tag, DownloadLink } from '@/utils/commonTypes'
import Swal from 'sweetalert2'
import commonFunctions from '../utils/commonFunctions'
import constants from '@/utils/constants'


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

type DigestRecord = {
    algo: string,
    digest: string
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
})
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
    
    artifact.value.tags = artifactTags.value
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

    try{
        if(props.isUpdateExistingBom){
            const response = await graphqlClient.mutate({
                mutation: gql`
                    mutation addArtifactManual($artifactInput: CreateArtifactInput, $artifactUuid: ID!) {
                        addArtifactManual(artifactInput: $artifactInput, artifactUuid: $artifactUuid) {
                            uuid
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
            const response = await graphqlClient.mutate({
                mutation: gql`
                    mutation addArtifactManual($artifactInput: CreateArtifactInput) {
                        addArtifactManual(artifactInput: $artifactInput) {
                            uuid
                        }
                    }`,
                variables: {
                    'artifactInput': createArtifactInput
                },
                fetchPolicy: 'no-cache'
            })
            emit('addArtifact')
        }

    }   catch (err: any) {
        Swal.fire(
            'Error!',
            commonFunctions.parseGraphQLError(err.message),
            'error'
        )
    }
};
const onCreateDownloadLinks = () => {
    return {
        uri: '',
        content: ''
    }
}

const onCreateDigests = () => {
    return {
        algo: '',
        digest: ''
    } as DigestRecord
}
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

const onProjChange = function (componentId: string) {
    store.dispatch('fetchBranches', componentId)
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
}

// const onSubmit = function () {
//     createArtifactForm.value?.validate((errors) => {
//         if (!errors) {
            
//             const artifactInput: any = {}
//             artifactInput.release = props.inputRelease
//             artifactInput.component = props.inputComponent
//             artifactInput.artifacts = artifact.value
//             if (artifactInput.release) {
//                 store.dispatch('addArtifactManual', artifactInput).then(response => {
//                     emit('addArtifact')
//                 })
//             } else {
//                 //deprecated
//                 // store.dispatch('createArtifact', artifactInput).then(response => {
//                 //     emit('addArtifact', response)
//                 // })
//             }
//         }
//     })
// }


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
//axios.get('/api/manual/v1/artifact/getTypes')
const artifactTypes = artTypesResp.data.artifactTypes.map((t: string) => {
    return {
        label: t,
        value: t
    }
})

const bomFormats = [
    {value: 'CYCLONEDX', label: 'CYCLONEDX'},
    {value: 'SPDX', label: 'SPDX'},
]

const contentTypes = [
    {value: 'OCI', label: 'OCI'},
    {value: 'PLAIN_JSON', label: 'Plain JSON'},
    {value: 'OCTET_STREAM', label: 'Octet Stream'},
    {value: 'PLAIN_XML', label: 'Plain XML'},
]



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