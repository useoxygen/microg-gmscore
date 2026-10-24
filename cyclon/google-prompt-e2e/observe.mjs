// Posts one prompt, leaves it unanswered, and reports whether this run's notification is still up at each check,
// then the run's Gms2FA events. Usage: node observe.mjs SERIAL CHECK...   (seconds, or minutes with an m suffix)
// e.g. node observe.mjs SERIAL 5 30 90 2.5m 4m
import { openBrowser, text, EMAIL } from './browser.mjs';
import { execFileSync, spawn } from 'node:child_process';

const [S, ...checks] = process.argv.slice(2);
if (!S || checks.length === 0) { console.error('usage: node observe.mjs SERIAL CHECK...'); process.exit(2); }
const sh = c => execFileSync('adb', ['-s', S, 'shell', c]).toString();
// The prompt notifications currently posted (the active list only, not the history), by their NotificationRecord
// object: every post makes a new one, even when GmsCore reuses a numeric id after a restart.
const promptIds = () => new Set([...sh('dumpsys notification --noredact | sed -n "/Notification List:/,/Notification attention state:/p"')
  .matchAll(/NotificationRecord\((0x[0-9a-f]+): pkg=com\.google\.android\.gms [^\n]*tag=two_step_verification/g)].map(m => m[1]));

const before = promptIds();
const lines = [];
const log = spawn('adb', ['-s', S, 'logcat', '-T', '1', '-v', 'time', '-s', 'Gms2FA:I']);
log.stdout.on('data', d => lines.push(...d.toString().split('\n').filter(l => l.includes('event=') && !l.includes('readiness'))));
const done = code => { log.kill(); console.log(lines.map(l => '  ' + l.slice(19)).join('\n')); process.exit(code); };

const { page, close } = await openBrowser(9336);
let t0;
try {
  await page.goto('https://accounts.google.com/signin/v2/identifier?hl=en&flowName=GlifWebSignIn');
  await page.fill('#identifierId', EMAIL);
  await page.keyboard.press('Enter');
  await page.getByText(/forgot password/i).first().click({ timeout: 15000 });
  await page.waitForTimeout(4000);
  const body = await text(page);
  if (/too many failed attempts/i.test(body)) { console.log('RATE-LIMITED'); await close(); done(3); }
  await page.getByText(/check your phone/i).first().waitFor({ timeout: 15000 });
  t0 = Date.now();
  console.log(new Date().toLocaleTimeString(), 'prompt requested:', body.match(/Google sent[^\n]*/)?.[0]);
} finally {
  await close().catch(() => {});
}

// This run's notification: one that was not posted before the prompt was requested.
let mine = [];
for (const end = Date.now() + 60000; Date.now() < end && mine.length === 0; await new Promise(r => setTimeout(r, 1000))) {
  mine = [...promptIds()].filter(id => !before.has(id));
}
console.log(`this run's notification: ${mine.length ? mine.join(', ') : 'none posted within 60 s'}${before.size ? ` (ignoring older ${[...before].join(', ')})` : ''}`);
if (mine.length === 0) done(1);
for (const c of checks) {
  const ms = c.endsWith('m') ? parseFloat(c) * 60000 : parseFloat(c) * 1000;
  await new Promise(r => setTimeout(r, Math.max(0, t0 + ms - Date.now())));
  const now = promptIds();
  console.log(`+${c}: up=${mine.some(id => now.has(id)) ? 1 : 0}`);
}
done(0);
