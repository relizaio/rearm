<template>
    <div class="finding-changes">
        <div v-if="normalizedFindingChanges && normalizedFindingChanges.summary">
            <div class="summary-tags">
                <n-tag type="error" size="small">{{ normalizedFindingChanges.summary.totalAppearedCount }} Appeared</n-tag>
                <n-tag type="success" size="small" class="tag-spacing">{{ normalizedFindingChanges.summary.totalResolvedCount }} Resolved</n-tag>
                <n-tag :type="normalizedFindingChanges.summary.netChange > 0 ? 'error' : normalizedFindingChanges.summary.netChange < 0 ? 'success' : 'default'" size="small" class="tag-spacing">
                    Net: {{ normalizedFindingChanges.summary.netChange > 0 ? '+' : '' }}{{ normalizedFindingChanges.summary.netChange }}
                </n-tag>
            </div>
            
            <div v-if="appearedFindings.length > 0">
                <h5 class="finding-new">New Findings ({{ appearedFindings.length }})</h5>
                <ul>
                    <li v-for="finding in appearedFindings" :key="`appeared-${finding.type}-${finding.findingId}-${finding.affectedComponent}`">
                        <span class="finding-row">
                            <n-tag v-if="finding.severity" :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                                {{ finding.severity }}
                            </n-tag>
                            <n-tag :type="getFindingTypeTagType(finding.type)" size="small">{{ finding.typeLabel }}</n-tag>
                            <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                            <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                                <template #trigger>
                                    <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                                </template>
                                Aliases: {{ finding.aliases.join(', ') }}
                            </n-tooltip>
                            <span v-if="finding.license"> - License: <strong>{{ finding.license }}</strong></span>
                            <span v-if="finding.violationDetails"> - {{ finding.violationDetails }}</span>
                            <span v-if="finding.ruleId"> ({{ finding.ruleId }})</span>
                            <template v-if="finding.affectedComponent">
                                <span class="in-label">in</span>
                                <code>{{ finding.affectedComponent }}</code>
                            </template>
                        </span>
                    </li>
                </ul>
            </div>

            <div v-if="resolvedFindings.length > 0">
                <h5 class="finding-resolved">Resolved Findings ({{ resolvedFindings.length }})</h5>
                <ul>
                    <li v-for="finding in resolvedFindings" :key="`resolved-${finding.type}-${finding.findingId}-${finding.affectedComponent}`">
                        <span class="finding-row">
                            <n-tag v-if="finding.severity" :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                                {{ finding.severity }}
                            </n-tag>
                            <n-tag :type="getFindingTypeTagType(finding.type)" size="small">{{ finding.typeLabel }}</n-tag>
                            <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                            <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                                <template #trigger>
                                    <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                                </template>
                                Aliases: {{ finding.aliases.join(', ') }}
                            </n-tooltip>
                            <span v-if="finding.license"> - License: <strong>{{ finding.license }}</strong></span>
                            <span v-if="finding.violationDetails"> - {{ finding.violationDetails }}</span>
                            <template v-if="finding.affectedComponent">
                                <span class="in-label">in</span>
                                <code>{{ finding.affectedComponent }}</code>
                            </template>
                        </span>
                    </li>
                </ul>
            </div>

            <div v-if="severityChangedFindings.length > 0">
                <h5 class="finding-changed">Severity Changed ({{ severityChangedFindings.length }})</h5>
                <ul>
                    <li v-for="finding in severityChangedFindings" :key="`changed-${finding.type}-${finding.findingId}-${finding.affectedComponent}`">
                        <span class="finding-row">
                            <n-tag v-if="finding.severity" :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                                {{ finding.severity }}
                            </n-tag>
                            <n-tag :type="getFindingTypeTagType(finding.type)" size="small">{{ finding.typeLabel }}</n-tag>
                            <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                            <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                                <template #trigger>
                                    <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                                </template>
                                Aliases: {{ finding.aliases.join(', ') }}
                            </n-tooltip>
                            <template v-if="finding.affectedComponent">
                                <span class="in-label">in</span>
                                <code>{{ finding.affectedComponent }}</code>
                            </template>
                        </span>
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
import type { ReleaseFindingChanges } from '../../types/changelog-sealed'

interface Finding {
    vulnId?: string
    cweId?: string
    ruleId?: string
    type?: string
    license?: string
    purl?: string
    location?: string
    severity?: string
    aliases?: Array<{ aliasId: string }> | string[]
    violationDetails?: string
}

interface LegacyFindingChanges {
    summary?: {
        totalAppearedCount: number
        totalResolvedCount: number
        netChange: number
    }
    appearedVulnerabilities?: Finding[]
    appearedViolations?: Finding[]
    appearedWeaknesses?: Finding[]
    resolvedVulnerabilities?: Finding[]
    resolvedViolations?: Finding[]
    resolvedWeaknesses?: Finding[]
    severityChangedVulnerabilities?: Finding[]
    severityChangedWeaknesses?: Finding[]
}

interface Props {
    findingChanges?: LegacyFindingChanges | ReleaseFindingChanges
}

const props = defineProps<Props>()

// Type guard to check if it's ReleaseFindingChanges (NONE mode)
const isNoneMode = computed(() => {
    return props.findingChanges && 'appearedCount' in props.findingChanges
})

// Convert ReleaseFindingChanges to legacy format for display
const normalizedFindingChanges = computed<LegacyFindingChanges>(() => {
    if (!props.findingChanges) return {}
    
    if (isNoneMode.value) {
        const none = props.findingChanges as ReleaseFindingChanges
        return {
            summary: {
                totalAppearedCount: none.appearedCount,
                totalResolvedCount: none.resolvedCount,
                netChange: none.appearedCount - none.resolvedCount
            },
            appearedVulnerabilities: none.appearedVulnerabilities.map(vulnId => ({
                vulnId,
                purl: '',
                severity: ''
            })),
            resolvedVulnerabilities: none.resolvedVulnerabilities.map(vulnId => ({
                vulnId,
                purl: '',
                severity: ''
            })),
            appearedViolations: [],
            appearedWeaknesses: [],
            resolvedViolations: [],
            resolvedWeaknesses: [],
            severityChangedVulnerabilities: [],
            severityChangedWeaknesses: []
        }
    }
    
    return props.findingChanges as LegacyFindingChanges
})

const severityOrder = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNASSIGNED', '-']

const getSeverityIndex = (severity: string) => {
    const index = severityOrder.indexOf(severity)
    return index === -1 ? severityOrder.length : index
}

const sortBySeverity = (findings: any[]) => {
    if (!findings) return []
    return [...findings].sort((a, b) => {
        const severityDiff = getSeverityIndex(a.severity || '') - getSeverityIndex(b.severity || '')
        if (severityDiff !== 0) return severityDiff
        return String(a.findingId || '').localeCompare(String(b.findingId || ''))
    })
}

const normalizeFinding = (finding: Finding, findingType: 'VULN' | 'VIOLATION' | 'WEAKNESS') => {
    let findingId = ''
    let affectedComponent = ''
    let aliases: string[] = []
    let typeLabel = ''
    
    if (findingType === 'VULN') {
        findingId = finding.vulnId || ''
        affectedComponent = finding.purl || ''
        aliases = Array.isArray(finding.aliases) 
            ? finding.aliases.map((a: any) => typeof a === 'string' ? a : a.aliasId)
            : []
        typeLabel = 'VULNERABILITY'
    } else if (findingType === 'VIOLATION') {
        findingId = finding.type || finding.license || ''
        affectedComponent = finding.purl || ''
        typeLabel = 'VIOLATION'
    } else if (findingType === 'WEAKNESS') {
        findingId = finding.cweId || finding.ruleId || ''
        affectedComponent = finding.location || ''
        typeLabel = 'WEAKNESS'
    }
    
    return {
        ...finding,
        findingId,
        affectedComponent,
        aliases,
        type: findingType,
        typeLabel
    }
}

const appearedFindings = computed(() => {
    const normalized = normalizedFindingChanges.value
    const vulnerabilities = (normalized.appearedVulnerabilities || []).map(f => normalizeFinding(f, 'VULN'))
    const violations = (normalized.appearedViolations || []).map(f => normalizeFinding(f, 'VIOLATION'))
    const weaknesses = (normalized.appearedWeaknesses || []).map(f => normalizeFinding(f, 'WEAKNESS'))
    return sortBySeverity([...vulnerabilities, ...violations, ...weaknesses])
})

const resolvedFindings = computed(() => {
    const normalized = normalizedFindingChanges.value
    const vulnerabilities = (normalized.resolvedVulnerabilities || []).map(f => normalizeFinding(f, 'VULN'))
    const violations = (normalized.resolvedViolations || []).map(f => normalizeFinding(f, 'VIOLATION'))
    const weaknesses = (normalized.resolvedWeaknesses || []).map(f => normalizeFinding(f, 'WEAKNESS'))
    return sortBySeverity([...vulnerabilities, ...violations, ...weaknesses])
})

const severityChangedFindings = computed(() => {
    const normalized = normalizedFindingChanges.value
    const vulnerabilities = (normalized.severityChangedVulnerabilities || []).map(f => normalizeFinding(f, 'VULN'))
    const weaknesses = (normalized.severityChangedWeaknesses || []).map(f => normalizeFinding(f, 'WEAKNESS'))
    return sortBySeverity([...vulnerabilities, ...weaknesses])
})

function getSeverityTagType(severity: string): 'default' | 'error' | 'warning' | 'info' | 'success' | 'primary' {
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
    if (!id) return null
    if (id.startsWith('CVE-') || id.startsWith('GHSA-')) {
        return `https://osv.dev/vulnerability/${id}`
    }
    if (id.startsWith('CWE-')) {
        const num = id.replace('CWE-', '')
        return `https://cwe.mitre.org/data/definitions/${num}.html`
    }
    return null
}

function openExternalLink(href: string) {
    const consentKey = 'externalLinkConsent'
    const consentExpiry = 'externalLinkConsentExpiry'
    const stored = localStorage.getItem(consentKey)
    const expiry = localStorage.getItem(consentExpiry)
    
    if (stored === 'true' && expiry && Date.now() < parseInt(expiry)) {
        window.open(href, '_blank', 'noopener,noreferrer')
        return
    }
    
    Swal.fire({
        title: 'Open External Link',
        html: `You are about to open an external link:<br><br><strong>${href}</strong><br><br>Do you want to continue?`,
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: 'Open Link',
        cancelButtonText: 'Cancel',
        input: 'checkbox',
        inputValue: 0,
        inputPlaceholder: "Don't ask me again for 15 days"
    }).then((result: any) => {
        if (result.isConfirmed) {
            if (result.value) {
                localStorage.setItem(consentKey, 'true')
                localStorage.setItem(consentExpiry, String(Date.now() + 15 * 24 * 60 * 60 * 1000))
            }
            window.open(href, '_blank', 'noopener,noreferrer')
        }
    })
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
        margin-top: 10px;
        margin-bottom: 4px;
    }
    
    .finding-resolved {
        color: #18a058;
        margin-top: 10px;
        margin-bottom: 4px;
    }
    
    .finding-changed {
        color: #f0a020;
        margin-top: 10px;
        margin-bottom: 4px;
    }
    
    .empty-state {
        padding: 20px;
        text-align: center;
        color: #999;
    }
    
    ul {
        list-style: none;
        padding-left: 0;
        margin-top: 4px;
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
        font-size: 0.9em;
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
    
    code {
        background: #f5f5f5;
        padding: 2px 6px;
        border-radius: 3px;
        font-size: 0.9em;
    }
    
    :deep(.severity-critical) {
        background-color: #5c0011 !important;
        color: #fff !important;
    }
}
</style>
