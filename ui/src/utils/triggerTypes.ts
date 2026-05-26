// Shared types for approval policy triggers (input/output events)

export type InputTriggerEvent = {
    uuid: string;
    name: string;
    celExpression: string | null;
    outputEvents: string[];
    // Optional else-branch action list. Fires when celExpression
    // evaluates to false. null/undefined or [] keeps the legacy
    // single-branch behavior (CEL false fires nothing).
    outputEventsOnFalse?: string[];
    enabled?: boolean;
    // Optional precondition CEL — evaluated BEFORE celExpression. If
    // it returns false (or throws) the rule is skipped entirely
    // (neither matched- nor else-branch actions fire). Necessary for
    // else-branch rules because the false branch would otherwise fire
    // on releases with missing data (metrics default to 0). Canonical
    // example: gate metrics rules on `release.firstScanned == true`.
    preconditionCelExpression?: string | null;
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
    celClientPayload?: string | null;
    snapshotApprovalEntry?: string | null;
    snapshotLifecycle?: string | null;
    approvedEnvironment?: string | null;
    checkName?: string | null;
}
