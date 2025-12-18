<template>
    <div class="addComponentBranchGlobal">
        <n-form :model="featureSetObj">
            <n-form-item
                        v-if="!props.productUuid"
                        label="Parent Organization" >
                <n-select
                        v-model:value="org"
                        v-on:update:value="onOrgChange"
                        :options="orgWithExt" />
            </n-form-item>
            <n-form-item
                        v-if="!props.productUuid"
                        path="featureSetObj.product"
                        label="Parent Product">
                <n-select
                            v-on:update:value="value => {onComponentChange(value)}"
                            v-model:value="featureSetObj.product"
                            required
                            :options="products" />
            </n-form-item>
            <n-form-item    path="featureSetObj.featureSet"
                            v-if="featureSetObj.product"
                            :label="myorg?.terminology?.featureSetLabel || 'Feature Set'">
                <n-select
                            v-model:value="featureSetObj.featureSet"
                            filterable
                            :options="branches" />
            </n-form-item>
            <n-form-item
                        v-if="!props.productUuid"
                        path="featureSetObj.type"
                        label="Integration Type">
                <n-select
                    v-model:value="featureSetObj.type"
                    :options="deploymentType === 'INDIVIDUAL' ? [{value: 'INTEGRATE', label: 'INTEGRATE'}, {value: 'NONE', label: 'NONE'}] : [{value: 'INTEGRATE', label: 'INTEGRATE'}, {value: 'TARGET', label: 'TARGET'}, {value: 'FOLLOW', label: 'FOLLOW'}, {value: 'NONE', label: 'NONE'}]" />
            </n-form-item>
            <n-form-item
                        v-if="!props.productUuid && !props.namespace"
                        path="featureSetObj.namespace"
                        label="Namespace">
                <n-input
                        placeholder="Enter namespace, defaults to 'default' if left blank"
                        v-model:value="featureSetObj.namespace" />
            </n-form-item>
            <n-form-item
                            v-if="!props.productUuid"
                            path="featureSetObj.configuration"
                            label="Extra Configuration (i.e. Helm values file)">
                <n-select
                        tag
                        filterable
                        :options='[{value: "default", label: "default"}, {value: "values-reliza.yaml", label: "values-reliza.yaml"}]'
                        v-model:value="featureSetObj.configuration" />
            </n-form-item>
            <n-form-item
                v-if="!props.productUuid"
                path="featureSetObj.alertsEnabled"
                label="Alerts"
            >
                <n-switch v-model:value="featureSetObj.alertsEnabled" size="large">
                    <template #checked-icon>
                        <NIcon>
                            <AlertOn24Regular/>
                        </NIcon>

                    </template>
                    <template #unchecked-icon>
                        <NIcon>
                            <AlertOff24Regular/>
                        </NIcon>
                    </template>
                </n-switch>

            </n-form-item>
            <n-button type="success" @click="onSubmit">Submit</n-button>
            <n-button type="warning" @click="onReset">Reset</n-button>
        </n-form>

    </div>
</template>
<script lang="ts">
export default {
    name: 'AddComponentBranch'
}
</script>
<script lang="ts" setup>
import { useStore } from 'vuex'
import { ComputedRef, computed, ref } from 'vue'
import { NButton, NForm, NFormItem, NInput, NSelect, NSwitch, NIcon } from 'naive-ui'
import { AlertOff24Regular, AlertOn24Regular} from '@vicons/fluent'
import constants from '../utils/constants'

const props = defineProps<{
    orgProp: String,
    instanceUuid: String,
    productUuid: String,
    namespace: String,
}>()
const emit = defineEmits(['addedComponentBranch'])

const store = useStore()
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
const initialOrg = props.orgProp ? props.orgProp : myorg.value
const org = ref(initialOrg)
const orgWithExt: ComputedRef<any> = computed((): any => {
    const orgWithExt = []
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
        label: 'External Catalog',
        value: constants.ExternalPublicComponentsOrg
    }
    orgWithExt.push(extOrgObj)
    return orgWithExt
})

const products: ComputedRef<any> = computed((): any => {
    const storeComponents = store.getters.productsOfOrg(org.value)
    return storeComponents.map((proj: any) => {
        const projObj = {
            label: proj.name,
            value: proj.uuid
        }
        return projObj
    })
})

const deploymentType: ComputedRef<any> = computed((): any => store.getters.instanceById(props.instanceUuid, -1))

const branches: ComputedRef<any> = computed((): any => {
    let branches = []
    const compuuid = featureSetObj.value.product
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

const onOrgChange = function () {
    if (org.value !== constants.ExternalPublicComponentsOrg) {
        store.dispatch('fetchProducts', org.value)
    }
    featureSetObj.value.product = ''
    featureSetObj.value.featureSet = ''
}

const onComponentChange = function (componentId: string) {
    featureSetObj.value.featureSet = ''
    store.dispatch('fetchBranches', componentId)
}

const featureSetObj = ref({
    product: props.productUuid ? props.productUuid : '',
    featureSet: '',
    type: '',
    namespace: props.namespace,
    configuration: '',
    alertsEnabled: false
})

const onReset = function () {
    featureSetObj.value = {
        product: props.productUuid ? props.productUuid : '',
        featureSet: '',
        type: '',
        namespace: props.namespace,
        configuration: '',
        alertsEnabled: false
    }
}

const onSubmit = function () {
    emit('addedComponentBranch', featureSetObj.value)
    onReset()
}

store.dispatch('fetchProducts', constants.ExternalPublicComponentsOrg)
if (!org.value) {
    await store.dispatch('fetchMyOrganizations')
} else {
    await store.dispatch('fetchProducts', org.value)
}
if (props.productUuid) onComponentChange(props.productUuid)



</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped lang="scss">
.addComponentBranchGlobal {
    margin-left: 20px;
}

</style>