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
// Gms2FA events that end the route before the notification is shown.
const FAILURES = 'pref_off|not_registered|malformed|already_active|account_not_matched|fetch_failed|not_showable|notifications_blocked|dispatch_dropped';

const t0 = Date.now();
const report = { serial, deny, locked, signin, steps: [], ok: false };
const step = (name, data = {}) => {
  report.steps.push({ name, ms: Date.now() - t0, ...data });
  console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${name}`, JSON.stringify(data));
};

const PIN = '7316';
let pinSet = false;
const p = phone(serial);
if (!(await p.unlock())) { console.error('the phone is locked with a PIN; unlock it first'); process.exit(2); }
const priorTop = p.top();
const log = p.watchLog();
const { page, close } = await openBrowser();
try {
  if (locked) {
    // Without a PIN the keyguard goes away on wake, so the run sets one. The power key locks at once.
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
    await page.locator('input[name=Passwd]').fill(password(), { timeout: 15000 });
    await page.keyboard.press('Enter');
  } else {
    await page.getByText(/forgot password/i).first().click({ timeout: 15000 });
  }
  await page.waitForTimeout(4000);
  if (!/check your phone/i.test(await text(page))) {
    // Google may pick another method first; the prompt is then under "Try another way".
    await page.getByText(/try another way/i).first().click({ timeout: 5000 });
    await page.getByText(/tap yes|get a notification|google prompt/i).first().click({ timeout: 10000 });
  }
  await page.getByText(/check your phone/i).first().waitFor({ timeout: 15000 });
  let body = await text(page);
  step('prompt requested', { url: page.url().split('?')[0], text: body.match(/Google sent[^\n]*/)?.[0] });

  // Phone: the route logs received, fetched and shown for one message.
  for (const ev of ['received', 'fetched', 'shown']) {
    const line = await log.seen(new RegExp(`event=(${ev}|${FAILURES})\\b`), 60000);
    step(`log ${ev}`, { line });
    if (!line) throw new Error(`no ${ev} event`);
    if (!line.includes(`event=${ev}`)) throw new Error(`route stopped: ${line}`);
  }
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
  p.tap(button);
  step('answered');

  // Browser: approval moves past "Check your phone"; denial ends on Google's refusal page.
  const promptUrl = page.url().split('?')[0];
  for (const end = Date.now() + 30000; Date.now() < end && !report.ok; await sleep(1000)) {
    body = await text(page);
    if (deny) report.ok = /tapped no|stopped|denied|didn.?t allow/i.test(body);
    else report.ok = page.url().split('?')[0] !== promptUrl && !/check your phone/i.test(body);
  }
  step('browser result', { url: page.url().split('?')[0], text: body.slice(0, 200) });
} catch (e) {
  step('error', { message: e.message });
} finally {
  await close();
  log.stop();
  if (pinSet) {
    // Unlock first if a failure left the phone locked, then remove the temporary PIN.
    if (p.locked()) { p.sh('input keyevent KEYCODE_WAKEUP; input swipe 540 1900 540 600 200'); await sleep(1000); p.sh(`input text ${PIN}; input keyevent KEYCODE_ENTER`); await sleep(1500); }
    p.sh(`locksettings clear --old ${PIN}`);
  }
  // Put the phone back as the owner left it.
  p.sh('cmd statusbar collapse');
  for (let i = 0; i < 4 && p.top() !== priorTop; i++) { p.sh('input keyevent KEYCODE_BACK'); await sleep(700); }
  step('cleanup', { top: p.top(), priorTop, gms2fa: log.lines.filter(l => l.includes('event=')) });
  const dir = join(import.meta.dirname, 'reports');
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, `report-${Date.now()}.json`), JSON.stringify(report, null, 2));
  console.log(report.ok ? 'PASS' : 'FAIL');
  process.exit(report.ok ? 0 : 1);
}
