import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { notify } from '@zhicai/shared/notifications.ts'
const searchQuery = ref('')
export function useDemo() {
  const router = useRouter()
  function showPage(name: string) {
    const paths: Record<string, string> = { store: '/', productList: '/products', productDetail: '/products/2001', checkout: '/checkout' }
    if (paths[name]) { void router.push(paths[name]); return }
    window.location.assign('http://127.0.0.1:5174/admin')
  }
  return { searchQuery, showPage, toast: notify, addCart: (_name?: string) => notify('请查看商品详情，选择规格并登录后加入购物车') }
}
