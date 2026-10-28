// One account-recovery prompt for the test account, left unanswered, then Google's "Resend it" at the given offsets
// (seconds). Prints the page around each resend and the ids of the phone's posted prompt notifications over time.
// Usage: node resend.mjs SERIAL END_SECONDS RESEND_AT...   e.g. node resend.mjs X 230 40 100
// Read the phone's Gms2FA events afterwards (logcat, or the journal: see README).
import { openBrowser, text, EMAIL } from './browser.mjs';
import { execFileSync } from 'node:child_process';
const [S, endS, ...resends] = process.argv.slice(2);
const sh = c => execFileSync('adb', ['-s', S, 'shell', c]).toString();
const notes = () => sh('dumpsys notification | grep -E "NotificationRecord.*two_step_verification" || true')
  .split('\n').filter(Boolean).map(l => (l.match(/id=(\d+)/) || [])[1]).join(',');
const ts = () => new Date().toLocaleTimeString('en-US', { hour12: false, timeZone: 'America/Los_Angeles' });
const { page, close } = await openBrowser({ port: 9343 });
try {
  await page.goto('https://accounts.google.com/signin/v2/identifier?hl=en&flowName=GlifWebSignIn');
  await page.fill('#identifierId', EMAIL); await page.keyboard.press('Enter');
  await page.waitForTimeout(5000);
  await page.getByText(/forgot password/i).first().click({ timeout: 10000 });
  await page.waitForTimeout(4000);
  let body = await text(page);
  console.log(ts(), 'PAGE', page.url().split('?')[0], JSON.stringify(body.slice(0, 500)));
  if (!/check your phone/i.test(body)) throw new Error('no check-your-phone page');
  const t0 = Date.now();
  const pending = resends.map(Number);
  let last = '';
  while (Date.now() - t0 < endS * 1000) {
    const el = (Date.now() - t0) / 1000;
    const n = notes();
    if (n !== last) { console.log(ts(), `+${el.toFixed(0)}s notifications ids=[${n}]`); last = n; }
    if (pending.length && el >= pending[0]) {
      pending.shift();
      const btn = page.getByRole('button', { name: /resend it/i }).or(page.getByRole('link', { name: /resend it/i })).first();
      let clicked = false;
      try { await btn.click({ timeout: 4000 }); clicked = true; } catch (e) {
        clicked = await page.evaluate(() => { const e = [...document.querySelectorAll('button, a, [role=button], [role=link]')].find(e => /resend it/i.test(e.innerText || '')); if (e) { e.click(); return true; } return false; });
      }
      console.log(ts(), `+${el.toFixed(0)}s RESEND clicked=${clicked}`);
      await page.waitForTimeout(3000);
      body = await text(page);
      console.log(ts(), 'PAGE', page.url().split('?')[0], JSON.stringify(body.slice(0, 500)));
    }
    await page.waitForTimeout(2000);
  }
  body = await text(page);
  console.log(ts(), 'PAGE END', page.url().split('?')[0], JSON.stringify(body.slice(0, 400)));
} finally { await close(); }
