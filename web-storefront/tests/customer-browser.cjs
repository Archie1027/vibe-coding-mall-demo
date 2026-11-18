// Approval-gated REAL browser/API flow. The Java fixture owns SQL setup and exact cleanup.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
if (process.env.M1C_BROWSER_ALLOW_WRITES !== 'approved-temporary-customer-browser') {
  console.error('Approved browser fixture gate required; no browser or request started.');
  process.exit(2);
}
const required = name => { const value = process.env[name]; if (!value) throw new Error('Missing fixture environment: ' + name); return value; };
const origin = required('M1C_BROWSER_ORIGIN'), apiBase = required('M1C_BROWSER_API_BASE');
assert.equal(origin, 'http://127.0.0.1:5173');
assert.match(apiBase, /^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/);
const owner = required('M1C_BROWSER_OWNER'), root = required('M1C_BROWSER_EVIDENCE'), manifest = required('M1C_BROWSER_MANIFEST');
assert.match(owner, /^[1-9][0-9]{0,18}$/);
assert.ok(path.isAbsolute(root) && path.isAbsolute(manifest));
const { chromium } = require(required('M1C_PLAYWRIGHT_MODULE'));
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
function note(stage, fields = {}) {
  const fd = fs.openSync(manifest, 'a');
  try { fs.writeSync(fd, JSON.stringify({ stage, ...fields }) + '\n'); fs.fsyncSync(fd); }
  finally { fs.closeSync(fd); }
}
const cases = [], requests = [], claimKeys = new Set(), createKeys = { ADDRESS: new Set(), CART: new Set() };
const products = new Map(), inFlight = new Set();
const completedRequests = new Set();
let browser, page, phase = 'initialise', proxyFailure, dropped = false, armDrop = false, firstAddressReply;
let currentAddresses = [], currentCart = [], pageErrors = 0;
function check(name, value) { assert.ok(value, name); cases.push(name); }
const money = minor => {
  const value = BigInt(minor);
  return `¥ ${(value / 100n).toLocaleString('zh-CN')}.${(value % 100n).toString().padStart(2, '0')}`;
};
async function until(predicate) {
  const deadline = Date.now() + 10000;
  while (!predicate()) { if (proxyFailure) throw proxyFailure; if (Date.now() > deadline) throw new Error('Fixture state deadline'); await new Promise(resolve => setTimeout(resolve, 50)); }
}
async function snapshot(name) {
  for (const width of [1440, 768, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.waitForLoadState('networkidle');
    check(`${name}: no horizontal overflow at ${width}`, await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: path.join(root, `${name}-${width}.png`), fullPage: true, mask: [page.locator('#password')] });
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
}
async function proxy(route) {
  const request = route.request(), url = new URL(request.url()), method = request.method(), pathname = url.pathname;
  assert.equal(url.origin, origin);
  const mutation = method !== 'GET' && pathname.startsWith('/api/v1/me/');
  const authWrite = method === 'POST' && ['/api/v1/auth/customer/login', '/api/v1/auth/logout'].includes(pathname);
  assert.ok(method === 'GET' || mutation || authWrite, 'Only known fixture API methods');
  const requestId = crypto.randomUUID(), record = { requestId, actor: owner, method, path: pathname + url.search };
  let kind, identity;
  if (mutation) {
    assert.ok(['POST', 'PUT', 'DELETE'].includes(method));
    kind = pathname.startsWith('/api/v1/me/addresses') ? 'ADDRESS' : 'CART';
    assert.match(pathname, /^\/api\/v1\/me\/(?:addresses(?:\/[1-9][0-9]*)?|cart\/items(?:\/[1-9][0-9]*)?)$/);
    record.key = request.headers()['idempotency-key']; assert.match(record.key ?? '', /^[0-9a-f-]{36}$/);
    record.bodySha256 = sha(request.postData() ?? '');
    identity = JSON.stringify([owner, method, pathname, record.key]); claimKeys.add(identity);
    assert.ok(claimKeys.size <= 16, 'Approved claim budget');
    if (method === 'POST') { createKeys[kind].add(identity); assert.ok(createKeys[kind].size <= 2, 'Approved create budget'); }
  }
  requests.push(record); note('browser-request', record);
  // This is an HTTP proxy to the actual test backend, with no mocked response or business-data edit.
  const response = await route.fetch({ url: apiBase + pathname + url.search, timeout: 15000, maxRetries: 0, maxRedirects: 0 });
  const traceId = response.headers()['x-trace-id'];
  note('browser-response-header', { requestId, status: response.status(), traceId: traceId ?? '' });
  assert.match(traceId ?? '', /^[0-9a-fA-F-]{36}$/);
  assert.ok(response.status() < 500, 'Infrastructure failures retain all SQL');
  const text = await response.text(), decoded = JSON.parse(text);
  assert.equal(decoded.code, 'OK', 'Unexpected business rejection retains all SQL');
  if (pathname === '/api/v1/auth/customer/login') {
    assert.match(decoded.data.accessToken, /^[A-Za-z0-9_-]{43}$/);
    note('browser-session', { redisSessionKey: 'zhicai:auth:session:' + sha(decoded.data.accessToken) });
  }
  if (method === 'GET' && /^\/api\/v1\/catalog\/products\/[1-9][0-9]*$/.test(pathname)) products.set(decoded.data.id, decoded.data);
  if (method === 'GET' && pathname === '/api/v1/me/addresses') currentAddresses = decoded.data.items;
  if (method === 'GET' && pathname === '/api/v1/me/cart') currentCart = decoded.data.items;
  if (mutation && ['POST', 'PUT'].includes(method)) {
    assert.match(decoded.data.id, /^[1-9][0-9]{0,18}$/);
    note('browser-resource', { requestId, actor: owner, resourceType: kind, resourceId: decoded.data.id });
  }
  if (mutation && method === 'POST' && pathname === '/api/v1/me/addresses') {
    if (armDrop && !dropped) {
      assert.equal(response.status(), 201); firstAddressReply = { identity, bodySha256: record.bodySha256, responseSha256: sha(text) };
      dropped = true; note('browser-response-dropped', { requestId, knownServerStatus: 201 });
      await route.abort('failed'); return;
    }
    if (firstAddressReply && identity === firstAddressReply.identity) {
      assert.equal(record.bodySha256, firstAddressReply.bodySha256); assert.equal(sha(text), firstAddressReply.responseSha256);
      check('lost-response retry preserves request key, body and stored envelope', true);
    }
  }
  await route.fulfill({ response });
  completedRequests.add(requestId);
}
async function navigate(pathname) {
  await page.evaluate(target => document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(target).then(() => true), pathname);
  await page.waitForURL(url => url.pathname === pathname);
}
async function fillAddress(recipient, isDefault) {
  for (const [label, value] of [['收件人', recipient], ['手机号', '10000000007'], ['省 / 自治区 / 直辖市', '江苏省'], ['市', '南京市'], ['区 / 县', '演示区'], ['详细地址', '仅用于开发验收的虚构地址']])
    await page.getByLabel(label, { exact: true }).fill(value);
  await page.getByRole('checkbox', { name: '设为默认地址', exact: true }).setChecked(isDefault);
}
async function addProduct(id, quantity) {
  await navigate('/products/' + id); await page.locator('.option-group .option').first().click();
  for (let n = 1; n < quantity; n++) await page.getByRole('button', { name: '增加数量', exact: true }).click();
  const before = requests.length;
  await page.getByRole('button', { name: '加入购物车', exact: true }).click();
  await until(() => requests.slice(before).some(r => r.method === 'POST' && r.path === '/api/v1/me/cart/items' && completedRequests.has(r.requestId)));
  await page.waitForFunction(() => document.querySelector('.detail-actions button')?.disabled === false);
  await page.getByText('已加入购物车', { exact: true }).waitFor();
}
async function run() {
  browser = await chromium.launch({ executablePath: required('M1C_CHROME_EXECUTABLE'), headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }); page = await context.newPage();
  page.on('pageerror', error => { pageErrors++; note('browser-page-error', { errorName: error.name }); });
  await context.route('**/api/v1/**', route => {
    const work = proxy(route).catch(async error => { proxyFailure = error; note('browser-proxy-error', { errorName: error.name }); await route.abort().catch(() => {}); });
    inFlight.add(work); return work.finally(() => inFlight.delete(work));
  });
  phase = 'guest login';
  await page.goto(origin + '/products/2001'); await page.locator('.option-group .option').first().click();
  await page.getByRole('button', { name: '加入购物车', exact: true }).click(); await page.waitForURL(url => url.pathname === '/login');
  await page.getByLabel('用户名', { exact: true }).fill(required('M1C_BROWSER_USERNAME'));
  await page.getByLabel('密码', { exact: true }).fill(required('M1C_BROWSER_PASSWORD')); delete process.env.M1C_BROWSER_PASSWORD;
  await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL('**/products/2001');
  await page.waitForLoadState('networkidle'); check('login return does not automatically add a product', !requests.some(r => r.method === 'POST' && r.path === '/api/v1/me/cart/items'));
  phase = 'address create and response loss'; await navigate('/account/addresses'); await page.getByRole('heading', { name: '还没有收货地址', exact: true }).waitFor();
  await page.getByRole('button', { name: '新增地址', exact: true }).first().click(); await fillAddress('浏览器演示甲', true); await snapshot('address-editor');
  armDrop = true; await page.getByRole('button', { name: '保存地址', exact: true }).click(); await page.getByRole('button', { name: '重试原操作', exact: true }).waitFor();
  check('unknown result freezes address form', await page.getByLabel('收件人', { exact: true }).isDisabled());
  page.once('dialog', dialog => dialog.dismiss()); await page.getByRole('button', { name: '退出', exact: true }).click();
  check('cancel logout keeps unknown request and customer session', new URL(page.url()).pathname === '/account/addresses' && !requests.some(r => r.path === '/api/v1/auth/logout'));
  await page.getByRole('button', { name: '重试原操作', exact: true }).click(); await until(() => currentAddresses.length === 1); await page.locator('.address-row').first().waitFor();
  check('replay leaves exactly one address', await page.locator('.address-row').count() === 1);
  phase = 'address edit and default'; await page.getByRole('button', { name: '新增地址', exact: true }).first().click(); await fillAddress('浏览器演示乙', false);
  await page.getByRole('button', { name: '保存地址', exact: true }).click(); await until(() => currentAddresses.length === 2);
  const second = page.locator('.address-row').filter({ hasText: '浏览器演示乙' }); await second.getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByRole('checkbox', { name: '设为默认地址', exact: true }).check(); await page.getByRole('button', { name: '保存地址', exact: true }).click();
  await until(() => currentAddresses.some(a => a.recipient === '浏览器演示乙' && a.isDefault));
  check('default switch is unique and increments displaced version', currentAddresses.filter(a => a.isDefault).length === 1 && currentAddresses.find(a => a.recipient === '浏览器演示甲').version === 1);
  const first = page.locator('.address-row').filter({ hasText: '浏览器演示甲' }); await first.getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByLabel('详细地址', { exact: true }).fill('修改后的虚构开发验收地址'); await page.getByRole('button', { name: '保存地址', exact: true }).click();
  await until(() => currentAddresses.some(a => a.recipient === '浏览器演示甲' && a.version === 2)); await snapshot('addresses');
  phase = 'cart add and edit'; await addProduct('2001', 2); await navigate('/cart'); await until(() => currentCart.length === 1);
  const firstSku = products.get('2001').skus.find(s => s.enabled), firstName = products.get('2001').name;
  check('detail selected SKU and quantity reached real cart', currentCart[0].skuId === firstSku.id && currentCart[0].quantity === 2);
  await page.getByLabel(firstName + '数量', { exact: true }).fill('3'); await page.getByRole('button', { name: '更新数量', exact: true }).first().click();
  await until(() => currentCart[0].quantity === 3); await page.locator('.cart-summary').getByText(money(BigInt(firstSku.priceMinor) * 3n), { exact: true }).waitFor();
  const checkbox = page.getByRole('checkbox', { name: '选择' + firstName, exact: true }); await checkbox.click(); await until(() => currentCart[0].selected === false);
  await page.locator('.cart-summary').getByText('¥ 0.00', { exact: true }).waitFor(); await checkbox.click(); await until(() => currentCart[0].selected === true);
  await addProduct('2002', 1); await navigate('/cart'); await until(() => currentCart.length === 2);
  const subtotal = currentCart.filter(line => line.valid && line.selected).reduce((sum, line) => sum + BigInt(line.priceMinor) * BigInt(line.quantity), 0n);
  await page.locator('.cart-summary').getByText(money(subtotal), { exact: true }).waitFor(); check('page subtotal uses exact current cents and confirmed selections', true); await snapshot('cart');
  phase = 'remove cart and addresses';
  while (currentCart.length) {
    const before = currentCart.length; page.once('dialog', dialog => dialog.accept()); await page.locator('.cart-row').first().getByRole('button', { name: '移除', exact: true }).click();
    await until(() => currentCart.length === before - 1);
  }
  await page.getByRole('heading', { name: '购物车还是空的', exact: true }).waitFor(); check('real cart removal returns empty state', true);
  await navigate('/account/addresses'); await until(() => currentAddresses.length === 2);
  page.once('dialog', dialog => dialog.dismiss()); await page.locator('.address-row').first().getByRole('button', { name: '删除', exact: true }).click();
  check('cancel address deletion sends no delete', !requests.some(r => r.method === 'DELETE' && r.path.startsWith('/api/v1/me/addresses/')));
  while (currentAddresses.length) {
    const before = currentAddresses.length; page.once('dialog', dialog => dialog.accept()); await page.locator('.address-row').first().getByRole('button', { name: '删除', exact: true }).click();
    await until(() => currentAddresses.length === before - 1);
  }
  await page.getByRole('heading', { name: '还没有收货地址', exact: true }).waitFor(); check('real address removal returns empty state', true);
  phase = 'logout'; await page.getByRole('button', { name: '退出', exact: true }).click(); await page.locator('header .session-actions').getByRole('link', { name: '登录', exact: true }).waitFor();
  await page.waitForLoadState('networkidle'); while (inFlight.size) await Promise.all([...inFlight]);
  if (proxyFailure) throw proxyFailure;
  check('real browser logout completed', requests.filter(r => r.path === '/api/v1/auth/logout').length === 1);
  check('browser has no uncaught page errors', pageErrors === 0);
  check('planned response-loss recovery was exercised', dropped && createKeys.ADDRESS.size === 2 && createKeys.CART.size === 2);
  note('browser-checks-passed', { cases, httpRequests: requests.length, distinctClaims: claimKeys.size });
  fs.writeFileSync(path.join(root, 'browser-result.json'), JSON.stringify({ mode: 'REAL_API_PROXY_NO_MOCKS', cases, httpRequests: requests.length, distinctClaims: claimKeys.size }, null, 2));
  console.log(JSON.stringify({ cases: cases.length, httpRequests: requests.length, distinctClaims: claimKeys.size, mode: 'real-api' }));
}
run().catch(async error => {
  note('browser-failed', { phase, errorName: error.name }); console.error(`Browser acceptance failed at ${phase} (${error.name}). SQL retained.`); process.exitCode = 1;
  if (page) await page.screenshot({ path: path.join(root, 'failure.png'), fullPage: true, mask: [page.locator('#password')] }).catch(() => {});
}).finally(async () => { await browser?.close(); });
