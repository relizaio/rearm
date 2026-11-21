<template>
    <n-modal :show="show" preset="card" title="Imported asset" style="max-width: 760px;"
        @update:show="(v: boolean) => emit('update:show', v)">
        <n-spin :show="loading">
            <n-text depth="3" style="font-size: 12px; display: block; margin-bottom: 8px;">
                The asset entry of the uploaded CycloneDX 2.0 document this level came from.
            </n-text>
            <pre class="safety-source">{{ pretty }}</pre>
        </n-spin>
    </n-modal>
</template>

<script lang="ts">
export default {
    name: 'SafetyContextSourceModal'
}
</script>
<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { NModal, NSpin, NText } from 'naive-ui'
import Swal from 'sweetalert2'
import graphqlClient from '@/utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { GET_SAFETY_SOURCE_ASSERTION } from '@/graphql/safetyContext'

const props = defineProps<{ show: boolean, uuid: string | null }>()
const emit = defineEmits(['update:show'])
const raw = ref<string>('')
const loading = ref(false)

// Rendered as text, never as HTML: the content is uploaded and untrusted.
const pretty = computed(() => {
    try {
        return JSON.stringify(JSON.parse(raw.value), null, 2)
    } catch {
        return raw.value
    }
})

watch(() => [props.show, props.uuid], async () => {
    if (!props.show || !props.uuid) return
    loading.value = true
    raw.value = ''
    try {
        const r = await graphqlClient.query({
            query: GET_SAFETY_SOURCE_ASSERTION,
            variables: { uuid: props.uuid },
            fetchPolicy: 'network-only'
        })
        raw.value = r.data?.getSafetyContextSourceAssertion ?? ''
    } catch (err: any) {
        Swal.fire('Error!', commonFunctions.parseGraphQLError(err.message), 'error')
    } finally {
        loading.value = false
    }
}, { immediate: true })
</script>

<style scoped>
.safety-source {
    max-height: 60vh;
    overflow: auto;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-word;
}
</style>
