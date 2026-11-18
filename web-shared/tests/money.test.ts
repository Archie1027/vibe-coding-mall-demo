import test from 'node:test'
import assert from 'node:assert/strict'
import { formatMinor, yuanToMinor, sumLines } from '../src/money.ts'

test('money strings retain cents beyond JavaScript safe integer range', () => {
  assert.equal(formatMinor('129900'), '¥ 1,299.00')
  assert.equal(formatMinor('9007199254740993'), '¥ 90,071,992,547,409.93')
  assert.equal(formatMinor('-1'), '-¥ 0.01')
  assert.equal(formatMinor('0'), '¥ 0.00')
})
test('input conversion and totals never round silently', () => {
  assert.equal(yuanToMinor('99.95'), '9995')
  assert.equal(sumLines([{ priceMinor: '9995', quantity: 3 }, { priceMinor: '5', quantity: 1 }]), '29990')
  assert.throws(() => yuanToMinor('1.001'))
  assert.throws(() => formatMinor('1.5'))
  assert.throws(() => sumLines([{ priceMinor: '100', quantity: 0 }]))
})
