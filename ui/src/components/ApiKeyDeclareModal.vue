<template>
    <n-modal preset="dialog" :show-icon="false" style="width: 560px;" :show="show"
        @update:show="(v: boolean) => { if (!v) emit('update:show', false) }">
        <template #header>{{ apiKey?.declaredName ? 'Rename declared key' : 'Declare key as…' }}</template>
        <p class="subtle" style="margin-top: 0;">
            The name an API_KEYS file knows this key by. The next apply naming it updates this key rather than creating another.
        </p>
        <n-form-item label="Declared name" :validation-status="shownProblem ? 'error' : undefined" :feedback="shownProblem">
            <n-input v-model:value="name" placeholder="e.g. release-pipeline"
                @update:value="serverError = ''" @keyup.enter="save" />
        </n-form-item>
        <n-space>
            <n-button type="success" :disabled="!!problem || unchanged || saving" @click="save">Declare</n-button>
            <n-button @click="emit('update:show', false)">Cancel</n-button>
        </n-space>
    </n-modal>
</template>

<script lang="ts" setup>
/**
 * Declare an API key made by hand under a name for API_KEYS files (task RD3-11). The name is checked here as
 * the server checks it; a refusal the server alone can make (another key carries the name) shows beside the input.
 */
import { NButton, NFormItem, NInput, NModal, NSpace } from 'naive-ui'
import { computed, ref, watch } from 'vue'
import gql from 'graphql-tag'
import graphqlClient from '../utils/graphql'
import commonFunctions from '@/utils/commonFunctions'
import { declaredNameProblem, declarePayload } from '@/utils/apiKeyDeclaration'

const props = defineProps<{
    show: boolean
    apiKey: any
    notify: (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void
}>()
const emit = defineEmits(['update:show', 'saved'])

const name = ref('')
const serverError = ref('')
const saving = ref(false)
watch(() => [props.show, props.apiKey], () => { name.value = props.apiKey?.declaredName ?? ''; serverError.value = '' }, { immediate: true })
const problem = computed(() => declaredNameProblem(name.value))
const unchanged = computed(() => name.value === (props.apiKey?.declaredName ?? ''))
// an empty field is not worth an error until something was typed
const shownProblem = computed(() => serverError.value || (name.value ? problem.value : ''))

async function save () {
    if (problem.value || unchanged.value || saving.value) return
    saving.value = true
    try {
        await graphqlClient.mutate({
            mutation: gql`mutation declareApiKey($apiKeyUuid: ID!, $name: String) {
                declareApiKey(apiKeyUuid: $apiKeyUuid, name: $name) { uuid declaredName } }`,
            variables: declarePayload(props.apiKey.uuid, name.value),
            fetchPolicy: 'no-cache'
        })
        props.notify('success', 'Declared', `Key declared as ${name.value}`)
        emit('saved')
        emit('update:show', false)
    } catch (error: any) {
        serverError.value = commonFunctions.parseGraphQLError(error.message)
    } finally { saving.value = false }
}
</script>
