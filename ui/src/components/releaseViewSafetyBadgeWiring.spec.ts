import { describe, it, expect } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { withoutComments, withoutHtmlComments } from '@/utils/specText'

/**
 * The pending badge beside the release's "Safety Context" tab label, read from ReleaseView.vue as
 * text (u13): the tab renders SafetyPendingBadge, and a change in the tab bumps its refreshKey.
 */
const source = readFileSync(fileURLToPath(new URL('./ReleaseView.vue', import.meta.url)), 'utf8')
const template = withoutHtmlComments(source.slice(0, source.indexOf('<script')))
const script = withoutComments(source.slice(source.indexOf('<script')))

function safetyPane (): string {
    const start = template.search(/<n-tab-pane[^>]*name="safety"/)
    if (start < 0) throw new Error('no safety tab pane in ReleaseView.vue')
    const end = template.indexOf('</n-tab-pane>', start)
    return template.slice(start, end)
}

describe('the Safety Context tab label carries the pending badge (u13)', () => {
    it('imports SafetyPendingBadge', () => {
        expect(script).toMatch(/import\s+SafetyPendingBadge\s+from\s+'@\/components\/SafetyPendingBadge\.vue'/)
    })

    it('renders the badge in the tab slot, after the label, with the release and a refreshKey', () => {
        const pane = safetyPane()
        const tab = /<template #tab>([\s\S]*?)<\/template>/.exec(pane)
        expect(tab).not.toBeNull()
        expect(tab![1]).toMatch(/Safety Context\s*<safety-pending-badge/)
        const badge = /<safety-pending-badge([^>]*)\/>/.exec(tab![1])![1]
        expect(badge).toContain(':org-uuid="release.org"')
        expect(badge).toContain(':release-uuid="releaseUuid"')
        expect(badge).toMatch(/:refresh-key="(\w+)"/)
    })

    it('the tab content changes that same refreshKey on @changed', () => {
        const pane = safetyPane()
        const key = /<safety-pending-badge[^>]*:refresh-key="(\w+)"/.exec(pane)![1]
        const tabContent = /<release-safety-context([^>]*)\/>/.exec(pane)
        expect(tabContent).not.toBeNull()
        expect(tabContent![1]).toMatch(new RegExp(`@changed="${key}\\+\\+"`))
        expect(script).toMatch(new RegExp(`const ${key}(: Ref<number>)? = ref\\(0\\)`))
    })
})
