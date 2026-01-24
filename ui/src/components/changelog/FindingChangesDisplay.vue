<template>
    <div class="finding-changes">
        <div v-if="findingChanges && findingChanges.summary">
            <div class="summary-tags">
                <n-tag type="error" size="small">{{ findingChanges.summary.totalAppearedCount }} Appeared</n-tag>
                <n-tag type="success" size="small" class="tag-spacing">{{ findingChanges.summary.totalResolvedCount }} Resolved</n-tag>
                <n-tag :type="findingChanges.summary.netChange > 0 ? 'error' : 'success'" size="small" class="tag-spacing">
                    Net: {{ findingChanges.summary.netChange > 0 ? '+' : '' }}{{ findingChanges.summary.netChange }}
                </n-tag>
            </div>
            
            <div v-if="appearedFindings.length > 0">
                <h5 class="finding-new">⚠️ New Findings ({{ appearedFindings.length }})</h5>
                <ul>
                    <li v-for="finding in appearedFindings" :key="`appeared-${finding.type}-${finding.findingId}-${finding.affectedComponent}`">
                        <n-tag :type="getFindingTypeTagType(finding.type)" size="small">{{ finding.typeLabel }}</n-tag>
                        <n-tag v-if="finding.severity" :type="getSeverityTagType(finding.severity)" size="small">{{ finding.severity }}</n-tag>
                        <strong>{{ finding.findingId }}</strong>
                        <span v-if="finding.aliases && finding.aliases.length > 0"> ({{ finding.aliases.join(', ') }})</span>
                        <span v-if="finding.license"> - License: <strong>{{ finding.license }}</strong></span>
                        <span v-if="finding.violationDetails"> - {{ finding.violationDetails }}</span>
                        <span v-if="finding.ruleId"> ({{ finding.ruleId }})</span>
                        in <code>{{ finding.affectedComponent }}</code>
                    </li>
                </ul>
            </div>

            <div v-if="resolvedFindings.length > 0">
                <h5 class="finding-resolved">✓ Resolved Findings ({{ resolvedFindings.length }})</h5>
                <ul>
                    <li v-for="finding in resolvedFindings" :key="`resolved-${finding.type}-${finding.findingId}-${finding.affectedComponent}`">
                        <n-tag :type="getFindingTypeTagType(finding.type)" size="small">{{ finding.typeLabel }}</n-tag>
                        <n-tag v-if="finding.severity" :type="getSeverityTagType(finding.severity)" size="small">{{ finding.severity }}</n-tag>
                        <strong>{{ finding.findingId }}</strong>
                        <span v-if="finding.aliases && finding.aliases.length > 0"> ({{ finding.aliases.join(', ') }})</span>
                        <span v-if="finding.license"> - License: <strong>{{ finding.license }}</strong></span>
                        <span v-if="finding.violationDetails"> - {{ finding.violationDetails }}</span>
                        in <code>{{ finding.affectedComponent }}</code>
                    </li>
                </ul>
            </div>

            <div v-if="severityChangedFindings.length > 0">
                <h5 class="finding-changed">⚡ Severity Changed ({{ severityChangedFindings.length }})</h5>
                <ul>
                    <li v-for="finding in severityChangedFindings" :key="`changed-${finding.type}-${finding.findingId}-${finding.affectedComponent}`">
                        <n-tag :type="getFindingTypeTagType(finding.type)" size="small">{{ finding.typeLabel }}</n-tag>
                        <n-tag v-if="finding.severity" :type="getSeverityTagType(finding.severity)" size="small">{{ finding.severity }}</n-tag>
                        <strong>{{ finding.findingId }}</strong>
                        <span v-if="finding.aliases && finding.aliases.length > 0"> ({{ finding.aliases.join(', ') }})</span>
                        in <code>{{ finding.affectedComponent }}</code>
                    </li>
                </ul>
            </div>
        </div>
        <div v-else class="empty-state">
            No finding changes detected
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { NTag } from 'naive-ui'

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

interface FindingChanges {
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
    findingChanges?: FindingChanges
}

const props = defineProps<Props>()

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
    const vulnerabilities = (props.findingChanges?.appearedVulnerabilities || []).map(f => normalizeFinding(f, 'VULN'))
    const violations = (props.findingChanges?.appearedViolations || []).map(f => normalizeFinding(f, 'VIOLATION'))
    const weaknesses = (props.findingChanges?.appearedWeaknesses || []).map(f => normalizeFinding(f, 'WEAKNESS'))
    return sortBySeverity([...vulnerabilities, ...violations, ...weaknesses])
})

const resolvedFindings = computed(() => {
    const vulnerabilities = (props.findingChanges?.resolvedVulnerabilities || []).map(f => normalizeFinding(f, 'VULN'))
    const violations = (props.findingChanges?.resolvedViolations || []).map(f => normalizeFinding(f, 'VIOLATION'))
    const weaknesses = (props.findingChanges?.resolvedWeaknesses || []).map(f => normalizeFinding(f, 'WEAKNESS'))
    return sortBySeverity([...vulnerabilities, ...violations, ...weaknesses])
})

const severityChangedFindings = computed(() => {
    const vulnerabilities = (props.findingChanges?.severityChangedVulnerabilities || []).map(f => normalizeFinding(f, 'VULN'))
    const weaknesses = (props.findingChanges?.severityChangedWeaknesses || []).map(f => normalizeFinding(f, 'WEAKNESS'))
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
    }
    
    .finding-resolved {
        color: #18a058;
        margin-top: 10px;
    }
    
    .finding-changed {
        color: #f0a020;
        margin-top: 10px;
    }
    
    .empty-state {
        padding: 20px;
        text-align: center;
        color: #999;
    }
    
    ul {
        margin-top: 8px;
    }
    
    code {
        background: #f5f5f5;
        padding: 2px 6px;
        border-radius: 3px;
        font-size: 0.9em;
    }
}
</style>
