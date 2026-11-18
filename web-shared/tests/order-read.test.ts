import test from 'node:test'
import assert from 'node:assert/strict'
import { parseOrderPage } from '../src/order-read.ts'

test('order list keeps supported page numbers and defaults when the Java int limit is exceeded', () => {
  assert.equal(parseOrderPage('2'), 2)
  assert.equal(parseOrderPage('2147483647'), 2_147_483_647)
  assert.equal(parseOrderPage('2147483648'), 1)
  assert.equal(parseOrderPage('9007199254740991'), 1)
})

test('order list defaults on missing, nonpositive, and fractional page numbers', () => {
  for (const invalid of [undefined, null, '0', '-1', '1.5', 'abc', ['2', '3']]) {
    assert.equal(parseOrderPage(invalid), 1)
  }
})
