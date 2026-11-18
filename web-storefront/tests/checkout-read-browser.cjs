const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { join } = require('node:path')
const { mkdirSync } = require('node:fs')

const playwright = require(process.env.ZHICAI_PLAYWRIGHT_MODULE)
const evidence = process.env.ZHICAI_BROWSER_EVIDENCE
const chrome = process.env.ZHICAI_CHROME
if (!evidence || !chrome) throw new Error('Browser evidence directory and Chrome path are required')
const run = join(evidence, `checkout-read-${randomUUID()}`)
mkdirSync(run, { recursive: true })

;(async () => {
  const context = await playwright.chromium.launchPersistentContext(join(run, 'profile'), { executablePath: chrome, headless: true, viewport: { width: 1440, height: 900 } })
  try {
    const page = await context.newPage()
    const requests = []
    await page.route('**/api/v1/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname
      requests.push(`${request.method()} ${path}`)
      let data
      if (request.method() === 'POST' && path === '/api/v1/auth/customer/login') data = { accessToken: 'browser-fixture-only', tokenType: 'Bearer', expiresAt: '2030-01-01T00:00:00Z' }
      else if (request.method() === 'GET' && path === '/api/v1/me') data = { id: '4', username: 'test', displayName: '测试顾客', accountType: 'CUSTOMER', permissions: [], warehouseIds: [], roleIds: [], enabled: true, version: 1 }
      else if (request.method() === 'GET' && path === '/api/v1/me/addresses') data = { items: [{ id: '1', recipient: '测试顾客', phone: '10000000007', province: '江苏省', city: '南京市', district: '江宁区', detail: '测试路 1 号', isDefault: true, version: 1 }], page: 1, size: 20, total: 1 }
      else if (request.method() === 'GET' && path === '/api/v1/me/cart') data = { items: [{ id: '1', skuId: '2001', productName: 'GeForce RTX 4080 SUPER 16GB 显卡', specs: [{ name: '显存', value: '16GB' }], priceMinor: '899900', quantity: 1, selected: true, valid: true, imageUrl: 'http://127.0.0.1:5173/assets/products/rtx-4080-super.jpg' }], page: 1, size: 100, total: 1 }
      assert.ok(data, `Unexpected API request: ${request.method()} ${path}`)
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'OK', data, traceId: 'browser-fixture' }) })
    })
    await page.goto('http://127.0.0.1:5173/checkout')
    await page.waitForURL(/\/login\?redirect=/)
    await page.locator('#username').fill('test')
    await page.locator('#password').fill('123456')
    await page.getByRole('button', { name: '登录' }).click()
    await page.waitForURL('http://127.0.0.1:5173/checkout')
    await page.getByText('GeForce RTX 4080 SUPER 16GB 显卡').waitFor()
    assert.equal(await page.getByRole('button', { name: '获取服务端报价' }).isDisabled(), true)
    assert.equal(await page.getByText('测试顾客 · 10000000007').count(), 1)
    for (const width of [1440, 768, 390]) {
      await page.setViewportSize({ width, height: 900 })
      await page.screenshot({ path: join(run, `checkout-${width}.png`), fullPage: true })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${width}px horizontal overflow`)
    }
    assert.equal(requests.some(value => /POST \/api\/v1\/(checkout\/quotes|orders)/.test(value)), false)
    console.log(JSON.stringify({ result: 'CHECKOUT_READ_BROWSER_OK', screenshots: run, requests }))
  } finally { await context.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
