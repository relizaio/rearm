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
            
            <FindingListSection title="New Findings" title-class="finding-new" key-prefix="appeared" :findings="appearedFindings" />
            <FindingListSection title="Resolved Findings" title-class="finding-resolved" key-prefix="resolved" :findings="resolvedFindings" />
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
import { computed } from 'vue'
import { NTag } from 'naive-ui'
import FindingListSection from './FindingListSection.vue'
import { getSeverityIndex } from '../../utils/findingUtils'
import type { ReleaseFindingChanges } from '../../types/changelog-sealed'

interface Props {
    findingChanges?: ReleaseFindingChanges
}

const props = defineProps<Props>()

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
    typeLabel: 'VULNERABILITY'
})

const normalizeViolation = (v: any) => ({
    findingId: v.type || '',
    affectedComponent: v.purl || '',
    severity: undefined as string | undefined,
    aliases: [] as string[],
    type: 'VIOLATION',
    typeLabel: 'VIOLATION'
})

const normalizeWeakness = (w: any) => ({
    findingId: w.cweId || w.ruleId || '',
    affectedComponent: w.location || '',
    severity: w.severity || '',
    aliases: [] as string[],
    type: 'WEAKNESS',
    typeLabel: 'WEAKNESS'
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
