import { ApolloServer } from '@apollo/server'
import { startStandaloneServer } from '@apollo/server/standalone'
import typeDefs from './schema.graphql'
import resolvers from './bomResolver';
import { logger } from './logger';


async function startApolloServer(typeDefs: any, resolvers: any) {
  // Start GraphQL server separately
  const server = new ApolloServer({
    typeDefs,
    resolvers,
    formatError: (err) => {
      logger.error(err);
      return err;
    },
  });

  const { url } = await startStandaloneServer(server, {
    listen: { port: 4000 },
  });

  logger.info(`🚀 GraphQL Server ready at ${url}`);
}

startApolloServer(typeDefs, resolvers)  