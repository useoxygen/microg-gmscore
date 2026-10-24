// End-to-end Google prompt test: Google asks the phone to confirm, the phone approves (or denies) on Google's page.
// Usage: node e2e.mjs SERIAL [--deny]. See README.md.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { openBrowser, text, EMAIL } from './browser.mjs';
import { phone, sleep } from './phone.mjs';

const [serial, ...flags] = process.argv.slice(2);
if (!serial) { console.error('usage: node e2e.mjs SERIAL [--deny]'); process.exit(2); }
const deny = flags.includes('--deny');
// Gms2FA events that end the route before the notification is shown.
const FAILURES = 'pref_off|not_registered|malformed|already_active|account_not_matched|fetch_failed|not_showable|notifications_blocked|dispatch_dropped';

const t0 = Date.now();
const report = { serial, deny, steps: [], ok: false };
const step = (name, data = {}) => {
  report.steps.push({ name, ms: Date.now() - t0, ...data });
  console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s] ${name}`, JSON.stringify(data));
};

const p = phone(serial);
const priorTop = p.top();
const log = p.watchLog();
const { page, close } = await openBrowser();
try {
  // Browser: "Forgot password?" from an unfamiliar browser makes Google verify through the account's phones.
  // A plain sign-in with the password is accepted without a prompt for accounts without 2-Step Verification.
  // The run never sets a new password.
  await page.goto('https://accounts.google.com/signin/v2/identifier?hl=en&flowName=GlifWebSignIn');
  await page.fill('#identifierId', EMAIL);
  await page.keyboard.press('Enter');
  await page.getByText(/forgot password/i).first().click({ timeout: 15000 });
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
  p.sh('cmd statusbar expand-notifications');
  await sleep(1500);
  const note = await p.waitFor(new RegExp(`${EMAIL.split('@')[0].replace('.', '\\.?')}|Google sign-in request`, 'i'), 10000);
  if (!note) throw new Error('notification not found in the shade');
  p.tap(note);
  step('notification tapped');

  // Phone: Google's confirmation page in GmsCore.
  const want = deny ? /^(\W\s)?no,? (don.?t allow|it.?s not me)$/i : /^(\W\s)?yes,? it.?s me$/i;
  const button = await p.waitFor(want, 30000);
  step('confirmation page', { top: p.top(), button: button?.text });
  if (!button) throw new Error(`confirmation button not found: ${p.nodes().map(n => n.text).join(' | ')}`);
  p.tap(button);
  step('answered');

  // Browser: approval moves past "Check your phone"; denial ends on Google's refusal page.
  const promptUrl = page.url().split('?')[0];
  for (const end = Date.now() + 30000; Date.now() < end && !report.ok; await sleep(1000)) {
    body = await text(page);
    if (deny) report.ok = /tapped no|stopped/i.test(body);
    else report.ok = page.url().split('?')[0] !== promptUrl && !/check your phone/i.test(body);
  }
  step('browser result', { url: page.url().split('?')[0], text: body.slice(0, 200) });
} catch (e) {
  step('error', { message: e.message });
} finally {
  await close();
  log.stop();
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
