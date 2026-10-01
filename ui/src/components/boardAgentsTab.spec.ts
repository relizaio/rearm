import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { parse as parseSfc } from '@vue/compiler-sfc'

// The board page's tab (task RD3-5): one tab named Agents, replacing Usage, showing the Agents panel.
const source = readFileSync(fileURLToPath(new URL('./AiAgentBoardsPanel.vue', import.meta.url)), 'utf8')
const template = parseSfc(source).descriptor.template?.content ?? ''

describe('the Agents tab', () => {
    it('replaces the Usage tab and mounts the Agents panel', () => {
        expect(template).toContain('<n-tab-pane name="agents" tab="Agents">')
        expect(template).not.toContain('tab="Usage"')
        expect(template).toContain('<AgentBoardAgentsPanel :board-uuid="selectedBoard"')
        expect(source).not.toContain('AgentBoardUsagePanel')
    })
})
