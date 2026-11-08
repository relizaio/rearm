import { ApolloClient, HttpLink, InMemoryCache } from '@apollo/client/core'
import { SetContextLink } from '@apollo/client/link/context'
import { ErrorLink } from '@apollo/client/link/error'
import kc from './keycloak'
import UploadHttpLink from "apollo-upload-client/UploadHttpLink.mjs";
import { onSeatRefusalError } from './seatRefusalModal'

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
// A license seat refusal (extensions.code LICENSE_SEAT_REFUSED) from any operation opens the
// blocking Sign-in refused dialog (TPF-168); the operation's own result still reaches its caller.
const seatRefusalLink = new ErrorLink(onSeatRefusalError)

export default new ApolloClient({
    link:  headerMiddleware.concat(seatRefusalLink).concat(uploadLink),
    cache: new InMemoryCache({})
})
