<template>
    <div class="createComponentGlobal">
        <div v-if="!props.isHideTitle">Create {{ componentProductWords.componentFirstUpper }}</div>
        <n-form
            ref="createComponentForm"
            :model="component"
            :rules="rules">
            <n-form-item    path="name"
                            label="Name">
                <n-input
                            v-model:value="component.name"
                            required
                            :placeholder="'Enter ' + componentProductWords.component + ' name'" />
            </n-form-item>
            <n-form-item    path="defaultBranch"
                            v-if="!isProduct"
                            :label="'Default ' + componentProductWords.branch">
                <n-select
                            v-model:value="component.defaultBranch"
                            :placeholder="'Enter ' + componentProductWords.component + ' default branch'"
                            :options="defaultBranchNames" />
            </n-form-item>
            <n-form-item    path="versionSchema"
                            label="Version Schema">
                <n-select
                            v-model:value="component.versionSchema"
                            tag
                            filterable
                            :placeholder="'Enter ' + componentProductWords.component + ' version schema'"
                            :options="constants.VersionTypes" />
                <n-input
                            v-if="component.versionSchema === 'custom_version'"
                            v-model:value="customVersionSchema"
                            placeholder="Custom Version Schema" />
            </n-form-item>
            <n-form-item    v-if="myUser.installationType !== 'OSS'"
                            path="marketingversionSchema"
                            label="Marketing Version Schema">
                <n-switch v-model:value="marketingVersionEnabled"  @update:value="toggleMarketingVersion"/>
                <n-select
                            v-if="marketingVersionEnabled"
                            v-model:value="component.marketingVersionSchema"
                            :placeholder="'Enter ' + componentProductWords.component + ' marketing version schema'"
                            :options="constants.VersionTypes" />
                <n-input
                            v-if="component.marketingVersionSchema === 'custom_version' && marketingVersionEnabled"
                            v-model:value="customMarketingVersion"
                            placeholder="Custom Version Schema" />
            </n-form-item>
            <n-form-item    path="featureBranchVersioning"
                            v-if="!isProduct"
                            label="Feature Branch Version Schema">
                <n-select
                            v-model:value="component.featureBranchVersioning"
                            placeholder="Enter version schema for feature branches"
                            :options="featureBranchVersionTypes" />
                <n-input
                            v-if="component.featureBranchVersioning === 'custom_version'"
                            v-model:value="customFeatureBranchVersioning"
                            placeholder="Custom Feature Branch Version Schema" />
            </n-form-item>
            <n-form-item    path="identifiers"
                            label="Identifiers">
                <n-dynamic-input v-model:value="component.identifiers" :on-create="onCreateIdentifier">
                    <template #create-button-default>
                        Add Identifier
                    </template>
                    <template #default="{ value }">
                        <n-select style="width: 200px;" v-model:value="value.idType"
                            :options="[{label: 'PURL', value: 'PURL'}, {label: 'TEI', value: 'TEI'}, {label: 'CPE', value: 'CPE'}]" />
                        <n-input type="text" v-model:value="value.idValue" placeholder="Enter identifier value" />
                    </template>
                </n-dynamic-input>
            </n-form-item>
            <n-form-item    path="vcs"
                            v-if="!isProduct"
                            label="Select VCS Repo">
                <n-select
                            v-on:update:value="value => {resolveCreateRepoStatus(value)}"
                            v-model:value="component.vcs"
                            :options="vcsRepos" />
            </n-form-item>
            <n-form-item v-if="!isProduct && isCreateNewRepo"
                        label="Create New VCS Repository">
                <create-vcs-repository
                                        v-if="isCreateNewRepo"
                                        @createdVcsRepo="createdVcsRepo"
                                        :orguuid="props.orgProp" />
            </n-form-item>
            <div v-if="!isCreateNewRepo">
                <n-button type="success" @click="onSubmit">Submit</n-button>
                <n-button type="warning" @click="onReset">Reset</n-button>
            </div>
        </n-form>
    </div>
</template>

<script lang="ts">
export default {
    name: 'CreateComponent'
}
</script>
<script lang="ts" setup>
import { Ref, ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { FormInst, NForm, NFormItem, NInput, NButton, NSelect, NSwitch, NDynamicInput } from 'naive-ui'
import CreateVcsRepository from '@/components/CreateVcsRepository.vue'
import constants from '@/utils/constants'

const props = defineProps<{
    orgProp: string,
    isProduct: Boolean,
    isHideTitle: Boolean
}>()
const emit = defineEmits(['componentCreated'])

const store = useStore()

const createComponentForm = ref<FormInst | null>(null)

const customFeatureBranchVersioning = ref('')
const customMarketingVersion = ref('')
const customVersionSchema = ref('')

const defaultBranchNames = [
    { label: 'main', value: 'main' },
    { label: 'master', value: 'master' }
]

const isCreateNewRepo:ComputedRef<boolean> = computed((): any => {
    return (!isCreatedNewRepo.value && component.value.vcs === 'add_new_repo')
})

const isCreatedNewRepo = ref(false)

const myUser = store.getters.myuser

const featureBranchVersionTypes = [
    {
        label: 'Feature Branch Default (Branch.Micro)',
        value: 'Branch.Micro'
    },
    {
        label: 'Feature Branch Calver (YYYY.0M.Branch.Micro)',
        value: 'YYYY.0M.Branch.Micro'
    },
    {
        label: 'Custom',
        value: 'custom_version'
    }
]

const marketingVersionEnabled = ref(false)


const toggleMarketingVersion = async function(value: boolean){
    if(value){
        marketingVersionEnabled.value = true
        component.value.versionType = 'MARKETING'
    }
    else{
        component.value.versionType = 'DEV'
        marketingVersionEnabled.value = false
    }
        
}

const onSubmit = async function () {
    createComponentForm.value?.validate((errors) => {
        if (!errors) {
            onSubmitSuccess()
        }
    })
}

const onSubmitSuccess = async function () {
    if (component.value.versionSchema === 'custom_version') {
        component.value.versionSchema = customVersionSchema.value
    }
    if (component.value.marketingVersionSchema === 'custom_version') {
        component.value.marketingVersionSchema = customMarketingVersion.value
    }
    if (component.value.featureBranchVersioning === 'custom_version') {
        component.value.featureBranchVersioning = customFeatureBranchVersioning.value
    }
    const storeResp = await store.dispatch('createComponent', component.value)
    emit('componentCreated', storeResp.uuid)
    onReset()
}

const onReset = function () {
    component.value = {
        defaultBranch: '',
        featureBranchVersioning: '',
        name: '',
        org: props.orgProp,
        type: props.isProduct ? 'PRODUCT' : 'COMPONENT',
        vcs: '',
        versionSchema: '',
        versionType: 'DEV',
        marketingVersionSchema: '',
        identifiers: []
    }
}

const component = ref({
    defaultBranch: '',
    featureBranchVersioning: '',
    name: '',
    org: props.orgProp ? props.orgProp : '',
    type: props.isProduct ? 'PRODUCT' : 'COMPONENT',
    vcs: '',
    versionSchema: '',
    versionType: 'DEV',
    marketingVersionSchema: '',
    identifiers: []
})

const componentProductWords = {
    componentFirstUpper: (!props.isProduct) ? 'Component' : 'Product',
    component: (!props.isProduct) ? 'component' : 'product',
    componentsFirstUpper: (!props.isProduct) ? 'Components' : 'Products',
    branch: (!props.isProduct) ? 'Branch' : 'Feature Set'
}

const resolveCreateRepoStatus = function (repoId: string) {
    isCreatedNewRepo.value = !(repoId === 'add_new_repo')
}

const rules = {
    name: {
        required: true,
        message: 'Name is required'
    },
    org: {
        required: true,
        message: 'Organization is required'
    },
    versionSchema: {
        required: true,
        message: 'Version schema is required'
    },
    featureBranchVersioning: {
        required: true,
        message: 'Feature branch version schema is required'
    }
}

const vcsRepos: ComputedRef<any> = computed((): any => {
    const storeVcs = store.getters.vcsReposByOrg(props.orgProp)
    const retMap = storeVcs.map((repo: any) => {
        const repoObj = {
            label: repo.name,
            value: repo.uuid
        }
        return repoObj
    })
    retMap.push({
        label: 'Add new repository',
        value: 'add_new_repo'
    })
    return retMap
})

const createdVcsRepo = async function (repoValue: string) {
    isCreatedNewRepo.value = true
    await store.dispatch('fetchVcsRepos', props.orgProp)
    component.value.vcs = repoValue
}

function onCreateIdentifier () {
    return {
        idType: '',
        idValue: ''
    }
}

store.dispatch('fetchVcsRepos', props.orgProp)


</script>

<style scoped lang="scss">
.createComponentGlobal {
    margin-left: 20px;
}
</style>