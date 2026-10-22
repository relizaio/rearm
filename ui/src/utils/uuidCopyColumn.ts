import { h } from 'vue'
import { NButton, NIcon } from 'naive-ui'
import { Clipboard } from '@vicons/tabler'

export type NotifyFn = (type: 'success' | 'error' | 'warning' | 'info', title: string, content: string) => void

/**
 * Copies text to the viewer's clipboard and reports the outcome through the page's notify
 * (the SbomSigningKeysPanel copy idiom). Empty text does nothing.
 */
export async function copyToClipboard (text: string, what: string, notify: NotifyFn): Promise<void> {
    if (!text) return
    // navigator.clipboard exists only on secure origins; a plain-HTTP install has none.
    if (!navigator.clipboard?.writeText) {
        notify('warning', 'Copy unavailable', 'Select the text and copy it.')
        return
    }
    try {
        await navigator.clipboard.writeText(text)
        notify('success', 'Copied', `${what} copied`)
    } catch (e: any) {
        notify('error', 'Copy failed', e?.message || 'Could not copy to clipboard')
    }
}

export interface UuidCopyColumnOptions {
    /** what the message names, e.g. 'Perspective UUID' */
    what: string
    /** data-testid of the <code> cell; the button gets `${testid}-copy` */
    testid: string
    notify: NotifyFn
    /** column title, default 'UUID' */
    title?: string
}

/**
 * An n-data-table column showing the row's full uuid in monospace with a copy button beside it.
 * A row without a uuid renders nothing, so no button can copy an empty value.
 */
export function uuidCopyColumn (o: UuidCopyColumnOptions) {
    return {
        key: 'uuid',
        title: o.title ?? 'UUID',
        render (row: any) {
            const uuid = typeof row?.uuid === 'string' ? row.uuid : ''
            if (!uuid) return ''
            return h('span', { style: 'display: inline-flex; align-items: center; gap: 4px;' }, [
                h('code', { 'data-testid': o.testid, style: 'font-family: monospace; font-size: 12px;' }, uuid),
                h(NButton, {
                    text: true,
                    size: 'small',
                    title: `Copy ${o.what}`,
                    'aria-label': `Copy ${o.what}`,
                    'data-testid': `${o.testid}-copy`,
                    onClick: () => copyToClipboard(uuid, o.what, o.notify)
                }, { icon: () => h(NIcon, null, { default: () => h(Clipboard) }) })
            ])
        }
    }
}
