<template>
    <div class="addComponentBranchGlobal">
        <!-- The parent organization is always the current org (there is no
             way to link a product from another org), so it is not a field. -->
        <n-form ref="featureSetForm" :model="featureSetObj" :rules="rules">
            <n-form-item
                        v-if="!props.productUuid"
                        path="product"
                        label="Parent Product">
                <n-select
                            v-on:update:value="value => {onComponentChange(value)}"
                            v-model:value="featureSetObj.product"
                            filterable
                            placeholder="Select or type to filter products"
                            :options="products"
                            data-testid="link-fs-product" />
            </n-form-item>
            <n-form-item    path="featureSet"
                            v-if="featureSetObj.product"
                            :label="myorg?.terminology?.featureSetLabel || 'Feature Set'">
                <n-select
                            v-model:value="featureSetObj.featureSet"
                            filterable
                            :options="branches" />
            </n-form-item>
            <n-form-item
                        v-if="!props.productUuid"
                        path="type"
                        label="Integration Type">
                <n-select
                    v-model:value="featureSetObj.type"
                    placeholder="Select integration type"
                    data-testid="link-fs-type"
                    :options="deploymentType === 'INDIVIDUAL' ? [{value: 'INTEGRATE', label: 'INTEGRATE'}, {value: 'NONE', label: 'NONE'}] : [{value: 'INTEGRATE', label: 'INTEGRATE'}, {value: 'TARGET', label: 'TARGET'}, {value: 'FOLLOW', label: 'FOLLOW'}, {value: 'NONE', label: 'NONE'}]" />
            </n-form-item>
            <n-form-item
                        v-if="!props.productUuid && !props.namespace"
                        path="namespace"
                        label="Namespace">
                <n-input
                        placeholder="Enter namespace, defaults to 'default' if left blank"
                        v-model:value="featureSetObj.namespace" />
            </n-form-item>
            <n-form-item
                            v-if="!props.productUuid"
                            path="configuration"
                            label="Extra Configuration (optional, e.g. Helm values file)">
                <n-select
                        tag
                        filterable
                        clearable
                        placeholder="Optional - leave empty for none"
                        :options='[{value: "default", label: "default"}, {value: "values-reliza.yaml", label: "values-reliza.yaml"}]'
                        v-model:value="featureSetObj.configuration" />
            </n-form-item>
            <!-- The per-product Alerts switch is intentionally not shown: alerts
                 are not wired to anything yet. alertsEnabled stays in the
                 emitted object (always false) so the update input is unchanged. -->
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
import { FormInst, FormRules, NButton, NForm, NFormItem, NInput, NSelect } from 'naive-ui'

const props = defineProps<{
    orgProp: String,
    instanceUuid: String,
    productUuid: String,
    namespace: String,
}>()
const emit = defineEmits(['addedComponentBranch'])

const store = useStore()
const myorg: ComputedRef<any> = computed((): any => store.getters.myorg)
// Always the current org: a product can only be linked from the org the
// instance belongs to.
const org = ref(props.orgProp ? props.orgProp : myorg.value)
const featureSetForm = ref<FormInst | null>(null)

// Parent product and integration type are what the backend needs to create
// the mapping; everything else on the form has a default.
const rules: FormRules = {
    product: { required: true, message: 'Parent product is required', trigger: ['blur', 'change'] },
    type: { required: true, message: 'Integration type is required', trigger: ['blur', 'change'] }
}

const products: ComputedRef<any> = computed((): any => {
    const storeComponents = store.getters.productsOfOrg(org.value)
    return storeComponents.map((proj: any) => {
        const projObj = {
            label: proj.name,
            value: proj.uuid
        }
        return projObj
    }).sort((a: any, b: any) => a.label.localeCompare(b.label))
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

const onComponentChange = function (componentId: string) {
    featureSetObj.value.featureSet = ''
    // Force a network refresh so the dropdown reflects feature sets created /
    // renamed since the last cached fetch. Triggered both by switching the
    // parent product and by the initial setup when this modal opens to edit
    // an existing integration.
    store.dispatch('fetchBranches', { componentId, forceRefresh: true })
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
    // Editing an existing link (productUuid set) only exposes the feature
    // set, so the product/type rules do not apply there.
    if (props.productUuid) {
        emit('addedComponentBranch', featureSetObj.value)
        onReset()
        return
    }
    // validate() also returns a promise that rejects with the same errors
    // the callback receives; the callback is the handler, so swallow the
    // rejection instead of surfacing an unhandled one.
    featureSetForm.value?.validate((errors) => {
        if (!errors) {
            emit('addedComponentBranch', featureSetObj.value)
            onReset()
        }
    }).catch(() => {})
}

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