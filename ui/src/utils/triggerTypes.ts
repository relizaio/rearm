// Shared types for approval policy triggers (input/output events)

export interface Condition {
    type: string;
    approvalEntry?: string;
    approvalState?: string;
    possibleLifecycles?: string[];
    possibleBranchTypes?: string[];
    metricsType?: string;
    comparisonSign?: string;
    metricsValue?: number;
    firstScannedPresent?: boolean;
}

export class UninitializedCondition implements Condition {
    type = ''
}

export class LifecycleCondition implements Condition {
    type = 'LIFECYCLE'
    possibleLifecycles: string[] = []
}

export class BranchTypeCondition implements Condition {
    type = 'BRANCH_TYPE'
    possibleBranchTypes: string[] = []
}

export class ApprovalEntryCondition implements Condition {
    type = 'APPROVAL_ENTRY'
    approvalEntry = ''
    approvalState = ''
}

export class MetricsCondition implements Condition {
    type = 'METRICS'
    metricsType = ''
    comparisonSign = ''
    metricsValue = 0
}

export class FirstScannedCondition implements Condition {
    type = 'FIRST_SCANNED'
    firstScannedPresent = true
}

export type ConditionGroup = {
    conditionGroups: ConditionGroup[];
    matchOperator: string;
    conditions: Condition[];
}

export type InputTriggerEvent = {
    uuid: string;
    name: string;
    conditionGroup: ConditionGroup;
    outputEvents: string[];
}

/**
 * Returns true if any condition group (at any depth) uses OR and mixes
 * APPROVAL_ENTRY conditions with non-APPROVAL_ENTRY conditions at the same level.
 * In that case the backend cannot identify which approval fired the trigger, so
 * VDR snapshots fall back to DATE-type (no approval key, no deduplication).
 */
export function hasMixedOrApprovalGroup(cg: ConditionGroup): boolean {
    if (cg.conditions && cg.matchOperator === 'OR') {
        const hasApproval = cg.conditions.some(c => c.type === 'APPROVAL_ENTRY')
        const hasNonApproval = cg.conditions.some(c => c.type !== 'APPROVAL_ENTRY')
        if (hasApproval && hasNonApproval) return true
    }
    return (cg.conditionGroups ?? []).some(sub => hasMixedOrApprovalGroup(sub))
}

export type OutputTriggerEvent = {
    uuid: string;
    name: string;
    type: string;
    toReleaseLifecycle?: string | null;
    integration?: string;
    users?: string[];
    notificationMessage?: string;
    vcs?: string;
    eventType?: string;
    clientPayload?: string;
    schedule?: string;
    includeSuppressed?: boolean;
}
