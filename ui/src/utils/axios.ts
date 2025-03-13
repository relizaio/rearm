import axios, { AxiosInstance } from 'axios'
import kc from './keycloak'

const axiosClient: AxiosInstance = axios.create();

axiosClient.interceptors.request.use(
    config => {
        const csrfToken = window.localStorage.getItem('csrf')
        config.headers['X-CSRF-Token'] = csrfToken
        try {
            kc.isTokenExpired() 
        } catch (err: any) {
            console.error(err)
            kc.updateToken()
        }
        config.headers.Authorization = `Bearer ${kc.token}`
        return config;
    }, error => {
        return Promise.reject(error);
    });
  
export default axiosClient;