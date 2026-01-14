import { ApolloServer } from '@apollo/server'
import { startStandaloneServer } from '@apollo/server/standalone'
import typeDefs from './schema.graphql'
import resolvers from './bomResolver';
import { logger } from './logger';

// Global error handlers to prevent process crashes (like Spring Boot)
// These ensure the service stays running even when unexpected errors occur
process.on('uncaughtException', (error: Error) => {
  logger.error({ 
    err: error,
    stack: error.stack 
  }, 'Uncaught Exception - Service will continue running');
  // Don't exit - let the service continue (unlike default Node.js behavior)
});

process.on('unhandledRejection', (reason: any, promise: Promise<any>) => {
  logger.error({ 
    err: reason,
    promise: promise 
  }, 'Unhandled Promise Rejection - Service will continue running');
  // Don't exit - let the service continue
});

// Handle graceful shutdown
process.on('SIGTERM', () => {
  logger.info('SIGTERM signal received - shutting down gracefully');
  process.exit(0);
});

process.on('SIGINT', () => {
  logger.info('SIGINT signal received - shutting down gracefully');
  process.exit(0);
});

async function startApolloServer(typeDefs: any, resolvers: any) {
  // Start GraphQL server separately
  const server = new ApolloServer({
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

  const { url } = await startStandaloneServer(server, {
    listen: { port: 4000 },
  });

  logger.info(`🚀 GraphQL Server ready at ${url}`);
}

// Wrap startup in try-catch to handle initialization errors
startApolloServer(typeDefs, resolvers).catch((error) => {
  logger.error({ err: error }, 'Failed to start Apollo Server');
  process.exit(1); // Only exit on startup failure
});  