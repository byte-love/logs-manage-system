import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from '../utils/auth'

const routes = [
  {
    path: '/',
    redirect: '/home'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue')
  },
  {
    path: '/home',
    name: 'Home',
    meta: { requiresAuth: true },
    component: () => import('../views/Home.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const loggedIn = isLoggedIn()

  if (to.path === '/login' && loggedIn) {
    next('/home')
    return
  }

  if (to.meta.requiresAuth && !loggedIn) {
    next('/login')
    return
  }

  next()
})

export default router
