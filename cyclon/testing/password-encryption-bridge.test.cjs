/* SPDX-License-Identifier: Apache-2.0 */
// Runs only our document-start shim against synthetic browser primitives; no Google code or network.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const activity = fs.readFileSync(path.join(__dirname, '../../play-services-core/src/main/kotlin/org/microg/gms/passwords/PasswordEncryptionActivity.kt'), 'utf8');
const match = activity.match(/internal val SHIM = """([\s\S]*?)"""\.trimIndent\(\)/);
assert.ok(match, 'actual production shim must be present');
const gaia = '12345678901234567890';
const script = match[1].replaceAll("${'$'}", '$').replace('__EXPECTED_GAIA__', JSON.stringify(gaia));

function page({ relay = false, pathname, search = '', selected = gaia } = {}) {
  const calls = [], messages = [], timers = [];
  class Xhr { send(...args) { calls.push(['xhr', ...args]); return 'sent'; } }
  class Form { submit() { calls.push(['form']); } }
  const origin = relay ? 'https://accounts.google.com' : 'https://passwords.google.com';
  pathname ??= relay ? '/encryption/unlock/datarelay' : '/embedded/encryption/enroll/run';
  const location = { origin, pathname, search, href: origin + pathname + search };
  const window = { location, _microgPasswordEncryption: { postMessage: raw => messages.push(JSON.parse(raw)) },
    WIZ_global_data: { w2btAe: '%.@.' + JSON.stringify([selected, null, '0']).substring(1) },
    fetch(...args) { calls.push(['fetch', ...args]); return Promise.resolve('fetched'); } };
  window.top = relay ? {} : window;
  const context = vm.createContext({ window, location, XMLHttpRequest: Xhr, HTMLFormElement: Form, URLSearchParams,
    setInterval: callback => { timers.push(callback); return timers.length; } });
  vm.runInContext(script, context, { timeout: 1000 });
  return { calls, messages, timers, window, Xhr, Form };
}

test('actual shim permits source-selected account requests without moving request bytes', async () => {
  const p = page();
  assert.equal(new p.Xhr().send('opaque synthetic body'), 'sent');
  assert.equal(await p.window.fetch('/synthetic-endpoint', { method: 'POST' }), 'fetched');
  new p.Form().submit();
  assert.deepEqual(p.calls.map(call => call[0]), ['xhr', 'fetch', 'form']);
  assert.equal(p.calls[0][1], 'opaque synthetic body');
  assert.deepEqual(p.messages, []);
});

test('changed or missing Gaia refuses before XHR/fetch/form provider dispatch', () => {
  const p = page();
  p.window.WIZ_global_data.w2btAe = '%.@."999999999",null,"1"]';
  assert.throws(() => new p.Xhr().send('must never dispatch'), /account changed/);
  assert.throws(() => p.window.fetch('/synthetic-endpoint'), /account changed/);
  assert.throws(() => new p.Form().submit(), /account changed/);
  delete p.window.WIZ_global_data;
  assert.throws(() => new p.Xhr().send('still blocked'), /account changed/);
  assert.deepEqual(p.calls, []);
});

test('done route and closeView request only one native readback and never assert success', () => {
  const p = page({ pathname: '/embedded/encryption/enroll/done' });
  p.timers[0](); p.window.mm.closeView(); p.timers[0]();
  assert.deepEqual(p.messages, [{ method: 'closeView' }]);
  assert.equal(p.window.mm.setVaultSharedKeys, undefined);
});

test('relay exposes exact source key delivery only for its declared Passwords parent', () => {
  const p = page({ relay: true, search: '?origin=https%3A%2F%2Fpasswords.google.com' });
  const synthetic = '{"chromesync":[{"epoch":7,"key":{"0":1}}]}';
  p.window.mm.setVaultSharedKeys(gaia, synthetic);
  assert.deepEqual(p.messages, [{ method: 'setVaultSharedKeys', accountId: gaia, keys: synthetic,
    frameUrl: 'https://accounts.google.com/encryption/unlock/datarelay?origin=https%3A%2F%2Fpasswords.google.com' }]);
  assert.equal(p.window.mm.closeView, undefined);
  let acknowledged = 0;
  p.window.onKeyDataSet = () => { acknowledged++; };
  p.window._microgPasswordEncryption.onmessage({ data: '{"status":1}' });
  assert.equal(acknowledged, 0);
  p.window._microgPasswordEncryption.onmessage({ data: '{"status":0}' });
  assert.equal(acknowledged, 1);
});

test('unrelated accounts frame and duplicate or foreign parent expose no source bridge', () => {
  for (const options of [
    { pathname: '/signin', search: '?origin=https%3A%2F%2Fpasswords.google.com' },
    { search: '?origin=https%3A%2F%2Fevil.test' },
    { search: '?origin=https%3A%2F%2Fpasswords.google.com&origin=https%3A%2F%2Fpasswords.google.com' },
  ]) {
    const p = page({ relay: true, ...options });
    assert.equal(p.window.mm, undefined); assert.deepEqual(p.messages, []);
  }
});

test('a fresh document rechecks source Gaia rather than trusting a preceding document', () => {
  const first = page(); new first.Xhr().send('first account');
  const next = page({ selected: '888888888' });
  assert.throws(() => new next.Xhr().send('second account'), /account changed/);
  assert.equal(first.calls.length, 1); assert.equal(next.calls.length, 0);
});
