<template>
    <div class="finding-changes">
        <div v-if="findingChanges">
            <!-- Summary Tags -->
            <div class="summary-tags">
                <n-tag type="warning" size="small">{{ newFindings.length }} New</n-tag>
                <n-tag v-if="isOrgLevelView && partiallyResolvedFindings.length > 0" type="info" size="small" class="tag-spacing">{{ partiallyResolvedFindings.length }} Partially Resolved</n-tag>
                <n-tag v-if="isOrgLevelView && inheritedTechnicalDebtFindings.length > 0" type="error" size="small" class="tag-spacing">{{ inheritedTechnicalDebtFindings.length }} Inherited Debt</n-tag>
                <n-tag v-if="!isOrgLevelView && stillPresentFindings.length > 0" type="info" size="small" class="tag-spacing">{{ stillPresentFindings.length }} Still Present</n-tag>
                <n-tag type="success" size="small" class="tag-spacing">{{ fullyResolvedFindings.length }} {{ isOrgLevelView ? 'Fully Resolved' : 'Resolved' }}</n-tag>
                <n-tag :type="netChange > 0 ? 'error' : netChange < 0 ? 'success' : 'default'" size="small" class="tag-spacing">
                    Net: {{ netChange > 0 ? '+' : '' }}{{ netChange }}
                </n-tag>
            </div>
            
            <FindingListSection title="New Findings" title-class="finding-new" key-prefix="new" :findings="newFindings" :rich-aliases="true">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-for="(seg, i) in getAppearedContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                    </div>
                </template>
            </FindingListSection>

            <FindingListSection v-if="isOrgLevelView" title="Partially Resolved" title-class="finding-partial" key-prefix="partial" :findings="partiallyResolvedFindings" :rich-aliases="true">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-for="(seg, i) in getResolvedContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                    </div>
                </template>
            </FindingListSection>

            <FindingListSection v-if="isOrgLevelView" title="Inherited Technical Debt" title-class="finding-inherited" key-prefix="inherited" :findings="inheritedTechnicalDebtFindings" :rich-aliases="true">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-for="(seg, i) in getInheritedDebtContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                    </div>
                </template>
            </FindingListSection>

            <FindingListSection v-if="!isOrgLevelView" title="Still Present" title-class="finding-present" key-prefix="present" :findings="stillPresentFindings" :rich-aliases="true">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-for="(seg, i) in getStillPresentContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                    </div>
                </template>
            </FindingListSection>

            <FindingListSection :title="isOrgLevelView ? 'Fully Resolved' : 'Resolved'" title-class="finding-resolved" key-prefix="resolved" :findings="fullyResolvedFindings" :rich-aliases="true">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-for="(seg, i) in getResolvedContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                    </div>
                </template>
            </FindingListSection>
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
import type { 
    FindingChangesWithAttribution,
    VulnerabilityWithAttribution,
    ViolationWithAttribution,
    WeaknessWithAttribution,
    OrgLevelContext
} from '../../types/changelog-attribution'

interface Props {
    findingChanges?: FindingChangesWithAttribution
    showAttribution?: boolean
}

const props = withDefaults(defineProps<Props>(), {
    showAttribution: true
})

type AttributionSegment = {
    text: string
    releaseUuid?: string
}

type NormalizedFinding = {
    findingId: string
    affectedComponent: string
    severity?: string
    aliases?: Array<{ aliasId: string }>
    type: 'VULN' | 'VIOLATION' | 'WEAKNESS'
    typeLabel: string
    appearedIn: any[]
    resolvedIn: any[]
    presentIn: any[]
    isNetAppeared: boolean
    isNetResolved: boolean
    isStillPresent: boolean
    orgContext?: OrgLevelContext
}

const sortBySeverity = (findings: NormalizedFinding[]) => {
    return [...findings].sort((a, b) => {
        const severityDiff = getSeverityIndex(a.severity) - getSeverityIndex(b.severity)
        if (severityDiff !== 0) return severityDiff
        return String(a.findingId || '').localeCompare(String(b.findingId || ''))
    })
}

const normalizeVulnerability = (vuln: VulnerabilityWithAttribution): NormalizedFinding => ({
    findingId: vuln.vulnId,
    affectedComponent: vuln.purl,
    severity: vuln.severity,
    aliases: vuln.aliases,
    type: 'VULN',
    typeLabel: 'VULNERABILITY',
    appearedIn: vuln.appearedIn,
    resolvedIn: vuln.resolvedIn,
    presentIn: vuln.presentIn,
    isNetAppeared: vuln.isNetAppeared,
    isNetResolved: vuln.isNetResolved,
    isStillPresent: vuln.isStillPresent,
    orgContext: vuln.orgContext
})

const normalizeViolation = (violation: ViolationWithAttribution): NormalizedFinding => ({
    findingId: violation.type,
    affectedComponent: violation.purl,
    type: 'VIOLATION',
    typeLabel: 'VIOLATION',
    appearedIn: violation.appearedIn,
    resolvedIn: violation.resolvedIn,
    presentIn: violation.presentIn,
    isNetAppeared: violation.isNetAppeared,
    isNetResolved: violation.isNetResolved,
    isStillPresent: violation.isStillPresent,
    orgContext: violation.orgContext
})

const normalizeWeakness = (weakness: WeaknessWithAttribution): NormalizedFinding => ({
    findingId: weakness.cweId || weakness.ruleId || '',
    affectedComponent: weakness.location,
    severity: weakness.severity || undefined,
    type: 'WEAKNESS',
    typeLabel: 'WEAKNESS',
    appearedIn: weakness.appearedIn,
    resolvedIn: weakness.resolvedIn,
    presentIn: weakness.presentIn,
    isNetAppeared: weakness.isNetAppeared,
    isNetResolved: weakness.isNetResolved,
    isStillPresent: weakness.isStillPresent,
    orgContext: weakness.orgContext
})

const allFindings = computed(() => {
    const findings: NormalizedFinding[] = []
    
    if (props.findingChanges?.vulnerabilities) {
        findings.push(...props.findingChanges.vulnerabilities.map(normalizeVulnerability))
    }
    if (props.findingChanges?.violations) {
        findings.push(...props.findingChanges.violations.map(normalizeViolation))
    }
    if (props.findingChanges?.weaknesses) {
        findings.push(...props.findingChanges.weaknesses.map(normalizeWeakness))
    }
    
    return findings
})

// Detect if this is org-level or component-level view
const isOrgLevelView = computed(() => 
    allFindings.value.some(f => f.orgContext !== null && f.orgContext !== undefined)
)

// ORG-LEVEL: Four-category system
const orgNewFindings = computed(() => 
    sortBySeverity(allFindings.value.filter(f => f.orgContext?.isNewToOrganization))
)

const orgPartiallyResolvedFindings = computed(() => 
    sortBySeverity(allFindings.value.filter(f => f.orgContext?.isPartiallyResolved))
)

const orgInheritedTechnicalDebtFindings = computed(() => 
    sortBySeverity(allFindings.value.filter(f => f.orgContext?.isInheritedInAllComponents))
)

const orgFullyResolvedFindings = computed(() => 
    sortBySeverity(allFindings.value.filter(f => f.orgContext?.isFullyResolved))
)

// COMPONENT-LEVEL: Three-category system
const componentNewFindings = computed(() => 
    sortBySeverity(allFindings.value.filter(f => f.isNetAppeared))
)

const componentResolvedFindings = computed(() => 
    sortBySeverity(allFindings.value.filter(f => f.isNetResolved))
)

const componentStillPresentFindings = computed(() => 
    sortBySeverity(allFindings.value.filter(f => f.isStillPresent))
)

// Unified accessors based on view type
const newFindings = computed(() => 
    isOrgLevelView.value ? orgNewFindings.value : componentNewFindings.value
)

const partiallyResolvedFindings = computed(() => 
    isOrgLevelView.value ? orgPartiallyResolvedFindings.value : []
)

const inheritedTechnicalDebtFindings = computed(() => 
    isOrgLevelView.value ? orgInheritedTechnicalDebtFindings.value : []
)

const fullyResolvedFindings = computed(() => 
    isOrgLevelView.value ? orgFullyResolvedFindings.value : componentResolvedFindings.value
)

const stillPresentFindings = computed(() => 
    isOrgLevelView.value ? [] : componentStillPresentFindings.value
)

const netChange = computed(() => {
    const appeared = newFindings.value.length
    const resolved = fullyResolvedFindings.value.length
    return appeared - resolved
})

function getFindingKey(finding: NormalizedFinding): string {
    return `${finding.type}-${finding.findingId}-${finding.affectedComponent}`
}


// Helper to get earliest release from presentIn array
function getEarliestRelease(finding: NormalizedFinding): { version: string, releaseUuid: string } | null {
    if (finding.presentIn.length === 0) return null
    
    const sorted = [...finding.presentIn].sort((a, b) => {
        return a.releaseVersion.localeCompare(b.releaseVersion, undefined, { numeric: true })
    })
    
    return { version: sorted[0].releaseVersion, releaseUuid: sorted[0].releaseUuid }
}

function getAppearedContextSegments(finding: NormalizedFinding): AttributionSegment[] {
    if (finding.appearedIn.length === 0) return []
    
    if (finding.orgContext) {
        const segments: AttributionSegment[] = [{ text: 'Appeared in ' }]
        finding.appearedIn.forEach((a, i) => {
            if (i > 0) segments.push({ text: ', ' })
            segments.push({ text: `${a.componentName}@${a.releaseVersion}`, releaseUuid: a.releaseUuid })
        })
        if (finding.orgContext.isNewToOrganization) {
            segments.push({ text: ' (first occurrence in organization)' })
        } else if (finding.orgContext.wasPreviouslyReported) {
            segments.push({ text: ' (was previously reported in other components)' })
        }
        return segments
    }
    
    const segments: AttributionSegment[] = [{ text: 'Appeared in ' }]
    finding.appearedIn.forEach((a, i) => {
        if (i > 0) segments.push({ text: ', ' })
        segments.push({ text: `${a.branchName} ${a.releaseVersion}`, releaseUuid: a.releaseUuid })
    })
    return segments
}

function getResolvedContextSegments(finding: NormalizedFinding): AttributionSegment[] {
    if (finding.resolvedIn.length === 0) return []
    
    if (finding.orgContext) {
        const segments: AttributionSegment[] = [{ text: 'Resolved in ' }]
        finding.resolvedIn.forEach((a, i) => {
            if (i > 0) segments.push({ text: ', ' })
            segments.push({ text: `${a.componentName}@${a.releaseVersion}`, releaseUuid: a.releaseUuid })
        })
        if (finding.orgContext.isFullyResolved) {
            segments.push({ text: ' (not seen in latest releases of other components)' })
        } else if (finding.orgContext.isPartiallyResolved) {
            const stillPresentAttrs = finding.presentIn
                .filter(p => finding.orgContext!.affectedComponentNames.includes(p.componentName))
            if (stillPresentAttrs.length > 0) {
                segments.push({ text: ', still present in ' })
                stillPresentAttrs.forEach((p, i) => {
                    if (i > 0) segments.push({ text: ', ' })
                    segments.push({ text: `${p.componentName}@${p.releaseVersion}`, releaseUuid: p.releaseUuid })
                })
            }
        }
        return segments
    }
    
    const segments: AttributionSegment[] = [{ text: 'Resolved in ' }]
    finding.resolvedIn.forEach((a, i) => {
        if (i > 0) segments.push({ text: ', ' })
        segments.push({ text: `${a.branchName} ${a.releaseVersion}`, releaseUuid: a.releaseUuid })
    })
    const earliest = getEarliestRelease(finding)
    if (earliest) {
        segments.push({ text: ', present since ' })
        segments.push({ text: earliest.version, releaseUuid: earliest.releaseUuid })
    }
    return segments
}

function getStillPresentContextSegments(finding: NormalizedFinding): AttributionSegment[] {
    if (finding.orgContext && finding.orgContext.componentCount > 0) {
        const presentAttrs = finding.presentIn
            .filter(p => finding.orgContext!.affectedComponentNames.includes(p.componentName))
        const segments: AttributionSegment[] = [
            { text: `Present in ${finding.orgContext.componentCount} component${finding.orgContext.componentCount > 1 ? 's' : ''}: ` }
        ]
        presentAttrs.forEach((p, i) => {
            if (i > 0) segments.push({ text: ', ' })
            segments.push({ text: `${p.componentName}@${p.releaseVersion}`, releaseUuid: p.releaseUuid })
        })
        return segments
    }
    
    if (finding.appearedIn.length > 0 && finding.resolvedIn.length > 0) {
        const segments: AttributionSegment[] = [{ text: 'Appeared in ' }]
        finding.appearedIn.forEach((a, i) => {
            if (i > 0) segments.push({ text: ', ' })
            segments.push({ text: `${a.branchName} ${a.releaseVersion}`, releaseUuid: a.releaseUuid })
        })
        segments.push({ text: ', resolved in ' })
        finding.resolvedIn.forEach((r, i) => {
            if (i > 0) segments.push({ text: ', ' })
            segments.push({ text: `${r.branchName} ${r.releaseVersion}`, releaseUuid: r.releaseUuid })
        })
        segments.push({ text: ', still present in some releases' })
        return segments
    }
    
    const earliest = getEarliestRelease(finding)
    if (earliest) {
        return [
            { text: 'Present since ' },
            { text: earliest.version, releaseUuid: earliest.releaseUuid }
        ]
    }
    return []
}

function getInheritedDebtContextSegments(finding: NormalizedFinding): AttributionSegment[] {
    if (finding.orgContext && finding.orgContext.isInheritedInAllComponents) {
        const presentAttrs = finding.presentIn
            .filter(p => finding.orgContext!.affectedComponentNames.includes(p.componentName))
        const segments: AttributionSegment[] = [
            { text: `Present in all ${finding.orgContext.componentCount} components since first release: ` }
        ]
        presentAttrs.forEach((p, i) => {
            if (i > 0) segments.push({ text: ', ' })
            segments.push({ text: `${p.componentName}@${p.releaseVersion}`, releaseUuid: p.releaseUuid })
        })
        return segments
    }
    
    return getStillPresentContextSegments(finding)
}
</script>

<style scoped lang="scss">
@import './finding-common';

</style>
