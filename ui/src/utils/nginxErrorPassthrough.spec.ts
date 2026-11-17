import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync } from 'fs'
import { fileURLToPath } from 'url'

// TPF-49: the UI nginx passes every upstream answer through as sent (status, headers, body), and
// keeps its branded pages for its own errors only, the server-down page above all. The behaviour
// is checked through nginx itself by ui/nginx/test/error-passthrough.sh in CI; this spec pins the
// template's shape so a later edit cannot bring the interception back in one location.
const read = (f: string) => readFileSync(fileURLToPath(new URL(f, import.meta.url)), 'utf8')
const template = read('../../nginx/default.conf.template')

interface Directive { name: string, args: string, block: string[] }

// A line reader for the template: comments dropped (the template has no # inside a quoted value),
// a stack of block names (upstream, server, location <prefix>), and each `<name> <args>;` line taken
// as a directive of the innermost block. The ${KEYCLOAK_...} placeholder lines carry no ; and are skipped.
function directivesOf (text: string): { directives: Directive[], locations: string[] } {
    const directives: Directive[] = []
    const locations: string[] = []
    const stack: string[] = []
    for (const raw of text.split('\n')) {
        const line = raw.replace(/#.*$/, '').trim()
        if (!line) continue
        const open = line.match(/^(.*?)\s*\{$/)
        if (open) {
            const head = open[1].trim().split(/\s+/)
            const name = head[0] === 'location' ? `location ${head.slice(1).join(' ')}` : head[0]
            stack.push(name)
            if (head[0] === 'location') locations.push(head.slice(1).join(' '))
            continue
        }
        if (line === '}') {
            stack.pop()
            continue
        }
        const directive = line.match(/^(\S+)\s*(.*?);$/)
        if (directive) directives.push({ name: directive[1], args: directive[2], block: [...stack] })
    }
    expect(stack).toEqual([])
    return { directives, locations }
}

const { directives, locations } = directivesOf(template)
const innermost = (d: Directive) => d.block[d.block.length - 1]
const inLocation = (d: Directive) => d.block.some(b => b.startsWith('location '))

// The fifteen locations that proxy, in file order: the main server's thirteen (architecture 3.3, and
// /api/programmatic from PFA-2), then the TEA-only server's two for custom domains (TPF-148).
const PROXIED = [
    '/v1', '/api', '/api/programmatic', '/tea', '/.well-known/tea', '/graphql',
    '/kauth', '/kauth/admin/Reliza', '/kauth/admin/realms/Reliza', '/kauth/admin/serverinfo',
    '/kauth/realms/Reliza', '/kauth/resources', '/kauth/js',
    '^~ /tea/', '= /.well-known/tea',
]

describe('the UI nginx template passes upstream answers through (TPF-49)', () => {
    it('has exactly one proxy_intercept_errors, at server level, set to off', () => {
        const found = directives.filter(d => d.name === 'proxy_intercept_errors')
        expect(found).toHaveLength(1)
        expect(innermost(found[0])).toBe('server')
        expect(inLocation(found[0])).toBe(false)
        expect(found[0].args).toBe('off')
    })

    it('proxies from exactly the fifteen locations, none with its own intercept switch or error_page', () => {
        const proxied = directives.filter(d => d.name === 'proxy_pass').map(d => innermost(d))
        expect(proxied).toEqual(PROXIED.map(p => `location ${p}`))
        for (const location of locations) {
            const own = directives.filter(d => innermost(d) === `location ${location}`)
            expect(own.filter(d => d.name === 'proxy_intercept_errors'), location).toEqual([])
            expect(own.filter(d => d.name === 'error_page'), location).toEqual([])
        }
    })

    it('keeps the server-down page at server level, and every error_page names a page that exists', () => {
        const pages = directives.filter(d => d.name === 'error_page')
        expect(pages.length).toBeGreaterThan(0)
        for (const page of pages) expect(innermost(page)).toBe('server')
        expect(pages.map(p => p.args.replace(/\s+/g, ' '))).toContain('502 503 504 /error_pages/server_down.html')
        for (const page of pages) {
            const target = page.args.split(/\s+/).pop() as string
            expect(target.startsWith('/error_pages/'), target).toBe(true)
            expect(existsSync(fileURLToPath(new URL(`../../public${target}`, import.meta.url))), target).toBe(true)
        }
    })

    it('serves the SPA for any unknown UI route', () => {
        const tryFiles = directives.filter(d => d.name === 'try_files' && innermost(d) === 'location /')
        expect(tryFiles.map(d => d.args)).toEqual(['$uri $uri/ /index.html'])
    })
})

describe('the programmatic API location waits PROGRAMMATIC_API_TIMEOUT seconds (PFA-2)', () => {
    const own = (location: string) => directives
        .filter(d => innermost(d) === `location ${location}`)
        .map(d => `${d.name} ${d.args}`)

    it('proxies /api/programmatic like /api, with the read and send timeouts from the variable', () => {
        expect(own('/api/programmatic')).toEqual(expect.arrayContaining([
            'proxy_pass http://backend',
            'proxy_connect_timeout 90',
            'proxy_read_timeout ${PROGRAMMATIC_API_TIMEOUT}',
            'proxy_send_timeout ${PROGRAMMATIC_API_TIMEOUT}',
        ]))
        expect(own('/api')).toEqual(expect.arrayContaining(['proxy_read_timeout 90', 'proxy_send_timeout 90']))
    })

    it('uses the placeholder in those two directives only', () => {
        const users = directives.filter(d => d.args.includes('PROGRAMMATIC_API_TIMEOUT'))
        expect(users.map(d => [innermost(d), d.name])).toEqual([
            ['location /api/programmatic', 'proxy_read_timeout'],
            ['location /api/programmatic', 'proxy_send_timeout'],
        ])
        expect(template.split('${PROGRAMMATIC_API_TIMEOUT}')).toHaveLength(3)
    })
})

describe('the TEA-only server block for custom domains (TPF-148)', () => {
    it('sizes the server name hash for hosts of up to 239 characters, once, at the top level', () => {
        const bucket = directives.filter(d => d.name === 'server_names_hash_bucket_size')
        expect(bucket.map(d => [d.block.length, d.args])).toEqual([[0, '512']])
        expect(read('../../nginx/nginx.conf')).not.toMatch(/server_names_hash_bucket_size/)
    })

    it('names the custom hosts from TEA_CUSTOM_DOMAINS and keeps the main server the default', () => {
        expect(directives.filter(d => d.name === 'server_name').map(d => d.args))
            .toEqual(['localhost', '${TEA_CUSTOM_DOMAINS}'])
        expect(directives.filter(d => d.name === 'listen').map(d => d.args)).toEqual(['80 default_server', '80'])
    })

    it('proxies only /tea/ and /.well-known/tea to the backend and answers 404 to everything else', () => {
        expect(locations.slice(-4)).toEqual(['^~ /tea/', '= /.well-known/tea', '= /tea', '/'])
        const teaProxies = directives.filter(d => d.name === 'proxy_pass'
            && ['location ^~ /tea/', 'location = /.well-known/tea'].includes(innermost(d)))
        expect(teaProxies.map(d => d.args)).toEqual(['http://backend', 'http://backend'])
        expect(directives.filter(d => d.name === 'return').map(d => [innermost(d), d.args]))
            .toEqual([['location = /tea', '404'], ['location /', '404']])
    })

    it('pins X-Forwarded-Host to the routed host in its two TEA locations, and nowhere else (TPF-150)', () => {
        const xfh = directives.filter(d => d.name === 'proxy_set_header' && /^X-Forwarded-Host\s/i.test(d.args))
        expect(xfh.map(d => [innermost(d), d.args.replace(/\s+/g, ' ')]))
            .toEqual([['location ^~ /tea/', 'X-Forwarded-Host $host'], ['location = /.well-known/tea', 'X-Forwarded-Host $host']])
    })
})
