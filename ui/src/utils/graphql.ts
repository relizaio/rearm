import { ApolloClient, HttpLink, InMemoryCache } from '@apollo/client/core'
import { setContext } from '@apollo/client/link/context'
import kc from './keycloak'
import UploadHttpLink from "apollo-upload-client/UploadHttpLink.mjs";

const uploadLink = new UploadHttpLink({ uri: '/graphql', credentials: 'same-origin' })

const headerMiddleware = setContext((_, { headers }) => {
    const csrfToken = window.localStorage.getItem('csrf');
    try {
        kc.isTokenExpired() 
    } catch (err: any) {
        console.error(err)
        kc.updateToken()
    }

    return {
        headers: {
            ...headers,
            'X-CSRF-Token': csrfToken,
            'AUTHORIZATION': `Bearer ${kc.token}`,
            'Apollo-Require-Preflight': 'true'
        },
    };
})
export default new ApolloClient({
    link:  headerMiddleware.concat(uploadLink),
    cache: new InMemoryCache({})
})
