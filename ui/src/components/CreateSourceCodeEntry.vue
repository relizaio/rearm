<template>
    <div class="createSourceCodeEntry">
        <h5>Create Source Code Release Entry</h5>
        <n-form
            ref="createSceForm"
            :model="sce"
            :rules="rules">
            <n-form-item v-if="!props.inputOrgUuid"
                            label="Parent Organization">
                <n-select
                            v-on:update:value="value => onOrgChange(value)"
                            v-model:value="orguuid"
                            :options="orgs" />
            </n-form-item>
            <n-form-item v-if="!props.inputBranch"
                        label="Parent Component">
                <n-select
                        v-on:update:value="value => onProjChange(value)"
                        v-model:value="compuuid"
                        :options="components" />
            </n-form-item>
            <n-form-item
                        path="branch" 
                        v-if="!props.inputBranch"
                        label="Parent Branch">
                <n-select
                        v-model:value="sce.branch"
                        :options="branches" />
            </n-form-item>
            <div v-if="sce.branch && !(branch && branch.vcs)">
                <p>Please link VCS repository to this branch first</p>
                <link-vcs
                            :branchUuid="sce.branch"
                            :orgprop="orguuid"
                            @linkVcsRepo="linkVcsRepo"

                />
            </div>
            <n-form-item
                        path="commit"
                        label="Commit Identifier (i.e. Git's SHA-1)">
                <n-input
                        v-model:value="sce.commit"
                        placeholder="Enter source code entry commit" />
            </n-form-item>
            <n-form-item
                        path="vcsTag"
                        label="VCS Tag">
                <n-input
                        v-model:value="sce.vcsTag"
                        placeholder="Enter VCS tag or its URI of your source code entry" />
            </n-form-item>
            <n-form-item
                        path="notes"
                        label="Notes">
                <n-input
                        type="textarea"
                        v-model:value="sce.notes"/>
            </n-form-item>
            <n-button type="success" @click="onSubmit">Add Source Code Entry</n-button>
            <n-button type="warning" @click="onReset">Reset Source Code Entry Input</n-button>
        </n-form>
    </div>
</template>

<script lang="ts">
export default {
    name: 'CreateSourceCodeEntry'
}
</script>
<script lang="ts" setup>
import { ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { FormInst, NForm, NFormItem, NInput, NSelect, NButton } from 'naive-ui'
import LinkVcs from '@/components/LinkVcs.vue'
import commonFunctions from '@/utils/commonFunctions'

const props = defineProps<{
    inputOrgUuid: String,
    inputBranch: String
}>()

const emit = defineEmits(['updateSce'])

const store = useStore()

const orguuid = ref(props.inputOrgUuid ? props.inputOrgUuid : '')
const compuuid = ref('')

const createSceForm = ref<FormInst | null>(null)
const rules = {
    branch: {
        required: true,
        message: 'Branch is required'
    },
    commit: {
        required: true,
        message: 'Commit is required'
    }
}
const sce = ref({
    branch: props.inputBranch ? props.inputBranch : '',
    commit: '',
    vcs: '',
    vcsBranch: '',
    vcsTag: '',
    notes: '',
    organizationUuid: orguuid.value
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

const branch: ComputedRef<any> = computed((): any => {
    let branch = null
    if (sce.value.branch) {
        branch = store.getters.branchById(sce.value.branch)
    }
    return branch
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


const linkVcsRepo = function (value: any) {
    const modifiedBranch = commonFunctions.deepCopy(branch.value)
    modifiedBranch.vcs = value.repo
    modifiedBranch.vcsBranch = value.branch
    store.dispatch('updateBranch', modifiedBranch)
}

const onReset = function () {
    sce.value = {
        branch: props.inputBranch ? props.inputBranch : '',
        commit: '',
        vcs: '',
        vcsBranch: '',
        vcsTag: '',
        notes: '',
        organizationUuid: orguuid.value
    }
}

const onSubmit = function () {
    createSceForm.value?.validate((errors) => {
        if (!errors) {
            sce.value.vcs = branch.value.vcs
            sce.value.vcsBranch = branch.value.vcsBranch
            store.dispatch('createSourceCodeEntry', sce.value).then(response => {
                emit('updateSce', response.uuid)
            })
        }
    })
}

const onOrgChange = function (orgId: string) {
    store.dispatch('fetchComponents', orgId)
}

const onProjChange = function (componentId: string) {
    store.dispatch('fetchBranches', componentId)
    store.dispatch('fetchVcsRepos', orguuid.value)
}

</script>

<style scoped lang="scss">
.createSourceCodeEntry {
    width: 95%;
    margin-left: 20px;
}
</style>