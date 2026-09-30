// Condition builder behind CelExpressionBuilder.vue: the visual rule condition <-> CEL.
//
// Kept out of the component so every clause type can be tested without mounting it. A clause
// left incomplete (no approval entry, an empty lifecycle or branch type list, an empty group) has
// no CEL: it used to compile to `true`, and a "publish on approval" rule saved that way fired on
// an unapproved release. The backend refuses a trivially-true condition as well
// (CelEvaluatorService.validateRuleCondition); isTriviallyTrueCel is its client-side twin.

export type ConditionType = 'LIFECYCLE' | 'BRANCH_TYPE' | 'APPROVAL_ENTRY' | 'ANY_APPROVAL' | 'METRICS' | 'FIRST_SCANNED'
export type MetricField = 'criticalVulns' | 'highVulns' | 'mediumVulns' | 'lowVulns' | 'unassignedVulns'
    | 'securityViolations' | 'operationalViolations' | 'licenseViolations'
export type CompOp = '==' | '!=' | '>' | '>=' | '<' | '<='

export interface LifecycleCondition   { type: 'LIFECYCLE';      lifecycles: string[] }
export interface BranchTypeCondition  { type: 'BRANCH_TYPE';    branchTypes: string[] }
export interface ApprovalEntryCondition { type: 'APPROVAL_ENTRY'; approvalEntry: string; approvalState: 'APPROVED' | 'DISAPPROVED' }
export interface AnyApprovalCondition { type: 'ANY_APPROVAL'; approvalState: 'APPROVED' | 'DISAPPROVED' }
export interface MetricsCondition     { type: 'METRICS';        metricField: MetricField; operator: CompOp; value: number }
export interface FirstScannedCondition { type: 'FIRST_SCANNED'; present: boolean }
export type Condition = LifecycleCondition | BranchTypeCondition | ApprovalEntryCondition | AnyApprovalCondition | MetricsCondition | FirstScannedCondition

export interface ConditionGroup { operator: 'AND' | 'OR'; conditions: Condition[] }
export interface BuilderState   { topOperator: 'AND' | 'OR'; groups: ConditionGroup[] }

// --- Completeness ------------------------------------------------------------

/** Why a clause cannot be compiled yet, or null when it is complete. */
export function incompleteReason(c: Condition): string | null {
    switch (c.type) {
    case 'LIFECYCLE':      return c.lifecycles.length ? null : 'Lifecycle has no lifecycle selected'
    case 'BRANCH_TYPE':    return c.branchTypes.length ? null : 'Branch Type has no branch type selected'
    case 'APPROVAL_ENTRY': return c.approvalEntry ? null : 'Approval Entry has no approval entry selected'
    case 'METRICS':        return (c.value === null || c.value === undefined || Number.isNaN(c.value))
        ? 'Metrics has no value' : null
    case 'ANY_APPROVAL':
    case 'FIRST_SCANNED':  return null
    }
}

/** Every incomplete clause and empty group, named so the user can find it. Empty when the state compiles. */
export function builderIssues(state: BuilderState): string[] {
    const issues: string[] = []
    const multiGroup = state.groups.length > 1
    state.groups.forEach((g, gi) => {
        const where = multiGroup ? `Group ${gi + 1}` : 'Condition'
        if (!g.conditions.length) {
            issues.push(`${where} has no conditions`)
            return
        }
        g.conditions.forEach((c, ci) => {
            const reason = incompleteReason(c)
            if (reason) issues.push(multiGroup || g.conditions.length > 1
                ? `${where}, clause ${ci + 1}: ${reason}`
                : reason)
        })
    })
    return issues
}

// --- Compile (builder -> CEL) ------------------------------------------------

/** CEL for one complete clause, or null when it is incomplete. */
export function conditionToCel(c: Condition): string | null {
    if (incompleteReason(c)) return null
    switch (c.type) {
    case 'LIFECYCLE':
        return c.lifecycles.length === 1
            ? `release.lifecycle == "${c.lifecycles[0]}"`
            : `release.lifecycle in [${c.lifecycles.map(l => `"${l}"`).join(', ')}]`
    case 'BRANCH_TYPE':
        return c.branchTypes.length === 1
            ? `release.branchType == "${c.branchTypes[0]}"`
            : `release.branchType in [${c.branchTypes.map(b => `"${b}"`).join(', ')}]`
    case 'APPROVAL_ENTRY':
        return `release.approvals["${c.approvalEntry}"] == "${c.approvalState}"`
    case 'ANY_APPROVAL':
        return c.approvalState === 'APPROVED' ? 'release.anyApproved' : 'release.anyDisapproved'
    case 'METRICS':
        return `release.${c.metricField} ${c.operator} ${c.value}`
    case 'FIRST_SCANNED':
        return `release.firstScanned == ${c.present}`
    }
}

function groupToCel(g: ConditionGroup): string | null {
    if (!g.conditions.length) return null
    const parts = g.conditions.map(conditionToCel)
    if (parts.some(p => p === null)) return null
    if (parts.length === 1) return parts[0]
    const op = g.operator === 'AND' ? ' && ' : ' || '
    return `(${parts.join(op)})`
}

/**
 * The CEL for a builder state; '' for no groups (a rule with no condition never fires), null
 * while any clause or group is incomplete -- see builderIssues for why.
 */
export function compile(state: BuilderState): string | null {
    if (!state.groups.length) return ''
    const parts = state.groups.map(groupToCel)
    if (parts.some(p => p === null)) return null
    const op = state.topOperator === 'AND' ? ' && ' : ' || '
    return parts.join(op)
}

// --- Parse (CEL -> builder) --------------------------------------------------

/** Split expr at top-level (depth==0) occurrences of && or ||.
 *  Returns { parts, operator } or null if mixed operators or parse error. */
export function splitTopLevel(expr: string): { parts: string[]; operator: 'AND' | 'OR' } | null {
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
export function parseCondition(expr: string): Condition | null {
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
export function parseCelToBuilder(cel: string): BuilderState | null {
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

// --- Trivially-true check ----------------------------------------------------

/** Remove parentheses that enclose the whole expression, however many pairs. */
function stripEnclosingParens(expr: string): string {
    let s = expr.trim()
    while (s.startsWith('(') && s.endsWith(')')) {
        let depth = 0
        let enclosesAll = true
        for (let i = 0; i < s.length; i++) {
            if (s[i] === '(') depth++
            else if (s[i] === ')') depth--
            if (depth === 0 && i < s.length - 1) { enclosesAll = false; break }
        }
        if (!enclosesAll) break
        s = s.slice(1, -1).trim()
    }
    return s
}

/**
 * Whether a condition has `true` as an operand of && / ||, at any nesting depth. Such a clause
 * matches every release; the backend refuses to save it. A condition that is just `true` is how a
 * rule says "always" and is allowed. An expression mixing && and || at one level is not analysed
 * here -- the backend still checks it.
 */
export function isTriviallyTrueCel(cel: string | null | undefined): boolean {
    if (!cel) return false
    return hasTrueOperand(stripEnclosingParens(cel))
}

function hasTrueOperand(expr: string): boolean {
    const split = splitTopLevel(expr)
    if (!split || split.parts.length < 2) return false
    return split.parts.some(p => {
        const part = stripEnclosingParens(p)
        return part === 'true' || hasTrueOperand(part)
    })
}
