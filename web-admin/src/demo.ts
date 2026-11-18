import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { notify } from '@zhicai/shared/notifications.ts'
const searchQuery = ref('')
export function useDemo() {
  const router = useRouter()
  function showPage(name: string) {
    const paths: Record<string, string> = { admin: '/admin', adminOrders: '/admin/orders', adminOrderDetail: '/admin/orders/31', purchaseForm: '/admin/purchases/new' }
    if (paths[name]) { void router.push(paths[name]); return }
    window.location.assign('http://127.0.0.1:5173/')
  }
  return { searchQuery, showPage, toast: notify, addCart: () => notify('此功能尚未开放') }
}
