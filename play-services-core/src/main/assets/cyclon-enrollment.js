/* SPDX-License-Identifier: Apache-2.0 */
// Only the Google-owned registration page is eligible for these bounded actions.
// No credentials, CAPTCHA response tokens or page text leave the WebView.
(function (id, account, submit, rechecks) {
  'use strict';
  if (location.origin !== 'https://www.google.com' ||
      location.pathname !== '/android/uncertified/') return 'outside';
  if (!/^[0-9]{19}$/.test(id) || /^0+$/.test(id)) return 'invalid_id';
  const visible = e => !!e && e.getClientRects().length > 0;
  // reCAPTCHA hides an expired challenge with visibility and opacity but keeps its layout box.
  const shown = e => visible(e) && (typeof e.checkVisibility !== 'function' ||
    e.checkVisibility({visibilityProperty: true, opacityProperty: true}));
  const accountButton = document.querySelector('[aria-label^="Google Account:"]');
  const accountLabel = accountButton && accountButton.getAttribute('aria-label');
  // The last parenthesized field is Google's email; a display name can contain parentheses.
  // Google may append a comma-separated status, e.g. ", Important account alert" after a new sign-in.
  const signedIn = typeof accountLabel === 'string' && /\(([^()]*)\)(?:,[^()]*)?\s*$/.exec(accountLabel);
  if (!signedIn || signedIn[1] !== account)
    return 'account_mismatch';
  // Google's input needs 19 digits, but its saved list may omit padding zeros.
  // Compare decimal strings without converting either 64-bit value to Number.
  const decimal = s => /^[0-9]{1,19}$/.test(s) ? s.replace(/^0+/, '') : null;
  // Google's page lists the exact IDs belonging to its currently signed-in account.
  if (Array.from(document.querySelectorAll('li[listItem]')).some(e => visible(e) && decimal(e.innerText.trim()) === decimal(id)))
    return 'accepted';
  const messages = Array.from(document.querySelectorAll('[role="alert"], [role="status"], [aria-live]'))
    .filter(visible).map(e => e.innerText.trim());
  // Exact messages verified in Google's English registration UI on 2026-10-06.
  // A success toast alone can belong to another ID. Require the ID list above.
  if (messages.some(t => t === 'Registering...')) return 'submitting';
  if (messages.some(t => /^(Uh oh\.|Error occurred|The reCAPTCHA was not solved|Value must|Value is the wrong)/.test(t)))
    return 'rejected';
  const inputs = Array.from(document.querySelectorAll('input'))
    .filter(e => visible(e) && e.maxLength === 19 && e.getAttribute('pattern') === '\\d*');
  if (inputs.length !== 1) return 'loading';
  const input = inputs[0];
  input.readOnly = true;
  if (input.value !== id) {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, id);
    input.dispatchEvent(new Event('input', {bubbles: true}));
    input.dispatchEvent(new Event('change', {bubbles: true}));
  }
  // Google can place the form inside a scrolling page below its overview. Bring
  // the bound input into view before activating its verification checkbox.
  if (!window.__cyclonEnrollmentFormVisible) {
    input.scrollIntoView({block: 'center', inline: 'nearest'});
    window.__cyclonEnrollmentFormVisible = true;
  }
  // Google owns the challenge. Try the checkbox once; image challenges stay in place.
  // An unsolved challenge expires after about two minutes and Google asks to check the
  // box again. Native code allows a bounded number of those re-checks per page.
  let challenge = false, expired = false;
  for (const frame of document.querySelectorAll('iframe')) {
    try {
      const u = new URL(frame.src || 'about:blank');
      if (u.origin !== location.origin || !u.pathname.startsWith('/recaptcha/api2/')) continue;
      const d = frame.contentDocument;
      if (u.pathname.endsWith('/bframe') && shown(frame) &&
          d && shown(d.querySelector('#rc-imageselect, #rc-audio'))) challenge = true;
      const checkbox = d && d.querySelector('[role="checkbox"]');
      if (!u.pathname.endsWith('/anchor') || !checkbox || checkbox.getAttribute('aria-checked') === 'true') continue;
      if (!frame.dataset.cyclonAttempted) {
        frame.dataset.cyclonAttempted = 'true';
        checkbox.click();
      } else if (!checkbox.classList.contains('recaptcha-checkbox-expired')) {
        delete frame.dataset.cyclonRecheck;
      } else if (!frame.dataset.cyclonRecheck) {
        const used = window.__cyclonEnrollmentRechecks || 0;
        if (used < rechecks) {
          window.__cyclonEnrollmentRechecks = used + 1;
          frame.dataset.cyclonRecheck = 'true';
          frame.scrollIntoView({block: 'center', inline: 'nearest'});
          checkbox.click();
        } else expired = true;
      }
    } catch (_) { /* cross-origin challenges remain interactive */ }
  }
  const response = document.querySelector('textarea[name="g-recaptcha-response"]');
  const verified = !!response && !!response.value;
  if (!verified) return challenge ? 'challenge' : expired ? 'expired' : 'verification';
  // Google's button uses the default submit type without a literal type attribute.
  const buttons = Array.from(document.querySelectorAll('button'))
    .filter(e => visible(e) && e.type === 'submit' && e.innerText.trim() === 'Register' && !e.disabled);
  if (buttons.length !== 1) return 'loading';
  if (submit && !window.__cyclonEnrollmentSubmitted) {
    // Native code persists submission admission before calling this branch.
    window.__cyclonEnrollmentSubmitted = true;
    buttons[0].click();
    return 'submitting';
  }
  return 'ready';
})(__CYCLON_ID__, __CYCLON_ACCOUNT__, __CYCLON_SUBMIT__, __CYCLON_RECHECKS__);
