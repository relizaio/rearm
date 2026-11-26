import { GraphQLError, Kind, parse } from 'graphql';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { logger } from '../../src/logger';
import { createGraphqlServer, formatGraphqlError } from '../../src/graphqlServer';
import { BomLineageNotPermittedError, ERROR_CODES, toGraphQLError } from '../../src/types/errors';
import { configure, cleanupDirs, gql, Http, startHttp } from './teaTestKit';

/**
 * What the server answers for an error (design 3.10): a variable error is built anew from a safe
 * message and the code alone, so that a credential in a variable is never echoed or logged; every
 * other error is returned as it was and logged from named fields; a failure in here is a fixed
 * internal error. And the lineage refusal of the add path as the GraphQL error it is.
 */

const BAD_INPUT = { code: 'BAD_USER_INPUT' };
const SECRET = 'sk-SECRET-VALUE';

afterEach(() => {
    vi.restoreAllMocks();
});

function spyError() {
    return vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
}

/** A GraphQL error that blames exactly one variable definition. */
function variableDefinitionError(message: string, name: string): GraphQLError {
    const document = parse(`query($${name}: String) { teaStatus { outcome } }`);
    const definition = (document.definitions[0] as any).variableDefinitions[0];
    expect(definition.kind).toBe(Kind.VARIABLE_DEFINITION);
    return new GraphQLError(message, { nodes: [definition] });
}

describe('a variable error', () => {
    it('keeps the valueless cause and omits the value, logging the safe message and the code alone', () => {
        const errors = spyError();
        const message = `Variable "$input" got invalid value {"credential":{"token":"${SECRET}"}}; Field "tokn" is not defined by type "TeaCredentialInput".`;
        const answer = formatGraphqlError({ message, locations: [{ line: 1, column: 1 }], extensions: { code: 'SOMETHING', stacktrace: [SECRET] } }, new GraphQLError(message));
        expect(answer).toEqual({ message: 'Variable "$input" got invalid value <omitted>; Field "tokn" is not defined by type "TeaCredentialInput".', extensions: BAD_INPUT });
        expect(errors).toHaveBeenCalledTimes(1);
        expect(errors.mock.calls[0]).toEqual([{ message: answer.message, code: 'BAD_USER_INPUT' }, 'GraphQL Error']);
        expect(JSON.stringify([answer, errors.mock.calls])).not.toContain(SECRET);
    });

    it('keeps each cause that names a field or a type and no value, and replaces any other', () => {
        spyError();
        const causes = [
            'Field "a" is not defined by type "Input".',
            'Field "a" is not defined by type "[Input!]!". Did you mean "b"?',
            'Field "a" is not defined by type "Input". Did you mean "b" or "c"?',
            'Field "a" is not defined by type "Input". Did you mean "b", "c" or "d"?',
            'Field "a" is not defined by type "Input". Did you mean "b", "c", or "d"?',
            'Field "a_1" of required type "String!" was not provided.',
            'Field "a" must be non-null.',
            'Expected type "Input" to be an object.',
            'Expected non-nullable type "String!" not to be null.',
            'Exactly one key must be specified for OneOf type "Choice".'
        ];
        for (const cause of causes) {
            const message = `Variable "$v" got invalid value "${SECRET}"; ${cause}`;
            expect(formatGraphqlError({ message }, new GraphQLError(message)).message, cause).toBe(`Variable "$v" got invalid value <omitted>; ${cause}`);
        }
        for (const cause of [`Expected type "String". String cannot represent a non string value: "${SECRET}"`, 'Field "a" is not defined by type "Input" with a value 5.',
            'Field "a b" is not defined by type "Input".', 'Expected type "Input" to be an object. And more.', 'Field "a" must be non-null. ', 'something else', '']) {
            const message = `Variable "$v" got invalid value "${SECRET}"; ${cause}`;
            const answer = formatGraphqlError({ message }, new GraphQLError(message));
            expect(answer.message, cause).toBe('Variable "$v" got invalid value <omitted>; invalid input');
            expect(answer.message).not.toContain(SECRET);
        }
    });

    it('takes the cause after the last separator, and has none when there is no separator', () => {
        spyError();
        const joined = `Variable "$v" got invalid value "a; b; ${SECRET}"; Field "x" must be non-null.`;
        expect(formatGraphqlError({ message: joined }, new GraphQLError(joined)).message).toBe('Variable "$v" got invalid value <omitted>; Field "x" must be non-null.');
        const none = `Variable "$v" got invalid value ${SECRET}`;
        expect(formatGraphqlError({ message: none }, new GraphQLError(none)).message).toBe('Variable "$v" got invalid value <omitted>; invalid input');
    });

    it('passes the messages that hold no value as they are', () => {
        spyError();
        for (const message of ['Variable "$input" of required type "TeaObjectInput!" was not provided.', 'Variable "$n" of non-null type "Int!" must not be null.']) {
            expect(formatGraphqlError({ message }, new GraphQLError(message))).toEqual({ message, extensions: BAD_INPUT });
        }
    });

    it('any other message that starts with a variable is named and made safe, or is plain "Invalid variable."', () => {
        spyError();
        const named = `Variable "$token" is wrong: ${SECRET}`;
        expect(formatGraphqlError({ message: named }, new GraphQLError(named))).toEqual({ message: 'Variable "$token" is invalid.', extensions: BAD_INPUT });
        const unnamed = `Variable "$1bad" is wrong: ${SECRET}`;
        expect(formatGraphqlError({ message: unnamed }, new GraphQLError(unnamed))).toEqual({ message: 'Invalid variable.', extensions: BAD_INPUT });
        const valueless = 'Variable "$x" of required type "Int!" was not provided';
        expect(formatGraphqlError({ message: valueless }, new GraphQLError(valueless)).message).toBe('Variable "$x" is invalid.');
    });

    it('an error that blames exactly one variable definition is one, whatever its message and code', () => {
        const errors = spyError();
        const error = variableDefinitionError(`Not a variable message ${SECRET}`, 'who');
        const answer = formatGraphqlError({ message: error.message, extensions: { code: 'GRAPHQL_VALIDATION_FAILED' } }, error);
        expect(answer).toEqual({ message: 'Variable "$who" is invalid.', extensions: BAD_INPUT });
        expect(errors.mock.calls[0]).toEqual([{ message: 'Variable "$who" is invalid.', code: 'BAD_USER_INPUT' }, 'GraphQL Error']);
        // two nodes, a node that is not a variable definition, and no node are other errors
        const document = parse('query($a: String, $b: String) { teaStatus { outcome } }');
        const defs = (document.definitions[0] as any).variableDefinitions;
        const two = new GraphQLError('two nodes', { nodes: defs });
        expect(formatGraphqlError({ message: two.message }, two)).toEqual({ message: 'two nodes' });
        const field = new GraphQLError('a field', { nodes: [(document.definitions[0] as any).selectionSet.selections[0]] });
        expect(formatGraphqlError({ message: field.message }, field)).toEqual({ message: 'a field' });
        expect(formatGraphqlError({ message: 'plain' }, new GraphQLError('plain'))).toEqual({ message: 'plain' });
        const odd = new GraphQLError('bad name', { nodes: [{ kind: Kind.VARIABLE_DEFINITION, variable: { name: { value: '1bad' } } } as any] });
        expect(formatGraphqlError({ message: odd.message }, odd)).toEqual({ message: 'Invalid variable.', extensions: BAD_INPUT });
        const notGraphql = Object.assign(new Error('x'), { nodes: [defs[0]] });
        expect(formatGraphqlError({ message: 'x' }, notGraphql)).toEqual({ message: 'x' });
    });
});

describe('every other error', () => {
    it('is returned as it was, and logged from named fields with the stack of the original error', () => {
        const errors = spyError();
        const original = new Error('inner');
        const graphql = new GraphQLError('outer', { originalError: original, path: ['teaProduct'] });
        const formatted = { message: 'outer', path: ['teaProduct'], extensions: { code: 'INTERNAL_SERVER_ERROR', detail: 1 } };
        expect(formatGraphqlError(formatted, graphql)).toBe(formatted);
        expect(errors).toHaveBeenCalledTimes(1);
        expect(errors.mock.calls[0]).toEqual([{ message: 'outer', path: ['teaProduct'], extensions: formatted.extensions, stack: original.stack }, 'GraphQL Error']);
        errors.mockClear();
        const plain = new Error('plain');
        formatGraphqlError({ message: 'plain' }, plain);
        expect(errors.mock.calls[0][0]).toEqual({ message: 'plain', path: undefined, extensions: undefined, stack: plain.stack });
        errors.mockClear();
        formatGraphqlError({ message: 'no original' }, new GraphQLError('no original'));
        expect((errors.mock.calls[0][0] as any).stack).toBeDefined();
        errors.mockClear();
        formatGraphqlError({ message: 'thrown string' }, new GraphQLError('thrown string', { originalError: 'text' as any }));
        expect((errors.mock.calls[0][0] as any).stack).toBeUndefined();
    });

    it('copes with an error that is not an object, and a message that is not text', () => {
        const errors = spyError();
        for (const error of [null, undefined, 'a string', 5]) {
            const formatted = { message: 'shown' };
            expect(formatGraphqlError(formatted, error)).toBe(formatted);
        }
        const noText = { message: undefined as any };
        expect(formatGraphqlError(noText, new Error('x'))).toBe(noText);
        expect(errors.mock.calls.at(-1)![0]).toMatchObject({ message: '' });
    });

    it('a failure while formatting answers a fixed internal error, logs one line, and survives a log that fails', () => {
        const errors = spyError();
        const hostile = { get message(): string { throw new Error(SECRET); } } as any;
        expect(formatGraphqlError(hostile, new Error('x'))).toEqual({ message: 'Internal server error', extensions: { code: 'INTERNAL_SERVER_ERROR' } });
        expect(errors.mock.calls).toEqual([['GraphQL Error: the error could not be formatted']]);
        errors.mockImplementation(() => { throw new Error('log down'); });
        expect(formatGraphqlError(hostile, new Error('x'))).toEqual({ message: 'Internal server error', extensions: { code: 'INTERNAL_SERVER_ERROR' } });
        // a log that fails while a variable error is answered is the same fixed error
        expect(formatGraphqlError({ message: 'Variable "$a" is bad' }, new Error('x'))).toEqual({ message: 'Internal server error', extensions: { code: 'INTERNAL_SERVER_ERROR' } });
    });
});

describe('the server', () => {
    let http: Http;

    beforeAll(async () => {
        http = await startHttp();
    });

    afterAll(async () => {
        await http.server.stop();
        cleanupDirs();
    });

    it('never puts a stack trace in an answer, whatever NODE_ENV is', async () => {
        configure();
        const answer = await gql(http.url, 'query { teaStatusNope { outcome } }');
        expect(answer.status).toBe(400);
        expect(answer.json.errors[0].extensions.code).toBe('GRAPHQL_VALIDATION_FAILED');
        expect(answer.json.errors[0].extensions.stacktrace).toBeUndefined();
        expect(answer.text).not.toContain('stacktrace');
        const saved = process.env.NODE_ENV;
        process.env.NODE_ENV = 'development';
        try {
            const dev = createGraphqlServer();
            await dev.start();
            const result = await dev.executeOperation({ query: 'query { teaStatusNope { outcome } }' });
            const errors = (result.body as any).singleResult.errors;
            expect(errors[0].extensions.stacktrace).toBeUndefined();
            await dev.stop();
        } finally {
            process.env.NODE_ENV = saved;
        }
    });

    it('answers a variable that holds a credential without it, in the answer and in the log', async () => {
        configure();
        const errors = spyError();
        const answer = await gql(http.url, 'mutation($input: TeaObjectInput!) { teaProduct(input: $input) { outcome } }', {
            input: { apiRoot: 'https://tea.example.com/tea', uuid: 'u', credential: { kind: 'BEARER_TOKEN', token: SECRET, unknownField: SECRET } }
        });
        expect(answer.text).not.toContain(SECRET);
        expect(answer.json.errors[0].extensions).toEqual(BAD_INPUT);
        expect(answer.json.errors[0].message).toMatch(/^Variable "\$input" got invalid value <omitted>; /);
        expect(JSON.stringify(errors.mock.calls)).not.toContain(SECRET);
    });
});

describe('the lineage refusal of the add path', () => {
    it('is a GraphQL error with its own code and the details of the stored lineage', () => {
        const mapped = toGraphQLError(new BomLineageNotPermittedError('lineage held', 'urn:uuid:s', 4, '5', 'existing-1'));
        expect(mapped).toEqual({
            message: 'lineage held',
            extensions: {
                code: ERROR_CODES.BOM_LINEAGE_NOT_PERMITTED,
                details: { serialNumber: 'urn:uuid:s', storedVersion: 4, newVersion: '5', existingUuid: 'existing-1' }
            }
        });
        expect(ERROR_CODES.BOM_LINEAGE_NOT_PERMITTED).toBe('BOM_LINEAGE_NOT_PERMITTED');
    });

    it('is an Error that carries its own name and a stack that starts with it and leaves the constructor out', () => {
        const error = new BomLineageNotPermittedError('lineage held', 'urn:uuid:s', null, null, 'existing-1');
        expect(error).toBeInstanceOf(Error);
        expect(error.name).toBe('BomLineageNotPermittedError');
        expect(error.stack).toMatch(/^BomLineageNotPermittedError: lineage held\n/);
        expect(error.stack).not.toContain('new BomLineageNotPermittedError');
        expect(error.storedVersion).toBeNull();
        expect(error.newVersion).toBeNull();
    });
});
