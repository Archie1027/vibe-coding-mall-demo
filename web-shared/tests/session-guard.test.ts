import test from 'node:test'
import assert from 'node:assert/strict'
import { installSessionGuard } from '../src/session-guard.ts'
import { createApiClient } from '../src/api.ts'

function setup(status: number) {
  let guard: (to: any) => any
  const navigations: unknown[] = [], notices: string[] = []
  const router = { beforeEach: (fn: any) => { guard = fn }, currentRoute: { value: { path: '/admin/orders', fullPath: '/admin/orders?q=abc' } }, replace: (to: unknown) => { navigations.push(to) } }
  let calls = 0
  const api = createApiClient('STAFF', async () => ++calls === 1
    ? new Response(JSON.stringify({ code: 'OK', data: { accessToken: 'test', tokenType: 'Bearer', expiresAt: '2030-01-01T00:00:00Z' } }))
    : new Response(JSON.stringify(status === 200 ? { code: 'OK', data: { accountType: 'STAFF', enabled: true, permissions: ['order:read'] } } : { code: 'UNAVAILABLE', message: '依赖不可用' }), { status }))
  const account = { value: null as any }
  installSessionGuard(router as any, api, account, 'STAFF', '/admin/login', message => notices.push(message))
  return { api, account, run: (to: any) => guard(to), navigations, notices }
}
const target = { meta: { requiresAuth: true }, fullPath: '/admin/orders?q=abc' }
test('protected navigation rereads current identity and preserves the intended URL', async () => {
  const state = setup(200)
  assert.deepEqual(await state.run(target), { path: '/admin/login', query: { redirect: target.fullPath } })
  await state.api.login('admin', 'example-password')
  assert.equal(await state.run(target), true)
  assert.deepEqual(state.account.value.permissions, ['order:read'])
})
test('expired session redirects once to login with the target preserved', async () => {
  const state = setup(401)
  await state.api.login('admin', 'example-password')
  assert.deepEqual(await state.run(target), { path: '/admin/login', query: { redirect: target.fullPath } })
  assert.equal(state.api.hasSession(), false)
  assert.equal(state.navigations.length, 0)
})
test('dependency outage cancels navigation and reports error without discarding a valid local token', async () => {
  const state = setup(503)
  await state.api.login('admin', 'example-password')
  assert.equal(await state.run(target), false)
  assert.equal(state.api.hasSession(), true)
  assert.equal(state.notices.length, 1)
})
