import test from 'node:test'
import assert from 'node:assert/strict'
import { createWriteAttempt } from '../src/write-attempt.ts'

test('unchanged retries retain key, changes and completed requests get a new key', () => {
  let serial = 0
  const attempt = createWriteAttempt(() => `test-key-${++serial}`)
  const first = attempt.prepare('POST', '/admin/brands', '{"name":"品牌","enabled":true}')
  assert.equal(attempt.prepare('POST', '/admin/brands', '{"name":"品牌","enabled":true}'), first)
  assert.notEqual(attempt.prepare('POST', '/admin/brands', '{"name":"新名","enabled":true}'), first)
  const current = attempt.prepare('PUT', '/admin/brands/1', '{"version":0}')
  assert.notEqual(current, first)
  attempt.clear()
  assert.notEqual(attempt.prepare('PUT', '/admin/brands/1', '{"version":0}'), current)
})
