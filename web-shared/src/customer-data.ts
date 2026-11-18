import { ApiError } from './api.ts'
import { sumLines } from './money.ts'
import type { Spec } from './catalog-api.ts'

export interface AddressInput { recipient: string; phone: string; province: string; city: string; district: string; detail: string; isDefault: boolean }
export interface Address extends AddressInput { id: string; version: number }
export interface CartLine { id: string; skuId: string; productName: string; specs: Spec[]; priceMinor: string; quantity: number; selected: boolean; valid: boolean; invalidReason?: string; imageUrl?: string }
export function addressInput(form: AddressInput): AddressInput {
  const fields = [['recipient', 50], ['province', 120], ['city', 120], ['district', 120], ['detail', 200]] as const
  for (const [field, max] of fields) if (typeof form[field] !== 'string' || !form[field].trim() || [...form[field]].length > max) throw new Error('请完整填写收件人与地址，并检查各字段长度')
  if (!/^1[0-9]{10}$/.test(form.phone)) throw new Error('请输入11位中国大陆手机号')
  if (typeof form.isDefault !== 'boolean') throw new Error('请选择是否设为默认地址')
  return { recipient: form.recipient, phone: form.phone, province: form.province, city: form.city, district: form.district, detail: form.detail, isDefault: form.isDefault }
}
export function cartPageSubtotal(lines: Pick<CartLine, 'priceMinor' | 'quantity' | 'selected' | 'valid'>[]): string { return sumLines(lines.filter(line => line.selected && line.valid)) }

export interface CustomerWriteRequest { method: 'POST' | 'PUT' | 'DELETE'; path: string; body?: string; key: string }
export type WriteOutcome = { kind: 'success' | 'blocked' } | { kind: 'unknown' | 'rejected' | 'refresh_failed'; error: unknown }
/** Retains the exact in-memory request after an ambiguous response; retries are explicit. */
export function createCustomerWriteAttempt(transport: (request: Readonly<CustomerWriteRequest>) => Promise<unknown>, changed: () => void = () => {}, newKey: () => string = () => crypto.randomUUID()) {
  let pending: Readonly<CustomerWriteRequest> | undefined, success: ((value: unknown) => Promise<void>) | undefined
  let busy = false, uncertain = false
  async function execute(): Promise<WriteOutcome> {
    if (busy || !pending) return { kind: 'blocked' }
    busy = true; changed()
    let value: unknown
    try { value = await transport(pending) }
    catch (error) {
      uncertain = !(error instanceof ApiError) || (error.status !== 401 && (error.status === 0 || error.status >= 500 || error.code === 'REQUEST_IN_PROGRESS' || error.code === 'INVALID_RESPONSE'))
      if (!uncertain) { pending = undefined; success = undefined }
      busy = false; changed()
      return { kind: uncertain ? 'unknown' : 'rejected', error }
    }
    const completed = success
    pending = undefined; success = undefined; uncertain = false
    try { await completed?.(value); return { kind: 'success' } }
    catch (error) { return { kind: 'refresh_failed', error } }
    finally { busy = false; changed() }
  }
  return {
    get busy() { return busy }, get uncertain() { return uncertain }, get frozen() { return busy || uncertain },
    async send(request: Omit<CustomerWriteRequest, 'key'>, onSuccess: (value: unknown) => Promise<void> = async () => {}): Promise<WriteOutcome> {
      if (busy || uncertain) return { kind: 'blocked' }
      pending = Object.freeze({ ...request, key: newKey() }); success = onSuccess
      return execute()
    },
    retry: execute,
    clear() { if (busy) return false; pending = undefined; success = undefined; uncertain = false; changed(); return true },
  }
}
