<template>
    <n-modal :show="show" @update:show="emit('update:show', $event)" preset="card" :title="title" style="width: 560px;">
        <n-form label-placement="top">
            <n-form-item label="Linked ReARM user">
                <n-select v-model:value="draft.user" :options="userOptions" filterable clearable
                          placeholder="None — standalone (key shell)"
                          @update:value="onUserChange"/>
            </n-form-item>
            <n-form-item label="Name" required>
                <n-input v-model:value="draft.name" placeholder="e.g. Alex Doe"/>
            </n-form-item>
            <n-form-item label="Email" required>
                <n-input v-model:value="draft.email" placeholder="alex@example.com"/>
            </n-form-item>
            <n-form-item label="Aliases (comma-separated, historical emails)">
                <n-input v-model:value="draft.aliasesText" placeholder="alex@old.example.com"/>
            </n-form-item>
        </n-form>
        <template #footer>
            <n-space>
                <n-button @click="emit('update:show', false)">Cancel</n-button>
                <n-button type="primary" :loading="saving" @click="save">Save</n-button>
            </n-space>
        </template>
    </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useStore } from 'vuex'
import {
    NButton, NForm, NFormItem, NInput, NModal, NSelect, NSpace, useNotification,
} from 'naive-ui'

const props = defineProps<{
    show: boolean,
    orgUuid: string,
    committer?: any,
}>()
const emit = defineEmits<{
    (e: 'update:show', value: boolean): void,
    (e: 'saved', value: any): void,
}>()

const store = useStore()
const notification = useNotification()

const saving = ref(false)
const users = ref<any[]>([])
const draft = ref<{ uuid?: string, name: string, email: string, aliasesText: string, user: string | null }>({
    name: '', email: '', aliasesText: '', user: null,
})

const title = computed(() => draft.value.uuid ? 'Edit committer' : 'New committer')

const userOptions = computed(() => users.value.map((u) => ({
    label: u.name ? `${u.name} (${u.email})` : u.email,
    value: u.uuid,
})))

// Reset draft + load users whenever the modal opens.
watch(() => props.show, async (v) => {
    if (!v) return
    const c = props.committer
    draft.value = c
        ? { uuid: c.uuid, name: c.name ?? '', email: c.email ?? '', aliasesText: (c.aliases ?? []).join(', '), user: c.user ?? null }
        : { name: '', email: '', aliasesText: '', user: null }
    try {
        users.value = await store.dispatch('fetchUsers', props.orgUuid) ?? []
    } catch (e: any) {
        notification.error({ content: `Failed to load users: ${e?.message ?? e}` })
    }
})

// Pre-populate name + email from the picked user when starting fresh /
// when those fields are blank. We never silently overwrite an admin's
// custom edit — only fill what they haven't typed yet.
function onUserChange (uuid: string | null) {
    if (!uuid) return
    const u = users.value.find((x) => x.uuid === uuid)
    if (!u) return
    if (!draft.value.name.trim()) draft.value.name = u.name ?? ''
    if (!draft.value.email.trim()) draft.value.email = u.email ?? ''
}

async function save () {
    if (!draft.value.name.trim() || !draft.value.email.trim()) {
        notification.warning({ content: 'Name and email are required' })
        return
    }
    saving.value = true
    try {
        const input: any = {
            org: props.orgUuid,
            name: draft.value.name.trim(),
            email: draft.value.email.trim().toLowerCase(),
        }
        if (draft.value.uuid) input.uuid = draft.value.uuid
        if (draft.value.user) input.user = draft.value.user
        const aliases = draft.value.aliasesText.split(',').map((a) => a.trim().toLowerCase()).filter(Boolean)
        if (aliases.length) input.aliases = aliases
        const saved = await store.dispatch('upsertCommitter', input)
        notification.success({ content: `Saved "${saved.name}"` })
        emit('update:show', false)
        emit('saved', saved)
    } catch (e: any) {
        notification.error({ content: `Save failed: ${e?.message ?? e}`, duration: 8000 })
    } finally {
        saving.value = false
    }
}
</script>
