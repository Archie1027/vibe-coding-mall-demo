import test from 'node:test'
import assert from 'node:assert/strict'
import { matchesOrder } from '../src/orders.ts'

test('order filters combine date, warehouse, source and keyword independently', () => {
  const row = { status: '待发货', text: 'ZC0031 林先生', date: '2026-10-20', warehouse: '华东一号仓', source: '商城前台' }
  assert.equal(matchesOrder(row, { period: '7', warehouse: '华东一号仓', keyword: '林', source: '商城前台' }, '2026-10-21'), true)
  assert.equal(matchesOrder(row, { warehouse: '华南二号仓' }, '2026-10-21'), false)
  assert.equal(matchesOrder(row, { period: 'today' }, '2026-10-21'), false)
  assert.equal(matchesOrder(row, { period: '7' }, '2026-10-28'), false)
  assert.equal(matchesOrder(row, { source: '后台录入' }, '2026-10-21'), false)
})
