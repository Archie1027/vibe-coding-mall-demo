const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { join } = require('node:path')
const { mkdirSync } = require('node:fs')

const playwright = require(process.env.ZHICAI_PLAYWRIGHT_MODULE)
const base = process.env.ZHICAI_BROWSER_BASE ?? 'http://127.0.0.1:5193'
const run = join(process.env.ZHICAI_BROWSER_EVIDENCE, `order-cancel-mock-${randomUUID()}`)
mkdirSync(run, { recursive: true })

;(async () => {
  const context = await playwright.chromium.launchPersistentContext(join(run, 'profile'), {
    executablePath: process.env.ZHICAI_CHROME,
    headless: true,
    viewport: { width: 1440, height: 900 },
  })
  try {
    const page = await context.newPage()
    page.setDefaultTimeout(10000)
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    const address = { recipient: '测试顾客', phone: '10000000007', province: '江苏省', city: '南京市', district: '江宁区', detail: '测试路1号', isDefault: true }
    const line = { id: '66', skuId: '2001', productName: 'GeForce RTX 4080 SUPER 16GB 显卡', specs: [{ name: '显存', value: '16GB' }], quantity: 1, unitPriceMinor: '899900', grossMinor: '899900', discountMinor: '0', payableMinor: '899900', returnedQuantity: 0, refundedMinor: '0' }
    const records = new Map()
    for (const id of ['55', '56', '57', '58', '59', '60', '61', '62']) records.set(id, {
      id, orderNo: `ZC-UI-${id}`, customerId: '4', warehouseId: '1', state: 'UNPAID',
      items: [line], addressSnapshot: address, grossMinor: '899900', discountMinor: '0',
      shippingMinor: '0', payableMinor: '899900', refundedMinor: '0',
      expiresAt: new Date(Date.now() + 1800000).toISOString(), createdAt: new Date().toISOString(), version: 0,
    })
    const attempts = new Map()
    let failRefresh = false
    const reply = (route, data, status = 200, code = 'OK') => route.fulfill({
      status, contentType: 'application/json', body: JSON.stringify({ code, message: code, data, traceId: 'browser-fixture' }),
    })
    await page.route('**/api/v1/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname
      if (path === '/api/v1/auth/customer/login') return reply(route, { accessToken: 'mock-only', tokenType: 'Bearer', expiresAt: '2030-01-01T00:00:00Z' })
      if (path === '/api/v1/me') return reply(route, { id: '4', username: 'test', displayName: '测试顾客', accountType: 'CUSTOMER', permissions: [], warehouseIds: [], roleIds: [], enabled: true, version: 0 })
      if (path === '/api/v1/orders' && request.method() === 'GET') return reply(route, { items: [...records.values()], page: 1, size: 20, total: records.size })
      const match = path.match(/^\/api\/v1\/orders\/(55|56|57|58|59|60|61|62)(\/cancel)?$/)
      assert.ok(match, `Unexpected API request ${request.method()} ${path}`)
      const id = match[1], order = records.get(id)
      if (request.method() === 'GET') {
        if (id === '58' && failRefresh) { failRefresh = false; return reply(route, null, 503, 'DEPENDENCY_UNAVAILABLE') }
        return reply(route, order)
      }
      assert.equal(request.method(), 'POST')
      assert.equal(match[2], '/cancel')
      const entries = attempts.get(id) ?? []
      entries.push({ key: request.headers()['idempotency-key'], body: request.postData() })
      attempts.set(id, entries)
      assert.match(entries.at(-1).key, /^[\x21-\x7e]{16,128}$/)
      assert.deepEqual(JSON.parse(request.postData()), { reason: '暂时不需要了' })
      if (id === '59') return reply(route, null, 401, 'UNAUTHENTICATED')
      if (id === '57') { order.state = 'PAID'; order.version++; return reply(route, null, 409, 'STATE_CONFLICT') }
      if (id === '60') {
        order.state = 'PAID'; order.version = 1
        if (entries.length === 1) return route.abort('failed')
        return reply(route, null, 409, 'STATE_CONFLICT')
      }
      if (id === '61' && entries.length === 1) return reply(route, null, 503, 'DEPENDENCY_UNAVAILABLE')
      order.state = id === '56' ? 'CLOSING' : 'CANCELLED'
      order.version = 1
      if (id === '55' && entries.length === 1) return route.abort('failed')
      if (id === '58') failRefresh = true
      if (id === '62') return reply(route, { ...order, id: '999' })
      return reply(route, order)
    })
    await page.goto(`${base}/account/orders/55`)
    await page.waitForURL(/\/login\?redirect=/)
    await page.locator('#username').fill('test')
    await page.locator('#password').fill('123456')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL(`${base}/account/orders/55`)
    await page.getByText('ZC-UI-55', { exact: false }).waitFor()
    if (process.env.ZHICAI_CANCEL_DISABLED === 'true') {
      assert.equal(await page.getByRole('button', { name: '取消订单', exact: true }).isDisabled(), true)
      assert.equal(attempts.size, 0)
      console.log(JSON.stringify({ result: 'ORDER_CANCEL_DISABLED_BROWSER_OK', evidence: run }))
      return
    }
    async function openCancel(id) {
      if (!page.url().endsWith(`/account/orders/${id}`)) {
        await page.getByRole('navigation', { name: '账户导航' }).getByRole('link', { name: '我的订单', exact: true }).click()
        await page.locator(`a[href="/account/orders/${id}"]`).click()
      }
      await page.getByRole('button', { name: '取消订单', exact: true }).click()
      assert.equal(await page.getByRole('button', { name: '确认取消订单', exact: true }).isDisabled(), true)
      await page.getByRole('textbox', { name: '取消原因' }).fill('暂时不需要了')
    }
    await openCancel('55')
    await page.getByRole('textbox', { name: '取消原因' }).fill('字'.repeat(501))
    assert.equal(await page.getByRole('button', { name: '确认取消订单', exact: true }).isDisabled(), true)
    await page.getByRole('textbox', { name: '取消原因' }).fill('😀'.repeat(500))
    assert.equal(await page.getByRole('button', { name: '确认取消订单', exact: true }).isEnabled(), true)
    await page.getByRole('textbox', { name: '取消原因' }).fill('暂时不需要了')
    for (const width of [1440, 768, 390]) {
      await page.setViewportSize({ width, height: 900 })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
      await page.screenshot({ path: join(run, `cancel-form-${width}.png`), fullPage: true })
    }
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.getByRole('button', { name: '确认取消订单', exact: true }).click()
    await page.getByRole('button', { name: '重试原操作', exact: true }).waitFor()
    assert.equal(await page.getByRole('textbox', { name: '取消原因' }).isDisabled(), true)
    assert.equal(await page.getByRole('button', { name: '确认取消订单', exact: true }).isDisabled(), true)
    assert.equal(await page.getByRole('button', { name: '暂不取消', exact: true }).isDisabled(), true)
    await page.getByRole('button', { name: '重试原操作', exact: true }).click()
    await page.getByText('已取消', { exact: true }).waitFor()
    assert.equal(attempts.get('55').length, 2)
    assert.deepEqual(attempts.get('55')[0], attempts.get('55')[1])
    await openCancel('56')
    await page.getByRole('button', { name: '确认取消订单', exact: true }).click()
    await page.getByText('支付核实中', { exact: true }).waitFor()
    assert.equal(await page.getByRole('button', { name: '取消订单', exact: true }).count(), 0)
    for (const width of [1440, 768, 390]) {
      await page.setViewportSize({ width, height: 900 })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
      await page.screenshot({ path: join(run, `closing-${width}.png`), fullPage: true })
    }
    await page.setViewportSize({ width: 1440, height: 900 })
    records.get('56').state = 'CANCELLED'
    records.get('56').version = 2
    await page.getByRole('button', { name: '刷新订单状态' }).click()
    await page.getByText('已取消', { exact: true }).waitFor()
    await openCancel('57')
    await page.getByRole('button', { name: '确认取消订单', exact: true }).click()
    await page.getByRole('button', { name: '重新加载订单', exact: true }).click()
    await page.getByText('已付款', { exact: true }).waitFor()
    assert.equal(attempts.get('57').length, 1)
    await openCancel('58')
    await page.getByRole('button', { name: '确认取消订单', exact: true }).click()
    await page.getByText('操作已成功，但最新资料加载失败，请重新加载。', { exact: false }).waitFor()
    assert.equal(await page.getByRole('button', { name: '取消订单', exact: true }).isDisabled(), true)
    await page.getByRole('button', { name: '重新加载订单', exact: true }).click()
    await page.getByText('已取消', { exact: true }).waitFor()
    assert.equal(attempts.get('58').length, 1)
    await openCancel('60')
    await page.getByRole('button', { name: '确认取消订单', exact: true }).click()
    await page.getByRole('button', { name: '重试原操作', exact: true }).click()
    await page.getByRole('button', { name: '重新加载订单', exact: true }).click()
    await page.getByText('已付款', { exact: true }).waitFor()
    assert.deepEqual(attempts.get('60')[0], attempts.get('60')[1])
    await openCancel('61')
    await page.getByRole('button', { name: '确认取消订单', exact: true }).click()
    await page.getByRole('button', { name: '重试原操作', exact: true }).click()
    await page.getByText('已取消', { exact: true }).waitFor()
    assert.deepEqual(attempts.get('61')[0], attempts.get('61')[1])
    await openCancel('62')
    await page.getByRole('button', { name: '确认取消订单', exact: true }).click()
    await page.getByText('操作已成功，但最新资料加载失败，请重新加载。', { exact: false }).waitFor()
    assert.equal(await page.getByRole('button', { name: '取消订单', exact: true }).isDisabled(), true)
    await page.getByRole('button', { name: '重新加载订单', exact: true }).click()
    await page.getByText('已取消', { exact: true }).waitFor()
    assert.equal(attempts.get('62').length, 1)
    await openCancel('59')
    await page.getByRole('button', { name: '确认取消订单', exact: true }).click()
    await page.waitForURL(/\/login\?redirect=/)
    assert.equal(attempts.get('59').length, 1)
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ result: 'ORDER_CANCEL_MOCK_BROWSER_OK', sameKeyAndBody: true, screenshots: run, orderAttempts: Object.fromEntries([...attempts].map(([id, values]) => [id, values.length])) }))
  } finally { await context.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
