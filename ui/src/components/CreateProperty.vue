<template>
    <div class="createPropertyGlobal">
        <div>Create Property</div>
        <n-form
            ref="createPropertyForm"
            :model="property"
            :rules="rules">
            <n-form-item
                        path="org"
                        v-if="!props.orgProp"
                        label="Parent Organization">
                <n-select
                        required
                        v-model:value="property.org"
                        :options="orgs" />
            </n-form-item>
            <n-form-item
                        path="uuid"
                        v-if="property.org"
                        label="Property Key">
                <n-select
                        required
                        v-model:value="property.uuid"
                        :options="properties" 
                        @update:value="handlePropertySelection"
                        />
            </n-form-item>
            <n-form-item
                            path="name"
                            v-if="property.uuid === 'add_new_property'"
                            label="Name or key for this new property">
                <n-input
                            v-model:value="property.name"
                            required
                            placeholder="Enter key or name" />
            </n-form-item>
            <n-form-item
                            path="dataType"
                            v-if="property.uuid === 'add_new_property'"
                            label="Data Type">
                <n-select
                        v-model:value="property.dataType"
                        required
                        :options="dataTypes"
                        />
            </n-form-item>
            <n-form-item path="namespace" :label="nsLabel">
                <n-select v-if="props.instanceType === InstanceType.STANDALONE_INSTANCE" 
                            v-model:value="property.namespace"
                            :options="namespaceOptions"
                            tag
                            filterable
                            data-testid="create-property-namespace" />
                <n-input v-else-if="props.instanceType === InstanceType.CLUSTER_INSTANCE" :disabled="true" :placeholder="props.reservedNs"></n-input>
                <n-input v-else :disabled="true" placeholder="CLUSTER--WIDE"></n-input>
            </n-form-item>
            <n-form-item v-if="props.instanceType !== InstanceType.CLUSTER" path="product" :label="productLabel">
                <n-select
                            v-model:value="property.product"
                            filterable
                            :options="productOptions"
                            data-testid="create-property-product" />
            </n-form-item>
            <n-form-item path="value" label="Value" v-if="property.dataType === 'JSON' || property.dataType === 'YAML'">
                <prism-editor class="editor" v-model="property.value" :highlight="highlighter" line-numbers></prism-editor>
            </n-form-item>
            <n-form-item path="value" label="Value" v-else>
                <n-input type="textarea" v-model:value="property.value" placeholder="Enter property value" />
            </n-form-item>

            <n-button type="success" @click="onSubmit">Submit</n-button>
            <n-button type="warning" @click="onReset">Reset</n-button>
        </n-form>
    </div>
</template>

<script lang="ts">
export default {
    name: 'CreateProperty'
}
</script>
<script lang="ts" setup>
import { ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { FormInst, NForm, NFormItem, NInput, NButton, NSelect, useNotification, NotificationType } from 'naive-ui'
import { PrismEditor } from 'vue-prism-editor';
import 'vue-prism-editor/dist/prismeditor.min.css';
import * as prism from 'prismjs';
import 'prismjs/components/prism-yaml';
import 'prismjs/components/prism-json';
import 'prismjs/themes/prism-tomorrow.css';
import constants from '@/utils/constants'

const props = defineProps<{
    orgProp: String,
    // The instance's product plans: { namespace, featureSetDetails.componentDetails{uuid,name} }.
    // Products are offered per namespace from these.
    productPlans: Array,
    knownNamespaces: Array,
    instProperties: Array,
    instanceType: String,
    reservedNs: String
}>()

const emit = defineEmits(['createdProperty'])


const store = useStore()
const InstanceType = constants.InstanceType
const createPropertyForm = ref<FormInst | null>(null)
const nsLabel: ComputedRef<string> = computed((): any => {
    let label = 'Namespace (defaults to cluster wide if empty)'
    if(props.instanceType === InstanceType.CLUSTER)
        label = 'Namespace (defaults to cluster wide for cluster)'
    else if(props.instanceType === InstanceType.CLUSTER_INSTANCE)
        label = 'Namespace (reserved namespace for the instance will be used)'
    else if(productScopesNamespaces.value)
        label = `Namespace (where ${selectedProductName.value} is deployed)`
    return label
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

const property = ref({
    uuid: '',
    org: props.orgProp ? props.orgProp : '',
    targetType: 'INSTANCE',
    dataType: '',
    name: '',
    namespace: props.reservedNs,
    product: '',
    value: ''
})

// A property targets a product in a namespace, and on a standalone instance
// (the one type that spans namespaces) the two must be a pair the instance
// actually deploys. The lists scope each other -- pick a namespace and only
// products deployed there are offered; pick a product and only its
// namespaces are offered -- and pairValidator rejects anything else (the
// namespace select accepts typed values, so the lists alone are not enough).
// No namespace (cluster-wide) or no product leaves the other side unscoped.
const isStandalone: ComputedRef<boolean> = computed((): boolean =>
    props.instanceType === InstanceType.STANDALONE_INSTANCE)
const plans = (): any[] => (props.productPlans as any[]) || []
const planProductUuid = (plan: any): string =>
    (plan.featureSetDetails && plan.featureSetDetails.componentDetails && plan.featureSetDetails.componentDetails.uuid) || ''
const planProductName = (plan: any): string =>
    (plan.featureSetDetails && plan.featureSetDetails.componentDetails && plan.featureSetDetails.componentDetails.name) || ''
const namespaceScopesProducts: ComputedRef<boolean> = computed((): boolean => isStandalone.value && !!property.value.namespace)
const productScopesNamespaces: ComputedRef<boolean> = computed((): boolean => isStandalone.value && !!property.value.product)
const selectedProductName: ComputedRef<string> = computed((): string => {
    const plan = plans().find((pl: any) => planProductUuid(pl) === property.value.product)
    return plan ? planProductName(plan) : ''
})
const productOptions: ComputedRef<any[]> = computed((): any[] => {
    const byUuid: Record<string, any> = {}
    plans().forEach((plan: any) => {
        if (namespaceScopesProducts.value && plan.namespace !== property.value.namespace) return
        const uuid = planProductUuid(plan)
        if (uuid && !byUuid[uuid]) byUuid[uuid] = { label: planProductName(plan), value: uuid }
    })
    // Keep the current selection in the list even when the namespace no
    // longer includes it, so it still renders by name (pairValidator flags
    // it) instead of collapsing to a bare uuid.
    if (property.value.product && !byUuid[property.value.product]) {
        const plan = plans().find((pl: any) => planProductUuid(pl) === property.value.product)
        if (plan) byUuid[property.value.product] = { label: planProductName(plan), value: property.value.product }
    }
    const opts = Object.values(byUuid).sort((a: any, b: any) => a.label.localeCompare(b.label))
    opts.unshift({ label: '', value: '' })
    return opts
})
const namespaceOptions: ComputedRef<any[]> = computed((): any[] => {
    if (!productScopesNamespaces.value) return (props.knownNamespaces as any[]) || []
    const seen: Record<string, boolean> = {}
    const opts: any[] = []
    plans().forEach((plan: any) => {
        if (planProductUuid(plan) !== property.value.product || !plan.namespace || seen[plan.namespace]) return
        seen[plan.namespace] = true
        opts.push({ label: plan.namespace, value: plan.namespace })
    })
    opts.sort((a: any, b: any) => a.label.localeCompare(b.label))
    opts.unshift({ label: '', value: '' })
    return opts
})
const productLabel: ComputedRef<string> = computed((): string =>
    namespaceScopesProducts.value ? `Product (deployed in ${property.value.namespace})` : 'Product')
// The product/namespace pair must be one of the instance's product plans.
// Both fields validate it so either one turning invalid is flagged at once;
// the namespace side carries the full message (returning an Error is how
// naive-ui carries one) and the product side a short one, so the same
// sentence is not shown twice.
const pairDeployed = (): boolean => {
    if (!isStandalone.value || !property.value.product || !property.value.namespace) return true
    return plans().some((plan: any) =>
        planProductUuid(plan) === property.value.product && plan.namespace === property.value.namespace)
}
const pairValidator = (): boolean | Error => pairDeployed() ||
    new Error(`${selectedProductName.value || 'This product'} is not deployed in namespace ${property.value.namespace}`)
// Deliberately no auto-clearing when one side changes: a pair that is no
// longer deployed is reported by pairValidator next to the field, which is
// clearer than a selection silently disappearing.

const properties: ComputedRef<any> = computed((): any => {
    const storeProps = store.getters.propertiesOfOrg(property.value.org)
    const retProps = storeProps.map((prop: any) => {
        let propObj = {
            label: prop.name,
            value: prop.uuid
        }
        return propObj
    })
    retProps.push({
        label: 'Add New Property',
        value: 'add_new_property'
    })
    return retProps
})

const dataTypes = [
    {label: 'String', value: 'STRING'},
    {label: 'Integer', value: 'INTEGER'},
    {label: 'Boolean', value: 'BOOLEAN'},
    {label: 'JSON', value: 'JSON'},
    {label: 'YAML', value: 'YAML'}
]

const rules = {
    uuid: {
        required: true,
        message: 'Property Key is required'
    },
    org: {
        required: true,
        message: 'Organization is required'
    },
    value: {
        required: true,
        message: 'Property value is required'
    },
    namespace: { validator: pairValidator, trigger: ['blur', 'change'] },
    product: { validator: pairDeployed, message: 'Not deployed in the selected namespace', trigger: ['blur', 'change'] }
}

const onReset = function () {
    property.value = {
        uuid: '',
        org: props.orgProp ? props.orgProp : '',
        targetType: 'INSTANCE',
        dataType: '',
        name: '',
        namespace: props.reservedNs,
        product: '',
        value: ''
    }
}

const onSubmit = async function () {
    createPropertyForm.value?.validate((errors) => {
        if (!errors) {
            let propertyExitsts = props.instProperties.find((prop: any) => 
                prop.product === property.value.product && prop.namespace === property.value.namespace && prop.uuid === property.value.uuid
            )
            if(propertyExitsts !== undefined){
                notify('error', 'Duplicate Property !', `A property already exists on the instance with key: ${propertyExitsts.property.name} for product: ${propertyExitsts.productDetails.name} and namespace: ${propertyExitsts.namespace}`)
            }else{
                onSubmitSuccess()
            }
            
        }
    }).catch(() => {})
}
const notification = useNotification()

const notify = async function (type: NotificationType, title: string, content: string) {
    notification[type]({
        content: content,
        meta: title,
        duration: 3500,
        keepAliveOnHover: true
    })
}
const onSubmitSuccess = async function () {
    const propToReturn: any = {}
    propToReturn.value = property.value.value
    if (property.value.namespace) {
        propToReturn.namespace = property.value.namespace
    }
    if (property.value.product) {
        propToReturn.product = property.value.product
    }
    if (property.value.uuid === 'add_new_property') {
        const propInput = {
            org: property.value.org,
            name: property.value.name,
            targetType: 'INSTANCE',
            dataType: property.value.dataType
        }
        const createPropStoreResp = await store.dispatch('createProperty', propInput)
        propToReturn.uuid = createPropStoreResp.uuid
        emit('createdProperty', propToReturn)
        onReset()
    } else {
        propToReturn.uuid = property.value.uuid
        emit('createdProperty', propToReturn)
        onReset()
    }
}

if (!props.orgProp) {
    store.dispatch('fetchMyOrganizations')
} else {
    store.dispatch('fetchProperties', props.orgProp)
}

const highlighter = function (code: string) {
    const lang = property.value.dataType === 'JSON' || property.value.dataType === 'YAML' ? property.value.dataType.toLowerCase() : 'markup'
    return prism.highlight(code, prism.languages[lang], lang)
}

const handlePropertySelection = async function(propertyUuid: string){
    console.log('handlePropertySelection', propertyUuid)
    if(propertyUuid !== 'add_new_property'){
        let selectedProperty = store.getters.propertiesOfOrg(property.value.org).find((p:any) => p.uuid === propertyUuid)
        
        property.value.dataType = selectedProperty?.dataType ?? ''
        
    }else{
        property.value.dataType = ''
    }
    
}

</script>

<style scoped lang="scss">
.createPropertyGlobal {
    margin-left: 20px;
}

.editor {
    background: #fffefe;
    color: #3a3838;
    font-family: Fira code, Fira Mono, Consolas, Menlo, Courier, monospace;
    font-size: 14px;
    line-height: 1.5;
    padding: 5px;
  }
</style>