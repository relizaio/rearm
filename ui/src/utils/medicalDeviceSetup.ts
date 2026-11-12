import gql from 'graphql-tag'
import type { DeviceWindowState } from './deviceSupportWindowInput'
import { deviceWindowMutationInput } from './componentDeviceWindow'
import {
    isSchemaDriftError, loadWithSchemaDriftFallback, withoutAdditiveFields, type DriftFallbackClient
} from './graphqlDriftFallback'
import { packageStateOf } from './packageSettings'

/**
 * The Medical device tab (FDAW-18, B5): two questions and one Save. Question 1 (with the 21 CFR 821
 * checkbox) is the component's deviceClass; question 2 is the product's FDA_PREMARKET package
 * settings switch. One `setUpMedicalDevice` writes the device answers and, each at its own gate, the
 * organization's export switch, its `fda` SBOM profile and package settings and the product's row;
 * the server says which steps landed. The UI holds no ADMIN read, so it never decides who may do
 * what: it offers Save to every writer and renders the steps the server reports.
 */

export const QUESTION_TRACKED_821 = 'Subject to an FDA tracking order (21 CFR 821)'
export const QUESTION_FDA_PREMARKET = 'Prepare FDA premarket submissions (US)?'
export const MEDICAL_DEVICE_SAVED = 'Medical device settings saved'
export const MEDICAL_DEVICE_SETUP_LINK_TEXT = 'Settings > Medical device'

export function questionIsMedical (name: string | null | undefined): string {
    return 'Is ' + (name || 'this component') + ' a medical device?'
}

/** FDAW-78 3.6 (O-4): the profile `fda` is the FDA recipe; `default` or none names no profile. */
export function fdaDefaultsLine (orgPackageProfile: string | null | undefined): string {
    if (orgPackageProfile === 'fda') return 'Using organization defaults (FDA recipe)'
    if (!orgPackageProfile || orgPackageProfile === 'default') return 'Using organization defaults'
    return 'Using organization defaults (SBOM profile ' + orgPackageProfile + ')'
}

export function fdaCustomizedLine (profile: string | null | undefined): string {
    return profile === 'fda' ? 'Customized for this product: FDA recipe' : 'Customized for this product: SBOM profile ' + profile
}

/** Above the status lines while one is not done (FDAW-78 3.6, C1): what Save does and who the organization steps need. */
export const DEVICE_SETUP_INTRO = 'Save completes the steps below that your role allows. The organization steps need an organization administrator.'

/** The must-fix alert's link text: the path the server's setupMustFix sentence names (FDAW-78 3.6, F-4). */
export const SBOM_PROFILES_LINK_TEXT = 'Organization Settings > Admin Settings > SBOM Profiles'

/** F-8: answering No to question 1 never turns the product's FDA row off; the line says where that is done. */
export function fdaStillOnLine (name: string | null | undefined): string {
    return 'FDA premarket submissions stay switched on for ' + (name || 'this product')
        + ': switch them off under Settings > Regulatory'
}

// ---- The Submissions tab of a product that is not set up (3.5) ----------------------------------

/** The empty state, before its link (the link text is MEDICAL_DEVICE_SETUP_LINK_TEXT). */
export function submissionsNotSetUpLead (name: string | null | undefined): string {
    return 'No submission yet. ' + (name || 'This product') + ' is not set up for FDA premarket submissions: set it up under '
}
export const SUBMISSIONS_NOT_SET_UP_TAIL = ' (one save).'
/** The "Not set up" cell of a kind switched off for the product, before its link. */
export function submissionsNotSetUpCell (kindLabel: string): string {
    return 'Not set up: ' + kindLabel + ' is switched off for this product.'
}
export const SUBMISSIONS_NOT_SET_UP_CELL_LINK = 'Set it up under Settings > Medical device'
export const SUBMISSIONS_ADMIN_DOES_THIS = '(the organization steps need an organization administrator)'

// ---- Question 1 <-> deviceClass -------------------------------------------------------------------

export interface DeviceAnswers {
    isDevice: boolean
    tracked: boolean
}

export function deviceClassFromAnswers (isDevice: boolean, tracked: boolean): string {
    if (!isDevice) return 'NONE'
    return tracked ? 'MEDICAL_TRACKED' : 'MEDICAL_UNTRACKED'
}

export function answersFromDeviceClass (deviceClass: string | null | undefined): DeviceAnswers {
    if (deviceClass === 'MEDICAL_TRACKED') return { isDevice: true, tracked: true }
    if (deviceClass === 'MEDICAL_UNTRACKED') return { isDevice: true, tracked: false }
    return { isDevice: false, tracked: false }
}

// ---- The UDI fields (F-6) -------------------------------------------------------------------------

export interface DeviceProfileForm {
    issuingAgency: string | null
    udiBearing: boolean
    brandName: string
    companyName: string
    gmdnCode: string
}

export const EMPTY_DEVICE_PROFILE: DeviceProfileForm = {
    issuingAgency: null, udiBearing: false, brandName: '', companyName: '', gmdnCode: ''
}

/** The form of a read medical profile; a null profile reads as the empty form. */
export function deviceProfileFormOf (profile: any): DeviceProfileForm {
    return {
        issuingAgency: profile?.issuingAgency ?? null,
        udiBearing: profile?.udiBearing === true,
        brandName: profile?.gudidDefaults?.brandName ?? '',
        companyName: profile?.gudidDefaults?.companyName ?? '',
        gmdnCode: profile?.gudidDefaults?.gmdnCode ?? ''
    }
}

/** Whether any of the five UDI fields differs from what was read. */
export function deviceProfileDiffers (edit: DeviceProfileForm, baseline: DeviceProfileForm): boolean {
    return edit.issuingAgency !== baseline.issuingAgency || edit.udiBearing !== baseline.udiBearing
        || edit.brandName !== baseline.brandName || edit.companyName !== baseline.companyName
        || edit.gmdnCode !== baseline.gmdnCode
}

function blankToNull (v: string | null | undefined): string | null {
    const t = (v ?? '').trim()
    return t ? t : null
}

/** The whole MedicalProfileInput: the server replaces the profile whole, so every field goes. */
export function medicalProfileInput (p: DeviceProfileForm): Record<string, unknown> {
    const gudid = { brandName: blankToNull(p.brandName), companyName: blankToNull(p.companyName), gmdnCode: blankToNull(p.gmdnCode) }
    return {
        issuingAgency: p.issuingAgency,
        udiBearing: p.udiBearing === true,
        gudidDefaults: gudid.brandName || gudid.companyName || gudid.gmdnCode ? gudid : null
    }
}

// ---- Question 2 and the product's row (F-1, F-2) --------------------------------------------------

/**
 * F-2: a product row reads "Customized" when it names another profile than default and the org's, or carries
 * parts other than the one the setup writes itself (FDAW-81: exactly DEVICE_SUPPORT_STATEMENT INCLUDE).
 */
export function isCustomized (row: any, orgPackageProfile: string | null | undefined): boolean {
    if (!row || row.mode === 'FOLLOW_PERSPECTIVE') return false
    if (Array.isArray(row.parts) && row.parts.length > 0 && !onlyStatementIncluded(row.parts)) return true
    const name = row.sbomProfile
    return !!name && name !== 'default' && name !== orgPackageProfile
}

function onlyStatementIncluded (parts: any[]): boolean {
    return parts.length === 1 && parts[0]?.part === 'DEVICE_SUPPORT_STATEMENT'
        && parts[0]?.options?.selection === 'INCLUDE'
}

// ---- The device support statement (FDAW-81, WRITEUP 6.2) ------------------------------------------

export type StatementSetupState = 'INCLUDED' | 'NOT_YET' | 'EXCLUDED'

/**
 * The selection the product's own row stores for DEVICE_SUPPORT_STATEMENT, as the setup save reads it
 * (mirrors MedicalDeviceSetupService.storedStatementSelection): no own row, a FOLLOW_PERSPECTIVE row or no
 * stored selection is NOT_YET.
 */
export function statementSetupState (status: any): StatementSetupState | null {
    if (status === null || status === undefined) return null
    const row = status.productPackage
    if (!row || row.mode === 'FOLLOW_PERSPECTIVE') return 'NOT_YET'
    const entry = Array.isArray(row.parts) ? row.parts.find((p: any) => p?.part === 'DEVICE_SUPPORT_STATEMENT') : undefined
    const sel = entry?.options?.selection
    if (sel === 'INCLUDE') return 'INCLUDED'
    if (sel === 'EXCLUDE') return 'EXCLUDED'
    return 'NOT_YET'
}

export const STATEMENT_INCLUDED_TEXT = 'Device support statement: included in this product\'s FDA premarket packages. '
    + 'Its text comes from the organization\'s three labeling statements; until they are written, a package that '
    + 'includes it is refused.'
export const STATEMENT_NOT_YET_TEXT = 'Device support statement: not set for this product yet. Save with Yes includes '
    + 'it in this product\'s FDA premarket packages; that step needs a product administrator.'
export const STATEMENT_EXCLUDED_TEXT = 'Device support statement: excluded for this product under Settings > Regulatory. '
    + 'Save does not change that choice.'

export function statementSetupLine (state: StatementSetupState): string {
    if (state === 'INCLUDED') return STATEMENT_INCLUDED_TEXT
    if (state === 'EXCLUDED') return STATEMENT_EXCLUDED_TEXT
    return STATEMENT_NOT_YET_TEXT
}

/** F-1: question 2's baseline: the product's own row's state, ENABLED (Yes) when it has none. */
export function fdaPremarketBaseline (status: any): 'ENABLED' | 'DISABLED' {
    const row = status?.productPackage
    if (!row) return 'ENABLED'
    if (row.mode === 'FOLLOW_PERSPECTIVE') return status?.productState === 'ENABLED' ? 'ENABLED' : 'DISABLED'
    return packageStateOf(row)
}

export interface MedicalDeviceSetupDraft {
    deviceClass: string
    deviceClassBaseline: string
    window: DeviceWindowState
    windowBaseline: DeviceWindowState
    profile: DeviceProfileForm
    profileBaseline: DeviceProfileForm
    /** Whether the FDA half (question 2) is shown: a Pro backend answering the status, for a device. */
    fdaShown: boolean
    fdaPremarket: 'ENABLED' | 'DISABLED'
    /** The product's own row, from the status: its name is sent only when it reads "Customized". */
    productPackage: any
    orgPackageProfile: string | null
}

/**
 * The MedicalDeviceSetupInput of the tab's one Save (3.5). The class from the answers; `medicalProfile`
 * whole when a UDI field changed, else null (F-6); under a device class the window part of FDAW-3's rule
 * (`deviceSupportWindow`, or `clearDeviceSupportWindow` on blanked dates), none under NONE; `fdaPremarket`
 * when the FDA half is shown and question 1 is yes, else null (F-8); `fdaSbomProfile` only for a
 * "Customized" row. The caller adds `expectedRevision` with `withExpectedRevision`.
 */
export function medicalDeviceSetupInput (d: MedicalDeviceSetupDraft): Record<string, unknown> {
    const isDevice = d.deviceClass !== 'NONE'
    const input: Record<string, unknown> = { deviceClass: d.deviceClass }
    input.medicalProfile = isDevice && deviceProfileDiffers(d.profile, d.profileBaseline) ? medicalProfileInput(d.profile) : null
    if (isDevice) {
        const w = deviceWindowMutationInput('', '', d.window, d.windowBaseline)
        if (w && 'deviceSupportWindow' in w) input.deviceSupportWindow = w.deviceSupportWindow
        if (w && 'clearDeviceSupportWindow' in w) input.clearDeviceSupportWindow = w.clearDeviceSupportWindow
    }
    input.fdaPremarket = d.fdaShown && isDevice ? d.fdaPremarket : null
    input.fdaSbomProfile = input.fdaPremarket && isCustomized(d.productPackage, d.orgPackageProfile)
        ? d.productPackage.sbomProfile : null
    return input
}

/** F-1, F-10: "Complete setup" shows only for the caller's own pending steps, with both questions yes. */
export function completeSetupOffered (status: any, answers: { isDevice: boolean, fdaPremarket: string | null }): boolean {
    return Array.isArray(status?.pendingForCaller) && status.pendingForCaller.length > 0
        && answers.isDevice === true && answers.fdaPremarket === 'ENABLED'
}

/** F-4: the component row was written by this save only when DEVICE_ANSWERS is WRITTEN. */
export function deviceStepWritten (result: any): boolean {
    const steps = Array.isArray(result?.steps) ? result.steps : []
    return steps.some((s: any) => s?.step === 'DEVICE_ANSWERS' && s?.outcome === 'WRITTEN')
}

export interface SetupStepLine {
    step: string
    text: string
}

/**
 * One line per step the caller could not make, with the server's sentence; none for the others. A sentence
 * an earlier line already carries is not repeated (FDAW-78 X13: the organization sentence came once per step).
 */
export function setupStepLines (result: any): SetupStepLine[] {
    const steps = Array.isArray(result?.steps) ? result.steps : []
    const seen = new Set<string>()
    const out: SetupStepLine[] = []
    for (const s of steps) {
        if (s?.outcome !== 'NOT_AUTHORIZED' || typeof s.text !== 'string' || seen.has(s.text)) continue
        seen.add(s.text)
        out.push({ step: String(s.step), text: s.text })
    }
    return out
}

export interface SetupStatusLine {
    step: string
    done: boolean
    /** What is done, shown as `✓ <text>`. */
    text: string
    /** What is still to do, shown as `Not done yet: <todo>`. */
    todo: string
}

/** The organization steps as the status reads them (FDAW-78 3.6, L10/C1): one line each, done or not. */
export function setupStatusLines (status: any): SetupStatusLine[] {
    if (!status) return []
    const p = status.orgPackageProfile
    return [
        { step: 'ORG_SUPPORT_EXPORT', done: status.supportExport === 'ENABLED',
            text: 'Support information is included in the SBOMs this organization exports',
            todo: 'include support information (level of support, end-of-support dates) in the SBOMs this organization exports' },
        { step: 'ORG_FDA_PROFILE', done: status.fdaProfile === 'INCLUDES_SUPPORT',
            text: 'The FDA recipe for SBOMs is ready (SBOM profile fda)',
            todo: status.fdaProfile === 'EXCLUDES_SUPPORT'
                ? 'the FDA recipe for SBOMs (SBOM profile fda) leaves out support information'
                : 'add the FDA recipe for SBOMs (SBOM profile fda)' },
        { step: 'ORG_PACKAGE_SETTINGS', done: !!p && p !== 'default',
            text: p === 'fda' ? 'The organization\'s FDA premarket defaults use the FDA recipe'
                : 'The organization\'s FDA premarket defaults use the SBOM profile ' + p,
            todo: 'point the organization\'s FDA premarket defaults at the FDA recipe' }
    ]
}

// ---- The documents --------------------------------------------------------------------------------

const STATUS_FIELDS = 'supportExport fdaProfile orgPackageProfile productPackage { mode state enabled sbomProfile parts { part options { selection } } } '
    + 'productState mustFix pending pendingForCaller'

/**
 * The status read, drift-guarded: `medicalDeviceSetupStatus` is an ADDITIVE field, so a backend
 * without it answers the core document (the component's uuid alone) and the FDA half hides, as the
 * device panel hides on a backend without the window.
 */
export const MEDICAL_DEVICE_SETUP_STATUS_QUERY = gql`
    query MedicalDeviceSetupStatus($componentUuid: ID!) {
        component(componentUuid: $componentUuid) { uuid }
        medicalDeviceSetupStatus(component: $componentUuid) { ${STATUS_FIELDS} }  #additive-field
    }`

export const SET_UP_MEDICAL_DEVICE = gql`
    mutation SetUpMedicalDevice($component: ID!, $setup: MedicalDeviceSetupInput!) {
        setUpMedicalDevice(component: $component, setup: $setup) {
            component { uuid revision deviceClass }
            steps { step outcome text }
            status { ${STATUS_FIELDS} }
            warnings { code message }
        }
    }`

export interface MedicalDeviceSetupStatusResult {
    /** False on a backend that cannot answer the status: the FDA half hides. */
    supported: boolean
    status: any
}

/** The status, or `supported: false` on a backend without the field; a real failure is thrown. */
export async function loadMedicalDeviceSetupStatus (
    client: DriftFallbackClient,
    componentUuid: string
): Promise<MedicalDeviceSetupStatusResult> {
    try {
        const r = await loadWithSchemaDriftFallback(client, {
            fullQuery: MEDICAL_DEVICE_SETUP_STATUS_QUERY,
            coreQuery: withoutAdditiveFields(MEDICAL_DEVICE_SETUP_STATUS_QUERY),
            variables: { componentUuid },
            extractPath: (d: any) => d?.medicalDeviceSetupStatus ?? null,
            fetchPolicy: 'no-cache'
        })
        if (r.degraded || !r.data) return { supported: false, status: null }
        return { supported: true, status: r.data }
    } catch (e: any) {
        if (isSchemaDriftError(e)) return { supported: false, status: null }
        throw e
    }
}
