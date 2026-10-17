import { ApolloServer } from '@apollo/server';
import { startStandaloneServer } from '@apollo/server/standalone';
import typeDefs from './schema.graphql';
import resolvers from './bomResolver';
import { logger } from './logger';

// One construction of the GraphQL server, shared by src/index.ts and the HTTP
// integration test (SCORE-4 design 3.8), so the request body limit the test pins
// is the one production serves. Importing this module starts nothing.

export function createGraphqlServer(): ApolloServer {
  return new ApolloServer({
    typeDefs,
    resolvers,
    formatError: (err) => {
      // Log all GraphQL errors but return them to client
      // This prevents errors from crashing the server
      logger.error({
        err: err,
        message: err.message,
        path: err.path,
        extensions: err.extensions
      }, 'GraphQL Error');
      return err;
    },
  });
}

// The standalone server's JSON body parser has a fixed 50mb limit (52428800 bytes)
// on the whole request; a larger body is answered with HTTP 413 before any resolver runs.
export async function startGraphqlHttp(server: ApolloServer, port: number): Promise<{ url: string }> {
  return startStandaloneServer(server, {
    listen: { port },
  });
}
