<template>
    <n-tooltip v-if="badge" trigger="hover">
        <template #trigger>
            <span data-testid="safety-pending-badge" style="margin-left: 6px;">
                <n-tag size="small" round type="warning" data-testid="safety-pending-count">{{ badge.pending }}</n-tag>
                <n-tag v-if="badge.downgrades" size="small" round type="error" style="margin-left: 4px;"
                    data-testid="safety-pending-downgrades">{{ badge.downgrades }}</n-tag>
            </span>
        </template>
        <span data-testid="safety-pending-tooltip">{{ badge.tooltip }}</span>
    </n-tooltip>
</template>

<script lang="ts">
export default {
    name: 'SafetyPendingBadge'
}
</script>
<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'
import { NTag, NTooltip } from 'naive-ui'
import graphqlClient from '@/utils/graphql'
import { GET_SAFETY_RELEASE_SUMMARY, pendingBadge } from '@/graphql/safetyContext'

// Pending safety proposals of a release, beside the Safety Context tab label. Shows nothing while
// loading, on an error, or for a caller without Finding Analysis read on the release.
const props = defineProps<{ orgUuid: string, releaseUuid: string, refreshKey?: number }>()

const summary = ref<any>(null)
const badge = computed(() => pendingBadge(summary.value))
let seq = 0

async function fetchSummary () {
    if (!props.orgUuid || !props.releaseUuid) return
    const mine = ++seq
    try {
        const r = await graphqlClient.query({
            query: GET_SAFETY_RELEASE_SUMMARY,
            variables: { org: props.orgUuid, release: props.releaseUuid },
            fetchPolicy: 'network-only'
        })
        if (mine === seq) summary.value = (r.data as any)?.getSafetyContextReleaseSummary ?? null
    } catch (err: any) {
        if (mine === seq) summary.value = null
        console.warn('Could not load the pending safety levels of this release', err?.message ?? err)
    }
}

onMounted(fetchSummary)
watch([() => props.orgUuid, () => props.releaseUuid, () => props.refreshKey], fetchSummary)
</script>
