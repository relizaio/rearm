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
            
            <!-- New Findings -->
            <div v-if="newFindings.length > 0">
                <h5 class="finding-new">New Findings ({{ newFindings.length }})</h5>
                <ul>
                    <li v-for="finding in newFindings" :key="getFindingKey(finding)">
                        <span class="finding-row">
                            <n-tag :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                                {{ finding.severity || 'UNASSIGNED' }}
                            </n-tag>
                            <n-tag :type="getFindingTypeTagType(finding.type)" size="small">
                                {{ finding.typeLabel }}
                            </n-tag>
                            <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                            <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                                <template #trigger>
                                    <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                                </template>
                                Aliases: <template v-for="(alias, idx) in finding.aliases" :key="alias.aliasId"><span v-if="idx > 0">, </span><a v-if="getFindingUrl(alias.aliasId)" :href="getFindingUrl(alias.aliasId)!" target="_blank" rel="noopener noreferrer" class="alias-tooltip-link" @click.prevent="openExternalLink(getFindingUrl(alias.aliasId)!)">{{ alias.aliasId }}</a><span v-else>{{ alias.aliasId }}</span></template>
                            </n-tooltip>
                            <span class="in-label">in</span>
                            <code>{{ finding.affectedComponent }}</code>
                        </span>
                        <div v-if="showAttribution" class="attribution">
                            <span v-for="(seg, i) in getAppearedContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        </div>
                    </li>
                </ul>
            </div>

            <!-- Partially Resolved Findings (Org-level only) -->
            <div v-if="isOrgLevelView && partiallyResolvedFindings.length > 0">
                <h5 class="finding-partial">Partially Resolved ({{ partiallyResolvedFindings.length }})</h5>
                <ul>
                    <li v-for="finding in partiallyResolvedFindings" :key="getFindingKey(finding)">
                        <span class="finding-row">
                            <n-tag :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                                {{ finding.severity || 'UNASSIGNED' }}
                            </n-tag>
                            <n-tag :type="getFindingTypeTagType(finding.type)" size="small">
                                {{ finding.typeLabel }}
                            </n-tag>
                            <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                            <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                                <template #trigger>
                                    <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                                </template>
                                Aliases: <template v-for="(alias, idx) in finding.aliases" :key="alias.aliasId"><span v-if="idx > 0">, </span><a v-if="getFindingUrl(alias.aliasId)" :href="getFindingUrl(alias.aliasId)!" target="_blank" rel="noopener noreferrer" class="alias-tooltip-link" @click.prevent="openExternalLink(getFindingUrl(alias.aliasId)!)">{{ alias.aliasId }}</a><span v-else>{{ alias.aliasId }}</span></template>
                            </n-tooltip>
                            <span class="in-label">in</span>
                            <code>{{ finding.affectedComponent }}</code>
                        </span>
                        <div v-if="showAttribution" class="attribution">
                            <span v-for="(seg, i) in getResolvedContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        </div>
                    </li>
                </ul>
            </div>

            <!-- Inherited Technical Debt (Org-level only, conditional display) -->
            <div v-if="isOrgLevelView && inheritedTechnicalDebtFindings.length > 0">
                <h5 class="finding-inherited">Inherited Technical Debt ({{ inheritedTechnicalDebtFindings.length }})</h5>
                <ul>
                    <li v-for="finding in inheritedTechnicalDebtFindings" :key="getFindingKey(finding)">
                        <span class="finding-row">
                            <n-tag :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                                {{ finding.severity || 'UNASSIGNED' }}
                            </n-tag>
                            <n-tag :type="getFindingTypeTagType(finding.type)" size="small">
                                {{ finding.typeLabel }}
                            </n-tag>
                            <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                            <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                                <template #trigger>
                                    <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                                </template>
                                Aliases: <template v-for="(alias, idx) in finding.aliases" :key="alias.aliasId"><span v-if="idx > 0">, </span><a v-if="getFindingUrl(alias.aliasId)" :href="getFindingUrl(alias.aliasId)!" target="_blank" rel="noopener noreferrer" class="alias-tooltip-link" @click.prevent="openExternalLink(getFindingUrl(alias.aliasId)!)">{{ alias.aliasId }}</a><span v-else>{{ alias.aliasId }}</span></template>
                            </n-tooltip>
                            <span class="in-label">in</span>
                            <code>{{ finding.affectedComponent }}</code>
                        </span>
                        <div v-if="showAttribution" class="attribution">
                            <span v-for="(seg, i) in getInheritedDebtContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        </div>
                    </li>
                </ul>
            </div>

            <!-- Still Present Findings (Component-level only) -->
            <div v-if="!isOrgLevelView && stillPresentFindings.length > 0">
                <h5 class="finding-present">Still Present ({{ stillPresentFindings.length }})</h5>
                <ul>
                    <li v-for="finding in stillPresentFindings" :key="getFindingKey(finding)">
                        <span class="finding-row">
                            <n-tag :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                                {{ finding.severity || 'UNASSIGNED' }}
                            </n-tag>
                            <n-tag :type="getFindingTypeTagType(finding.type)" size="small">
                                {{ finding.typeLabel }}
                            </n-tag>
                            <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                            <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                                <template #trigger>
                                    <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                                </template>
                                Aliases: <template v-for="(alias, idx) in finding.aliases" :key="alias.aliasId"><span v-if="idx > 0">, </span><a v-if="getFindingUrl(alias.aliasId)" :href="getFindingUrl(alias.aliasId)!" target="_blank" rel="noopener noreferrer" class="alias-tooltip-link" @click.prevent="openExternalLink(getFindingUrl(alias.aliasId)!)">{{ alias.aliasId }}</a><span v-else>{{ alias.aliasId }}</span></template>
                            </n-tooltip>
                            <span class="in-label">in</span>
                            <code>{{ finding.affectedComponent }}</code>
                        </span>
                        <div v-if="showAttribution" class="attribution">
                            <span v-for="(seg, i) in getStillPresentContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        </div>
                    </li>
                </ul>
            </div>

            <!-- Resolved Findings -->
            <div v-if="fullyResolvedFindings.length > 0">
                <h5 class="finding-resolved">{{ isOrgLevelView ? 'Fully Resolved' : 'Resolved' }} ({{ fullyResolvedFindings.length }})</h5>
                <ul>
                    <li v-for="finding in fullyResolvedFindings" :key="getFindingKey(finding)">
                        <span class="finding-row">
                            <n-tag :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                                {{ finding.severity || 'UNASSIGNED' }}
                            </n-tag>
                            <n-tag :type="getFindingTypeTagType(finding.type)" size="small">
                                {{ finding.typeLabel }}
                            </n-tag>
                            <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                            <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                                <template #trigger>
                                    <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                                </template>
                                Aliases: <template v-for="(alias, idx) in finding.aliases" :key="alias.aliasId"><span v-if="idx > 0">, </span><a v-if="getFindingUrl(alias.aliasId)" :href="getFindingUrl(alias.aliasId)!" target="_blank" rel="noopener noreferrer" class="alias-tooltip-link" @click.prevent="openExternalLink(getFindingUrl(alias.aliasId)!)">{{ alias.aliasId }}</a><span v-else>{{ alias.aliasId }}</span></template>
                            </n-tooltip>
                            <span class="in-label">in</span>
                            <code>{{ finding.affectedComponent }}</code>
                        </span>
                        <div v-if="showAttribution" class="attribution">
                            <span v-for="(seg, i) in getResolvedContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        </div>
                    </li>
                </ul>
            </div>
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
import { NTag, NTooltip, NIcon } from 'naive-ui'
import { Info20Regular } from '@vicons/fluent'
import Swal from 'sweetalert2'
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

const severityOrder = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED', '-']

const getSeverityIndex = (severity?: string) => {
    if (!severity) return severityOrder.length
    const index = severityOrder.indexOf(severity)
    return index === -1 ? severityOrder.length : index
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

function getSeverityTagType(severity?: string): 'default' | 'error' | 'warning' | 'info' | 'success' | 'primary' {
    switch (severity) {
        case 'CRITICAL':
        case 'HIGH':
            return 'error'
        case 'MEDIUM':
            return 'warning'
        case 'LOW':
            return 'info'
        case 'UNASSIGNED':
            return 'default'
        default:
            return 'default'
    }
}

function getFindingTypeTagType(type: string): 'default' | 'error' | 'warning' | 'info' | 'success' | 'primary' {
    switch (type) {
        case 'VULN':
            return 'error'
        case 'VIOLATION':
            return 'warning'
        case 'WEAKNESS':
            return 'info'
        default:
            return 'default'
    }
}

function getFindingUrl(id: string): string | null {
    if (id.startsWith('ALPINE-CVE-') || id.startsWith('CVE-') || id.startsWith('GHSA-')) {
        return `https://osv.dev/vulnerability/${id}`
    }
    if (id.startsWith('CWE-')) {
        const raw = id.slice(4)
        const num = String(parseInt(raw, 10))
        if (num && num !== 'NaN') {
            return `https://cwe.mitre.org/data/definitions/${num}.html`
        }
    }
    return null
}

async function openExternalLink(href: string) {
    try {
        const LS_KEY = 'rearm_external_link_consent_until'
        const now = Date.now()
        const stored = localStorage.getItem(LS_KEY)
        if (stored && Number(stored) > now) {
            window.open(href, '_blank')
            return
        }
        const result = await Swal.fire({
            icon: 'info',
            title: 'Open external link?\n',
            text: 'This will open a vulnerability database resource external to ReARM. Please confirm that you want to proceed.',
            showCancelButton: true,
            confirmButtonText: 'Open',
            cancelButtonText: 'Cancel',
            input: 'checkbox',
            inputValue: 0,
            inputPlaceholder: "Don't ask me again for 15 days"
        })
        if (result.isConfirmed) {
            if (result.value === 1) {
                const fifteenDaysMs = 15 * 24 * 60 * 60 * 1000
                localStorage.setItem(LS_KEY, String(now + fifteenDaysMs))
            }
            window.open(href, '_blank')
        }
    } catch (err) {
        window.open(href, '_blank')
    }
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
.finding-changes {
    .summary-tags {
        margin-bottom: 10px;
    }
    
    .tag-spacing {
        margin-left: 8px;
    }
    
    .finding-new {
        color: #d03050;
        margin-top: 12px;
        margin-bottom: 4px;
        font-size: 0.9em;
    }
    
    .finding-partial {
        color: #f0a020;
        margin-top: 12px;
        margin-bottom: 4px;
        font-size: 0.9em;
    }
    
    .finding-inherited {
        color: #c04080;
        margin-top: 12px;
        margin-bottom: 4px;
        font-size: 0.9em;
    }
    
    .finding-resolved {
        color: #18a058;
        margin-top: 12px;
        margin-bottom: 4px;
        font-size: 0.9em;
    }
    
    .finding-present {
        color: #2080f0;
        margin-top: 12px;
        margin-bottom: 4px;
        font-size: 0.9em;
    }
    
    .empty-state {
        padding: 20px;
        text-align: center;
        color: #999;
    }
    
    ul {
        margin-top: 2px;
        list-style: none;
        padding-left: 0;
    }
    
    li {
        padding: 4px 0;
        border-bottom: 1px solid #f0f0f0;
        
        &:last-child {
            border-bottom: none;
        }
    }
    
    .finding-row {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        flex-wrap: wrap;
    }
    
    .in-label {
        color: #999;
        font-size: 0.85em;
    }
    
    code {
        background: #f5f5f5;
        padding: 1px 5px;
        border-radius: 3px;
        font-size: 0.82em;
        color: #666;
    }
    
    .finding-link {
        color: #2080f0;
        text-decoration: none;
        
        &:hover {
            text-decoration: underline;
        }
    }
    
    .alias-icon {
        cursor: pointer;
        color: #999;
        vertical-align: middle;
        
        &:hover {
            color: #2080f0;
        }
    }
    
    .alias-tooltip-link {
        color: #70c0ff;
        text-decoration: none;
        
        &:hover {
            text-decoration: underline;
        }
    }
    
    .attribution {
        padding-left: 16px;
        font-size: 0.85em;
        line-height: 1.4;
    }
    
    .attribution-context {
        color: #888;
        font-style: italic;
    }
    
    .release-link {
        color: #2080f0;
        text-decoration: none;
        font-style: italic;
        
        &:hover {
            text-decoration: underline;
        }
    }

    // Severity color overrides for CRITICAL distinction
    :deep(.severity-critical) {
        background-color: #5c0011 !important;
        color: #fff !important;
    }
}
</style>
