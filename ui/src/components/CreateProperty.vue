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
                            :options="props.knownNamespaces"
                            tag
                            filterable />
                <n-input v-else-if="props.instanceType === InstanceType.CLUSTER_INSTANCE" :disabled="true" :placeholder="props.reservedNs"></n-input>
                <n-input v-else :disabled="true" placeholder="CLUSTER--WIDE"></n-input>
            </n-form-item>
            <n-form-item v-if="props.instanceType !== InstanceType.CLUSTER" path="product" label="Product">
                <n-select
                            v-model:value="property.product"
                            :options="props.knownProducts" />
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
    knownProducts: Array,
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
    }
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
    })
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