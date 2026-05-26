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
