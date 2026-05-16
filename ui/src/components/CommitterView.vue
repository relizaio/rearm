<template>
    <div class="committerView" v-if="committer">
        <n-breadcrumb separator="›" class="crumbs">
            <n-breadcrumb-item @click="openAgentsOfOrg">AI Agents</n-breadcrumb-item>
            <n-breadcrumb-item @click="openCommitters">Committers</n-breadcrumb-item>
            <n-breadcrumb-item>{{ committer.name }}</n-breadcrumb-item>
        </n-breadcrumb>

        <div class="hero">
            <h3>{{ committer.name }}</h3>
            <n-tag size="small" :type="committer.status === 'ACTIVE' ? 'success' : 'default'">{{ committer.status }}</n-tag>
        </div>

        <n-descriptions :column="1" bordered label-placement="left" label-align="left" :label-style="metaLabelStyle">
            <n-descriptions-item label="UUID"><code>{{ committer.uuid }}</code></n-descriptions-item>
            <n-descriptions-item label="Email"><code>{{ committer.email }}</code></n-descriptions-item>
            <n-descriptions-item label="Aliases">
                <span v-if="committer.aliases?.length">
                    <code v-for="a in committer.aliases" :key="a" style="margin-right: 6px;">{{ a }}</code>
                </span>
                <span v-else class="dim">—</span>
            </n-descriptions-item>
            <n-descriptions-item label="Linked user">
                <code v-if="committer.user">{{ committer.user }}</code>
                <span v-else class="dim">— (external contributor)</span>
            </n-descriptions-item>
            <n-descriptions-item label="Created">{{ formatDate(committer.createdDate) }}</n-descriptions-item>
        </n-descriptions>

        <SigningKeyManager
            v-if="committer.org"
            :org="committer.org"
            owner-type="COMMITTER"
            :owner-uuid="committer.uuid"
            style="margin-top: 16px;"
        />
    </div>
    <n-spin v-else size="small"/>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useStore } from 'vuex'
import { useRoute, useRouter } from 'vue-router'
import { NBreadcrumb, NBreadcrumbItem, NDescriptions, NDescriptionsItem, NSpin, NTag, useNotification } from 'naive-ui'
import SigningKeyManager from './SigningKeyManager.vue'

const store = useStore()
const route = useRoute()
const router = useRouter()
const notification = useNotification()

const uuid = computed(() => route.params.uuid as string)
const committer = ref<any>(null)
const metaLabelStyle = { width: '180px' }

onMounted(load)

async function load () {
    try {
        committer.value = await store.dispatch('fetchCommitter', uuid.value)
    } catch (e: any) {
        notification.error({ content: `Failed to load committer: ${e?.message ?? e}` })
    }
}

function openAgentsOfOrg () {
    if (committer.value?.org) router.push({ name: 'AiAgentsOfOrg', params: { orguuid: committer.value.org } })
    else router.back()
}

function openCommitters () {
    if (committer.value?.org) router.push({ name: 'CommittersOfOrg', params: { orguuid: committer.value.org } })
    else router.back()
}

function formatDate (s: any) {
    return s ? new Date(s).toLocaleString('en-CA') : '—'
}
</script>

<style scoped>
.committerView { padding: 16px; }
.crumbs { margin-bottom: 12px; font-size: 13px; }
.crumbs :deep(.n-breadcrumb-item__link) { cursor: pointer; }
.hero { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.hero h3 { margin: 0; }
.dim { color: var(--n-text-color-3, #666); }
</style>
