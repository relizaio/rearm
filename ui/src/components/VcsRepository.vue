<template>
    <div class="vcsRepoView">
        <div v-if="vcsRepo">
            <h1>VCS Repository — {{ vcsRepo.name }}</h1>
            <div class="meta">URI: {{ vcsRepo.uri }}</div>

            <h3 class="mt-4">
                Pull-request validation
                <n-tooltip trigger="hover">
                    <template #trigger>
                        <n-icon size="16" style="cursor: help;">
                            <QuestionMark />
                        </n-icon>
                    </template>
                    Optional EXTERNAL_VALIDATION trigger that the PR aggregator uses to dispatch the
                    aggregated check-run verdict to the SCM. At most one trigger per VCS repository.
                </n-tooltip>
            </h3>

            <div v-if="!editing && !currentTrigger" class="empty">
                <p>No PR validation trigger configured.</p>
                <n-button @click="startEdit">Add validation trigger</n-button>
            </div>

            <div v-if="!editing && currentTrigger" class="trigger-summary">
                <p><strong>Name:</strong> {{ currentTrigger.name || '—' }}</p>
                <p><strong>Integration:</strong> {{ currentTrigger.integration || '—' }}</p>
                <p><strong>Installation ID:</strong> {{ currentTrigger.schedule || '—' }}</p>
                <p><strong>Check name override:</strong> {{ currentTrigger.checkName || '(default)' }}</p>
                <n-space>
                    <n-button @click="startEdit">Edit</n-button>
                    <n-button type="error" @click="clearTrigger">Remove</n-button>
                </n-space>
            </div>

            <n-form v-if="editing" :model="draft" label-placement="top" class="mt-3">
                <n-form-item label="Name" required>
                    <n-input v-model:value="draft.name" placeholder="e.g. PR validation"/>
                </n-form-item>
                <n-form-item label="Integration UUID (GITHUB_VALIDATE)" required>
                    <n-input v-model:value="draft.integration" placeholder="UUID of the GitHub validation integration"/>
                </n-form-item>
                <n-form-item label="GitHub installation ID">
                    <n-input v-model:value="draft.schedule" placeholder="(optional) GitHub App installation id"/>
                </n-form-item>
                <n-form-item label="Check name override">
                    <n-input v-model:value="draft.checkName" placeholder="(optional) defaults to rearm/pr/<identity>"/>
                </n-form-item>
                <n-space>
                    <n-button type="primary" :disabled="!draft.name || !draft.integration" @click="saveTrigger">Save</n-button>
                    <n-button @click="cancelEdit">Cancel</n-button>
                </n-space>
            </n-form>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted, watch } from 'vue'
import { useStore } from 'vuex'
import { useRoute } from 'vue-router'
import { NButton, NForm, NFormItem, NIcon, NInput, NSpace, NTooltip, useNotification } from 'naive-ui'
import { QuestionMark } from '@vicons/tabler'

const store = useStore()
const route = useRoute()
const notification = useNotification()

const vcsRepoUuid = computed(() => route.params.uuid as string)
const editing = ref(false)
const draft = reactive({
    uuid: '',
    name: '',
    type: 'EXTERNAL_VALIDATION',
    integration: '',
    schedule: '',
    checkName: '',
    eventType: '',
    clientPayload: '',
    celClientPayload: ''
})

const vcsRepo = computed(() => store.getters.vcsRepoById(vcsRepoUuid.value))
const currentTrigger = computed(() => {
    const triggers = vcsRepo.value?.outputTriggers
    return triggers && triggers.length > 0 ? triggers[0] : null
})

const startEdit = () => {
    if (currentTrigger.value) {
        Object.assign(draft, {
            uuid: currentTrigger.value.uuid || '',
            name: currentTrigger.value.name || '',
            type: 'EXTERNAL_VALIDATION',
            integration: currentTrigger.value.integration || '',
            schedule: currentTrigger.value.schedule || '',
            checkName: currentTrigger.value.checkName || '',
            eventType: currentTrigger.value.eventType || '',
            clientPayload: currentTrigger.value.clientPayload || '',
            celClientPayload: currentTrigger.value.celClientPayload || ''
        })
    } else {
        Object.assign(draft, {
            uuid: '',
            name: '',
            type: 'EXTERNAL_VALIDATION',
            integration: '',
            schedule: '',
            checkName: '',
            eventType: '',
            clientPayload: '',
            celClientPayload: ''
        })
    }
    editing.value = true
}

const cancelEdit = () => { editing.value = false }

const saveTrigger = async () => {
    try {
        const trigger = {
            uuid: draft.uuid || undefined,
            name: draft.name,
            type: 'EXTERNAL_VALIDATION',
            integration: draft.integration,
            schedule: draft.schedule || null,
            checkName: draft.checkName || null,
            eventType: draft.eventType || null,
            clientPayload: draft.clientPayload || null,
            celClientPayload: draft.celClientPayload || null
        }
        await store.dispatch('setVcsRepoOutputTriggers', {
            vcsUuid: vcsRepoUuid.value,
            triggers: [trigger]
        })
        notification.success({ title: 'Saved', content: 'PR validation trigger updated.', duration: 3500 })
        editing.value = false
    } catch (e: any) {
        notification.error({ title: 'Save failed', content: e?.message || 'Unknown error', duration: 5000 })
    }
}

const clearTrigger = async () => {
    try {
        await store.dispatch('setVcsRepoOutputTriggers', {
            vcsUuid: vcsRepoUuid.value,
            triggers: []
        })
        notification.success({ title: 'Removed', content: 'PR validation trigger cleared.', duration: 3500 })
    } catch (e: any) {
        notification.error({ title: 'Remove failed', content: e?.message || 'Unknown error', duration: 5000 })
    }
}

onMounted(() => {
    if (vcsRepoUuid.value) {
        store.dispatch('fetchVcsRepo', vcsRepoUuid.value)
    }
})

watch(vcsRepoUuid, (uuid) => {
    if (uuid) store.dispatch('fetchVcsRepo', uuid)
})
</script>

<style scoped lang="scss">
.vcsRepoView {
    padding: 1rem;
}
.meta {
    color: #555;
    margin-bottom: 1rem;
}
.empty {
    color: #777;
    font-style: italic;
    padding: 0.5rem 0;
}
.trigger-summary {
    background: #fafafa;
    border: 1px solid #eee;
    padding: 0.75rem 1rem;
    border-radius: 4px;
    p { margin: 0.25rem 0; }
}
.mt-3 { margin-top: 0.75rem; }
.mt-4 { margin-top: 1rem; }
</style>
