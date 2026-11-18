import assert from 'node:assert/strict'
import test from 'node:test'
import { quoteMatchesItems, selectedQuoteItems, type CheckoutQuote } from '../src/checkout.ts'
import type { CartLine } from '../src/customer-data.ts'

const line = (overrides: Partial<CartLine> = {}): CartLine => ({ id: '1', skuId: '2001', productName: '显卡', specs: [], priceMinor: '899900', quantity: 1, selected: true, valid: true, ...overrides })
const quote = (items = [{ skuId: '2001', productName: '显卡', specs: [], quantity: 1, unitPriceMinor: '899900', grossMinor: '899900', discountMinor: '0', payableMinor: '899900' }]): CheckoutQuote => ({ id: '8', warehouseId: '1', items, grossMinor: '899900', discountMinor: '0', shippingMinor: '0', payableMinor: '899900', expiresAt: '2030-01-01T00:00:00Z' })

test('selected cart lines include every selected item and reject unavailable items', () => {
  assert.deepEqual(selectedQuoteItems([line(), line({ id: '2', skuId: '2002', selected: false })]), [{ skuId: '2001', quantity: 1 }])
  assert.throws(() => selectedQuoteItems([line({ valid: false })]), /失效商品/)
  assert.throws(() => selectedQuoteItems([line({ selected: false })]), /选择商品/)
})

test('quote identity and quantities must match the cart while server prices may change', () => {
  assert.equal(quoteMatchesItems(quote(), [{ skuId: '2001', quantity: 1 }]), true)
  assert.equal(quoteMatchesItems(quote(), [{ skuId: '2001', quantity: 2 }]), false)
  assert.equal(quoteMatchesItems(quote(), [{ skuId: '2002', quantity: 1 }]), false)
  assert.equal(quoteMatchesItems(quote([quote().items[0], quote().items[0]]), [{ skuId: '2001', quantity: 1 }, { skuId: '2002', quantity: 1 }]), false)
})
