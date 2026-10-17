import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { ApolloClient, InMemoryCache, gql } from '@apollo/client/core'
import { CombinedGraphQLErrors, ServerError } from '@apollo/client/errors'
import UploadHttpLink from 'apollo-upload-client/UploadHttpLink.mjs'
import { isSchemaDriftError } from './graphqlDriftFallback'

// TPF-49: the UI nginx now passes the backend's error answers through instead of its branded page.
// The UI keys its API error handling on the status, never on the body, so both answers must reach
// it as the same error. The client is built the way graphql.ts builds it (UploadHttpLink, no error
// link, no retry link), without the keycloak header link: graphql.ts is not imported, because
// keycloak.ts runs keycloak.init at load time and needs a browser.
const page = (name: string) =>
    readFileSync(fileURLToPath(new URL(`../../public/error_pages/${name}`, import.meta.url)), 'utf8')

interface Answer { status: number, body: string, headers: Record<string, string> }

const nginxPage = (status: number, name: string): Answer =>
    ({ status, body: page(name), headers: { 'Content-Type': 'text/html' } })

async function errorFor (answer: Answer): Promise<any> {
    const stub = async () => new Response(answer.body, { status: answer.status, headers: answer.headers })
    const client = new ApolloClient({
        link: new UploadHttpLink({ uri: 'http://ui.test/graphql', fetch: stub }),
        cache: new InMemoryCache(),
    })
    try {
        await client.query({ query: gql`query q { __typename }`, fetchPolicy: 'no-cache' })
    } catch (e) {
        return e
    }
    throw new Error('the query resolved; an error was expected')
}

function expectServerError (e: any, status: number) {
    expect(ServerError.is(e)).toBe(true)
    expect(e.statusCode).toBe(status)
    expect(e.message).toBe(`Response not successful: Received status code ${status}`)
}

describe('the UI reads API errors by status, whether nginx passes the answer or its page (TPF-49)', () => {
    it('429: the backend\'s JSON answer and nginx\'s page are the same ServerError', async () => {
        const backend: Answer = {
            status: 429,
            body: '{"error":"Rate limit exceeded"}',
            headers: { 'Content-Type': 'application/json', 'Retry-After': '1' },
        }
        expectServerError(await errorFor(backend), 429)
        expectServerError(await errorFor(nginxPage(429, 'generic.html')), 429)
    })

    it('401: the backend\'s empty challenge and nginx\'s page are the same ServerError', async () => {
        const backend: Answer = { status: 401, body: '', headers: { 'WWW-Authenticate': 'Bearer error="invalid_token"' } }
        expectServerError(await errorFor(backend), 401)
        expectServerError(await errorFor(nginxPage(401, '401.html')), 401)
    })

    it('400: a validation error counts as schema drift as JSON and as nginx\'s page', async () => {
        const backend: Answer = {
            status: 400,
            body: JSON.stringify({
                errors: [{
                    message: "Validation error (FieldUndefined@[enrichment]) : Field 'enrichment' in type 'Query' is undefined",
                    extensions: { classification: 'ValidationError' },
                }],
            }),
            headers: { 'Content-Type': 'application/graphql-response+json' },
        }
        const fromBackend = await errorFor(backend)
        expect(CombinedGraphQLErrors.is(fromBackend)).toBe(true)
        expect(isSchemaDriftError(fromBackend)).toBe(true)

        const fromPage = await errorFor(nginxPage(400, 'generic.html'))
        expect(ServerError.is(fromPage)).toBe(true)
        expect(fromPage.statusCode).toBe(400)
        expect(isSchemaDriftError(fromPage)).toBe(true)
    })

    it('502: nginx\'s own server-down page is unchanged, a ServerError with its status', async () => {
        expectServerError(await errorFor(nginxPage(502, 'server_down.html')), 502)
    })
})
