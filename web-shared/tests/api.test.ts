import test from 'node:test'
import assert from 'node:assert/strict'
import { createApiClient, ApiError } from '../src/api.ts'

test('Bearer stays in client memory, sends no cookies, and 401 clears it', async () => {
  const seen: RequestInit[] = []
  const client = createApiClient('STAFF', async (_url, init) => {
    seen.push(init ?? {})
    if (seen.length === 1) return new Response(JSON.stringify({ code: 'OK', data: { accessToken: 'test-only', tokenType: 'Bearer', expiresAt: '2030-01-01T00:00:00Z' } }))
    return new Response(JSON.stringify({ code: 'UNAUTHENTICATED', message: '会话已失效', traceId: 'test' }), { status: 401 })
  })
  await client.login('admin', 'example-password')
  assert.equal(client.hasSession(), true)
  await assert.rejects(client.request('/me'), (e: unknown) => e instanceof ApiError && e.status === 401)
  assert.equal(client.hasSession(), false)
  assert.equal(new Headers(seen[1]?.headers).get('Authorization'), 'Bearer test-only')
  assert.equal(seen[1]?.credentials, 'omit')
  assert.equal(new Headers(seen[0]?.headers).has('Authorization'), false)
})
test('failed logout clears local session but reports server failure', async () => {
  let n = 0
  const client = createApiClient('CUSTOMER', async () => ++n === 1
    ? new Response(JSON.stringify({ code: 'OK', data: { accessToken: 'test', tokenType: 'Bearer', expiresAt: '2030-01-01T00:00:00Z' } }))
    : new Response(JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', message: '服务不可用' }), { status: 503 }))
  await client.login('customer', 'example-password')
  await assert.rejects(client.logout())
  assert.equal(client.hasSession(), false)
})

test('a non-JSON 401 still invalidates the session and notifies its owner', async () => {
  let n = 0, invalidated = 0
  const client = createApiClient('STAFF', async () => ++n === 1
    ? new Response(JSON.stringify({ code: 'OK', data: { accessToken: 'test', tokenType: 'Bearer', expiresAt: '2030-01-01T00:00:00Z' } }))
    : new Response('Unauthorized', { status: 401 }))
  client.onUnauthorized(() => invalidated++)
  await client.login('admin', 'example-password')
  await assert.rejects(client.request('/me'))
  assert.equal(client.hasSession(), false)
  assert.equal(invalidated, 1)
})

test('multipart upload leaves Content-Type boundary to the browser and retains idempotency key', async () => {
  let captured: RequestInit | undefined
  const client=createApiClient('STAFF',async (_url,options)=>{captured=options;return new Response(JSON.stringify({code:'OK',data:{id:'123'}}))})
  const body=new FormData();body.append('file',new Blob(['test'],{type:'image/png'}),'photo.png')
  await client.request('/admin/assets/images',{method:'POST',body,headers:{'Idempotency-Key':'unchanged-upload-key'}})
  const headers=new Headers(captured?.headers)
  assert.equal(headers.has('Content-Type'),false);assert.equal(headers.get('Idempotency-Key'),'unchanged-upload-key');assert.equal(captured?.body,body)
})
