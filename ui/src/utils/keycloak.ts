import Keycloak from "keycloak-js"

const keycloak = new Keycloak({
    url: "/kauth",
    realm: 'Reliza',
    clientId: 'login-app'
})

keycloak.onAuthSuccess = () => {console.log('keycloak ready')}
keycloak.onTokenExpired = async () => {
    await keycloak.updateToken(30)
}

await keycloak.init({
    onLoad: 'login-required',
    checkLoginIframe: false,
    // Authorization Code Flow with PKCE (S256). login-app is a public client, so
    // PKCE protects the code->token exchange against authorization-code interception.
    pkceMethod: 'S256'
})

export default keycloak