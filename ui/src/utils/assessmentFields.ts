import {
    AnalysisState,
    DISCOVERY_METHOD_OPTIONS,
    MAX_CONTROLS,
    RESIDUAL_RISK_OPTIONS,
    RISK_CONTROL_TYPE_OPTIONS,
    RISK_LEVEL_OPTIONS
} from '@/constants/vulnAnalysis'

/**
 * The typed assessment fields of a finding analysis (discovery method, risk level, risk
 * controls, and the residual risk conclusion of FDAW-162) as the Create and Update dialogs edit them, and the permission check both
 * finding analysis views share. Pure: no store, no GraphQL.
 *
 * The backend reads a missing or null key as "keep what is stored" and an empty controls list
 * as "clear the controls", so the dialogs send a key only for a field the user changed
 * (assessmentInputOf). That also keeps a CE backend, whose input has none of the keys, working.
 */

export interface RiskControlRow {
    type: string | null
    description: string
}

export interface AssessmentForm {
    discoveryMethod: string | null
    riskLevel: string | null
    controls: RiskControlRow[]
    residualRiskAcceptability: string | null
    residualRiskRationale: string
    riskFileReference: string
}

export interface VisibleAssessmentFields {
    discoveryMethod: boolean
    riskLevel: boolean
    controls: boolean
    /** The residual risk conclusion (acceptability, rationale, risk file reference): Exploitable only. */
    residualRisk: boolean
}

/** The texts the backend refuses a controls list with (AssessmentFields.validated), the ones a form can produce. */
export const CONTROLS_TOO_MANY = `controls: at most ${MAX_CONTROLS} controls per assessment`
export const CONTROL_TYPE_REQUIRED = 'control type is required'
export const CONTROL_DESCRIPTION_REQUIRED = 'control description is required'

export function emptyAssessmentForm (): AssessmentForm {
    return {
        discoveryMethod: null,
        riskLevel: null,
        controls: [],
        residualRiskAcceptability: null,
        residualRiskRationale: '',
        riskFileReference: ''
    }
}

/** The form of a stored analysis record (or history entry); a record without the fields reads as empty. */
export function assessmentFormOf (record: any): AssessmentForm {
    return {
        discoveryMethod: record?.discoveryMethod || null,
        riskLevel: record?.riskLevel || null,
        controls: (record?.controls || []).map((c: any) => ({ type: c?.type || null, description: c?.description || '' })),
        residualRiskAcceptability: record?.residualRiskAcceptability || null,
        residualRiskRationale: record?.residualRiskRationale || '',
        riskFileReference: record?.riskFileReference || ''
    }
}

/** Is the edition one whose backend has the fields? Unknown is treated as not, so no CE backend is sent them. */
export function assessmentFieldsAvailable (installationType: string | null | undefined): boolean {
    return !!installationType && installationType !== 'OSS'
}

/**
 * Discovery method for every state but In Triage (and none); risk level and controls for Exploitable and Resolved;
 * the residual risk conclusion for Exploitable only.
 */
export function visibleAssessmentFields (state: string | null | undefined): VisibleAssessmentFields {
    const assessed = !!state && state !== AnalysisState.IN_TRIAGE
    const risk = state === AnalysisState.EXPLOITABLE || state === AnalysisState.RESOLVED
    return { discoveryMethod: assessed, riskLevel: risk, controls: risk, residualRisk: state === AnalysisState.EXPLOITABLE }
}

/** Descriptions trimmed; a row with neither a type nor a description dropped. */
export function cleanControls (rows: RiskControlRow[] | null | undefined): RiskControlRow[] {
    return (rows || [])
        .map(r => ({ type: r?.type || null, description: (r?.description || '').trim() }))
        .filter(r => r.type !== null || r.description !== '')
}

/** The first text the backend would refuse these rows with, or null when it accepts them. */
export function validateControls (rows: RiskControlRow[] | null | undefined): string | null {
    const cleaned = cleanControls(rows)
    if (cleaned.length > MAX_CONTROLS) return CONTROLS_TOO_MANY
    for (const row of cleaned) {
        if (!row.type) return CONTROL_TYPE_REQUIRED
        if (!row.description) return CONTROL_DESCRIPTION_REQUIRED
    }
    return null
}

function sameControls (a: RiskControlRow[], b: RiskControlRow[]): boolean {
    return a.length === b.length && a.every((r, i) => r.type === b[i].type && r.description === b[i].description)
}

/**
 * The form as it is to be sent for this state: a field the state hides keeps its initial value,
 * so a value typed before the state changed is not sent.
 */
export function shownAssessmentForm (initial: AssessmentForm, form: AssessmentForm, state: string | null | undefined): AssessmentForm {
    const visible = visibleAssessmentFields(state)
    return {
        discoveryMethod: visible.discoveryMethod ? form.discoveryMethod : initial.discoveryMethod,
        riskLevel: visible.riskLevel ? form.riskLevel : initial.riskLevel,
        controls: visible.controls ? form.controls : initial.controls,
        residualRiskAcceptability: visible.residualRisk ? form.residualRiskAcceptability : initial.residualRiskAcceptability,
        residualRiskRationale: visible.residualRisk ? form.residualRiskRationale : initial.residualRiskRationale,
        riskFileReference: visible.residualRisk ? form.riskFileReference : initial.riskFileReference
    }
}

/**
 * The input keys to send, only for a field the user changed: discoveryMethod and riskLevel when
 * set and different from the initial value; controls (cleaned, [] to clear) when they differ
 * from the initial ones in order or content; residualRiskAcceptability when set and different; the residual risk
 * rationale and the risk file reference, trimmed, when their trimmed value differs from the trimmed initial one
 * ('' to clear a stored text). For a create, initial is emptyAssessmentForm().
 */
export function assessmentInputOf (initial: AssessmentForm, form: AssessmentForm): Record<string, unknown> {
    const input: Record<string, unknown> = {}
    if (form.discoveryMethod && form.discoveryMethod !== initial.discoveryMethod) {
        input.discoveryMethod = form.discoveryMethod
    }
    if (form.riskLevel && form.riskLevel !== initial.riskLevel) {
        input.riskLevel = form.riskLevel
    }
    const controls = cleanControls(form.controls)
    if (!sameControls(controls, cleanControls(initial.controls))) {
        input.controls = controls.map(c => ({ type: c.type, description: c.description }))
    }
    if (form.residualRiskAcceptability && form.residualRiskAcceptability !== initial.residualRiskAcceptability) {
        input.residualRiskAcceptability = form.residualRiskAcceptability
    }
    const rationale = (form.residualRiskRationale || '').trim()
    if (rationale !== (initial.residualRiskRationale || '').trim()) input.residualRiskRationale = rationale
    const reference = (form.riskFileReference || '').trim()
    if (reference !== (initial.riskFileReference || '').trim()) input.riskFileReference = reference
    return input
}

/** The text before the missing labels of residualRiskMissing. */
export const RESIDUAL_RISK_MISSING_PREFIX = 'Needed before a regulatory package can be confirmed or finalized: '

/**
 * The backend's gap labels (AssessmentLabels) for a form that the gate would call incomplete: null unless the state
 * is Exploitable; else 'residual risk acceptability' when no acceptability is chosen and 'residual risk rationale'
 * when the rationale is blank, joined with ', ' after RESIDUAL_RISK_MISSING_PREFIX; null when neither is missing.
 * A warning only: Save is never blocked by it.
 */
export function residualRiskMissing (state: string | null | undefined, form: AssessmentForm): string | null {
    if (state !== AnalysisState.EXPLOITABLE) return null
    const missing: string[] = []
    if (!form.residualRiskAcceptability) missing.push('residual risk acceptability')
    if (!(form.residualRiskRationale || '').trim()) missing.push('residual risk rationale')
    return missing.length ? RESIDUAL_RISK_MISSING_PREFIX + missing.join(', ') : null
}

/**
 * May the user write finding analyses in this organization: an ORGANIZATION permission row of
 * the organization with type ADMIN or the FINDING_ANALYSIS_WRITE function (the server's gate).
 */
export function canWriteFindingAnalysis (permissions: any[] | null | undefined, orgUuid: string | null | undefined): boolean {
    if (!permissions || !orgUuid) return false
    const orgPermission = permissions.find((p: any) =>
        p?.scope === 'ORGANIZATION' && p.org === orgUuid && p.object === orgUuid)
    return !!(orgPermission?.type === 'ADMIN' || orgPermission?.functions?.includes('FINDING_ANALYSIS_WRITE'))
}

const labelOf = (options: Array<{ label: string, value: string }>, value: string) =>
    options.find(o => o.value === value)?.label || value

/**
 * The typed fields of a record or history entry as tooltip lines, by their labels:
 * `Discovery method: <label>`, `Risk level: <label>`, one `Risk control: <kind>: <description>` per control, then
 * `Residual risk: <label>`, `Residual risk rationale: <text>` and `Risk file reference: <text>` when present.
 */
export function assessmentSummaryLines (entry: any): string[] {
    const lines: string[] = []
    if (entry?.discoveryMethod) lines.push(`Discovery method: ${labelOf(DISCOVERY_METHOD_OPTIONS, entry.discoveryMethod)}`)
    if (entry?.riskLevel) lines.push(`Risk level: ${labelOf(RISK_LEVEL_OPTIONS, entry.riskLevel)}`)
    for (const c of entry?.controls || []) {
        lines.push(`Risk control: ${labelOf(RISK_CONTROL_TYPE_OPTIONS, c?.type)}: ${c?.description || ''}`)
    }
    if (entry?.residualRiskAcceptability) {
        lines.push(`Residual risk: ${labelOf(RESIDUAL_RISK_OPTIONS, entry.residualRiskAcceptability)}`)
    }
    if (entry?.residualRiskRationale) lines.push(`Residual risk rationale: ${entry.residualRiskRationale}`)
    if (entry?.riskFileReference) lines.push(`Risk file reference: ${entry.riskFileReference}`)
    return lines
}
