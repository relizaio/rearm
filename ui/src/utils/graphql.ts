import { ApolloClient, ApolloLink, HttpLink, InMemoryCache } from '@apollo/client/core'
import { SetContextLink } from '@apollo/client/link/context'
import kc from './keycloak'
import UploadHttpLink from "apollo-upload-client/UploadHttpLink.mjs";

const uploadLink = new UploadHttpLink({ uri: '/graphql', credentials: 'same-origin' })

function omitDeep(obj: any, key: string): any {
    if (Array.isArray(obj)) return obj.map(item => omitDeep(item, key))
    if (obj !== null && typeof obj === 'object' && !(obj instanceof File) && !(obj instanceof Blob)) {
        return Object.keys(obj).reduce((acc: any, k) => {
            if (k !== key) acc[k] = omitDeep(obj[k], key)
            return acc
        }, {})
    }
    return obj
}

const cleanTypenameLink = new ApolloLink((operation, forward) => {
    if (operation.query.definitions.some(
        (def: any) => def.kind === 'OperationDefinition' && def.operation === 'mutation'
    )) {
        operation.variables = omitDeep(operation.variables, '__typename')
    }
    return forward(operation)
})

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
    link: cleanTypenameLink.concat(headerMiddleware.concat(uploadLink)),
    cache: new InMemoryCache({})
})
