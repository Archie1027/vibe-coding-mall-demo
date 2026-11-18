import type { Spec } from './catalog-api.ts'
import type { CartLine } from './customer-data.ts'

export interface QuoteLine {
  skuId: string; productName: string; specs: Spec[]; quantity: number
  unitPriceMinor: string; grossMinor: string; discountMinor: string; payableMinor: string
}
export interface CheckoutQuote {
  id: string; warehouseId: string; items: QuoteLine[]
  grossMinor: string; discountMinor: string; shippingMinor: string; payableMinor: string
  expiresAt: string
}
export interface QuoteItem { skuId: string; quantity: number }

/** The quote must cover every selected line, including unavailable ones that need attention. */
export function selectedQuoteItems(cart: CartLine[]): QuoteItem[] {
  const selected = cart.filter(line => line.selected)
  if (!selected.length) throw new Error('请先在购物车选择商品')
  if (selected.some(line => !line.valid)) throw new Error('购物车有已选的失效商品，请先取消勾选或移除')
  if (selected.length > 100) throw new Error('一次最多为 100 种商品规格获取报价')
  if (new Set(selected.map(line => line.skuId)).size !== selected.length) throw new Error('购物车商品规格重复，请重新加载')
  if (selected.some(line => !/^[1-9]\d*$/.test(line.skuId) || !Number.isInteger(line.quantity) || line.quantity < 1 || line.quantity > 99)) throw new Error('购物车商品资料不完整，请重新加载')
  return selected.map(line => ({ skuId: line.skuId, quantity: line.quantity }))
}

/** Server pricing is authoritative; only identity and quantity must match the confirmation. */
export function quoteMatchesItems(quote: CheckoutQuote, items: QuoteItem[]): boolean {
  if (!quote || !/^[1-9]\d*$/.test(quote.id) || !Array.isArray(quote.items) || quote.items.length !== items.length || !Number.isFinite(Date.parse(quote.expiresAt))) return false
  const expected = new Map(items.map(item => [item.skuId, item.quantity]))
  const seen = new Set<string>()
  return quote.items.every(line => {
    if (seen.has(line.skuId) || expected.get(line.skuId) !== line.quantity) return false
    seen.add(line.skuId)
    return true
  })
}
