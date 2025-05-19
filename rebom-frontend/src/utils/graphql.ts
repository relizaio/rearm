import { ApolloClient, HttpLink, InMemoryCache } from '@apollo/client/core'

const httpLink = new HttpLink({
    uri: '/graphql'
})
export default new ApolloClient({
    link: httpLink,
    cache: new InMemoryCache({
        addTypename: false
    })
})
