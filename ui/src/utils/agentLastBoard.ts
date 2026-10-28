// The board a person last opened, per organization, kept in this browser (localStorage
// relizaLastBoard_<org>), so the boards tab and the boards dashboard open on it again. A board in the URL
// still wins, and a remembered board the list no longer holds is passed over for the first one. Storage
// that is unavailable (private mode, blocked) reads as nothing remembered and writes nothing.

export const LAST_BOARD_KEY_PREFIX = 'relizaLastBoard_'

export function lastBoardKey (orgUuid: string): string {
    return LAST_BOARD_KEY_PREFIX + orgUuid
}

type BoardStorage = Pick<Storage, 'getItem' | 'setItem'>

function storageOf (storage?: BoardStorage | null): BoardStorage | null {
    if (storage !== undefined) return storage
    try { return window.localStorage } catch { return null }
}

/** The board last opened in the organization, or null. */
export function readLastBoard (orgUuid: string | null | undefined, storage?: BoardStorage | null): string | null {
    if (!orgUuid) return null
    try { return storageOf(storage)?.getItem(lastBoardKey(orgUuid)) || null } catch { return null }
}

/** Remembers the board opened in the organization; nothing for no board. */
export function writeLastBoard (orgUuid: string | null | undefined, boardUuid: string | null | undefined,
    storage?: BoardStorage | null): void {
    if (!orgUuid || !boardUuid) return
    try { storageOf(storage)?.setItem(lastBoardKey(orgUuid), boardUuid) } catch { /* storage unavailable */ }
}

/**
 * The board the panel opens on: the URL's when the list holds it; none when the URL names one it does not
 * (the person cannot open it, RD2-9, so it is reported as hidden rather than replaced); else the remembered
 * one when the list holds it; else the first.
 */
export function initialBoard (boards: { uuid: string }[], fromUrl: string | null | undefined,
    remembered: string | null | undefined): { board: string | null, hidden: string | null } {
    const listed = (uuid: string | null | undefined) => !!uuid && boards.some(b => b.uuid === uuid)
    if (fromUrl) return listed(fromUrl) ? { board: fromUrl, hidden: null } : { board: null, hidden: fromUrl }
    if (listed(remembered)) return { board: remembered as string, hidden: null }
    return { board: boards[0]?.uuid ?? null, hidden: null }
}
