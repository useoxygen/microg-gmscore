// Phone side of the Google prompt test: logcat watch, notification lookup, UI taps over adb.
import { execFileSync, spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

const SHOT = join(import.meta.dirname, 'phone-shot.png');

export function phone(serial) {
  const adb = (...args) => execFileSync('adb', ['-s', serial, ...args], { maxBuffer: 64 << 20 }).toString();
  const sh = cmd => adb('shell', cmd);

  // Collects Gms2FA lines from now on; `seen(re)` waits for a matching line.
  function watchLog() {
    const lines = [];
    const proc = spawn('adb', ['-s', serial, 'logcat', '-T', '1', '-v', 'time', '-s', 'Gms2FA:*', 'GmsAuthZen:*']);
    proc.stdout.on('data', d => lines.push(...d.toString().split('\n').filter(Boolean)));
    const seen = async (re, ms) => {
      for (const end = Date.now() + ms; Date.now() < end; await sleep(500)) {
        const hit = lines.find(l => re.test(l));
        if (hit) return hit;
      }
      return null;
    };
    return { lines, seen, stop: () => proc.kill() };
  }

  // The foreground activity and its task ("component t123"), so a second instance of the same screen is told apart.
  const top = () => {
    const m = sh('dumpsys activity activities | grep -m1 topResumedActivity || true').match(/u0 (\S+) (t\d+)/);
    return m ? `${m[1]} ${m[2]}` : undefined;
  };
  const locked = () => /isKeyguardShowing=true/.test(sh('dumpsys window | grep -m1 isKeyguardShowing || true'));
  // Wakes the phone and dismisses a keyguard without a PIN; a phone with a PIN stays locked.
  async function unlock() {
    sh('input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard');
    await sleep(1500);
    return !locked();
  }

  // On-screen text from a screenshot via macOS Vision OCR; Cyclon phones block uiautomator dumps.
  function nodes() {
    const png = execFileSync('adb', ['-s', serial, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 });
    writeFileSync(SHOT, png);
    return execFileSync(join(import.meta.dirname, 'ocr'), [SHOT]).toString().split('\n').filter(Boolean).map(l => {
      const [x, y, ...t] = l.split(' ');
      return { text: t.join(' '), cx: +x, cy: +y };
    });
  }
  const find = re => nodes().find(n => re.test(n.text));
  const tap = n => sh(`input tap ${n.cx} ${n.cy}`);
  async function waitFor(re, ms) {
    for (const end = Date.now() + ms; Date.now() < end; await sleep(1000)) {
      const n = find(re);
      if (n) return n;
    }
    return null;
  }
  return { sh, watchLog, top, locked, unlock, nodes, tap, waitFor };
}

export const sleep = ms => new Promise(r => setTimeout(r, ms));
