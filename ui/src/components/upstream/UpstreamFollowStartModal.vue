<template>
    <n-modal :show="show" preset="card" title="Follow an item" style="width: 640px; max-width: 95vw;" @update:show="onUpdateShow">
        <div data-testid="upstream-follow-start">
            <n-form label-placement="top" @submit.prevent>
                <n-form-item label="Source" :feedback="tried && !model.source ? SOURCE_REQUIRED : ''" :validation-status="tried && !model.source ? 'error' : undefined">
                    <n-select v-model:value="model.source" :options="sourceOptions" placeholder="Choose a source" data-testid="upstream-start-source" />
                </n-form-item>
                <n-form-item label="Kind">
                    <n-radio-group v-model:value="model.kind" data-testid="upstream-start-kind">
                        <n-space>
                            <n-radio v-for="k in ITEM_KINDS" :key="k" :value="k" :data-testid="`upstream-start-kind-${k}`">{{ itemKindLabel[k] }}</n-radio>
                        </n-space>
                    </n-radio-group>
                </n-form-item>
                <n-form-item label="Upstream uuid" :feedback="uuidFeedback" :validation-status="uuidProblem && tried ? 'error' : undefined">
                    <n-input v-model:value="model.upstreamUuid" type="text" placeholder="00000000-0000-0000-0000-000000000000" data-testid="upstream-start-uuid" />
                </n-form-item>
            </n-form>
            <n-alert v-if="error" type="error" class="upstream-start-error" data-testid="upstream-start-error">{{ error }}</n-alert>
            <n-space justify="end">
                <n-button :disabled="sending" data-testid="upstream-start-cancel" @click="close">Cancel</n-button>
                <n-button type="primary" :loading="sending" :disabled="sending" data-testid="upstream-start-submit" @click="submit">Prepare</n-button>
            </n-space>
        </div>
    </n-modal>
</template>

<script lang="ts" setup>
// The start of a follow (ReARM Pro, TPF-185 design 3.7): a source, a kind and the item's uuid on
// the supplier's server. Submitting prepares the item and opens the confirmation page; nothing is
// followed by this step, the backend creates a preparation only. Also opened with source, kind
// and uuid filled in, through the optional props. Without the sources prop it reads the active
// sources of the organization itself.
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NAlert, NButton, NForm, NFormItem, NInput, NModal, NRadio, NRadioGroup, NSelect, NSpace } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import { isUuid, refusalOf } from '@/utils/upstream'
import { PREPARE_UPSTREAM_FOLLOW } from '@/utils/upstreamFollowQueries'
import { GET_UPSTREAM_SOURCES } from '@/utils/upstreamSourceQueries'
import { followUuidHint, itemKindLabel } from '@/utils/upstreamLabels'

const props = defineProps<{
    show: boolean
    orgUuid: string
    /** The active sources to choose from ({ uuid, name, status }); read by the modal when absent. */
    sources?: any[] | null
    source?: string | null
    kind?: string | null
    upstreamUuid?: string | null
}>()

const emit = defineEmits<{
    (e: 'update:show', value: boolean): void
}>()

const ITEM_KINDS = ['PRODUCT', 'COMPONENT']
const SOURCE_REQUIRED = 'Choose a source.'
const UUID_PROBLEM = 'Enter the uuid of the item on the supplier\'s server, for example 3f2a6c1e-8b7d-4e5f-9a0b-1c2d3e4f5a6b.'

const router = useRouter()
const model = ref({ source: null as string | null, kind: 'PRODUCT', upstreamUuid: '' })
const tried = ref(false)
const sending = ref(false)
const error = ref('')
const readSources = ref<any[]>([])

const activeSources = computed((): any[] => (props.sources ?? readSources.value).filter(s => s.status === 'ACTIVE'))
const sourceOptions = computed(() => activeSources.value.map(s => ({ label: s.name, value: s.uuid })))

/** The uuid as it is sent: trimmed and lower-cased. */
const normalizedUuid = computed((): string => model.value.upstreamUuid.trim().toLowerCase())
const uuidProblem = computed((): string => isUuid(normalizedUuid.value) ? '' : UUID_PROBLEM)
const uuidFeedback = computed((): string => tried.value && uuidProblem.value ? uuidProblem.value : followUuidHint)

function reset () {
    model.value = {
        source: props.source ?? null,
        kind: props.kind && ITEM_KINDS.includes(props.kind) ? props.kind : 'PRODUCT',
        upstreamUuid: props.upstreamUuid ?? '',
    }
    tried.value = false
    error.value = ''
}

async function readActiveSources () {
    if (props.sources || !props.orgUuid) return
    try {
        const resp: any = await graphqlClient.query({
            query: GET_UPSTREAM_SOURCES, variables: { org: props.orgUuid, statuses: ['ACTIVE'] }, fetchPolicy: 'no-cache',
        })
        readSources.value = resp?.data?.upstreamSources ?? []
    } catch (err) {
        error.value = refusalOf(err).text
    }
}

watch(() => props.show, (open) => {
    if (!open) return
    reset()
    readActiveSources()
}, { immediate: true })

function close () {
    if (sending.value) return
    emit('update:show', false)
}

function onUpdateShow (value: boolean) {
    if (!value) close()
}

async function submit () {
    tried.value = true
    error.value = ''
    if (!model.value.source || uuidProblem.value || sending.value) return
    sending.value = true
    try {
        const resp: any = await graphqlClient.mutate({
            mutation: PREPARE_UPSTREAM_FOLLOW,
            variables: { source: model.value.source, kind: model.value.kind, upstreamUuid: normalizedUuid.value },
            fetchPolicy: 'no-cache',
        })
        const uuid = resp?.data?.prepareUpstreamFollow?.uuid
        if (!isUuid(uuid)) return
        sending.value = false
        emit('update:show', false)
        router.push({ name: 'UpstreamFollowPreparation', params: { orguuid: props.orgUuid, uuid } })
    } catch (err) {
        error.value = refusalOf(err).text
    } finally {
        sending.value = false
    }
}
</script>

<style scoped>
.upstream-start-error {
    margin-bottom: 12px;
}
</style>
