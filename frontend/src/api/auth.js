import api from './http'

export function login(data) {
  return api.post('/auth/login', data)
}

export function logout() {
  return api.post('/auth/logout')
}

export default api
