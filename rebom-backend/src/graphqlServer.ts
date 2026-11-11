import { ApolloServer } from '@apollo/server';
import { startStandaloneServer } from '@apollo/server/standalone';
import { GraphQLError, GraphQLFormattedError, Kind } from 'graphql';
import typeDefs from './schema.graphql';
import resolvers from './bomResolver';
import { logger } from './logger';
import { teaContextOf, TeaRequestContext } from './services/tea/teaCallerGate';

// One construction of the GraphQL server, shared by src/index.ts and the HTTP
// integration test (SCORE-4 design 3.8), so the request body limit the test pins
// is the one production serves. Importing this module starts nothing.

const BAD_USER_INPUT = 'BAD_USER_INPUT';
const INTERNAL_SERVER_ERROR = 'INTERNAL_SERVER_ERROR';

// GraphQL names, and types made of names with ! [ ] (TPF-180 design 3.10 part 2).
const NAME = '[_A-Za-z][_0-9A-Za-z]*';
const TYPE = '[_0-9A-Za-z!\\[\\]]+';
const DID_YOU_MEAN = `( Did you mean "${NAME}"((, "${NAME}")*,? or "${NAME}")?\\?)?`;

/** graphql-js causes that name a field or a type and no value; anything else is replaced. */
const VALUELESS_CAUSES: readonly RegExp[] = [
    new RegExp(`^Field "${NAME}" is not defined by type "${TYPE}"\\.${DID_YOU_MEAN}$`),
    new RegExp(`^Field "${NAME}" of required type "${TYPE}" was not provided\\.$`),
    new RegExp(`^Field "${NAME}" must be non-null\\.$`),
    new RegExp(`^Expected type "${TYPE}" to be an object\\.$`),
    new RegExp(`^Expected non-nullable type "${TYPE}" not to be null\\.$`),
    new RegExp(`^Exactly one key must be specified for OneOf type "${TYPE}"\\.$`)
];

/** Variable messages that hold no value and pass as they are. */
const VALUELESS_VARIABLE_MESSAGES: readonly RegExp[] = [
    new RegExp(`^Variable "\\$${NAME}" of required type "${TYPE}" was not provided\\.$`),
    new RegExp(`^Variable "\\$${NAME}" of non-null type "${TYPE}" must not be null\\.$`)
];

const VARIABLE_MESSAGE = /^Variable "\$/;
const VARIABLE_NAME = new RegExp(`^Variable "\\$(${NAME})"`);
const INVALID_VALUE = new RegExp(`^Variable "\\$${NAME}" got invalid value `);
const CAUSE_SEPARATOR = '; ';

/** Apollo's own test for an input coercion error: exactly one node, a variable definition. */
function blamesOneVariable(error: unknown): boolean {
    if (!(error instanceof GraphQLError)) return false;
    return error.nodes?.length === 1 && error.nodes[0].kind === Kind.VARIABLE_DEFINITION;
}

function variableNameOf(message: string, error: unknown): string | null {
    const fromMessage = VARIABLE_NAME.exec(message);
    if (fromMessage) return fromMessage[1];
    if (error instanceof GraphQLError && error.nodes?.length === 1 && error.nodes[0].kind === Kind.VARIABLE_DEFINITION) {
        const name = error.nodes[0].variable.name.value;
        return new RegExp(`^${NAME}$`).test(name) ? name : null;
    }
    return null;
}

/** The message of a variable error without any value the caller sent (design 3.10 part 2). */
function safeVariableMessage(message: string, error: unknown): string {
    if (VALUELESS_VARIABLE_MESSAGES.some((pattern) => pattern.test(message))) return message;
    const name = variableNameOf(message, error);
    if (name !== null && INVALID_VALUE.test(message)) {
        const at = message.lastIndexOf(CAUSE_SEPARATOR);
        const cause = at >= 0 ? message.substring(at + CAUSE_SEPARATOR.length) : '';
        const kept = VALUELESS_CAUSES.some((pattern) => pattern.test(cause)) ? cause : 'invalid input';
        return `Variable "$${name}" got invalid value <omitted>; ${kept}`;
    }
    return name !== null ? `Variable "$${name}" is invalid.` : 'Invalid variable.';
}

/**
 * The server's formatError (TPF-180 design 3.10). A variable error (a message starting
 * `Variable "$`, or an error that blames exactly one variable definition, whatever its code) is
 * answered with an object built anew from a safe message and the code only, and logged with those
 * two only: a variable may hold a credential, and graphql-js prints the value into the message
 * (and its stack). Every other error is returned as before and logged from named fields. If
 * anything in here throws, the answer is a fixed internal error, never the error it was given.
 */
export function formatGraphqlError(formattedError: GraphQLFormattedError, error: unknown): GraphQLFormattedError {
    try {
        const message = typeof formattedError.message === 'string' ? formattedError.message : '';
        if (VARIABLE_MESSAGE.test(message) || blamesOneVariable(error)) {
            const safe = safeVariableMessage(message, error);
            logger.error({ message: safe, code: BAD_USER_INPUT }, 'GraphQL Error');
            return { message: safe, extensions: { code: BAD_USER_INPUT } };
        }
        const original = error instanceof GraphQLError && error.originalError ? error.originalError : error;
        logger.error({
            message,
            path: formattedError.path,
            extensions: formattedError.extensions,
            stack: original instanceof Error ? original.stack : undefined
        }, 'GraphQL Error');
        return formattedError;
    } catch {
        try {
            logger.error('GraphQL Error: the error could not be formatted');
        } catch {
            // nothing more to do: the answer below holds nothing of the error
        }
        return { message: 'Internal server error', extensions: { code: INTERNAL_SERVER_ERROR } };
    }
}

export function createGraphqlServer(): ApolloServer<TeaRequestContext> {
    return new ApolloServer<TeaRequestContext>({
        typeDefs,
        resolvers,
        // Explicit, whatever NODE_ENV is: no error answer carries extensions.stacktrace.
        includeStacktraceInErrorResponses: false,
        formatError: formatGraphqlError,
    });
}

// The standalone server's JSON body parser has a fixed 50mb limit (52428800 bytes)
// on the whole request; a larger body is answered with HTTP 413 before any resolver runs.
// The context copies exactly one thing from the request: the TEA caller header.
export async function startGraphqlHttp(server: ApolloServer<TeaRequestContext>, port: number): Promise<{ url: string }> {
    return startStandaloneServer(server, {
        listen: { port },
        context: async ({ req }) => teaContextOf(req.headers),
    });
}
