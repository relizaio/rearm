<template>
    <div class="finding-changes">
        <div v-if="findingChanges">
            <!-- Summary Tags -->
            <div class="summary-tags">
                <n-tooltip trigger="hover" placement="bottom">
                    <template #trigger>
                        <n-tag type="warning" size="small">{{ newFindings.length }} New</n-tag>
                    </template>
                    <span v-if="isOrgLevelView">Findings that appear for the first time across the entire organization in this period.</span>
                    <span v-else>Findings introduced in this component for the first time in this period.</span>
                </n-tooltip>
                <n-tooltip v-if="isOrgLevelView && partiallyResolvedFindings.length > 0" trigger="hover" placement="bottom">
                    <template #trigger>
                        <n-tag type="info" size="small" class="tag-spacing">{{ partiallyResolvedFindings.length }} Partially Resolved</n-tag>
                    </template>
                    <span>Findings resolved in some components but still present in others within this period.</span>
                </n-tooltip>
                <n-tooltip v-if="isOrgLevelView && inheritedTechnicalDebtFindings.length > 0" trigger="hover" placement="bottom">
                    <template #trigger>
                        <n-tag type="error" size="small" class="tag-spacing">{{ inheritedTechnicalDebtFindings.length }} Inherited Debt</n-tag>
                    </template>
                    <span>Findings that existed before this period and remain unresolved — pre-existing technical debt carried across the entire date range.</span>
                </n-tooltip>
                <n-tooltip v-if="!isOrgLevelView && stillPresentFindings.length > 0" trigger="hover" placement="bottom">
                    <template #trigger>
                        <n-tag type="info" size="small" class="tag-spacing">{{ stillPresentFindings.length }} Still Present</n-tag>
                    </template>
                    <span>Findings that existed before this period and remain unresolved in this component.</span>
                </n-tooltip>
                <n-tooltip trigger="hover" placement="bottom">
                    <template #trigger>
                        <n-tag type="success" size="small" class="tag-spacing">{{ fullyResolvedFindings.length }} {{ isOrgLevelView ? 'Fully Resolved' : 'Resolved' }}</n-tag>
                    </template>
                    <span v-if="isOrgLevelView">Findings resolved across all affected components and no longer present anywhere in this period.</span>
                    <span v-else>Findings that were present before and are no longer detected in this component.</span>
                </n-tooltip>
                <n-tooltip trigger="hover" placement="bottom">
                    <template #trigger>
                        <n-tag :type="netChange > 0 ? 'error' : netChange < 0 ? 'success' : 'default'" size="small" class="tag-spacing">
                            Net: {{ netChange > 0 ? '+' : '' }}{{ netChange }}
                        </n-tag>
                    </template>
                    <span>Net change in findings: new findings minus resolved findings for this period.</span>
                </n-tooltip>
                <n-tooltip v-if="newlyKevCount > 0" trigger="hover" placement="bottom">
                    <template #trigger>
                        <n-tag type="error" size="small" :bordered="false" class="tag-spacing worsened-tag">Newly KEV ({{ newlyKevCount }})</n-tag>
                    </template>
                    <span>Findings newly flagged as a CISA Known Exploited Vulnerability within this period.</span>
                </n-tooltip>
                <n-tooltip v-if="severityIncreasedCount > 0" trigger="hover" placement="bottom">
                    <template #trigger>
                        <n-tag type="warning" size="small" :bordered="false" class="tag-spacing worsened-tag">Severity ↑ ({{ severityIncreasedCount }})</n-tag>
                    </template>
                    <span>Findings whose severity was raised within this period.</span>
                </n-tooltip>
            </div>
            
            <FindingListSection title="New Findings" title-class="finding-new" key-prefix="new" :findings="newFindings" :rich-aliases="true"
                :description="isOrgLevelView ? 'Findings that appear for the first time across the entire organization in this period.' : 'Findings introduced in this component for the first time in this period.'"
                @kev-click="openKevModal">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-for="(seg, i) in getAppearedContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        <a v-if="moreCount(finding.appearedInCount, finding.appearedIn) > 0 && canDrillDown" class="more-link" @click.prevent="openAttributionDrawer(finding, 'APPEARED')">+{{ moreCount(finding.appearedInCount, finding.appearedIn) }} more</a>
                        <span v-else-if="moreCount(finding.appearedInCount, finding.appearedIn) > 0" class="more-text">+{{ moreCount(finding.appearedInCount, finding.appearedIn) }} more</span>
                        <span v-if="finding.orgContext?.isNewlyKev" class="worsened-badge worsened-badge-kev" title="Newly flagged as a CISA Known Exploited Vulnerability in this period">KEV added</span>
                        <span v-if="finding.orgContext?.isSeverityIncreased" class="worsened-badge worsened-badge-sev" title="Severity was raised in this period">Severity ↑<template v-if="finding.orgContext?.previousSeverity"> ({{ finding.orgContext.previousSeverity }} → {{ finding.severity || 'UNASSIGNED' }})</template></span>
                        <a v-if="canViewTimeline(finding)" class="timeline-link" @click.prevent="openTimeline(finding)">View timeline</a>
                    </div>
                </template>
            </FindingListSection>

            <FindingListSection v-if="isOrgLevelView && worsenedFindings.length > 0" title="Worsened / Newly KEV" title-class="finding-inherited" key-prefix="worsened" :findings="worsenedFindings" :rich-aliases="true"
                description="Findings still present that got worse in this period — newly listed as a CISA Known Exploited Vulnerability and/or had their severity raised. Findings that are also brand-new appear only under New Findings (badged there)."
                @kev-click="openKevModal">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-if="finding.orgContext?.isNewlyKev" class="worsened-badge worsened-badge-kev" title="Newly flagged as a CISA Known Exploited Vulnerability in this period">KEV added</span>
                        <span v-if="finding.orgContext?.isSeverityIncreased" class="worsened-badge worsened-badge-sev" title="Severity was raised in this period">Severity ↑<template v-if="finding.orgContext?.previousSeverity"> ({{ finding.orgContext.previousSeverity }} → {{ finding.severity || 'UNASSIGNED' }})</template></span>
                        <span v-for="(seg, i) in getStillPresentContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        <a v-if="moreCount(finding.presentInCount, finding.presentIn) > 0 && canDrillDown" class="more-link" @click.prevent="openAttributionDrawer(finding, 'PRESENT')">+{{ moreCount(finding.presentInCount, finding.presentIn) }} more</a>
                        <span v-else-if="moreCount(finding.presentInCount, finding.presentIn) > 0" class="more-text">+{{ moreCount(finding.presentInCount, finding.presentIn) }} more</span>
                        <a v-if="canViewTimeline(finding)" class="timeline-link" @click.prevent="openTimeline(finding)">View timeline</a>
                    </div>
                </template>
            </FindingListSection>

            <FindingListSection v-if="isOrgLevelView" title="Partially Resolved" title-class="finding-partial" key-prefix="partial" :findings="partiallyResolvedFindings" :rich-aliases="true"
                description="Findings resolved in some components but still present in others within this period."
                @kev-click="openKevModal">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-if="finding.orgContext?.isNewlyKev" class="worsened-badge worsened-badge-kev" title="Newly flagged as a CISA Known Exploited Vulnerability in this period">KEV added</span>
                        <span v-if="finding.orgContext?.isSeverityIncreased" class="worsened-badge worsened-badge-sev" title="Severity was raised in this period">Severity ↑<template v-if="finding.orgContext?.previousSeverity"> ({{ finding.orgContext.previousSeverity }} → {{ finding.severity || 'UNASSIGNED' }})</template></span>
                        <span v-for="(seg, i) in getResolvedContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        <a v-if="moreCount(finding.resolvedInCount, finding.resolvedIn) > 0 && canDrillDown" class="more-link" @click.prevent="openAttributionDrawer(finding, 'RESOLVED')">+{{ moreCount(finding.resolvedInCount, finding.resolvedIn) }} more</a>
                        <span v-else-if="moreCount(finding.resolvedInCount, finding.resolvedIn) > 0" class="more-text">+{{ moreCount(finding.resolvedInCount, finding.resolvedIn) }} more</span>
                        <a v-if="canViewTimeline(finding)" class="timeline-link" @click.prevent="openTimeline(finding)">View timeline</a>
                    </div>
                </template>
            </FindingListSection>

            <FindingListSection v-if="isOrgLevelView" title="Inherited Technical Debt" title-class="finding-inherited" key-prefix="inherited" :findings="inheritedTechnicalDebtFindings" :rich-aliases="true"
                description="Findings that existed before this period and remain unresolved — pre-existing technical debt carried across the entire date range."
                @kev-click="openKevModal">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-if="finding.orgContext?.isNewlyKev" class="worsened-badge worsened-badge-kev" title="Newly flagged as a CISA Known Exploited Vulnerability in this period">KEV added</span>
                        <span v-if="finding.orgContext?.isSeverityIncreased" class="worsened-badge worsened-badge-sev" title="Severity was raised in this period">Severity ↑<template v-if="finding.orgContext?.previousSeverity"> ({{ finding.orgContext.previousSeverity }} → {{ finding.severity || 'UNASSIGNED' }})</template></span>
                        <span v-for="(seg, i) in getInheritedDebtContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        <a v-if="moreCount(finding.presentInCount, finding.presentIn) > 0 && canDrillDown" class="more-link" @click.prevent="openAttributionDrawer(finding, 'PRESENT')">+{{ moreCount(finding.presentInCount, finding.presentIn) }} more</a>
                        <span v-else-if="moreCount(finding.presentInCount, finding.presentIn) > 0" class="more-text">+{{ moreCount(finding.presentInCount, finding.presentIn) }} more</span>
                        <a v-if="canViewTimeline(finding)" class="timeline-link" @click.prevent="openTimeline(finding)">View timeline</a>
                    </div>
                </template>
            </FindingListSection>

            <FindingListSection v-if="!isOrgLevelView" title="Still Present" title-class="finding-present" key-prefix="present" :findings="stillPresentFindings" :rich-aliases="true"
                description="Findings that existed before this period and remain unresolved in this component."
                @kev-click="openKevModal">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-if="finding.orgContext?.isNewlyKev" class="worsened-badge worsened-badge-kev" title="Newly flagged as a CISA Known Exploited Vulnerability in this period">KEV added</span>
                        <span v-if="finding.orgContext?.isSeverityIncreased" class="worsened-badge worsened-badge-sev" title="Severity was raised in this period">Severity ↑<template v-if="finding.orgContext?.previousSeverity"> ({{ finding.orgContext.previousSeverity }} → {{ finding.severity || 'UNASSIGNED' }})</template></span>
                        <span v-for="(seg, i) in getStillPresentContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        <a v-if="moreCount(finding.presentInCount, finding.presentIn) > 0 && canDrillDown" class="more-link" @click.prevent="openAttributionDrawer(finding, 'PRESENT')">+{{ moreCount(finding.presentInCount, finding.presentIn) }} more</a>
                        <span v-else-if="moreCount(finding.presentInCount, finding.presentIn) > 0" class="more-text">+{{ moreCount(finding.presentInCount, finding.presentIn) }} more</span>
                        <a v-if="canViewTimeline(finding)" class="timeline-link" @click.prevent="openTimeline(finding)">View timeline</a>
                    </div>
                </template>
            </FindingListSection>

            <FindingListSection :title="isOrgLevelView ? 'Fully Resolved' : 'Resolved'" title-class="finding-resolved" key-prefix="resolved" :findings="fullyResolvedFindings" :rich-aliases="true"
                :description="isOrgLevelView ? 'Findings resolved across all affected components and no longer present anywhere in this period.' : 'Findings that were present before and are no longer detected in this component.'"
                @kev-click="openKevModal">
                <template #attribution="{ finding }">
                    <div v-if="showAttribution" class="attribution">
                        <span v-for="(seg, i) in getResolvedContextSegments(finding)" :key="i" class="attribution-context"><router-link v-if="seg.releaseUuid" :to="{ name: 'ReleaseView', params: { uuid: seg.releaseUuid } }" class="release-link">{{ seg.text }}</router-link><span v-else>{{ seg.text }}</span></span>
                        <a v-if="moreCount(finding.resolvedInCount, finding.resolvedIn) > 0 && canDrillDown" class="more-link" @click.prevent="openAttributionDrawer(finding, 'RESOLVED')">+{{ moreCount(finding.resolvedInCount, finding.resolvedIn) }} more</a>
                        <span v-else-if="moreCount(finding.resolvedInCount, finding.resolvedIn) > 0" class="more-text">+{{ moreCount(finding.resolvedInCount, finding.resolvedIn) }} more</span>
                        <a v-if="canViewTimeline(finding)" class="timeline-link" @click.prevent="openTimeline(finding)">View timeline</a>
                    </div>
                </template>
            </FindingListSection>

            <kev-details-modal v-model:show="showKevModal" :cve-id="kevModalCveId" :org-uuid="orgUuid || ''" />

            <n-drawer v-model:show="showTimeline" :width="560" placement="right">
                <n-drawer-content :title="timelineTitle" closable>
                    <n-spin :show="timelineLoading">
                        <OverTimeFindingChanges
                            :over-time-finding-changes="timelineItems"
                            :finding-key-filter="''"
                            :org-uuid="orgUuid"
                        />
                        <div v-if="timelineItems.length < timelineTotal" class="drawer-load-more">
                            <n-button size="small" :loading="timelineLoading" @click="loadMoreTimeline">Load more ({{ timelineItems.length }} of {{ timelineTotal }})</n-button>
                        </div>
                    </n-spin>
                </n-drawer-content>
            </n-drawer>

            <n-drawer v-model:show="showAttributionDrawer" :width="560" placement="right">
                <n-drawer-content :title="attributionTitle" closable>
                    <n-spin :show="attributionLoading">
                        <p class="drawer-total">{{ attributionTotal }} total</p>
                        <ul class="attribution-list">
                            <li v-for="item in attributionItems" :key="`${item.releaseUuid}-${item.componentUuid}`">
                                <router-link :to="{ name: 'ReleaseView', params: { uuid: item.releaseUuid } }" class="release-link">{{ attributionLabel(item) }}</router-link>
                            </li>
                        </ul>
                        <div v-if="attributionItems.length === 0 && !attributionLoading" class="drawer-empty">No entries.</div>
                        <div v-if="attributionItems.length < attributionTotal" class="drawer-load-more">
                            <n-button size="small" :loading="attributionLoading" @click="loadMoreAttribution">Load more ({{ attributionItems.length }} of {{ attributionTotal }})</n-button>
                        </div>
                    </n-spin>
                </n-drawer-content>
            </n-drawer>
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
import { NTag, NTooltip, NDrawer, NDrawerContent, NSpin, NButton } from 'naive-ui'
import FindingListSection from './FindingListSection.vue'
import OverTimeFindingChanges from './OverTimeFindingChanges.vue'
import KevDetailsModal from '../KevDetailsModal.vue'
import { getSeverityIndex } from '../../utils/findingUtils'
import { resolveKevCveId } from '../../utils/kevService'
import {
    fetchFindingAttribution,
    fetchFindingChangeTimeline,
    type ComponentAttributionEntry
} from '../../utils/changelogQueries'
import type {
    FindingChangesWithAttribution,
    VulnerabilityWithAttribution,
    ViolationWithAttribution,
    WeaknessWithAttribution,
    OrgLevelContext
} from '../../types/changelog-attribution'
import type { MetricsRevisionFindingChange } from '../../types/changelog-sealed'

type AttributionBucket = 'APPEARED' | 'PRESENT' | 'RESOLVED'

interface Props {
    findingChanges?: FindingChangesWithAttribution
    showAttribution?: boolean
    orgUuid?: string
    // Flat re-scan timeline (same GraphQL payload) - retained for backward compat
    // but no longer used to gate/populate the timeline drawer (which now fetches
    // server-side; the inline payload is capped and can miss older events).
    overTimeFindingChanges?: MetricsRevisionFindingChange[]
    // Scope of the "first occurrence in ..." attribution text. Defaults to
    // 'organization' so the org changelog view renders unchanged; the
    // component/product posture rollup passes 'component' / 'product'.
    scopeLabel?: 'organization' | 'component' | 'product'
    // Drill-down context: date window + scope for the "+N more" attribution
    // drawer and the per-finding timeline drawer (both fetched server-side).
    dateFrom?: string
    dateTo?: string
    componentUuid?: string
    branchUuid?: string
    perspectiveUuid?: string
}

const props = withDefaults(defineProps<Props>(), {
    showAttribution: true,
    scopeLabel: 'organization'
})

// Drill-down is available only when we have the org + date window to scope a query.
const canDrillDown = computed(() => !!(props.orgUuid && props.dateFrom && props.dateTo))

const showKevModal = ref(false)
const kevModalCveId = ref('')

function openKevModal(finding: any) {
    kevModalCveId.value = resolveKevCveId({ id: finding.findingId, aliases: finding.aliases })
    showKevModal.value = true
}

// ---- Per-finding timeline drill-down (drawer, fetched server-side) ----
// The inline overTimeFindingChanges payload is capped to the newest 1000 events
// so a client-side per-finding filter can MISS older events. The drawer instead
// pages the finding's full timeline via fetchFindingChangeTimeline.
const showTimeline = ref(false)
const timelineTitle = ref('')
const timelineItems = ref<MetricsRevisionFindingChange[]>([])
const timelineTotal = ref(0)
const timelinePage = ref(0)
const timelineLoading = ref(false)
const timelineFindingKey = ref('')
const TIMELINE_PAGE_SIZE = 50

// A timeline link is offered whenever drill-down context is present - the events
// live in a separate store that the changelog payload no longer carries, so we
// gate on scope availability rather than the (capped/absent) inline array.
function canViewTimeline(finding: NormalizedFinding): boolean {
    return canDrillDown.value && !!finding.findingKey
}

async function loadTimelinePage(page: number) {
    if (!props.orgUuid || !props.dateFrom || !props.dateTo) return
    timelineLoading.value = true
    try {
        const res = await fetchFindingChangeTimeline({
            orgUuid: props.orgUuid,
            componentUuid: props.componentUuid,
            branchUuid: props.branchUuid,
            perspectiveUuid: props.perspectiveUuid,
            dateFrom: props.dateFrom,
            dateTo: props.dateTo,
            findingKey: timelineFindingKey.value,
            page,
            pageSize: TIMELINE_PAGE_SIZE
        })
        if (page === 0) {
            timelineItems.value = res.items as MetricsRevisionFindingChange[]
        } else {
            timelineItems.value = [...timelineItems.value, ...(res.items as MetricsRevisionFindingChange[])]
        }
        timelineTotal.value = res.total
        timelinePage.value = res.page
    } catch (error) {
        console.error('Error fetching finding change timeline:', error)
    } finally {
        timelineLoading.value = false
    }
}

function openTimeline(finding: NormalizedFinding) {
    timelineFindingKey.value = finding.findingKey
    timelineTitle.value = `Timeline — ${finding.findingId}`
    timelineItems.value = []
    timelineTotal.value = 0
    timelinePage.value = 0
    showTimeline.value = true
    loadTimelinePage(0)
}

function loadMoreTimeline() {
    loadTimelinePage(timelinePage.value + 1)
}

// ---- "+N more" attribution drill-down (drawer, fetched server-side) ----
const showAttributionDrawer = ref(false)
const attributionTitle = ref('')
const attributionItems = ref<ComponentAttributionEntry[]>([])
const attributionTotal = ref(0)
const attributionPage = ref(0)
const attributionLoading = ref(false)
const attributionFindingKey = ref('')
const attributionFindingKind = ref<'VULNERABILITY' | 'VIOLATION' | 'WEAKNESS'>('VULNERABILITY')
const attributionBucket = ref<AttributionBucket>('APPEARED')
const ATTRIBUTION_PAGE_SIZE = 50

const BUCKET_LABELS: Record<AttributionBucket, string> = {
    APPEARED: 'Appeared in',
    PRESENT: 'Present in',
    RESOLVED: 'Resolved in'
}

function findingKindOf(finding: NormalizedFinding): 'VULNERABILITY' | 'VIOLATION' | 'WEAKNESS' {
    switch (finding.type) {
        case 'VULN': return 'VULNERABILITY'
        case 'VIOLATION': return 'VIOLATION'
        default: return 'WEAKNESS'
    }
}

function attributionLabel(item: ComponentAttributionEntry): string {
    if (item.componentName) return `${item.componentName}@${item.releaseVersion}`
    if (item.branchName) return `${item.branchName} ${item.releaseVersion}`
    return item.releaseVersion
}

async function loadAttributionPage(page: number) {
    if (!props.orgUuid || !props.dateFrom || !props.dateTo) return
    attributionLoading.value = true
    try {
        const res = await fetchFindingAttribution({
            orgUuid: props.orgUuid,
            componentUuid: props.componentUuid,
            branchUuid: props.branchUuid,
            perspectiveUuid: props.perspectiveUuid,
            dateFrom: props.dateFrom,
            dateTo: props.dateTo,
            findingKind: attributionFindingKind.value,
            findingKey: attributionFindingKey.value,
            bucket: attributionBucket.value,
            page,
            pageSize: ATTRIBUTION_PAGE_SIZE
        })
        if (page === 0) {
            attributionItems.value = res.items
        } else {
            attributionItems.value = [...attributionItems.value, ...res.items]
        }
        attributionTotal.value = res.total
        attributionPage.value = res.page
    } catch (error) {
        console.error('Error fetching finding attribution:', error)
    } finally {
        attributionLoading.value = false
    }
}

function openAttributionDrawer(finding: NormalizedFinding, bucket: AttributionBucket) {
    attributionFindingKey.value = finding.findingKey
    attributionFindingKind.value = findingKindOf(finding)
    attributionBucket.value = bucket
    attributionTitle.value = `${BUCKET_LABELS[bucket]} — ${finding.findingId}`
    attributionItems.value = []
    attributionTotal.value = 0
    attributionPage.value = 0
    showAttributionDrawer.value = true
    loadAttributionPage(0)
}

function loadMoreAttribution() {
    loadAttributionPage(attributionPage.value + 1)
}

// "+N more" count for a bucket: total (from *InCount) minus the inline preview
// entries already shown. Returns 0 when nothing is hidden.
function moreCount(count: number, shown: unknown[]): number {
    return Math.max(0, (count || 0) - (shown ? shown.length : 0))
}

type AttributionSegment = {
    text: string
    releaseUuid?: string
}

type NormalizedFinding = {
    findingId: string
    findingKey: string
    affectedComponent: string
    severity?: string
    aliases?: Array<{ aliasId: string }>
    knownExploited?: boolean
    type: 'VULN' | 'VIOLATION' | 'WEAKNESS'
    typeLabel: string
    appearedIn: any[]
    resolvedIn: any[]
    presentIn: any[]
    appearedInCount: number
    resolvedInCount: number
    presentInCount: number
    isNetAppeared: boolean
    isNetResolved: boolean
    isStillPresent: boolean
    orgContext?: OrgLevelContext
    analysisState?: string | null
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
    findingKey: vuln.findingKey,
    affectedComponent: vuln.purl,
    severity: vuln.severity,
    aliases: vuln.aliases,
    knownExploited: !!vuln.knownExploited,
    type: 'VULN',
    typeLabel: 'VULNERABILITY',
    appearedIn: vuln.appearedIn,
    resolvedIn: vuln.resolvedIn,
    presentIn: vuln.presentIn,
    appearedInCount: vuln.appearedInCount,
    resolvedInCount: vuln.resolvedInCount,
    presentInCount: vuln.presentInCount,
    isNetAppeared: vuln.isNetAppeared,
    isNetResolved: vuln.isNetResolved,
    isStillPresent: vuln.isStillPresent,
    orgContext: vuln.orgContext,
    analysisState: vuln.analysisState
})

const normalizeViolation = (violation: ViolationWithAttribution): NormalizedFinding => ({
    findingId: violation.type,
    findingKey: violation.findingKey,
    affectedComponent: violation.purl,
    type: 'VIOLATION',
    typeLabel: 'VIOLATION',
    appearedIn: violation.appearedIn,
    resolvedIn: violation.resolvedIn,
    presentIn: violation.presentIn,
    appearedInCount: violation.appearedInCount,
    resolvedInCount: violation.resolvedInCount,
    presentInCount: violation.presentInCount,
    isNetAppeared: violation.isNetAppeared,
    isNetResolved: violation.isNetResolved,
    isStillPresent: violation.isStillPresent,
    orgContext: violation.orgContext,
    analysisState: violation.analysisState
})

const normalizeWeakness = (weakness: WeaknessWithAttribution): NormalizedFinding => ({
    findingId: weakness.cweId || weakness.ruleId || '',
    findingKey: weakness.findingKey,
    affectedComponent: weakness.location,
    severity: weakness.severity || undefined,
    type: 'WEAKNESS',
    typeLabel: 'WEAKNESS',
    appearedIn: weakness.appearedIn,
    resolvedIn: weakness.resolvedIn,
    presentIn: weakness.presentIn,
    appearedInCount: weakness.appearedInCount,
    resolvedInCount: weakness.resolvedInCount,
    presentInCount: weakness.presentInCount,
    isNetAppeared: weakness.isNetAppeared,
    isNetResolved: weakness.isNetResolved,
    isStillPresent: weakness.isStillPresent,
    orgContext: weakness.orgContext,
    analysisState: weakness.analysisState
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
    sortBySeverity(allFindings.value.filter(f => f.orgContext && (
        f.orgContext.isInheritedInAllComponents ||
        (f.isStillPresent && !f.orgContext.isNewToOrganization && !f.orgContext.isPartiallyResolved && !f.orgContext.isFullyResolved)
    )))
)

const orgFullyResolvedFindings = computed(() =>
    sortBySeverity(allFindings.value.filter(f => f.orgContext?.isFullyResolved))
)

// A finding "worsened" if it newly became KEV and/or had its severity raised
// this period. Nullable fields => coerce to boolean so flag-off payloads are inert.
function isWorsened(f: NormalizedFinding): boolean {
    return !!(f.orgContext?.isNewlyKev || f.orgContext?.isSeverityIncreased)
}

// Worsened section lists worsened findings that are NOT already surfaced under
// New Findings - a KEV/severity-worsened finding that is also brand-new is
// badge-only in New (signed-off decision: no double-listing).
const worsenedFindings = computed(() =>
    sortBySeverity(allFindings.value.filter(f => isWorsened(f) && !f.orgContext?.isNewToOrganization))
)

// Headline counts. Prefer the backend rollup totals; fall back to a client-side
// count of the org-context flags when the rollup fields are absent (both are
// nullable - 0 when the backend feature flag is off, hiding the tags).
const newlyKevCount = computed(() => {
    const total = props.findingChanges?.totalNewlyKev
    if (total != null) return total
    return allFindings.value.filter(f => !!f.orgContext?.isNewlyKev).length
})

const severityIncreasedCount = computed(() => {
    const total = props.findingChanges?.totalSeverityIncreased
    if (total != null) return total
    return allFindings.value.filter(f => !!f.orgContext?.isSeverityIncreased).length
})

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
            segments.push({ text: ` (first occurrence in ${props.scopeLabel})` })
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
    if (finding.orgContext && finding.orgContext.componentCount > 0) {
        const presentAttrs = finding.presentIn
            .filter(p => finding.orgContext!.affectedComponentNames.includes(p.componentName))
        const totalComponents = finding.orgContext.componentCount
        const prefix = finding.orgContext.isInheritedInAllComponents
            ? `Present in all ${totalComponents} components since first release: `
            : `Present in ${totalComponents} component${totalComponents > 1 ? 's' : ''} since first release: `
        const segments: AttributionSegment[] = [{ text: prefix }]
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
@use './finding-common';

.finding-changes {
    .worsened-tag {
        font-weight: 600;
    }

    .worsened-badge {
        display: inline-block;
        margin-right: 8px;
        padding: 0 6px;
        border-radius: 3px;
        font-size: 0.85em;
        font-style: normal;
        font-weight: 600;
        line-height: 1.5;
        white-space: nowrap;
    }

    .worsened-badge-kev {
        background: #fde3e3;
        color: #a5211b;
    }

    .worsened-badge-sev {
        background: #fdf0d9;
        color: #a06600;
    }

    .timeline-link {
        margin-left: 8px;
        color: #2080f0;
        font-style: normal;
        cursor: pointer;
        text-decoration: none;

        &:hover {
            text-decoration: underline;
        }
    }

    .more-link {
        margin-left: 6px;
        color: #2080f0;
        font-style: normal;
        cursor: pointer;
        text-decoration: none;

        &:hover {
            text-decoration: underline;
        }
    }

    .more-text {
        margin-left: 6px;
        color: #888;
        font-style: italic;
    }
}

.drawer-total {
    margin-bottom: 10px;
    color: #666;
    font-style: italic;
}

.attribution-list {
    list-style: none;
    padding-left: 0;
    margin: 0;

    li {
        padding: 4px 0;
        border-bottom: 1px solid #f0f0f0;

        &:last-child {
            border-bottom: none;
        }
    }

    .release-link {
        color: #2080f0;
        text-decoration: none;

        &:hover {
            text-decoration: underline;
        }
    }
}

.drawer-empty {
    padding: 12px 0;
    color: #999;
    font-style: italic;
}

.drawer-load-more {
    margin-top: 12px;
    text-align: center;
}
</style>
