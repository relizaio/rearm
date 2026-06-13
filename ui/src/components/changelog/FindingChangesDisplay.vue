<template>
    <div class="finding-changes">
        <div v-if="summary">
            <div class="summary-tags">
                <n-tag type="error" size="small">{{ summary.totalAppearedCount }} Appeared</n-tag>
                <n-tag type="success" size="small" class="tag-spacing">{{ summary.totalResolvedCount }} Resolved</n-tag>
                <n-tag :type="summary.netChange > 0 ? 'error' : summary.netChange < 0 ? 'success' : 'default'" size="small" class="tag-spacing">
                    Net: {{ summary.netChange > 0 ? '+' : '' }}{{ summary.netChange }}
                </n-tag>
            </div>
            
            <FindingListSection title="New Findings" title-class="finding-new" key-prefix="appeared" :findings="appearedFindings" @kev-click="openKevModal" />
            <FindingListSection title="Resolved Findings" title-class="finding-resolved" key-prefix="resolved" :findings="resolvedFindings" @kev-click="openKevModal" />
            <kev-details-modal v-model:show="showKevModal" :cve-id="kevModalCveId" :org-uuid="orgUuid || ''" />
        </div>
        <div v-else class="empty-state">
            <div class="summary-tags">
                <n-tag type="warning" size="small">0 Appeared</n-tag>
                <n-tag type="success" size="small" class="tag-spacing">0 Resolved</n-tag>
                <n-tag type="default" size="small" class="tag-spacing">Net: 0</n-tag>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import { NTag } from 'naive-ui'
import FindingListSection from './FindingListSection.vue'
import KevDetailsModal from '../KevDetailsModal.vue'
import { getSeverityIndex } from '../../utils/findingUtils'
import { resolveKevCveId } from '../../utils/kevService'
import type { ReleaseFindingChanges } from '../../types/changelog-sealed'

interface Props {
    findingChanges?: ReleaseFindingChanges
    orgUuid?: string
}

const props = defineProps<Props>()

const showKevModal = ref(false)
const kevModalCveId = ref('')

function openKevModal(finding: any) {
    kevModalCveId.value = resolveKevCveId({ id: finding.findingId, aliases: finding.aliases })
    showKevModal.value = true
}

const summary = computed(() => {
    if (!props.findingChanges) return null
    const fc = props.findingChanges
    return {
        totalAppearedCount: fc.appearedCount,
        totalResolvedCount: fc.resolvedCount,
        netChange: fc.appearedCount - fc.resolvedCount
    }
})

const sortBySeverity = (findings: any[]) => {
    if (!findings) return []
    return [...findings].sort((a, b) => {
        const severityDiff = getSeverityIndex(a.severity || '') - getSeverityIndex(b.severity || '')
        if (severityDiff !== 0) return severityDiff
        return String(a.findingId || '').localeCompare(String(b.findingId || ''))
    })
}

const normalizeVuln = (v: any) => ({
    findingId: v.vulnId || '',
    affectedComponent: v.purl || '',
    severity: v.severity || '',
    aliases: Array.isArray(v.aliases) ? v.aliases.map((a: any) => typeof a === 'string' ? a : a.aliasId) : [],
    type: 'VULN',
    typeLabel: 'VULNERABILITY',
    analysisState: v.analysisState || null,
    knownExploited: !!v.knownExploited
})

const normalizeViolation = (v: any) => ({
    findingId: v.type || '',
    affectedComponent: v.purl || '',
    severity: undefined as string | undefined,
    aliases: [] as string[],
    type: 'VIOLATION',
    typeLabel: 'VIOLATION',
    analysisState: v.analysisState || null
})

const normalizeWeakness = (w: any) => ({
    findingId: w.cweId || w.ruleId || '',
    affectedComponent: w.location || '',
    severity: w.severity || '',
    aliases: [] as string[],
    type: 'WEAKNESS',
    typeLabel: 'WEAKNESS',
    analysisState: w.analysisState || null
})

const appearedFindings = computed(() => {
    if (!props.findingChanges) return []
    const fc = props.findingChanges
    return sortBySeverity([
        ...(fc.appearedVulnerabilities || []).map(normalizeVuln),
        ...(fc.appearedViolations || []).map(normalizeViolation),
        ...(fc.appearedWeaknesses || []).map(normalizeWeakness)
    ])
})

const resolvedFindings = computed(() => {
    if (!props.findingChanges) return []
    const fc = props.findingChanges
    return sortBySeverity([
        ...(fc.resolvedVulnerabilities || []).map(normalizeVuln),
        ...(fc.resolvedViolations || []).map(normalizeViolation),
        ...(fc.resolvedWeaknesses || []).map(normalizeWeakness)
    ])
})

</script>

<style scoped lang="scss">
@use './finding-common';

</style>
