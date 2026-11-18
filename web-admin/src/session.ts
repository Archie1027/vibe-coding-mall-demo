import { ref } from 'vue'
import { createApiClient, type Account } from '@zhicai/shared/api.ts'
export const api = createApiClient('STAFF')
export const account = ref<Account | null>(null)
