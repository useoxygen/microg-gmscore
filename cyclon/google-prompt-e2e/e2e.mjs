// End-to-end Google prompt test: Google asks the phone to confirm, the phone approves (or denies) on Google's page.
// Usage: node e2e.mjs SERIAL [--deny] [--locked] [--signin]. See README.md.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { openBrowser, text, EMAIL, password } from './browser.mjs';
import { phone, sleep } from './phone.mjs';

const [serial, ...flags] = process.argv.slice(2);
if (!serial) { console.error('usage: node e2e.mjs SERIAL [--deny] [--locked] [--signin]'); process.exit(2); }
const deny = flags.includes('--deny');
// --locked answers from a PIN lock screen: it sets a temporary PIN for the run and removes it afterwards; --signin uses a password sign-in (needs 2-Step Verification on the account).
const locked = flags.includes('--locked');
const signin = flags.includes('--signin');
// Gms2FA events that end a message's route before its notification is shown. A fetch_failed line is terminal only
// with final=true; earlier attempts are retried.
const TERMINAL = /event=(expired|pref_off|malformed|account_not_matched|not_showable|notifications_blocked|dispatch_dropped)\b|event=fetch_failed\b.*final=true/;

const t0 = Date.now();
const report = { serial, deny, locked, signin, steps: [], ok: false };
// The password (only read for --signin) never reaches the console or the report, even inside an error message.
let secret = null;
const redact = value => {
  if (typeof value === 'string') return secret ? value.split(secret).join('***') : value;
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, redact(v)]));
  return value;
};
const step = (name, data = {}) => {
  try {
    const entry = redact({ name, ms: Date.now() - t0, ...data });
    report.steps.push(entry);
    console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${name}`, JSON.stringify(entry).slice(0, 2000));
  } catch {
    // Logging must never stop the run before cleanup.
  }
};

/**
 * Groups Gms2FA lines by session. Returns the session that showed a prompt, else the first one that ended (an older
 * prompt's late message can end while this run's prompt is still on its way), else null.
 */
function routeOutcome(lines) {
  const sessions = new Map();
  for (const line of lines) {
    const id = line.match(/session=(\w+)/)?.[1];
    if (id) sessions.set(id, [...(sessions.get(id) ?? []), line]);
  }
  for (const [id, events] of sessions) {
    if (events.some(l => /event=shown\b/.test(l))) return { id, events, shown: true };
  }
  for (const [id, events] of sessions) {
    const end = events.find(l => TERMINAL.test(l));
    if (end) return { id, events, shown: false, end };
  }
  return null;
}

const PIN = '7316';
let pinSet = false;
const p = phone(serial);
if (!(await p.unlock())) { console.error('the phone is locked with a PIN; unlock it first'); process.exit(2); }
const priorTop = p.top();
const log = p.watchLog();
let browser = null;

// Each cleanup step runs on its own, so a failure in one (e.g. deleting the browser profile) never skips removing the
// temporary PIN or restoring the phone. It runs once, from the normal end or from Ctrl-C / SIGTERM.
let cleanupDone = null;
const cleanup = () => cleanupDone ??= (async () => {
  const attempt = async (name, fn) => { try { await fn(); } catch (e) { step(`cleanup ${name} failed`, { message: e.message }); } };
  await attempt('browser', () => browser?.close());
  await attempt('log', () => log.stop());
  if (pinSet) {
    await attempt('pin', async () => {
      // Unlock first if a failure left the phone locked, then remove the temporary PIN and check it is gone.
      if (p.locked()) {
        p.sh('input keyevent KEYCODE_WAKEUP; input swipe 540 1900 540 600 200');
        await sleep(1000);
        p.sh(`input text ${PIN}; input keyevent KEYCODE_ENTER`);
        await sleep(1500);
      }
      p.sh(`locksettings clear --old ${PIN} || true`);
      if (!/verified successfully/i.test(p.sh('locksettings verify 2>&1 || true'))) throw new Error('the temporary PIN is still set');
      pinSet = false;
    });
  }
  // Put the phone back as the owner left it: same task in front, shade closed.
  await attempt('shade', () => p.sh('cmd statusbar collapse'));
  await attempt('foreground', async () => {
    for (let i = 0; i < 4 && p.top() !== priorTop; i++) { p.sh('input keyevent KEYCODE_BACK'); await sleep(700); }
  });
  const restored = !pinSet && p.top() === priorTop;
  step('cleanup', { top: p.top(), priorTop, restored, gms2fa: log.lines.filter(l => l.includes('event=')) });
  if (!restored) report.ok = false;
})();

const finish = async code => {
  await cleanup();
  const dir = join(import.meta.dirname, 'reports');
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, `report-${Date.now()}.json`), JSON.stringify(report, null, 2));
  console.log(report.ok ? 'PASS' : 'FAIL');
  process.exit(code ?? (report.ok ? 0 : 1));
};
// A signal stops the main flow (it checks before each phone change and while waiting; closing the browser ends
// pending browser waits), and the flow's own finally runs the cleanup. A second signal, or 30 s without the flow
// settling, cleans up directly.
let aborted = null;
const checkAbort = () => { if (aborted) throw new Error(`interrupted by ${aborted}`); };
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    report.ok = false;
    if (aborted) { finish(130); return; }
    aborted = signal;
    step('interrupted', { signal });
    browser?.close().catch(() => {});
    setTimeout(() => finish(130), 30000).unref();
  });
}

try {
  browser = await openBrowser();
  const { page } = browser;
  if (locked) {
    // Without a PIN the keyguard goes away on wake, so the run sets one. The power key locks at once.
    checkAbort();
    p.sh(`locksettings set-pin ${PIN}`);
    pinSet = true;
    p.sh('input keyevent KEYCODE_POWER');
    await sleep(2500);
    p.sh('input keyevent KEYCODE_WAKEUP');
    await sleep(1500);
    if (!p.locked()) throw new Error('the phone did not lock');
  }
  // Browser: from an unfamiliar browser, "Forgot password?" makes Google verify through the account's phones.
  // A password sign-in asks the phone only on accounts with 2-Step Verification (--signin).
  // The run never sets a new password.
  await page.goto('https://accounts.google.com/signin/v2/identifier?hl=en&flowName=GlifWebSignIn');
  await page.fill('#identifierId', EMAIL);
  await page.keyboard.press('Enter');
  if (signin) {
    secret = password();
    try {
      await page.locator('input[name=Passwd]').fill(secret, { timeout: 15000 });
    } catch {
      // Playwright's error text can repeat the filled value; report a fixed message instead.
      throw new Error('could not enter the password');
    }
    await page.keyboard.press('Enter');
  } else {
    await page.getByText(/forgot password/i).first().click({ timeout: 15000 });
  }
  await page.waitForTimeout(4000);
  step('challenge', { url: page.url().split('?')[0], text: (await text(page)).slice(0, 500) });
  // Many recoveries or denials in a row make Google pause prompts for the account for a few hours.
  if (/too many failed attempts/i.test(await text(page))) throw new Error('Google rate-limited this account ("too many failed attempts"); retry in a few hours');
  if (!/check your phone/i.test(await text(page))) {
    // Google may pick another method first; the prompt is then under "Try another way".
    await page.getByText(/try another way/i).first().click({ timeout: 5000 });
    await page.getByText(/tap yes|get a notification|google prompt/i).first().click({ timeout: 10000 });
  }
  await page.getByText(/check your phone/i).first().waitFor({ timeout: 15000 });
  step('prompt requested', { url: page.url().split('?')[0], text: (await text(page)).match(/Google sent[^\n]*/)?.[0] });

  // Phone: one message's session goes received -> fetched -> shown. Other sessions (removal tickles, ignored
  // messages) are skipped, and a fetch attempt that is retried does not count as a failure.
  // Wait for a shown prompt; an ended session only decides after 10 more seconds without one.
  let outcome = null;
  let endedAt = null;
  for (const end = Date.now() + 60000; Date.now() < end; await sleep(500)) {
    checkAbort();
    outcome = routeOutcome(log.lines);
    if (outcome?.shown) break;
    if (outcome) endedAt ??= Date.now();
    if (endedAt && Date.now() - endedAt > 10000) break;
  }
  step('route', outcome ?? { shown: false });
  if (!outcome) throw new Error('no Gms2FA session showed the prompt or ended within 60 s');
  if (!outcome.shown) throw new Error(`route stopped: ${outcome.end}`);
  const account = new RegExp(EMAIL.split('@')[0].replace('.', '\\.?'), 'i');
  if (locked) {
    // The account name may appear on the lock screen only if the owner allows private notification content there.
    await sleep(2000);
    const screen = p.nodes().map(n => n.text);
    const privateAllowed = p.sh('settings get secure lock_screen_allow_private_notifications').trim() !== '0';
    step('lock screen', { screen, privateAllowed });
    if (!privateAllowed && screen.some(t => account.test(t))) throw new Error('lock screen shows the account name');
    // Unlock like the owner: swipe up, PIN.
    p.sh('input swipe 540 1900 540 600 200');
    await sleep(1000);
    p.sh(`input text ${PIN}; input keyevent KEYCODE_ENTER`);
    await sleep(2000);
    if (p.locked()) throw new Error('PIN unlock failed');
  }
  checkAbort();
  p.sh('cmd statusbar expand-notifications');
  await sleep(1500);
  const note = await p.waitFor(new RegExp(`${account.source}|Google sign-in request`, 'i'), 10000);
  if (!note) throw new Error('notification not found in the shade');
  p.tap(note);
  step('notification tapped');

  // Phone: Google's confirmation page in GmsCore.
  // OCR reads the buttons' icons as varying characters, so match the label's end; the warning text quoting
  // "No, don't allow." ends with a period and does not match.
  const want = deny ? /\bno,? (don.?t allow|it.?s not me)$/i : /\byes,? it.?s me$/i;
  const button = await p.waitFor(want, 30000);
  step('confirmation page', { top: p.top(), button: button?.text });
  if (!button) throw new Error(`confirmation button not found: ${p.nodes().map(n => n.text).join(' | ')}`);
  checkAbort();
  p.tap(button);
  step('answered');

  // Browser: approval moves past "Check your phone"; denial ends on Google's refusal page.
  const promptUrl = page.url().split('?')[0];
  let body;
  for (const end = Date.now() + 30000; Date.now() < end && !report.ok; await sleep(1000)) {
    body = await text(page);
    if (deny) report.ok = /tapped no|stopped|denied|didn.?t allow/i.test(body);
    else report.ok = page.url().split('?')[0] !== promptUrl && !/check your phone/i.test(body);
  }
  step('browser result', { url: page.url().split('?')[0], text: body.slice(0, 200) });
} catch (e) {
  report.ok = false;
  step('error', { message: e?.message });
} finally {
  await finish(aborted ? 130 : undefined);
}
