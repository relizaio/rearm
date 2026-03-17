import { ApolloClient, HttpLink, InMemoryCache } from '@apollo/client/core'
import { SetContextLink } from '@apollo/client/link/context'
import kc from './keycloak'
import UploadHttpLink from "apollo-upload-client/UploadHttpLink.mjs";

const uploadLink = new UploadHttpLink({ uri: '/graphql', credentials: 'same-origin' })

// Helper function to read XSRF-TOKEN cookie
function getCsrfTokenFromCookie(): string | undefined {
    return document.cookie
        .split('; ')
        .find(row => row.startsWith('XSRF-TOKEN='))
        ?.split('=')[1];
}

const headerMiddleware = new SetContextLink(({ headers }) => {
    const csrfToken = getCsrfTokenFromCookie();
    try {
        kc.isTokenExpired() 
    } catch (err: any) {
        console.error(err)
        kc.updateToken()
    }

    return {
        headers: {
            ...headers,
            'X-XSRF-TOKEN': csrfToken,
            'AUTHORIZATION': `Bearer ${kc.token}`,
            'Apollo-Require-Preflight': 'true'
        },
    };
})
export default new ApolloClient({
    link:  headerMiddleware.concat(uploadLink),
    cache: new InMemoryCache({})
})
