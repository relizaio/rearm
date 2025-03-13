<template>
    <div class="createMarketingReleaseGlobal">
        <div>Create Marketing Release</div>
        <n-form
            ref="createMarketingReleaseForm"
            :model="marketingRelease"
            :rules="rules">
            <n-form-item    path="version"
                            label="Version">
                <n-input
                            v-model:value="marketingRelease.version"
                            required
                            placeholder="Enter marketing version" />
            </n-form-item>
            <n-button type="success" @click="onSubmit">Submit</n-button>
            <n-button type="warning" @click="onReset">Reset</n-button>
        </n-form>
    </div>
</template>

<script lang="ts">
export default {
    name: 'CreateMarketingRelease'
}
</script>
<script lang="ts" setup>import { Ref, ref, ComputedRef, computed } from 'vue'
import { useStore } from 'vuex'
import { FormInst, NForm, NFormItem, NInput, NButton, NSelect, NSwitch } from 'naive-ui'

const props = defineProps<{
    orgProp: String,
    componentProp: String
}>()

const emit = defineEmits(['marketingReleaseCreated'])


const store = useStore()

const createMarketingReleaseForm = ref<FormInst | null>(null)

const onSubmit = async function () {
    createMarketingReleaseForm.value?.validate((errors) => {
        if (!errors) {
            onSubmitSuccess()
        }
    })
}

const onSubmitSuccess = async function () {
    const storeResp = await store.dispatch('createMarketingRelease', marketingRelease.value)
    emit('marketingReleaseCreated', storeResp.uuid)
    onReset()
}

const onReset = function () {
    marketingRelease.value = {
        version: '',
        org: props.orgProp,
        component: props.componentProp,
        notes: ''
    }
}

const marketingRelease = ref({
    version: '',
    org: props.orgProp,
    component: props.componentProp,
    notes: ''
})

const rules = {
    version: {
        required: true,
        message: 'Version is required'
    }
}

</script>

<style scoped lang="scss">
.createMarketingReleaseGlobal {
    margin-left: 20px;
}
</style>