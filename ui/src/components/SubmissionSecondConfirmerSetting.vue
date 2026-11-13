<template>
    <div class="second-confirmer-setting mt-4" data-testid="second-confirmer-setting">
        <h5>Second confirmer</h5>
        <div v-if="loadError" data-testid="second-confirmer-load-error" class="text-danger">
            {{ SECOND_CONFIRMER_UNREAD }}
            <n-button size="tiny" style="margin-left: 8px;" data-testid="second-confirmer-retry" @click="read">Retry</n-button>
        </div>
        <template v-else-if="value !== null">
            <n-space align="center">
                <n-switch data-testid="second-confirmer-switch" :value="value" :disabled="!isWritable || saving"
                    @update:value="change" />
                <span data-testid="second-confirmer-label">{{ SECOND_CONFIRMER_LABEL }}</span>
            </n-space>
            <div data-testid="second-confirmer-help" class="text-muted second-confirmer-line">{{ SECOND_CONFIRMER_HELP }}</div>
            <div v-if="!isWritable" data-testid="second-confirmer-admin-note" class="text-muted second-confirmer-line">{{ SECOND_CONFIRMER_ADMIN }}</div>
            <n-alert v-if="saveError" data-testid="second-confirmer-error" type="error" :show-icon="true" class="second-confirmer-line">
                {{ saveError }}
            </n-alert>
        </template>
    </div>
</template>

<script setup lang="ts">
// The organization's second-confirmer switch in Organization Settings, Regulatory tab (task FDAW-32 3.9).
// Its own read of the organization's value (no-cache, the organization taken by uuid, on only for exactly
// true) and its own one-field patch; the settings form's save never names the field. Nothing is shown as
// a state that was not read; a refused change goes back and shows the server's sentence as text.
import { onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NSpace, NSwitch, useNotification } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import submissionQueries from '@/utils/submissionQueries'
import { graphqlRefusalText } from '@/utils/graphqlErrorText'
import { secondConfirmerOf } from '@/utils/submissionDrafts'

const SECOND_CONFIRMER_LABEL = 'Require a second confirmer for regulatory packages'
const SECOND_CONFIRMER_HELP = 'Off by default. When on, one person confirms a built draft and a different person finalizes it. '
    + 'A change applies to drafts already open at their next act.'
const SECOND_CONFIRMER_ADMIN = 'An organization administrator changes this.'
const SECOND_CONFIRMER_UNREAD = 'The setting could not be read.'

const props = withDefaults(defineProps<{
    orgUuid?: string | null
    isWritable?: boolean
}>(), {
    orgUuid: null,
    isWritable: false,
})

const notification = useNotification()
const value = ref<boolean | null>(null)
const loadError = ref(false)
const saving = ref(false)
const saveError = ref<string | null>(null)

async function read () {
    loadError.value = false
    value.value = null
    try {
        const resp: any = await graphqlClient.query({ query: submissionQueries.OrgSecondConfirmerGql, fetchPolicy: 'no-cache' })
        const read = secondConfirmerOf(resp, props.orgUuid)
        if (read === null) {
            loadError.value = true
            return
        }
        value.value = read
    } catch (err: any) {
        console.error(err)
        loadError.value = true
    }
}

async function change (on: boolean) {
    if (!props.isWritable || saving.value || value.value === null) return
    const before = value.value
    saveError.value = null
    saving.value = true
    value.value = on
    try {
        const resp: any = await graphqlClient.mutate({ mutation: submissionQueries.UpdateSecondConfirmerGql,
            variables: { orgUuid: props.orgUuid, on }, fetchPolicy: 'no-cache' })
        value.value = resp?.data?.updateOrganizationSettings?.settings?.submissionSecondConfirmer === true
        notification.success({ content: 'Saved', duration: 2000 })
    } catch (err: any) {
        console.error(err)
        value.value = before
        saveError.value = graphqlRefusalText(err)
    } finally {
        saving.value = false
    }
}

onMounted(read)
watch(() => props.orgUuid, read)
</script>

<style scoped>
.second-confirmer-line {
    margin-top: 6px;
}
</style>
