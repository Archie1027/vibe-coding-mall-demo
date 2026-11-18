import test from 'node:test'
import assert from 'node:assert/strict'
import { filterProducts } from '../src/catalog.ts'

const products = [
  { id: '1', name: 'RTX 显卡', brand: 'NVIDIA', category: '显卡', priceMinor: '9007199254740993', rating: '4.9' },
  { id: '2', name: 'SSD 固态硬盘', brand: '三星', category: '存储', priceMinor: '9007199254740992', rating: '4.8' },
]
test('catalog filtering combines conditions and keeps exact integer price order', () => {
  assert.deepEqual(filterProducts(products, { sort: 'priceAsc' }).map(p => p.id), ['2', '1'])
  assert.deepEqual(filterProducts(products, { keyword: 'rtx', category: '显卡', brand: 'NVIDIA' }).map(p => p.id), ['1'])
  assert.equal(filterProducts(products, { keyword: '不存在' }).length, 0)
  assert.deepEqual(products.map(p => p.id), ['1', '2'])
})
