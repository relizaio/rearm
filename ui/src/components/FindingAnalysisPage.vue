<template>
    <div class="finding-analysis-page">
        <n-tabs v-model:value="activeTab" type="segment" animated @update:value="onTabChange">
            <n-tab-pane name="findings" tab="Finding Analysis">
                <vulnerability-analysis />
            </n-tab-pane>
            <n-tab-pane name="vex" tab="VEX Statement Proposals">
                <vex-proposals-inbox />
            </n-tab-pane>
            <n-tab-pane name="attestations" tab="Mitigation Attestations">
                <mitigation-attestations-inbox />
            </n-tab-pane>
        </n-tabs>
    </div>
</template>

<script lang="ts">
export default {
    name: 'FindingAnalysisPage'
}
</script>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NTabs, NTabPane } from 'naive-ui'
import VulnerabilityAnalysis from './VulnerabilityAnalysis.vue'
import VexProposalsInbox from './VexProposalsInbox.vue'
import MitigationAttestationsInbox from './MitigationAttestationsInbox.vue'

type TabKey = 'findings' | 'vex' | 'attestations'

const route = useRoute()
const router = useRouter()
const activeTab = ref<TabKey>('findings')

function parseTab(q: unknown): TabKey {
    if (q === 'vex' || q === 'attestations' || q === 'findings') return q
    return 'findings'
}

onMounted(() => {
    activeTab.value = parseTab(route.query.tab)
})

function onTabChange(val: TabKey) {
    router.replace({ query: { ...route.query, tab: val } })
}
</script>

<style scoped>
.finding-analysis-page {
    padding: 0 16px;
}
</style>
