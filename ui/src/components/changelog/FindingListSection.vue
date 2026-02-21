<template>
    <div v-if="findings.length > 0">
        <h5 :class="titleClass">{{ title }} ({{ findings.length }})</h5>
        <ul>
            <li v-for="finding in findings" :key="`${keyPrefix}-${finding.type}-${finding.findingId}-${finding.affectedComponent}`">
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
</template>

<script lang="ts" setup>
import { NTag, NTooltip, NIcon } from 'naive-ui'
import { Info20Regular } from '@vicons/fluent'
import { getSeverityTagType, getFindingTypeTagType, getFindingUrl, openExternalLink } from '../../utils/findingUtils'

interface Props {
    title: string
    titleClass: string
    keyPrefix: string
    findings: any[]
    richAliases?: boolean
}

withDefaults(defineProps<Props>(), {
    richAliases: false
})
</script>

<style scoped lang="scss">
@use './finding-common';
</style>
