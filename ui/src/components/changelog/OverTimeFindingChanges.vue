<template>
    <div class="over-time-finding-changes">
        <div v-if="dateBuckets.length > 0">
            <p class="over-time-note">
                Finding changes detected by re-scans over the selected period, grouped by the date the change was observed.
            </p>
            <div v-for="bucket in dateBuckets" :key="bucket.dateKey" class="date-bucket">
                <h4 class="date-heading">{{ bucket.dateLabel }}</h4>

                <FindingListSection
                    title="New"
                    title-class="finding-new"
                    :key-prefix="`ot-appeared-${bucket.dateKey}`"
                    :findings="bucket.appeared"
                    description="Findings that first appeared in a release on this date."
                    @kev-click="openKevModal"
                >
                    <template #attribution="{ finding }">
                        <div class="release-attribution">{{ releaseLabel(finding) }}</div>
                    </template>
                </FindingListSection>
                <FindingListSection
                    title="Resolved"
                    title-class="finding-resolved"
                    :key-prefix="`ot-resolved-${bucket.dateKey}`"
                    :findings="bucket.resolved"
                    description="Findings that were no longer detected in a release as of this date."
                    @kev-click="openKevModal"
                >
                    <template #attribution="{ finding }">
                        <div class="release-attribution">{{ releaseLabel(finding) }}</div>
                    </template>
                </FindingListSection>
                <FindingListSection
                    title="Severity increased"
                    title-class="finding-partial"
                    :key-prefix="`ot-severity-${bucket.dateKey}`"
                    :findings="bucket.severityIncreased"
                    description="Findings whose severity was raised on this date."
                    @kev-click="openKevModal"
                >
                    <template #attribution="{ finding }">
                        <div class="release-attribution">
                            {{ releaseLabel(finding) }}
                            <template v-if="finding.previousSeverity">
                                <span class="severity-change">Severity: <strong>{{ finding.previousSeverity }}</strong>
                                    <span class="severity-arrow">→</span>
                                    <strong>{{ finding.severity || 'UNASSIGNED' }}</strong>
                                </span>
                            </template>
                        </div>
                    </template>
                </FindingListSection>
                <FindingListSection
                    title="KEV listed"
                    title-class="finding-inherited"
                    :key-prefix="`ot-kev-${bucket.dateKey}`"
                    :findings="bucket.kevAdded"
                    description="Findings newly flagged as a CISA Known Exploited Vulnerability on this date."
                    @kev-click="openKevModal"
                >
                    <template #attribution="{ finding }">
                        <div class="release-attribution">{{ releaseLabel(finding) }}</div>
                    </template>
                </FindingListSection>
            </div>
            <kev-details-modal v-model:show="showKevModal" :cve-id="kevModalCveId" :org-uuid="orgUuid || ''" />
        </div>
        <div v-else class="empty-state">
            <p class="no-data-hint">No re-scan-driven finding changes were detected in the selected period.</p>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import FindingListSection from './FindingListSection.vue'
import KevDetailsModal from '../KevDetailsModal.vue'
import {
    normalizeFindingChangeRecord,
    sortBySeverityThenId,
    findingChangeRecordKey,
    type NormalizedReleaseFinding
} from '../../utils/findingUtils'
import { resolveKevCveId } from '../../utils/kevService'
import type { MetricsRevisionFindingChange } from '../../types/changelog-sealed'

interface Props {
    overTimeFindingChanges?: MetricsRevisionFindingChange[]
    orgUuid?: string
    // Drill-down mode: when set, only timeline records whose finding matches this
    // type-scoped id key are shown (all releases/components of that one finding).
    findingKeyFilter?: string
}

const props = defineProps<Props>()

// Type-scoped id key for a raw over-time record: identifies a single logical
// finding (e.g. a CVE) across releases/components - deliberately excludes the
// per-release purl/location so "same CVE in two releases" collapses to one
// timeline. Client-side grouping key only (findingChangeRecordKey in findingUtils);
// NOT the backend findingKey used by the server-side timeline drill-down.
function recordFindingKey(rec: MetricsRevisionFindingChange): string | null {
    return findingChangeRecordKey(rec)
}

// Per-release attribution line for a normalized over-time finding (closes #41):
// two identical CVE rows from different releases are now distinguishable.
function releaseLabel(finding: { componentName?: string, version?: string }): string {
    const parts: string[] = []
    if (finding.componentName) parts.push(finding.componentName)
    if (finding.version) parts.push(finding.version)
    if (parts.length === 0) return ''
    return finding.componentName && finding.version
        ? `${finding.componentName}@${finding.version}`
        : parts.join(' ')
}

const showKevModal = ref(false)
const kevModalCveId = ref('')

function openKevModal(finding: any) {
    kevModalCveId.value = resolveKevCveId({ id: finding.findingId, aliases: finding.aliases })
    showKevModal.value = true
}

// A normalized finding carrying the over-time-specific context (release + previousSeverity).
type OverTimeFinding = NormalizedReleaseFinding & {
    componentName: string
    version: string
    releaseUuid: string
    previousSeverity?: string | null
}

interface DateBucket {
    dateKey: string
    dateLabel: string
    appeared: OverTimeFinding[]
    resolved: OverTimeFinding[]
    severityIncreased: OverTimeFinding[]
    kevAdded: OverTimeFinding[]
}

function dayKey(changeDate: string): string {
    // Bucket by calendar day in the viewer's local zone.
    const d = new Date(changeDate)
    if (isNaN(d.getTime())) return changeDate
    return d.toLocaleDateString('en-CA')
}

function dayLabel(changeDate: string): string {
    const d = new Date(changeDate)
    if (isNaN(d.getTime())) return changeDate
    return d.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' })
}

function toOverTimeFinding(rec: MetricsRevisionFindingChange): OverTimeFinding | null {
    const base = normalizeFindingChangeRecord(rec)
    if (!base) return null
    return {
        ...base,
        componentName: rec.componentName,
        version: rec.version,
        releaseUuid: rec.releaseUuid,
        previousSeverity: rec.previousSeverity ?? null
    }
}

const dateBuckets = computed<DateBucket[]>(() => {
    let records = props.overTimeFindingChanges || []
    if (props.findingKeyFilter) {
        records = records.filter(rec => recordFindingKey(rec) === props.findingKeyFilter)
    }
    const byDay = new Map<string, { label: string, records: MetricsRevisionFindingChange[] }>()

    for (const rec of records) {
        const key = dayKey(rec.changeDate)
        let bucket = byDay.get(key)
        if (!bucket) {
            bucket = { label: dayLabel(rec.changeDate), records: [] }
            byDay.set(key, bucket)
        }
        bucket.records.push(rec)
    }

    const keys = Array.from(byDay.keys()).sort((a, b) => b.localeCompare(a)) // descending by day

    return keys.map((key) => {
        const { label, records: dayRecords } = byDay.get(key)!
        const appeared: OverTimeFinding[] = []
        const resolved: OverTimeFinding[] = []
        const severityIncreased: OverTimeFinding[] = []
        const kevAdded: OverTimeFinding[] = []

        for (const rec of dayRecords) {
            const f = toOverTimeFinding(rec)
            if (!f) continue
            switch (rec.changeKind) {
                case 'APPEARED': appeared.push(f); break
                case 'RESOLVED': resolved.push(f); break
                case 'SEVERITY_INCREASED': severityIncreased.push(f); break
                case 'KEV_ADDED': kevAdded.push(f); break
            }
        }

        return {
            dateKey: key,
            dateLabel: label,
            appeared: sortBySeverityThenId(appeared) as OverTimeFinding[],
            resolved: sortBySeverityThenId(resolved) as OverTimeFinding[],
            severityIncreased: sortBySeverityThenId(severityIncreased) as OverTimeFinding[],
            kevAdded: sortBySeverityThenId(kevAdded) as OverTimeFinding[]
        }
    })
})
</script>

<style scoped lang="scss">
@use './finding-common';

.over-time-finding-changes {
    .over-time-note {
        margin-bottom: 16px;
        font-style: italic;
        color: #666;
    }

    .date-bucket {
        margin-bottom: 20px;
        padding-bottom: 8px;
        border-bottom: 1px solid #eee;

        &:last-child {
            border-bottom: none;
        }
    }

    .date-heading {
        margin-top: 16px;
        margin-bottom: 8px;
        color: #444;
        font-weight: 600;
    }

    .release-attribution {
        margin-left: 24px;
        font-size: 0.85em;
        color: #666;
    }

    .severity-change {
        margin-left: 8px;
        color: #666;

        .severity-arrow {
            margin: 0 4px;
        }
    }

    .no-data-hint {
        padding: 20px;
        text-align: center;
        color: #999;
        font-style: italic;
    }
}
</style>
