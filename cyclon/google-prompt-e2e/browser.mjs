// Drives a real Chrome over CDP with a throwaway profile, so Google sees an unfamiliar, unflagged browser.
import { chromium } from 'playwright-core';
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
export const EMAIL = process.env.GPROMPT_EMAIL || 'coraliss.mail@gmail.com';

export async function openBrowser(port = 9333) {
  const dir = mkdtempSync(join(tmpdir(), 'gprompt-profile-'));
  const proc = spawn(CHROME, [
    `--user-data-dir=${dir}`, `--remote-debugging-port=${port}`,
    '--no-first-run', '--no-default-browser-check', '--window-size=1100,900', 'about:blank',
  ], { stdio: 'ignore' });
  let browser;
  for (let i = 0; i < 50 && !browser; i++) {
    await new Promise(r => setTimeout(r, 200));
    browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`).catch(() => null);
  }
  if (!browser) { proc.kill(); throw new Error('Chrome did not open its debugging port'); }
  const page = browser.contexts()[0].pages()[0];
  const close = async () => {
    await browser.close().catch(() => {});
    proc.kill();
    await new Promise(r => setTimeout(r, 1000));
    rmSync(dir, { recursive: true, force: true, maxRetries: 5 });
  };
  return { page, close };
}

export async function text(page) {
  return (await page.locator('body').innerText().catch(() => '')).replace(/\n{2,}/g, '\n').slice(0, 1500);
}
