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
}
