import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from './views/DashboardView.vue'
import OrdersView from './views/OrdersView.vue'
import OrderDetailView from './views/OrderDetailView.vue'
import PurchaseView from './views/PurchaseView.vue'
import LoginView from './views/LoginView.vue'
import CatalogView from './views/CatalogView.vue'
import { api, account } from './session'
import { installSessionGuard } from '@zhicai/shared/session-guard.ts'
export const router = createRouter({ history: createWebHistory(), scrollBehavior: () => ({ top: 0 }), routes: [
  { path: '/admin/login', component: LoginView, alias: '/login' }, { path: '/', redirect: '/admin' },
  { path: '/admin', component: DashboardView, meta: { requiresAuth: true } },
  { path: '/admin/products', component: CatalogView, meta: { requiresAuth: true } },
  { path: '/admin/orders', component: OrdersView, meta: { requiresAuth: true } },
  { path: '/admin/orders/:id', component: OrderDetailView, meta: { requiresAuth: true } },
  { path: '/admin/purchases/new', component: PurchaseView, meta: { requiresAuth: true } },
  { path: '/:pathMatch(.*)*', redirect: '/admin' },
] })
installSessionGuard(router, api, account, 'STAFF', '/admin/login')
