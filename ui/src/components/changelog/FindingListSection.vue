<template>
    <div v-if="activeFindings.length > 0 || suppressedFindings.length > 0">
        <div v-if="activeFindings.length > 0">
            <h5 :class="titleClass" class="section-title-row">
                {{ title }} ({{ activeFindings.length }})
                <n-tooltip v-if="description" trigger="hover" placement="right">
                    <template #trigger>
                        <n-icon class="section-info-icon" :size="14"><Info20Regular /></n-icon>
                    </template>
                    {{ description }}
                </n-tooltip>
            </h5>
            <ul>
                <li v-for="finding in activeFindings" :key="`${keyPrefix}-${finding.type}-${finding.findingId}-${finding.affectedComponent}`">
                    <span class="finding-row">
                        <n-tag v-if="finding.severity !== undefined" :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                            {{ finding.severity || 'UNASSIGNED' }}
                        </n-tag>
                        <n-tag :type="getFindingTypeTagType(finding.type)" size="small">{{ finding.typeLabel }}</n-tag>
                        <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                        <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                            <template #trigger>
                                <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                            </template>
                            <template v-if="richAliases">
                                Aliases: <template v-for="(alias, idx) in finding.aliases" :key="alias.aliasId || alias"><span v-if="idx > 0">, </span><a v-if="getFindingUrl(alias.aliasId || alias)" :href="getFindingUrl(alias.aliasId || alias)!" target="_blank" rel="noopener noreferrer" class="alias-tooltip-link" @click.prevent="openExternalLink(getFindingUrl(alias.aliasId || alias)!)">{{ alias.aliasId || alias }}</a><span v-else>{{ alias.aliasId || alias }}</span></template>
                            </template>
                            <template v-else>
                                Aliases: {{ finding.aliases.map((a: any) => typeof a === 'string' ? a : a.aliasId).join(', ') }}
                            </template>
                        </n-tooltip>
                        <span v-if="finding.license"> - License: <strong>{{ finding.license }}</strong></span>
                        <span v-if="finding.violationDetails"> - {{ finding.violationDetails }}</span>
                        <span v-if="finding.ruleId"> ({{ finding.ruleId }})</span>
                        <template v-if="finding.affectedComponent">
                            <span class="in-label">in</span>
                            <code>{{ finding.affectedComponent }}</code>
                        </template>
                    </span>
                    <slot name="attribution" :finding="finding" />
                </li>
            </ul>
        </div>
        
        <div v-if="suppressedFindings.length > 0" class="suppressed-section">
            <h5 :class="titleClass" class="suppressed-header" @click="toggleSuppressed" style="cursor: pointer;">
                <n-icon :size="16" style="vertical-align: middle; margin-right: 4px;">
                    <ChevronRight20Regular v-if="!showSuppressed" />
                    <ChevronDown20Regular v-else />
                </n-icon>
                {{ title }} - Suppressed ({{ suppressedFindings.length }})
            </h5>
            <n-collapse-transition :show="showSuppressed">
                <ul>
                    <li v-for="finding in suppressedFindings" :key="`${keyPrefix}-suppressed-${finding.type}-${finding.findingId}-${finding.affectedComponent}`">
                        <span class="finding-row suppressed-finding">
                            <n-tag type="default" size="small" class="suppressed-tag">{{ getAnalysisStateLabel(finding.analysisState) }}</n-tag>
                            <n-tag v-if="finding.severity !== undefined" :type="getSeverityTagType(finding.severity)" :bordered="false" size="small" :class="'severity-' + (finding.severity || 'UNASSIGNED').toLowerCase()">
                                {{ finding.severity || 'UNASSIGNED' }}
                            </n-tag>
                            <n-tag :type="getFindingTypeTagType(finding.type)" size="small">{{ finding.typeLabel }}</n-tag>
                            <strong><a v-if="getFindingUrl(finding.findingId)" :href="getFindingUrl(finding.findingId)!" target="_blank" rel="noopener noreferrer" class="finding-link" @click.prevent="openExternalLink(getFindingUrl(finding.findingId)!)">{{ finding.findingId }}</a><span v-else>{{ finding.findingId }}</span></strong>
                            <n-tooltip v-if="finding.aliases && finding.aliases.length > 0" trigger="hover">
                                <template #trigger>
                                    <n-icon class="alias-icon" :size="16"><Info20Regular /></n-icon>
                                </template>
                                <template v-if="richAliases">
                                    Aliases: <template v-for="(alias, idx) in finding.aliases" :key="alias.aliasId || alias"><span v-if="idx > 0">, </span><a v-if="getFindingUrl(alias.aliasId || alias)" :href="getFindingUrl(alias.aliasId || alias)!" target="_blank" rel="noopener noreferrer" class="alias-tooltip-link" @click.prevent="openExternalLink(getFindingUrl(alias.aliasId || alias)!)">{{ alias.aliasId || alias }}</a><span v-else>{{ alias.aliasId || alias }}</span></template>
                                </template>
                                <template v-else>
                                    Aliases: {{ finding.aliases.map((a: any) => typeof a === 'string' ? a : a.aliasId).join(', ') }}
                                </template>
                            </n-tooltip>
                            <span v-if="finding.license"> - License: <strong>{{ finding.license }}</strong></span>
                            <span v-if="finding.violationDetails"> - {{ finding.violationDetails }}</span>
                            <span v-if="finding.ruleId"> ({{ finding.ruleId }})</span>
                            <template v-if="finding.affectedComponent">
                                <span class="in-label">in</span>
                                <code>{{ finding.affectedComponent }}</code>
                            </template>
                        </span>
                        <slot name="attribution" :finding="finding" />
                    </li>
                </ul>
            </n-collapse-transition>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import { NTag, NTooltip, NIcon, NCollapseTransition } from 'naive-ui'
import { Info20Regular, ChevronRight20Regular, ChevronDown20Regular } from '@vicons/fluent'
import { getSeverityTagType, getFindingTypeTagType, getFindingUrl, openExternalLink } from '../../utils/findingUtils'

interface Props {
    title: string
    titleClass: string
    keyPrefix: string
    findings: any[]
    richAliases?: boolean
    description?: string
}

const props = withDefaults(defineProps<Props>(), {
    richAliases: false,
    description: undefined
})

const showSuppressed = ref(false)

const isSuppressed = (finding: any): boolean => {
    return finding.analysisState === 'FALSE_POSITIVE' || finding.analysisState === 'NOT_AFFECTED'
}

const activeFindings = computed(() => {
    return props.findings.filter(f => !isSuppressed(f))
})

const suppressedFindings = computed(() => {
    return props.findings.filter(f => isSuppressed(f))
})

const toggleSuppressed = () => {
    showSuppressed.value = !showSuppressed.value
}

const getAnalysisStateLabel = (state: string | null | undefined): string => {
    if (!state) return 'SUPPRESSED'
    switch (state) {
        case 'FALSE_POSITIVE': return 'FALSE POSITIVE'
        case 'NOT_AFFECTED': return 'NOT AFFECTED'
        default: return state
    }
}
</script>

<style scoped lang="scss">
@use './finding-common';

.section-title-row {
    display: flex;
    align-items: center;
    gap: 5px;
}

.section-info-icon {
    opacity: 0.5;
    cursor: default;
    flex-shrink: 0;
    &:hover {
        opacity: 0.9;
    }
}

.suppressed-section {
    margin-top: 12px;
}

.suppressed-header {
    display: flex;
    align-items: center;
    user-select: none;
    
    &:hover {
        opacity: 0.8;
    }
}

.suppressed-finding {
    opacity: 0.7;
}

.suppressed-tag {
    background-color: #f0f0f0;
    color: #666;
    font-weight: 500;
}
</style>
