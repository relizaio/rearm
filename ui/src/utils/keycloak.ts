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
    checkLoginIframe: false
})

export default keycloak