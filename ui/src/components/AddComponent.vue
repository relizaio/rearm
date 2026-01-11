<template>
    <div class="addComponentBranchGlobal">
        <n-form ref="formRef" :model="addComponentObject" :rules="formRules">

            <n-form-item
                        v-if="!org || props.addExtOrg"
                        label="Parent Organization" >
                <n-select
                        v-model:value="org"
                        v-on:update:value="onOrgChange"
                        :options="props.addExtOrg ? orgWithExt : orgs" />
            </n-form-item>
            <n-form-item    path="uuid"
                            :label="'Parent ' + words.componentFirstUpper">
                <n-select
                            v-on:update:value="value => {onComponentChange(value)}"
                            v-model:value="addComponentObject.uuid"
                            filterable
                            :options="components" />
            </n-form-item>
            <n-form-item    path="branch"
                            v-if="addComponentObject.uuid"
                            :label="'Parent ' + words.componentFirstUpper + ' ' + words.branchFirstUpper  + (props.requireBranch ? ' (Required for Auto-Integrate)' : ' (Optional, used for Auto-Integrate)')">
                <n-select
                            v-on:update:value="value => {onBranchChange(value)}"
                            v-model:value="addComponentObject.branch"
                            filterable
                            :options="branches" />
            </n-form-item>
            <n-form-item    path="addComponentObject.release"
                            v-if="addComponentObject.branch"
                            label="Pinned Release For Auto-Integrate (Optional)">
                <n-select
                            filterable
                            clearable
                            v-model:value="addComponentObject.release"
                            :options="releases" />
            </n-form-item>
            <n-form-item v-if="false"  path="addComponentObject.status"
                            :label="words.componentFirstUpper + ' Requirement Status'">
                <n-select
                                v-model:value="addComponentObject.status"
                                required
                                :options="permissionOptions" />
            </n-form-item>
            <n-button type="success" @click="onSubmit">Submit</n-button>
            <n-button type="warning" @click="onReset">Reset</n-button>
        </n-form>

    </div>
</template>
<script lang="ts">
export default {
    name: 'AddComponent'
}
</script>
<script lang="ts" setup>
import { useStore } from 'vuex'
import {  ComputedRef, Ref, computed, ref } from 'vue'
import { NButton, NForm, NFormItem, NSelect, FormInst, FormRules } from 'naive-ui'
import constants from '../utils/constants'
import commonFunctions from '../utils/commonFunctions'


const props = defineProps<{
    orgProp: string,
    inputType?: string,
    addExtOrg: boolean,
    inputProj?: string,
    inputBranch?: string,
    inputStatus?: string,
    inputRelease?: string,
    requireBranch?: boolean
}>()

const emit = defineEmits(['addedComponent'])

const store = useStore()
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const initialOrg = props.orgProp ? props.orgProp : myorg.value
const org = ref(initialOrg)
const componentProduct = props.inputType ? props.inputType : 'COMPONENT'
const orgTerminology = myorg.value?.terminology
const resolvedWords = commonFunctions.resolveWords(componentProduct === 'COMPONENT', orgTerminology)
const words: Ref<any> = ref({})
words.value = {
    branchFirstUpper: resolvedWords.branchFirstUpper,
    branch: resolvedWords.branch,
    componentFirstUpper: resolvedWords.componentFirstUpper,
    component: resolvedWords.component,
    componentsFirstUpper: resolvedWords.componentsFirstUpper
}
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

const orgWithExt: ComputedRef<any> = computed((): any => {
    const orgWithExt = []
    if (props.addExtOrg) {
        const storeOrgs = store.getters.allOrganizations
        storeOrgs.forEach((so: any) => {
            if (so.uuid === initialOrg) {
                const orgObj = {
                    label: so.name,
                    value: so.uuid
                }
                orgWithExt.push(orgObj)
            }
        })
    }
    return orgWithExt
})

const components: ComputedRef<any> = computed((): any => {
    let storeComponents: any[] = []
    if (componentProduct === 'COMPONENT' && org.value !== constants.ExternalPublicComponentsOrg) {
        storeComponents = store.getters.componentsOfOrg(org.value)
    } else if(componentProduct === 'PRODUCT') {
        storeComponents = store.getters.productsOfOrg(org.value)
    }

    if (storeComponents) {
        storeComponents.sort((a: any, b: any) => {
            if (a.name < b.name) {
                return -1
            } else if (a.name > b.name) {
                return 1
            } else {
                return 0
            }
        })
    }
    
    
    return storeComponents.map((proj: any) => {
        const projObj = {
            label: proj.name,
            value: proj.uuid
        }
        return projObj
    })
})

const products: ComputedRef<any> = computed((): any => {
    let storeComponents: any[]
    
    storeComponents = store.getters.productsOfOrg(org.value)

    if (storeComponents) {
        storeComponents.sort((a: any, b: any) => {
            if (a.name < b.name) {
                return -1
            } else if (a.name > b.name) {
                return 1
            } else {
                return 0
            }
        })
    }
    
    return storeComponents.map((proj: any) => {
        const projObj = {
            label: proj.name,
            value: proj.uuid
        }
        return projObj
    })
})

const branches: ComputedRef<any> = computed((): any => {
    let branches = []
    const compuuid = addComponentObject.value.uuid
    if (compuuid) {
        const storeBranches = store.getters.branchesOfComponent(compuuid)
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

const releases: ComputedRef<any> = computed((): any => {
    let releases = []
    const branchuuid = addComponentObject.value.branch
    if (branchuuid) {
        let storeReleases = store.getters.releasesOfBranch(branchuuid)
        releases = storeReleases.map((r: any) => {
            let rObj = {
                label: r.version,
                value: r.uuid
            }
            return rObj
        })
    }
    return releases
})

const onOrgChange = function () {
    if (org.value !== constants.ExternalPublicComponentsOrg) {
        store.dispatch('fetchProducts', org.value)
        store.dispatch('fetchComponents', org.value)
    }
    addComponentObject.value.uuid = ''
}

const onComponentChange = function (componentId: string) {
    addComponentObject.value.branch = ''
    store.dispatch('fetchBranches', componentId)
}

const onBranchChange = function (branchId: string) {
    addComponentObject.value.release = ''
    store.dispatch('fetchReleases', { branch: branchId })
}

const addComponentObject = ref({
    uuid: '',
    status: 'REQUIRED',
    branch: '',
    release: ''
})

const permissionOptions = [
    { label: 'Required', value: 'REQUIRED' },
    { label: 'Transient', value: 'TRANSIENT'},
    { label: 'Ignored', value: 'IGNORED'}
]

const formRef = ref<FormInst | null>(null)

const formRules: FormRules = {
    uuid: {
        required: true,
        message: 'Please select a component',
        trigger: ['change']
    },
    branch: {
        required: props.requireBranch,
        message: 'Please select a branch. Branch is required for auto-integrate functionality.',
        trigger: ['change']
    }
}

const onReset = function () {
    addComponentObject.value = {
        uuid: '',
        status: 'REQUIRED',
        branch: '',
        release: ''
    }
}

const onSubmit = function () {
    formRef.value?.validate((errors) => {
        if (!errors) {
            emit('addedComponent', addComponentObject.value)
            onReset()
        }
    })
}

if (!org.value) {
    await store.dispatch('fetchMyOrganizations')
} else {
    await store.dispatch('fetchComponents', org.value)
}

if (props.inputProj) {
    // if supplied but not present, assume external org
    let projs = store.getters.componentsOfOrg(org.value)
    let projIncl = projs.filter((p: any) => (p.uuid === props.inputProj))
    if (!projIncl || !projIncl.length) {
        org.value = constants.ExternalPublicComponentsOrg
        onOrgChange()
    }
    addComponentObject.value.uuid = props.inputProj
    onComponentChange(props.inputProj)
}
if (props.inputBranch) {
    addComponentObject.value.branch = props.inputBranch
    onBranchChange(props.inputBranch)
}
if (props.inputStatus) addComponentObject.value.status = props.inputStatus
if (props.inputRelease) addComponentObject.value.release = props.inputRelease


        

</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.addComponentBranchGlobal {
    margin-left: 20px;
}

</style>