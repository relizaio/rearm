// Shared types for approval policy triggers (input/output events)

export type InputTriggerEvent = {
    uuid: string;
    name: string;
    celExpression: string | null;
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
    includeSuppressed?: boolean;
    celClientPayload?: string | null;
    snapshotApprovalEntry?: string | null;
    snapshotLifecycle?: string | null;
    approvedEnvironment?: string | null;
}
