export interface Tag {
    key: string,
    value: string
}

export interface DownloadLink {
    uri: string,
    content: string
}

export type ApprovalRole = {
    id: string;
    displayView: string;
}

export type ApprovalRequirement = {
    allowedApprovalRoleIds: string[];
    allowedApprovalRoleIdExpanded: ApprovalRole[];
    requiredNumberOfApprovals: number;
    permittedNumberOfDisapprovals: number;
}

export type ApprovalEntry = {
    uuid: string;
    org: string;
    approvalRequirements: ApprovalRequirement[];
    approvalName: string;
}