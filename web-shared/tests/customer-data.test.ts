import test from 'node:test'
import assert from 'node:assert/strict'
import { addressInput, cartPageSubtotal, createCustomerWriteAttempt } from '../src/customer-data.ts'
import { ApiError } from '../src/api.ts'

const address={recipient:'演示收件人',phone:'10000000007',province:'江苏省',city:'南京市',district:'演示区',detail:'演示街道1号',isDefault:true}
test('address contract retains explicit default and all required fields',()=>assert.deepEqual(addressInput(address),address))
test('invalid phone, blank address and too-long recipient are rejected',()=>{
 for(const value of [{...address,phone:'123'},{...address,detail:' '},{...address,recipient:'a'.repeat(51)},{...address,province:'a'.repeat(121)},{...address,city:'a'.repeat(121)},{...address,district:'a'.repeat(121)},{...address,detail:'a'.repeat(201)}])assert.throws(()=>addressInput(value))
})
test('page subtotal uses exact cents and excludes unchecked or invalid lines',()=>{
 assert.equal(cartPageSubtotal([{priceMinor:'9007199254740993',quantity:3,selected:true,valid:true},{priceMinor:'100',quantity:2,selected:false,valid:true},{priceMinor:'999',quantity:1,selected:true,valid:false}]),'27021597764222979')
})
test('unknown writes block new operations and replay their exact original request', async () => {
 const requests: unknown[] = []; let calls = 0, keys = 0, completed = 0
 const write = createCustomerWriteAttempt(async request => { requests.push({...request}); if (++calls === 1) throw new ApiError(503,'UNAVAILABLE','unknown'); return {id:'1'} }, undefined, () => `key-${++keys}`)
 const original = {method:'POST' as const,path:'/me/cart/items',body:'{"skuId":"3","quantity":2}'}
 assert.equal((await write.send(original, async () => { completed++ })).kind,'unknown')
 original.body = '{"skuId":"4","quantity":8}'
 assert.equal(write.frozen,true)
 assert.equal((await write.send(original)).kind,'blocked')
 assert.equal((await write.retry()).kind,'success')
 assert.deepEqual(requests[0], requests[1]); assert.equal(keys,1); assert.equal(completed,1); assert.equal(write.frozen,false)
})
test('refresh failure after confirmed success releases the write without replaying it', async () => {
 let sends = 0
 const write = createCustomerWriteAttempt(async () => { sends++; return {} })
 const result = await write.send({method:'DELETE',path:'/me/addresses/1'}, async () => { assert.equal(write.frozen,true); throw new Error('read failed') })
 assert.equal(result.kind,'refresh_failed'); assert.equal(write.frozen,false)
 assert.equal((await write.retry()).kind,'blocked'); assert.equal(sends,1)
})
test('business rejection ends an attempt while a pending request and refresh both block concurrent writes', async () => {
 let resolve!: (value: unknown) => void, finish!: () => void
 const write = createCustomerWriteAttempt(() => new Promise(done => { resolve=done }))
 const first = write.send({method:'PUT',path:'/me/cart/items/1',body:'{}'}, () => new Promise<void>(done => {finish=done}))
 assert.equal((await write.send({method:'DELETE',path:'/me/cart/items/2'})).kind,'blocked')
 resolve({}); await Promise.resolve(); await Promise.resolve()
 assert.equal(write.busy,true); assert.equal((await write.retry()).kind,'blocked'); finish(); await first; assert.equal(write.frozen,false)
 const rejected = createCustomerWriteAttempt(async () => { throw new ApiError(409,'VERSION_CONFLICT','changed') })
 assert.equal((await rejected.send({method:'PUT',path:'/me/addresses/1',body:'{}'})).kind,'rejected'); assert.equal(rejected.frozen,false)
})
test('invalid response and in-progress are ambiguous while unauthorized is a known rejection', async () => {
 for (const [error,kind] of [[new ApiError(200,'INVALID_RESPONSE','bad'),'unknown'],[new ApiError(409,'REQUEST_IN_PROGRESS','wait'),'unknown'],[new ApiError(401,'INVALID_RESPONSE','expired'),'rejected']] as const) {
  const write=createCustomerWriteAttempt(async()=>{throw error})
  assert.equal((await write.send({method:'POST',path:'/me/cart/items',body:'{}'})).kind,kind)
 }
})
