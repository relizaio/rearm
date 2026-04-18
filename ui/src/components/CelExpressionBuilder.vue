<template>
    <div style="width: 100%;">
        <!-- Mode toggle -->
        <n-radio-group v-model:value="mode" size="small" style="margin-bottom: 10px;" @update:value="switchMode">
            <n-radio-button value="builder">Visual Builder</n-radio-button>
            <n-radio-button value="cel">CEL Expression</n-radio-button>
        </n-radio-group>

        <!-- Builder mode -->
        <div v-if="mode === 'builder'">
            <n-alert v-if="!canParseCurrentCel" type="warning" style="margin-bottom: 10px; font-size: 13px;">
                This expression cannot be represented in the visual builder. Edit in CEL mode.
            </n-alert>
            <template v-else>
                <!-- Top-level operator (only when >1 group) -->
                <div v-if="builderState.groups.length > 1" style="margin-bottom: 8px; display: flex; align-items: center; gap: 8px;">
                    <span style="font-size: 13px;">Match groups by:</span>
                    <n-radio-group v-model:value="builderState.topOperator" size="small">
                        <n-radio value="AND">All (AND)</n-radio>
                        <n-radio value="OR">Any (OR)</n-radio>
                    </n-radio-group>
                </div>

                <!-- Groups -->
                <div
                    v-for="(group, gi) in builderState.groups"
                    :key="gi"
                    style="border: 1px solid #e0e0e6; border-radius: 6px; padding: 10px 12px; margin-bottom: 8px; background: #fafafa;"
                >
                    <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px;">
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <span style="font-size: 12px; color: #666; font-weight: 500;">Group {{ gi + 1 }}</span>
                            <template v-if="group.conditions.length > 1">
                                <span style="font-size: 12px; color: #666;">match:</span>
                                <n-radio-group v-model:value="group.operator" size="small">
                                    <n-radio value="AND">All</n-radio>
                                    <n-radio value="OR">Any</n-radio>
                                </n-radio-group>
                            </template>
                        </div>
                        <n-button
                            v-if="builderState.groups.length > 1"
                            size="tiny"
                            type="error"
                            quaternary
                            @click="removeGroup(gi)"
                        >Remove Group</n-button>
                    </div>

                    <!-- Conditions -->
                    <div
                        v-for="(cond, ci) in group.conditions"
                        :key="ci"
                        style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap;"
                    >
                        <n-select
                            :value="cond.type"
                            :options="conditionTypeOptions"
                            size="small"
                            style="width: 160px;"
                            @update:value="changeConditionType(group, ci, $event)"
                        />

                        <!-- LIFECYCLE -->
                        <template v-if="cond.type === 'LIFECYCLE'">
                            <n-select
                                v-model:value="(cond as LifecycleCondition).lifecycles"
                                :options="lifecycleOptions"
                                size="small"
                                multiple
                                style="min-width: 200px; flex: 1;"
                                placeholder="Select lifecycles"
                            />
                        </template>

                        <!-- BRANCH_TYPE -->
                        <template v-else-if="cond.type === 'BRANCH_TYPE'">
                            <n-select
                                v-model:value="(cond as BranchTypeCondition).branchTypes"
                                :options="branchTypeOptions"
                                size="small"
                                multiple
                                style="min-width: 200px; flex: 1;"
                                placeholder="Select branch types"
                            />
                        </template>

                        <!-- APPROVAL_ENTRY -->
                        <template v-else-if="cond.type === 'APPROVAL_ENTRY'">
                            <n-select
                                v-model:value="(cond as ApprovalEntryCondition).approvalEntry"
                                :options="approvalEntryOptions"
                                size="small"
                                style="min-width: 180px; flex: 1;"
                                placeholder="Select approval entry"
                            />
                            <n-select
                                v-model:value="(cond as ApprovalEntryCondition).approvalState"
                                :options="approvalStateOptions"
                                size="small"
                                style="width: 150px;"
                            />
                        </template>

                        <!-- ANY_APPROVAL -->
                        <template v-else-if="cond.type === 'ANY_APPROVAL'">
                            <span style="font-size: 13px;">Any approval is</span>
                            <n-select
                                v-model:value="(cond as AnyApprovalCondition).approvalState"
                                :options="approvalStateOptions"
                                size="small"
                                style="width: 150px;"
                            />
                        </template>

                        <!-- METRICS -->
                        <template v-else-if="cond.type === 'METRICS'">
                            <n-select
                                v-model:value="(cond as MetricsCondition).metricField"
                                :options="metricFieldOptions"
                                size="small"
                                style="min-width: 200px; flex: 1;"
                            />
                            <n-select
                                v-model:value="(cond as MetricsCondition).operator"
                                :options="compOpOptions"
                                size="small"
                                style="width: 80px;"
                            />
                            <n-input-number
                                v-model:value="(cond as MetricsCondition).value"
                                size="small"
                                style="width: 90px;"
                                :min="0"
                            />
                        </template>

                        <!-- FIRST_SCANNED -->
                        <template v-else-if="cond.type === 'FIRST_SCANNED'">
                            <n-select
                                v-model:value="(cond as FirstScannedCondition).present"
                                :options="[{label: 'Present', value: true}, {label: 'Not Present', value: false}]"
                                size="small"
                                style="width: 160px;"
                            />
                        </template>

                        <!-- Delete condition -->
                        <n-button size="tiny" quaternary type="error" @click="removeCondition(group, ci)">×</n-button>
                    </div>

                    <n-button size="tiny" dashed @click="addCondition(group)">+ Add Condition</n-button>
                </div>

                <n-button size="small" dashed @click="addGroup" style="margin-top: 2px;">+ Add Group</n-button>
            </template>
        </div>

        <!-- CEL mode -->
        <div v-else>
            <div style="display: flex; align-items: flex-start; gap: 6px;">
                <n-input
                    :value="celText"
                    type="textarea"
                    :rows="4"
                    style="font-family: monospace; flex: 1;"
                    :placeholder="placeholder || 'release.lifecycle == &quot;ASSEMBLED&quot; &amp;&amp; release.criticalVulns == 0'"
                    @update:value="onCelInput"
                />
                <n-tooltip trigger="hover" style="max-width: 620px;">
                    <template #trigger>
                        <n-icon size="16" style="margin-top: 6px; cursor: help; color: #888;"><QuestionCircle20Regular /></n-icon>
                    </template>
                    <div>
                        <strong>Available variables:</strong>
                        <table style="border-collapse: collapse; margin-top: 6px; font-size: 12px;">
                            <tbody>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.lifecycle</code></td><td>string — e.g. "ASSEMBLED", "GENERAL_AVAILABILITY"</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.version</code></td><td>string — e.g. "1.2.3"</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.branchType</code></td><td>string — "RELEASE", "HOTFIX", "FEATURE"</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.firstScanned</code></td><td>bool</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.criticalVulns</code></td><td>int</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.highVulns</code></td><td>int</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.mediumVulns</code></td><td>int</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.lowVulns</code></td><td>int</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.unassignedVulns</code></td><td>int</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.securityViolations</code></td><td>int</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.operationalViolations</code></td><td>int</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.licenseViolations</code></td><td>int</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.approvals["&lt;uuid&gt;"]</code></td><td>string — "APPROVED" or "DISAPPROVED"</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.anyApproved</code></td><td>bool — true if any approval entry is APPROVED</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.anyDisapproved</code></td><td>bool — true if any approval entry is DISAPPROVED</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>approvals</code></td><td>map&lt;string,string&gt; — top-level, keyed by approval entry UUID</td></tr>
                                <tr><td style="padding: 2px 8px 2px 0;"><code>release.component</code></td><td>string (UUID)</td></tr>
                            </tbody>
                        </table>
                        <div style="margin-top: 8px;"><strong>Examples:</strong></div>
                        <pre style="font-size: 11px; margin: 4px 0;">release.lifecycle == "ASSEMBLED"
release.lifecycle == "GENERAL_AVAILABILITY" &amp;&amp; release.criticalVulns == 0
release.approvals["3fa85f64-5717-4562-b3fc-2c963f66afa6"] == "APPROVED"
release.branchType in ["RELEASE", "HOTFIX"] &amp;&amp; release.firstScanned == true &amp;&amp; release.securityViolations == 0

// any / all across approval entries:
release.lifecycle in ["DRAFT","ASSEMBLED","READY_TO_SHIP"] &amp;&amp; release.anyDisapproved
approvals.exists(k, approvals[k] == "DISAPPROVED")
approvals.all(k, approvals[k] == "APPROVED")
approvals.filter(k, approvals[k] == "APPROVED").size() &gt;= 3</pre>
                    </div>
                </n-tooltip>
            </div>
            <div
                v-if="celText && !canParseCurrentCel"
                style="margin-top: 4px; font-size: 12px; color: #888;"
            >
                Note: this expression cannot be converted to visual builder.
            </div>
        </div>

        <!-- Error from parent -->
        <div v-if="error" style="color: #d03050; font-size: 12px; margin-top: 4px;">{{ error }}</div>
    </div>
</template>

<script lang="ts" setup>
import { ref, reactive, watch, nextTick } from 'vue'
import { NRadioGroup, NRadioButton, NRadio, NSelect, NInputNumber, NButton, NAlert, NInput, NTooltip, NIcon } from 'naive-ui'
import { QuestionCircle20Regular } from '@vicons/fluent'
import constants from '../utils/constants'

// ─── Types ───────────────────────────────────────────────────────────────────

type ConditionType = 'LIFECYCLE' | 'BRANCH_TYPE' | 'APPROVAL_ENTRY' | 'ANY_APPROVAL' | 'METRICS' | 'FIRST_SCANNED'
type MetricField = 'criticalVulns' | 'highVulns' | 'mediumVulns' | 'lowVulns' | 'unassignedVulns'
    | 'securityViolations' | 'operationalViolations' | 'licenseViolations'
type CompOp = '==' | '!=' | '>' | '>=' | '<' | '<='

interface LifecycleCondition   { type: 'LIFECYCLE';      lifecycles: string[] }
interface BranchTypeCondition  { type: 'BRANCH_TYPE';    branchTypes: string[] }
interface ApprovalEntryCondition { type: 'APPROVAL_ENTRY'; approvalEntry: string; approvalState: 'APPROVED' | 'DISAPPROVED' }
interface AnyApprovalCondition { type: 'ANY_APPROVAL'; approvalState: 'APPROVED' | 'DISAPPROVED' }
interface MetricsCondition     { type: 'METRICS';        metricField: MetricField; operator: CompOp; value: number }
interface FirstScannedCondition { type: 'FIRST_SCANNED'; present: boolean }
type Condition = LifecycleCondition | BranchTypeCondition | ApprovalEntryCondition | AnyApprovalCondition | MetricsCondition | FirstScannedCondition

interface ConditionGroup { operator: 'AND' | 'OR'; conditions: Condition[] }
interface BuilderState   { topOperator: 'AND' | 'OR'; groups: ConditionGroup[] }

// ─── Props / Emits ───────────────────────────────────────────────────────────

interface Props {
    modelValue: string | null
    approvalEntryOptions: { label: string; value: string }[]
    placeholder?: string
    error?: string
}

const props = withDefaults(defineProps<Props>(), {
    placeholder: '',
    error: ''
})

const emit = defineEmits<{
    (e: 'update:modelValue', value: string): void
}>()

// ─── Static option lists ─────────────────────────────────────────────────────

const conditionTypeOptions = [
    { label: 'Lifecycle',       value: 'LIFECYCLE' },
    { label: 'Branch Type',     value: 'BRANCH_TYPE' },
    { label: 'Approval Entry',  value: 'APPROVAL_ENTRY' },
    { label: 'Any Approval',    value: 'ANY_APPROVAL' },
    { label: 'Metrics',         value: 'METRICS' },
    { label: 'First Scanned',   value: 'FIRST_SCANNED' }
]

const lifecycleOptions = constants.LifecycleValueOptions

const branchTypeOptions = [
    { label: 'Main',    value: 'BASE' },
    { label: 'Feature', value: 'FEATURE' },
    { label: 'Regular', value: 'REGULAR' },
    { label: 'Release', value: 'RELEASE' },
    { label: 'Develop', value: 'DEVELOP' },
    { label: 'Hotfix',  value: 'HOTFIX' }
]

const approvalStateOptions = [
    { label: 'Approved',     value: 'APPROVED' },
    { label: 'Disapproved',  value: 'DISAPPROVED' }
]

const metricFieldOptions = [
    { label: 'Critical Vulnerabilities',    value: 'criticalVulns' },
    { label: 'High Vulnerabilities',        value: 'highVulns' },
    { label: 'Medium Vulnerabilities',      value: 'mediumVulns' },
    { label: 'Low Vulnerabilities',         value: 'lowVulns' },
    { label: 'Unassigned Vulnerabilities',  value: 'unassignedVulns' },
    { label: 'Security Violations',         value: 'securityViolations' },
    { label: 'Operational Violations',      value: 'operationalViolations' },
    { label: 'License Violations',          value: 'licenseViolations' }
]

const compOpOptions = [
    { label: '==', value: '==' },
    { label: '!=', value: '!=' },
    { label: '>',  value: '>' },
    { label: '>=', value: '>=' },
    { label: '<',  value: '<' },
    { label: '<=', value: '<=' }
]

// ─── State ───────────────────────────────────────────────────────────────────

const mode = ref<'builder' | 'cel'>('builder')
const builderState = reactive<BuilderState>({ topOperator: 'AND', groups: [] })
const celText = ref('')
const canParseCurrentCel = ref(true)
const internalChange = ref(false)

// ─── Compile (builder → CEL) ─────────────────────────────────────────────────

function conditionToCel(c: Condition): string {
    switch (c.type) {
    case 'LIFECYCLE':
        if (!c.lifecycles.length) return 'true'
        return c.lifecycles.length === 1
            ? `release.lifecycle == "${c.lifecycles[0]}"`
            : `release.lifecycle in [${c.lifecycles.map(l => `"${l}"`).join(', ')}]`
    case 'BRANCH_TYPE':
        if (!c.branchTypes.length) return 'true'
        return c.branchTypes.length === 1
            ? `release.branchType == "${c.branchTypes[0]}"`
            : `release.branchType in [${c.branchTypes.map(b => `"${b}"`).join(', ')}]`
    case 'APPROVAL_ENTRY':
        if (!c.approvalEntry) return 'true'
        return `release.approvals["${c.approvalEntry}"] == "${c.approvalState}"`
    case 'ANY_APPROVAL':
        return c.approvalState === 'APPROVED' ? 'release.anyApproved' : 'release.anyDisapproved'
    case 'METRICS':
        return `release.${c.metricField} ${c.operator} ${c.value}`
    case 'FIRST_SCANNED':
        return `release.firstScanned == ${c.present}`
    }
}

function groupToCel(g: ConditionGroup): string {
    if (!g.conditions.length) return 'true'
    const parts = g.conditions.map(conditionToCel)
    if (parts.length === 1) return parts[0]
    const op = g.operator === 'AND' ? ' && ' : ' || '
    return `(${parts.join(op)})`
}

function compile(state: BuilderState): string {
    if (!state.groups.length) return ''
    const parts = state.groups.map(groupToCel)
    const op = state.topOperator === 'AND' ? ' && ' : ' || '
    return parts.join(op)
}

// ─── Parse (CEL → builder) ───────────────────────────────────────────────────

/** Split expr at top-level (depth==0) occurrences of && or ||.
 *  Returns { parts, operator } or null if mixed operators or parse error. */
function splitTopLevel(expr: string): { parts: string[]; operator: 'AND' | 'OR' } | null {
    const parts: string[] = []
    let depth = 0
    let start = 0
    let foundOp: 'AND' | 'OR' | null = null

    for (let i = 0; i < expr.length; i++) {
        const ch = expr[i]
        if (ch === '(') { depth++; continue }
        if (ch === ')') { depth--; continue }
        if (depth === 0) {
            if (expr[i] === '&' && expr[i + 1] === '&') {
                if (foundOp && foundOp !== 'AND') return null   // mixed operators
                foundOp = 'AND'
                parts.push(expr.slice(start, i).trim())
                start = i + 2
                i++
                continue
            }
            if (expr[i] === '|' && expr[i + 1] === '|') {
                if (foundOp && foundOp !== 'OR') return null    // mixed operators
                foundOp = 'OR'
                parts.push(expr.slice(start, i).trim())
                start = i + 2
                i++
                continue
            }
        }
    }
    parts.push(expr.slice(start).trim())
    return { parts: parts.filter(p => p), operator: foundOp ?? 'AND' }
}

/** Parse a single condition expression string into a Condition object. */
function parseCondition(expr: string): Condition | null {
    const s = expr.trim()

    // LIFECYCLE == "X"
    let m = s.match(/^release\.lifecycle\s*==\s*"([^"]+)"$/)
    if (m) return { type: 'LIFECYCLE', lifecycles: [m[1]] }

    // LIFECYCLE in ["X", "Y"]
    m = s.match(/^release\.lifecycle\s+in\s+\[([^\]]+)\]$/)
    if (m) {
        const lifecycles = m[1].match(/"([^"]+)"/g)?.map(v => v.replace(/"/g, '')) ?? []
        return { type: 'LIFECYCLE', lifecycles }
    }

    // BRANCH_TYPE == "X"
    m = s.match(/^release\.branchType\s*==\s*"([^"]+)"$/)
    if (m) return { type: 'BRANCH_TYPE', branchTypes: [m[1]] }

    // BRANCH_TYPE in ["X", "Y"]
    m = s.match(/^release\.branchType\s+in\s+\[([^\]]+)\]$/)
    if (m) {
        const branchTypes = m[1].match(/"([^"]+)"/g)?.map(v => v.replace(/"/g, '')) ?? []
        return { type: 'BRANCH_TYPE', branchTypes }
    }

    // APPROVAL_ENTRY: release.approvals["uuid"] == "APPROVED|DISAPPROVED"
    m = s.match(/^release\.approvals\["([^"]+)"\]\s*==\s*"(APPROVED|DISAPPROVED)"$/)
    if (m) return { type: 'APPROVAL_ENTRY', approvalEntry: m[1], approvalState: m[2] as 'APPROVED' | 'DISAPPROVED' }

    // ANY_APPROVAL: release.anyApproved / release.anyDisapproved (optional "== true")
    m = s.match(/^release\.(anyApproved|anyDisapproved)(?:\s*==\s*true)?$/)
    if (m) return { type: 'ANY_APPROVAL', approvalState: m[1] === 'anyApproved' ? 'APPROVED' : 'DISAPPROVED' }

    // FIRST_SCANNED
    m = s.match(/^release\.firstScanned\s*==\s*(true|false)$/)
    if (m) return { type: 'FIRST_SCANNED', present: m[1] === 'true' }

    // METRICS: release.FIELD OP NUMBER
    const metricFields = 'criticalVulns|highVulns|mediumVulns|lowVulns|unassignedVulns|securityViolations|operationalViolations|licenseViolations'
    m = s.match(new RegExp(`^release\\.(${metricFields})\\s*(==|!=|>=|<=|>|<)\\s*(\\d+)$`))
    if (m) return { type: 'METRICS', metricField: m[1] as MetricField, operator: m[2] as CompOp, value: parseInt(m[3], 10) }

    return null
}

/** Parse a group expression (may be wrapped in parens or a bare condition). */
function parseGroup(expr: string): ConditionGroup | null {
    let s = expr.trim()
    // Unwrap outer parens if present
    if (s.startsWith('(') && s.endsWith(')')) {
        s = s.slice(1, -1).trim()
    }

    const split = splitTopLevel(s)
    if (!split) return null

    const conditions: Condition[] = []
    for (const part of split.parts) {
        const cond = parseCondition(part.trim())
        if (!cond) return null
        conditions.push(cond)
    }
    return { operator: split.operator, conditions }
}

/** Parse a full CEL expression into BuilderState, or return null if unparseable. */
function parseCelToBuilder(cel: string): BuilderState | null {
    const s = cel.trim()
    if (!s) return { topOperator: 'AND', groups: [] }

    const topSplit = splitTopLevel(s)
    if (!topSplit) return null

    // Each top-level part is either a paren-wrapped group or a bare condition
    const groups: ConditionGroup[] = []
    for (const part of topSplit.parts) {
        const trimmed = part.trim()
        const isGroup = trimmed.startsWith('(') && trimmed.endsWith(')')

        if (isGroup) {
            const g = parseGroup(trimmed)
            if (!g) return null
            groups.push(g)
        } else {
            // Bare condition or bare multi-condition flat expression
            // Try to parse as a group (splitTopLevel will find inner operator)
            const g = parseGroup(trimmed)
            if (!g) return null
            // Only accept as a single-condition group if the top split already found the top operator
            // and this is a leaf. If this part itself contains operators, we collapse to one group.
            // This handles the case of flat expressions like `c1 && c2` becoming 1 group.
            if (topSplit.parts.length === 1) {
                // The entire expression is one flat group
                return { topOperator: 'AND', groups: [g] }
            }
            groups.push(g)
        }
    }

    // If all top-level parts are bare conditions (no parens), treat them as one group
    const allBare = topSplit.parts.every(p => !p.trim().startsWith('('))
    if (allBare && groups.length > 1) {
        // Merge into a single group using the top-level operator as the group operator
        const conditions: Condition[] = []
        for (const g of groups) {
            conditions.push(...g.conditions)
        }
        return { topOperator: 'AND', groups: [{ operator: topSplit.operator, conditions }] }
    }

    return { topOperator: topSplit.operator, groups }
}

// ─── Builder helpers ──────────────────────────────────────────────────────────

function createDefaultCondition(type: ConditionType): Condition {
    switch (type) {
    case 'LIFECYCLE':      return { type: 'LIFECYCLE', lifecycles: [] }
    case 'BRANCH_TYPE':   return { type: 'BRANCH_TYPE', branchTypes: [] }
    case 'APPROVAL_ENTRY': return { type: 'APPROVAL_ENTRY', approvalEntry: '', approvalState: 'APPROVED' }
    case 'ANY_APPROVAL':   return { type: 'ANY_APPROVAL', approvalState: 'DISAPPROVED' }
    case 'METRICS':        return { type: 'METRICS', metricField: 'criticalVulns', operator: '==', value: 0 }
    case 'FIRST_SCANNED':  return { type: 'FIRST_SCANNED', present: true }
    }
}

function changeConditionType(group: ConditionGroup, index: number, newType: ConditionType) {
    group.conditions.splice(index, 1, createDefaultCondition(newType))
}

function addCondition(group: ConditionGroup) {
    group.conditions.push(createDefaultCondition('LIFECYCLE'))
}

function removeCondition(group: ConditionGroup, index: number) {
    group.conditions.splice(index, 1)
}

function addGroup() {
    builderState.groups.push({ operator: 'AND', conditions: [createDefaultCondition('LIFECYCLE')] })
}

function removeGroup(index: number) {
    builderState.groups.splice(index, 1)
}

// ─── Mode toggle ──────────────────────────────────────────────────────────────

function switchMode(newMode: 'builder' | 'cel') {
    if (newMode === 'cel' && mode.value === 'builder') {
        celText.value = compile(builderState)
        mode.value = 'cel'
        return
    }
    if (newMode === 'builder' && mode.value === 'cel') {
        const parsed = parseCelToBuilder(celText.value)
        if (parsed) {
            Object.assign(builderState, { topOperator: parsed.topOperator })
            builderState.groups.splice(0, builderState.groups.length, ...parsed.groups)
            canParseCurrentCel.value = true
            mode.value = 'builder'
        } else {
            canParseCurrentCel.value = false
            // stay in cel mode — n-radio-group already updated to 'builder' visually,
            // but we force it back to 'cel' on next tick
            nextTick(() => { mode.value = 'cel' })
        }
        return
    }
    mode.value = newMode
}

// ─── CEL textarea input ───────────────────────────────────────────────────────

function onCelInput(val: string) {
    celText.value = val
    canParseCurrentCel.value = !val || parseCelToBuilder(val) !== null
    internalChange.value = true
    emit('update:modelValue', val)
    nextTick(() => { internalChange.value = false })
}

// ─── Builder state → emit ─────────────────────────────────────────────────────

watch(
    builderState,
    () => {
        if (mode.value !== 'builder') return
        const cel = compile(builderState)
        celText.value = cel
        internalChange.value = true
        emit('update:modelValue', cel)
        nextTick(() => { internalChange.value = false })
    },
    { deep: true }
)

// ─── Initialise from parent modelValue ───────────────────────────────────────

watch(
    () => props.modelValue,
    (val) => {
        if (internalChange.value) return
        const text = val ?? ''
        celText.value = text
        const parsed = parseCelToBuilder(text)
        if (parsed) {
            Object.assign(builderState, { topOperator: parsed.topOperator })
            builderState.groups.splice(0, builderState.groups.length, ...parsed.groups)
            canParseCurrentCel.value = true
        } else {
            canParseCurrentCel.value = false
            mode.value = 'cel'
        }
    },
    { immediate: true }
)
</script>
