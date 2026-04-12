import kc from './keycloak'

function getCsrfToken(): string | undefined {
    const row = document.cookie.split('; ').find(r => r.startsWith('XSRF-TOKEN='))
    return row ? row.split('=')[1] : undefined
}

export async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
    try {
        kc.isTokenExpired()
    } catch (err: any) {
        console.error(err)
        await kc.updateToken()
    }

    const headers = new Headers(options.headers)
    headers.set('Authorization', `Bearer ${kc.token}`)
    const csrfToken = getCsrfToken()
    if (csrfToken) {
        headers.set('X-XSRF-TOKEN', csrfToken)
    }

    return fetch(url, { ...options, headers })
}

export async function fetchArrayBufferWithAuth(url: string): Promise<ArrayBuffer> {
    const response = await fetchWithAuth(url)
    if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
    }
    return response.arrayBuffer()
}
