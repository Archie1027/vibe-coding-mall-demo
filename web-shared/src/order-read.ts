import type { Spec } from './catalog-api.ts'
import type { AddressInput } from './customer-data.ts'

export type OrderState = 'UNPAID' | 'CLOSING' | 'CANCELLED' | 'PAID' | 'SHIPPED' | 'COMPLETED'
export interface OrderLine {
  id: string; skuId: string; productName: string; specs: Spec[]; quantity: number
  unitPriceMinor: string; grossMinor: string; discountMinor: string; payableMinor: string
  returnedQuantity: number; refundedMinor: string
}
export interface Order {
  id: string; orderNo: string; customerId: string; warehouseId: string; state: OrderState
  items: OrderLine[]; addressSnapshot: AddressInput; grossMinor: string; discountMinor: string
  shippingMinor: string; payableMinor: string; refundedMinor: string
  expiresAt: string; createdAt: string; version: number
}
export const orderStateLabel: Record<OrderState, string> = {
  UNPAID: '待付款', CLOSING: '支付核实中', CANCELLED: '已取消',
  PAID: '已付款', SHIPPED: '已发货', COMPLETED: '已完成',
}

export function parseOrderPage(queryPage: string | null | (string | null)[] | undefined): number {
  const page = Number(queryPage ?? 1)
  // The order list controller binds the page to a Java int.
  return Number.isInteger(page) && page >= 1 && page <= 2_147_483_647 ? page : 1
}
