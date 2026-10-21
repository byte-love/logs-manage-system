import axios from 'axios'
import { clearLoginUser } from '../utils/auth'

const api = axios.create({
  baseURL: '/api',
  timeout: 15000
})

let authInterceptorInstalled = false
let redirectingToLogin = false

export function installAuthInterceptor(router) {
  if (authInterceptorInstalled) return
  authInterceptorInstalled = true

  api.interceptors.response.use(
    (response) => response,
    (error) => {
      const status = error.response?.status
      const requestUrl = String(error.config?.url || '')
      const isLoginRequest = requestUrl.includes('/auth/login')

      if (status === 401 && !isLoginRequest) {
        clearLoginUser()
        if (!redirectingToLogin && router.currentRoute.value.path !== '/login') {
          redirectingToLogin = true
          Promise.resolve(router.replace('/login')).finally(() => {
            redirectingToLogin = false
          })
        }
      }

      return Promise.reject(error)
    }
  )
}

export default api
