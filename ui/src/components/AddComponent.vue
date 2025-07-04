<template>
    <div class="addComponentBranchGlobal">
        <n-form :model="addComponentObject">

            <n-form-item
                        v-if="!org || props.addExtOrg"
                        label="Parent Organization" >
                <n-select
                        v-model:value="org"
                        v-on:update:value="onOrgChange"
                        :options="props.addExtOrg ? orgWithExt : orgs" />
            </n-form-item>
            <n-form-item    path="addComponentObject.uuid"
                            :label="'Parent ' + words.componentFirstUpper">
                <n-select
                            v-on:update:value="value => {onComponentChange(value)}"
                            v-model:value="addComponentObject.uuid"
                            filterable
                            required
                            :options="components" />
            </n-form-item>
            <n-form-item    path="addComponentObject.branch"
                            v-if="addComponentObject.uuid"
                            :label="'Parent ' + words.componentFirstUpper + ' ' + words.branchFirstUpper  + ' (Optional, used for Auto-Integrate)'">
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
import { NButton, NForm, NFormItem, NSelect } from 'naive-ui'
import constants from '../utils/constants'


const props = defineProps<{
    orgProp: String,
    inputType?: String,
    addExtOrg: Boolean,
    inputProj?: String,
    inputBranch?: String,
    inputStatus?: String,
    inputRelease?: String
}>()

const emit = defineEmits(['addedComponent'])

const store = useStore()
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const initialOrg = props.orgProp ? props.orgProp : myorg.value
const org = ref(initialOrg)
const componentProduct = props.inputType ? props.inputType : 'COMPONENT'
const words: Ref<any> = ref({})
words.value = {
    branchFirstUpper: (componentProduct === 'COMPONENT') ? 'Branch' : 'Feature Set',
    branch: (componentProduct === 'COMPONENT') ? 'branch' : 'feature set',
    componentFirstUpper: (componentProduct === 'COMPONENT') ? 'Component' : 'Product',
    component: (componentProduct === 'COMPONENT') ? 'component' : 'product',
    componentsFirstUpper: (componentProduct === 'COMPONENT') ? 'Components' : 'Products'
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
        const extOrgObj = {
            label: 'External Public Components',
            value: constants.ExternalPublicComponentsOrg
        }
        orgWithExt.push(extOrgObj)
    }
    return orgWithExt
})

const components: ComputedRef<any> = computed((): any => {
    let storeComponents: any[]
    if (componentProduct === 'COMPONENT' && org.value !== constants.ExternalPublicComponentsOrg) {
        storeComponents = store.getters.componentsOfOrg(org.value)
    } else if(componentProduct === 'PRODUCT') {
        storeComponents = store.getters.productsOfOrg(org.value)
    } else {
        storeComponents = store.getters.externalComponents()
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

const onReset = function () {
    addComponentObject.value = {
        uuid: '',
        status: 'REQUIRED',
        branch: '',
        release: ''
    }
}

const onSubmit = function () {
    emit('addedComponent', addComponentObject.value)
    onReset()
}

store.dispatch('fetchComponents', constants.ExternalPublicComponentsOrg)
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