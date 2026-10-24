// Posts one prompt, leaves it unanswered, and reports the phone's Gms2FA events and whether the notification is still up.
// Usage: node observe.mjs SERIAL SECONDS...   e.g. node observe.mjs X 5 30 90 (checks after 5, 30, 90 s; minutes with m suffix)
import { openBrowser, text, EMAIL } from './browser.mjs';
import { execFileSync, spawn } from 'node:child_process';
const [S, ...checks] = process.argv.slice(2);
const sh = c => execFileSync('adb', ['-s', S, 'shell', c]).toString();
const up = () => sh('dumpsys notification --noredact | sed -n "/Notification List:/,/Notification attention state:/p" | grep -c "tag=two_step_verification" || true').trim();
const lines = [];
const log = spawn('adb', ['-s', S, 'logcat', '-T', '1', '-v', 'time', '-s', 'Gms2FA:I']);
log.stdout.on('data', d => lines.push(...d.toString().split('\n').filter(l => l.includes('event=') && !l.includes('readiness'))));
const { page, close } = await openBrowser(9336);
let t0;
try {
  await page.goto('https://accounts.google.com/signin/v2/identifier?hl=en&flowName=GlifWebSignIn');
  await page.fill('#identifierId', EMAIL); await page.keyboard.press('Enter');
  await page.getByText(/forgot password/i).first().click({ timeout: 15000 });
  await page.waitForTimeout(4000);
  const body = await text(page);
  if (/too many failed attempts/i.test(body)) { console.log('RATE-LIMITED'); process.exit(3); }
  await page.getByText(/check your phone/i).first().waitFor({ timeout: 15000 });
  t0 = Date.now();
  console.log(new Date().toLocaleTimeString(), 'prompt requested:', body.match(/Google sent[^\n]*/)?.[0]);
} finally { await close(); }
for (const c of checks) {
  const ms = c.endsWith('m') ? parseFloat(c) * 60000 : parseFloat(c) * 1000;
  await new Promise(r => setTimeout(r, Math.max(0, t0 + ms - Date.now())));
  console.log(`+${c}: notification up=${up()}`);
}
log.kill();
console.log(lines.map(l => '  ' + l.slice(19)).join('\n'));
