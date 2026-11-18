const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { join } = require('node:path')
const { mkdirSync } = require('node:fs')

const playwright = require(process.env.ZHICAI_PLAYWRIGHT_MODULE)
const evidence = process.env.ZHICAI_BROWSER_EVIDENCE
const chrome = process.env.ZHICAI_CHROME
if (!evidence || !chrome) throw new Error('Browser evidence directory and Chrome path are required')
const run = join(evidence, `checkout-write-mock-${randomUUID()}`)
mkdirSync(run, { recursive: true })

;(async () => {
  const context = await playwright.chromium.launchPersistentContext(join(run, 'profile'), { executablePath: chrome, headless: true, viewport: { width: 1440, height: 900 } })
  try {
    const page = await context.newPage()
    const requests = [], orderAttempts = []
    const address = { id: '1', recipient: '测试顾客', phone: '10000000007', province: '江苏省', city: '南京市', district: '江宁区', detail: '测试路 1 号', isDefault: true, version: 1 }
    const cart = { id: '1', skuId: '2001', productName: 'GeForce RTX 4080 SUPER 16GB 显卡', specs: [{ name: '显存', value: '16GB' }], priceMinor: '899900', quantity: 1, selected: true, valid: true, imageUrl: 'http://127.0.0.1:5173/assets/products/rtx-4080-super.jpg' }
    const line = { skuId: '2001', productName: cart.productName, specs: cart.specs, quantity: 1, unitPriceMinor: '899900', grossMinor: '899900', discountMinor: '0', payableMinor: '899900' }
    const quote = { id: '8', warehouseId: '1', items: [line], grossMinor: '899900', discountMinor: '0', shippingMinor: '10000', payableMinor: '909900', expiresAt: new Date(Date.now() + 300000).toISOString() }
    const order = { id: '55', orderNo: 'ZC-MOCK-55', customerId: '4', warehouseId: '1', state: 'UNPAID', items: [{ ...line, id: '66', returnedQuantity: 0, refundedMinor: '0' }], addressSnapshot: address, grossMinor: '899900', discountMinor: '0', shippingMinor: '10000', payableMinor: '909900', refundedMinor: '0', expiresAt: new Date(Date.now() + 1800000).toISOString(), createdAt: new Date().toISOString(), version: 0 }
    const respond = (route, data, status = 200, code = 'OK') => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message: code, data, traceId: 'browser-mock' }) })
    await page.route('**/api/v1/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname, method = request.method()
      requests.push(`${method} ${path}`)
      if (method === 'POST' && path === '/api/v1/auth/customer/login') return respond(route, { accessToken: 'mock-only', tokenType: 'Bearer', expiresAt: '2030-01-01T00:00:00Z' })
      if (method === 'GET' && path === '/api/v1/me') return respond(route, { id: '4', username: 'test', displayName: '测试顾客', accountType: 'CUSTOMER', permissions: [], warehouseIds: [], roleIds: [], enabled: true, version: 1 })
      if (method === 'GET' && path === '/api/v1/me/addresses') return respond(route, { items: [address], page: 1, size: 20, total: 1 })
      if (method === 'GET' && path === '/api/v1/me/cart') return respond(route, { items: [cart], page: 1, size: 100, total: 1 })
      if (method === 'POST' && path === '/api/v1/checkout/quotes') {
        assert.deepEqual(JSON.parse(request.postData()), { addressId: '1', items: [{ skuId: '2001', quantity: 1 }] })
        assert.match(request.headers()['idempotency-key'], /^[\x21-\x7e]{16,128}$/)
        return respond(route, quote, 201)
      }
      if (method === 'POST' && path === '/api/v1/orders') {
        orderAttempts.push({ key: request.headers()['idempotency-key'], body: request.postData() })
        assert.deepEqual(JSON.parse(request.postData()), { quoteId: '8', remark: '工作日配送' })
        if (orderAttempts.length === 1) return route.abort('failed')
        return respond(route, order, 201)
      }
      if (method === 'GET' && path === '/api/v1/orders/55') return respond(route, order)
      throw new Error(`Unexpected API request: ${method} ${path}`)
    })
    await page.goto('http://127.0.0.1:5173/checkout')
    await page.waitForURL(/\/login\?redirect=/)
    await page.locator('#username').fill('test')
    await page.locator('#password').fill('123456')
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL('http://127.0.0.1:5173/checkout')
    await page.getByRole('button', { name: '获取服务端报价' }).click()
    await page.getByText('¥ 9,099.00').waitFor()
    await page.getByRole('textbox', { name: '给商家的留言（选填）' }).fill('工作日配送')
    await page.getByRole('button', { name: '确认金额并创建待付款订单' }).click()
    await page.getByRole('button', { name: '重试原操作' }).waitFor()
    assert.equal(await page.getByRole('button', { name: '确认金额并创建待付款订单' }).isDisabled(), true)
    await page.getByRole('button', { name: '重试原操作' }).click()
    await page.waitForURL('http://127.0.0.1:5173/account/orders/55')
    await page.getByText('ZC-MOCK-55').waitFor()
    assert.equal(orderAttempts.length, 2)
    assert.equal(orderAttempts[0].key, orderAttempts[1].key)
    assert.equal(orderAttempts[0].body, orderAttempts[1].body)
    assert.equal(requests.filter(value => value === 'POST /api/v1/checkout/quotes').length, 1)
    await page.screenshot({ path: join(run, 'order-detail.png'), fullPage: true })
    console.log(JSON.stringify({ result: 'CHECKOUT_WRITE_MOCK_BROWSER_OK', evidence: run, quoteRequests: 1, orderAttempts: 2, sameKeyAndBody: true }))
  } finally { await context.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
