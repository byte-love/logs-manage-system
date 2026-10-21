const LOGIN_USER_KEY = 'logs_manage_login_user'

export function saveLoginUser(user) {
  localStorage.setItem(LOGIN_USER_KEY, JSON.stringify(user))
}

export function getLoginUser() {
  const raw = localStorage.getItem(LOGIN_USER_KEY)
  if (!raw) {
    return null
  }

  try {
    return JSON.parse(raw)
  } catch (error) {
    localStorage.removeItem(LOGIN_USER_KEY)
    return null
  }
}

export function clearLoginUser() {
  localStorage.removeItem(LOGIN_USER_KEY)
}

export function isLoggedIn() {
  return !!getLoginUser()
}
