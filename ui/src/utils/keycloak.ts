import Keycloak from "keycloak-js"

const keycloak = new Keycloak({
    url: "/kauth",
    realm: 'Reliza',
    clientId: 'login-app'
})

keycloak.onAuthSuccess = () => {console.log('keycloak ready')}
keycloak.onTokenExpired = async () => {
    try {
        await keycloak.updateToken(30)
    } catch (e) {
        // Refresh failed — e.g. the rotated refresh token was revoked after a
        // lost-response retry (revokeRefreshToken realm setting). Without this
        // the session is silently dead and every API call 401s; bounce through
        // login instead (a silent redirect while the SSO cookie is alive).
        console.warn('token refresh failed, re-authenticating', e)
        await keycloak.login()
    }
}

// Break-glass / SSO routing: ?kc_idp_hint=<alias> on the app URL is passed
// through to Keycloak's IdP redirector. With an org IdP enforced as the
// default login path, a bookmarked https://<host>/?kc_idp_hint=local skips
// the IdP redirect and reaches the local-account form even when the IdP is
// down. Routing the hint through keycloak-js (instead of a hand-built /auth
// URL) keeps the flow on Authorization Code + PKCE.
const idpHint = new URLSearchParams(window.location.search).get('kc_idp_hint')

await keycloak.init({
    onLoad: idpHint ? 'check-sso' : 'login-required',
    checkLoginIframe: false,
    // Authorization Code Flow with PKCE (S256). login-app is a public client, so
    // PKCE protects the code->token exchange against authorization-code interception.
    pkceMethod: 'S256'
})
if (!keycloak.authenticated) {
    await keycloak.login(idpHint ? { idpHint } : {})
}

export default keycloak
