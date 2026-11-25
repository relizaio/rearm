// The pure form model of an upstream source (ReARM Pro, TPF-185 design 3.6): the starting values,
// the validation, and the inputs of the create and the update mutation.
//
// The two secret values (a bearer token, an API key's secret) are NOT part of the model: they live
// in two refs of the form modal and are handed to these functions as an argument, so that the
// model can be kept, compared and logged by nobody without carrying them.

export enum CredentialChange {
    KEEP = 'KEEP',
    REPLACE = 'REPLACE',
    REMOVE = 'REMOVE',
}

export type UpstreamCredentialKindValue = 'NONE' | 'BEARER_TOKEN' | 'API_KEY'
export type UpstreamDocumentClassValue = 'SBOM' | 'VEX' | 'OTHER'
export type ImportModeValue = 'AUTO_ACCEPT' | 'STAGE' | 'REJECT'

export const DOCUMENT_CLASSES: readonly UpstreamDocumentClassValue[] = ['SBOM', 'VEX', 'OTHER']
export const IMPORT_MODES: readonly ImportModeValue[] = ['AUTO_ACCEPT', 'STAGE', 'REJECT']

export const NAME_MAX = 100
export const ROOT_MAX = 1024
export const SECRET_MAX = 8192
export const INTERVAL_MIN = 60
export const INTERVAL_MAX = 43200
export const INTERVAL_DEFAULT = 1440

export const rootHint = 'The root of the supplier\'s TEA server, without the TEA version at its end, for example https://tea.example.com/tea'
export const rootChangeNeedsCredential = 'The root changes while a credential is stored: enter the credential again or remove it.'
export const nothingToChange = 'Nothing to change.'
export const reServingFieldLabel = 'Include this supplier\'s other documents in our own TEA publications'

/** The form's values, without the two secrets. */
export interface UpstreamSourceFormModel {
    name: string
    root: string
    /** On create: the kind to store. On edit: the kind of the replacing credential (never NONE). */
    credentialKind: UpstreamCredentialKindValue
    /** Edit only; create ignores it. */
    credentialChange: CredentialChange
    keyId: string
    documentClasses: UpstreamDocumentClassValue[]
    vexImportMode: ImportModeValue
    autoIntegrate: boolean
    syncIntervalMinutes: number | null
    reServe: boolean
}

/** The two secret values, as typed. Never trimmed: the server refuses a leading or trailing space with its sentence. */
export interface UpstreamSourceSecrets {
    token: string
    keySecret: string
}

/** The source as the list query serves it, as far as the form reads it. */
export interface LoadedUpstreamSource {
    uuid: string
    name: string
    tea?: { root: string } | null
    credential: { kind: UpstreamCredentialKindValue, keyId?: string | null, setDate?: string | null }
    documentClasses: UpstreamDocumentClassValue[]
    vexImportMode: ImportModeValue
    autoIntegrate: 'ENABLED' | 'DISABLED'
    syncIntervalMinutes: number
    documentReServing: 'WITHHELD' | 'RE_SERVED'
}

export type FormField = 'name' | 'root' | 'documentClasses' | 'vexImportMode' | 'syncIntervalMinutes'
    | 'token' | 'keyId' | 'keySecret' | 'credential'

export type FormProblems = Partial<Record<FormField, string>>

/** What the create form starts with (design 5.3 item 7). */
export function createModel (initialRoot?: string | null): UpstreamSourceFormModel {
    return {
        name: '',
        root: typeof initialRoot === 'string' ? initialRoot : '',
        credentialKind: 'NONE',
        credentialChange: CredentialChange.KEEP,
        keyId: '',
        documentClasses: [...DOCUMENT_CLASSES],
        vexImportMode: 'STAGE',
        autoIntegrate: false,
        syncIntervalMinutes: INTERVAL_DEFAULT,
        reServe: false,
    }
}

/** What the edit form starts with: the stored values, Keep for the credential, nothing secret. */
export function editModel (loaded: LoadedUpstreamSource): UpstreamSourceFormModel {
    return {
        name: loaded.name,
        root: loaded.tea?.root ?? '',
        credentialKind: loaded.credential.kind === 'NONE' ? 'BEARER_TOKEN' : loaded.credential.kind,
        credentialChange: CredentialChange.KEEP,
        keyId: '',
        documentClasses: [...loaded.documentClasses],
        vexImportMode: loaded.vexImportMode,
        autoIntegrate: loaded.autoIntegrate === 'ENABLED',
        syncIntervalMinutes: loaded.syncIntervalMinutes,
        reServe: loaded.documentReServing === 'RE_SERVED',
    }
}

function settingsProblems (model: UpstreamSourceFormModel): FormProblems {
    const problems: FormProblems = {}
    const name = model.name.trim()
    if (!name) problems.name = 'Enter a name.'
    else if (name.length > NAME_MAX) problems.name = `The name is at most ${NAME_MAX} characters.`
    const root = model.root.trim()
    if (!root) problems.root = 'Enter the server root.'
    else if (root.length > ROOT_MAX) problems.root = `The server root is at most ${ROOT_MAX} characters.`
    if (!model.documentClasses.length) problems.documentClasses = 'Choose at least one kind of document.'
    if (!IMPORT_MODES.includes(model.vexImportMode)) problems.vexImportMode = 'Choose what happens to the supplier\'s VEX.'
    const interval = model.syncIntervalMinutes
    if (typeof interval !== 'number' || !Number.isInteger(interval) || interval < INTERVAL_MIN || interval > INTERVAL_MAX) {
        problems.syncIntervalMinutes = `The sync interval is a whole number of minutes from ${INTERVAL_MIN} to ${INTERVAL_MAX}.`
    }
    return problems
}

function secretProblem (value: string, what: string): string | null {
    if (!value) return `Enter the ${what}.`
    if (value.length > SECRET_MAX) return `The ${what} is at most ${SECRET_MAX} characters.`
    return null
}

/** The problems of the credential fields of the given kind; nothing for NONE. */
function credentialProblems (kind: UpstreamCredentialKindValue, keyId: string, secrets: UpstreamSourceSecrets): FormProblems {
    const problems: FormProblems = {}
    if (kind === 'BEARER_TOKEN') {
        const p = secretProblem(secrets.token, 'token')
        if (p) problems.token = p
    } else if (kind === 'API_KEY') {
        const k = secretProblem(keyId, 'key id')
        if (k) problems.keyId = k
        const s = secretProblem(secrets.keySecret, 'key secret')
        if (s) problems.keySecret = s
    }
    return problems
}

/** The problems of a create form, one per field at most; empty when the form can be sent. */
export function createProblems (model: UpstreamSourceFormModel, secrets: UpstreamSourceSecrets): FormProblems {
    return { ...settingsProblems(model), ...credentialProblems(model.credentialKind, model.keyId, secrets) }
}

function rootChanged (loaded: LoadedUpstreamSource, model: UpstreamSourceFormModel): boolean {
    return model.root.trim() !== (loaded.tea?.root ?? '')
}

/** The problems of an edit form: the settings, the replacing credential, and the root rule of design 3.6. */
export function updateProblems (loaded: LoadedUpstreamSource, model: UpstreamSourceFormModel, secrets: UpstreamSourceSecrets): FormProblems {
    const problems: FormProblems = { ...settingsProblems(model) }
    if (model.credentialChange === CredentialChange.REPLACE) {
        if (model.credentialKind === 'NONE') problems.credential = 'Choose the kind of the new credential.'
        else Object.assign(problems, credentialProblems(model.credentialKind, model.keyId, secrets))
    } else if (model.credentialChange === CredentialChange.KEEP
        && loaded.credential.kind !== 'NONE' && rootChanged(loaded, model)) {
        problems.credential = rootChangeNeedsCredential
    }
    return problems
}

/** The credential input of a kind: exactly the fields of that kind, nothing of another. */
function credentialInput (kind: UpstreamCredentialKindValue, keyId: string, secrets: UpstreamSourceSecrets): Record<string, string> {
    if (kind === 'BEARER_TOKEN') return { kind, token: secrets.token }
    if (kind === 'API_KEY') return { kind, keyId, keySecret: secrets.keySecret }
    return { kind: 'NONE' }
}

/** UpstreamSourceInput of a create. */
export function buildCreateInput (model: UpstreamSourceFormModel, secrets: UpstreamSourceSecrets): Record<string, any> {
    return {
        kind: 'TEA',
        name: model.name.trim(),
        tea: { root: model.root.trim() },
        credential: credentialInput(model.credentialKind, model.keyId, secrets),
        documentClasses: DOCUMENT_CLASSES.filter(c => model.documentClasses.includes(c)),
        vexImportMode: model.vexImportMode,
        autoIntegrate: model.autoIntegrate ? 'ENABLED' : 'DISABLED',
        syncIntervalMinutes: model.syncIntervalMinutes,
        documentReServing: model.reServe ? 'RE_SERVED' : 'WITHHELD',
    }
}

function sameClasses (a: readonly string[], b: readonly string[]): boolean {
    return a.length === b.length && a.every(c => b.includes(c))
}

/**
 * UpstreamSourceUpdateInput of an edit: only the fields whose value differs from the loaded source.
 * The key credential is left out altogether for Keep (the schema reads an absent key as "keep the
 * stored credential"); Replace sends the complete new credential, Remove sends kind NONE.
 */
export function buildUpdateInput (loaded: LoadedUpstreamSource, model: UpstreamSourceFormModel, secrets: UpstreamSourceSecrets): Record<string, any> {
    const input: Record<string, any> = {}
    const name = model.name.trim()
    if (name !== loaded.name) input.name = name
    if (rootChanged(loaded, model)) input.tea = { root: model.root.trim() }
    if (model.credentialChange === CredentialChange.REPLACE) {
        input.credential = credentialInput(model.credentialKind, model.keyId, secrets)
    } else if (model.credentialChange === CredentialChange.REMOVE) {
        input.credential = { kind: 'NONE' }
    }
    if (!sameClasses(model.documentClasses, loaded.documentClasses)) {
        input.documentClasses = DOCUMENT_CLASSES.filter(c => model.documentClasses.includes(c))
    }
    if (model.vexImportMode !== loaded.vexImportMode) input.vexImportMode = model.vexImportMode
    const autoIntegrate = model.autoIntegrate ? 'ENABLED' : 'DISABLED'
    if (autoIntegrate !== loaded.autoIntegrate) input.autoIntegrate = autoIntegrate
    if (model.syncIntervalMinutes !== loaded.syncIntervalMinutes) input.syncIntervalMinutes = model.syncIntervalMinutes
    const reServing = model.reServe ? 'RE_SERVED' : 'WITHHELD'
    if (reServing !== loaded.documentReServing) input.documentReServing = reServing
    return input
}

/** "every 24 hours", "every hour", "every 90 minutes". */
export function intervalWords (minutes: number | null | undefined): string {
    if (typeof minutes !== 'number' || !Number.isFinite(minutes)) return ''
    if (minutes % 60 === 0) {
        const hours = minutes / 60
        return hours === 1 ? 'every hour' : `every ${hours} hours`
    }
    return `every ${minutes} minutes`
}
