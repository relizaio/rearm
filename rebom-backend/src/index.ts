import { createGraphqlServer, startGraphqlHttp } from './graphqlServer';
import { logger } from './logger';
import { initEncryption } from './services/encryptionService';
import { startEnrichmentScheduler, stopEnrichmentScheduler } from './services/enrichmentScheduler';
import { logRawRepositoryCensus } from './services/oci';

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
  stopEnrichmentScheduler();
  process.exit(0);
});

process.on('SIGINT', () => {
  logger.info('SIGINT signal received - shutting down gracefully');
  stopEnrichmentScheduler();
  process.exit(0);
});

async function startApolloServer() {
  // Initialize encryption service (uses defaults if env vars not set)
  initEncryption();

  // Start GraphQL server separately
  const server = createGraphqlServer();
  const { url } = await startGraphqlHttp(server, 4000);

  logger.info(`🚀 GraphQL Server ready at ${url}`);

  // Start enrichment scheduler after server is ready
  startEnrichmentScheduler();

  // SQL-only census: rows still pending on-demand raw-repository resolution
  // (no OCI traffic; resolution itself happens lazily on raw fetches).
  logRawRepositoryCensus().catch((err) => {
    logger.error({ err }, 'Raw-repository census failed');
  });
}

// Wrap startup in try-catch to handle initialization errors
startApolloServer().catch((error) => {
  logger.error({ err: error }, 'Failed to start Apollo Server');
  process.exit(1); // Only exit on startup failure
});  