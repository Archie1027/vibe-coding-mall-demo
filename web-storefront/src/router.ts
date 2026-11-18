import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import FeaturedView from './views/FeaturedView.vue'
import ProductsView from './views/ProductsView.vue'
import ProductDetailView from './views/ProductDetailView.vue'
import CheckoutView from './views/CheckoutView.vue'
import LoginView from './views/LoginView.vue'
import AccountView from './views/AccountView.vue'
import AddressesView from './views/AddressesView.vue'
import CartView from './views/CartView.vue'
import OrdersView from './views/OrdersView.vue'
import { api, account } from './session'
import { installSessionGuard } from '@zhicai/shared/session-guard.ts'
export const router = createRouter({ history: createWebHistory(), scrollBehavior(to, from, savedPosition) {
  if (to.path === '/products' && from.path === to.path) return false
  if (savedPosition) {
    const restoredPosition = { ...savedPosition, behavior: 'instant' as const }
    if (to.path === '/' || to.path === '/products' || to.path === '/featured') {
      return new Promise(resolve => {
        const finish = () => {
          window.removeEventListener('zhicai-content-ready', ready)
          clearTimeout(timeout)
          resolve(window.location.pathname === to.path ? restoredPosition : false)
        }
        const ready = (event: Event) => { if ((event as CustomEvent<string>).detail === to.path) finish() }
        window.addEventListener('zhicai-content-ready', ready)
        const timeout = window.setTimeout(finish, 5000)
      })
    }
    return restoredPosition
  }
  return { top: 0 }
}, routes: [
  { path: '/', component: HomeView }, { path: '/products', component: ProductsView },
  { path: '/featured', component: FeaturedView },
  { path: '/products/:id', component: ProductDetailView }, { path: '/checkout', component: CheckoutView, meta: { requiresAuth: true } },
  { path: '/login', component: LoginView }, { path: '/account', component: AccountView, meta: { requiresAuth: true } },
  { path: '/account/addresses', component: AddressesView, meta: { requiresAuth: true } },
  { path: '/cart', component: CartView, meta: { requiresAuth: true } },
  { path: '/account/orders', component: OrdersView, meta: { requiresAuth: true } },
  { path: '/account/orders/:id', component: OrdersView, meta: { requiresAuth: true } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
] })
installSessionGuard(router, api, account, 'CUSTOMER', '/login')
