import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApolloServer } from '@apollo/server';
import { GraphQLError, parse, OperationDefinitionNode } from 'graphql';
import typeDefs from '../../src/schema.graphql';
import resolvers from '../../src/bomResolver';
import { createGraphqlServer, formatGraphqlError, startGraphqlHttp } from '../../src/graphqlServer';
import { logger } from '../../src/logger';
import { TeaRequestContext } from '../../src/services/tea/teaCallerGate';
import { cleanupDirs, configure, gql, LogSpy, MARK_TOKEN, spyLogs, TOKEN, UUID_A } from './teaTestKit';

/**
 * No value of a rejected variable in an answer or a log line (TPF-180 design 3.10; rows T-26,
 * T-27, T-88, T-89). The server is built as production builds it, under three values of NODE_ENV
 * set before the constructor reads it.
 */

const API_ROOT = 'https://tea.example.com/tea';
const PRODUCT = 'mutation($input: TeaObjectInput!) { teaProduct(input: $input) { outcome } }';
const ADD_BOM = 'mutation($bomInput: BomInput!) { addBom(bomInput: $bomInput) { uuid } }';

/** Cases (a) to (e) of T-26: [label, query, variables, variable name, ending]. */
const CASES: Array<[string, string, Record<string, unknown>, string, RegExp]> = [
    ['(a) unknown field beside the token', PRODUCT,
        { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: MARK_TOKEN, bogus: 1 } } },
        'input', /; Field "bogus" is not defined by type "TeaCredentialInput"\.$/],
    ['(b) required field missing beside the token', PRODUCT,
        { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: { authRoot: API_ROOT, token: MARK_TOKEN } } },
        'input', /; Field "kind" of required type "TeaCredentialKind!" was not provided\.$/],
    ['(c) the enum value is the marker', PRODUCT,
        { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: { kind: MARK_TOKEN } } },
        'input', /; invalid input$/],
    ['(d) the token is an object holding the marker', PRODUCT,
        { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: { v: MARK_TOKEN } } } },
        'input', /; invalid input$/],
    ['(e) addBom with an unknown field beside the marker', ADD_BOM,
        { bomInput: { format: 'CYCLONEDX', bom: {}, org: UUID_A, rebomOptions: { name: MARK_TOKEN }, bogus: MARK_TOKEN } },
        'bomInput', /; Field "bogus" is not defined by type "BomInput"\.( Did you mean "bom" or "tags"\?)?$/]
];

const NODE_ENVS: Array<string | undefined> = [undefined, 'production', 'development'];

async function withNodeEnv<T>(value: string | undefined, fn: () => Promise<T>): Promise<T> {
    const saved = process.env.NODE_ENV;
    if (value === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = value;
    try {
        return await fn();
    } finally {
        if (saved === undefined) delete process.env.NODE_ENV;
        else process.env.NODE_ENV = saved;
    }
}

async function serve(build: () => ApolloServer<TeaRequestContext>): Promise<{ server: ApolloServer<TeaRequestContext>, url: string }> {
    const server = build();
    const { url } = await startGraphqlHttp(server, 0);
    return { server, url };
}

function expectRedacted(label: string, text: string, variable: string, ending: RegExp, logs: LogSpy): void {
    expect(text, label).not.toContain(MARK_TOKEN);
    const json = JSON.parse(text);
    expect(json.errors, label).toHaveLength(1);
    const error = json.errors[0];
    expect(Object.keys(error).sort(), label).toEqual(['extensions', 'message']);
    expect(error.extensions, label).toEqual({ code: 'BAD_USER_INPUT' });
    expect(error.message.startsWith(`Variable "$${variable}" got invalid value <omitted>; `), `${label}: ${error.message}`).toBe(true);
    expect(error.message, label).toMatch(ending);
    expect(logs.text(), label).not.toContain(MARK_TOKEN);
}

describe('TEA error redaction', () => {
    afterEach(() => {
        vi.restoreAllMocks();
        cleanupDirs();
    });

    it('T-26 as production builds the server, under each NODE_ENV: no value, no stack, only message and code', async () => {
        configure();
        for (const nodeEnv of NODE_ENVS) {
            await withNodeEnv(nodeEnv, async () => {
                const { server, url } = await serve(createGraphqlServer);
                const logs = spyLogs();
                try {
                    for (const [label, query, variables, variable, ending] of CASES) {
                        const answer = await gql(url, query, variables, TOKEN);
                        expectRedacted(`${label} NODE_ENV=${nodeEnv}`, answer.text, variable, ending, logs);
                    }
                } finally {
                    logs.restore();
                    await server.stop();
                }
            });
        }
    });

    it('T-27 other errors keep message, path, code and details; no stacktrace under any NODE_ENV; the log has the stack', async () => {
        for (const nodeEnv of NODE_ENVS) {
            await withNodeEnv(nodeEnv, async () => {
                const { server, url } = await serve(createGraphqlServer);
                const logs = spyLogs();
                try {
                    const unknownField = JSON.parse((await gql(url, 'query { teaStatus { nope } }', {}, TOKEN)).text);
                    expect(unknownField.errors[0].message).toBe('Cannot query field "nope" on type "TeaStatus".');
                    expect(unknownField.errors[0].extensions.code).toBe('GRAPHQL_VALIDATION_FAILED');
                    expect(unknownField.errors[0].extensions.stacktrace).toBeUndefined();

                    const thrown = JSON.parse((await gql(url, ADD_BOM, { bomInput: { format: 'CYCLONEDX', bom: {}, org: 'not-an-org', rebomOptions: {} } }, TOKEN)).text);
                    const error = thrown.errors[0];
                    expect(error.message).toBe('org must be a uuid');
                    expect(error.path).toEqual(['addBom']);
                    expect(error.extensions.code).toBe('BOM_VALIDATION_ERROR');
                    expect(error.extensions.details).toEqual({ field: 'org', value: 'not-an-org' });
                    expect(error.extensions.stacktrace, String(nodeEnv)).toBeUndefined();
                    const line = logs.calls().find((call) => call[1] === 'GraphQL Error' && (call[0] as any).message === 'org must be a uuid');
                    expect(line).toBeDefined();
                    expect(String((line![0] as any).stack)).toContain('org must be a uuid');
                } finally {
                    logs.restore();
                    await server.stop();
                }
            });
        }
    });

    it('T-88 with includeStacktraceInErrorResponses forced on, cases (a) to (e) are unchanged; a resolver error carries a stacktrace', async () => {
        configure();
        const { server, url } = await serve(() => new ApolloServer<TeaRequestContext>({
            typeDefs, resolvers, includeStacktraceInErrorResponses: true, formatError: formatGraphqlError
        }));
        const logs = spyLogs();
        try {
            for (const [label, query, variables, variable, ending] of CASES) {
                const answer = await gql(url, query, variables, TOKEN);
                expectRedacted(label, answer.text, variable, ending, logs);
            }
            const thrown = JSON.parse((await gql(url, ADD_BOM, { bomInput: { format: 'CYCLONEDX', bom: {}, org: 'not-an-org', rebomOptions: {} } }, TOKEN)).text);
            expect(Array.isArray(thrown.errors[0].extensions.stacktrace)).toBe(true);
        } finally {
            logs.restore();
            await server.stop();
        }
    });

    it('T-89 formatGraphqlError: an answer built anew from an allow-list, the valueless forms kept, fail closed', () => {
        const variableNode = (parse('mutation($x: String) { hello }').definitions[0] as OperationDefinitionNode).variableDefinitions![0];
        const original = new Error(`boom ${MARK_TOKEN}`);
        (original as any).cause = MARK_TOKEN;
        (original as any).originalError = { message: MARK_TOKEN };
        original.stack = `Error: ${MARK_TOKEN}\n    at somewhere`;
        const formatted = (message: string) => ({
            message,
            extensions: { code: 'BAD_USER_INPUT', stacktrace: [`GraphQLError: ${MARK_TOKEN}`], details: { value: MARK_TOKEN }, exception: { token: MARK_TOKEN } }
        });
        const cases: Array<[string, unknown, string]> = [
            [`Variable "$input" got invalid value { token: "${MARK_TOKEN}" } at "input.kind"; Value "${MARK_TOKEN}" does not exist in "K" enum.`, original,
                'Variable "$input" got invalid value <omitted>; invalid input'],
            [`Variable "$input" got invalid value { x: "${MARK_TOKEN}" } at "input"; Field "a ${MARK_TOKEN}" is not defined by type "T".`, original,
                'Variable "$input" got invalid value <omitted>; invalid input'],
            ['Variable "$input" of required type "TeaObjectInput!" was not provided.', original,
                'Variable "$input" of required type "TeaObjectInput!" was not provided.'],
            ['Variable "$input" of non-null type "TeaObjectInput!" must not be null.', original,
                'Variable "$input" of non-null type "TeaObjectInput!" must not be null.'],
            [`Variable "$x" does something unknown with ${MARK_TOKEN}`, original, 'Variable "$x" is invalid.'],
            [`Something with ${MARK_TOKEN}`, new GraphQLError(`Something with ${MARK_TOKEN}`, { nodes: [variableNode], extensions: { code: 'CUSTOM' } }),
                'Variable "$x" is invalid.']
        ];
        const logs = spyLogs();
        try {
            for (const [message, error, expected] of cases) {
                const result = formatGraphqlError(formatted(message), error);
                expect(Object.keys(result).sort(), message).toEqual(['extensions', 'message']);
                expect(result.extensions, message).toEqual({ code: 'BAD_USER_INPUT' });
                expect(result.message, message).toBe(expected);
                expect(JSON.stringify(result), message).not.toContain(MARK_TOKEN);
            }
            expect(logs.text()).not.toContain(MARK_TOKEN);
        } finally {
            logs.restore();
        }
        vi.spyOn(logger, 'error').mockImplementation(() => {
            throw new Error(`logger down ${MARK_TOKEN}`);
        });
        const failed = formatGraphqlError(formatted(`Variable "$input" got invalid value "${MARK_TOKEN}"; x`), original);
        expect(failed).toEqual({ message: 'Internal server error', extensions: { code: 'INTERNAL_SERVER_ERROR' } });
    });
});
