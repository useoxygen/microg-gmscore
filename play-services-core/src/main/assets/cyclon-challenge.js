/* SPDX-License-Identifier: Apache-2.0 */
(function(action, tiles, expected, account) {
  'use strict';
  if(location.origin !== 'https://www.google.com' || location.pathname !== '/android/uncertified/')return null;
  const identity=document.querySelector('[aria-label^="Google Account:"]');
  const accountLabel=identity&&identity.getAttribute('aria-label');
  const signedIn=typeof accountLabel==='string'&&/\(([^()]*)\)\s*$/.exec(accountLabel);
  if(!signedIn||signedIn[1]!==account)return null;
  const visible=e=>!!e&&e.getClientRects().length>0;
  for(const frame of document.querySelectorAll('iframe')) {
    try {
      const url=new URL(frame.src);
      if(url.origin!==location.origin||url.pathname!=='/recaptcha/api2/bframe'||!visible(frame))continue;
      const d=frame.contentDocument, puzzle=d?.querySelector('#rc-imageselect');
      if(!visible(puzzle))continue;
      const cells=Array.from(d.querySelectorAll('.rc-imageselect-tile'));
      const verify=d.querySelector('#recaptcha-verify-button');
      if(![9,16].includes(cells.length)||!visible(verify))return null;
      const selected=cells.flatMap((c,i)=>c.classList.contains('rc-imageselect-tileselected')?[i]:[]);
      const bounds=frame.getBoundingClientRect();
      // Fingerprint stays in native code. No frame URL, image URL or puzzle text is exported.
      const fingerprint=JSON.stringify([puzzle.innerText,cells.map(c=>[c.className,c.querySelector('img')?.src]),
        [bounds.left,bounds.top,bounds.right,bounds.bottom],verify.disabled]);
      if(action) {
        if(fingerprint!==expected)return {phase:'stale'};
        if(action==='tiles'&&Array.isArray(tiles)&&tiles.length>0&&tiles.length<=16&&
            new Set(tiles).size===tiles.length&&tiles.every(i=>Number.isInteger(i)&&i>=0&&i<cells.length&&!selected.includes(i))) {
          for(const i of tiles)cells[i].click();
          return {phase:'acted'};
        }
        if(action==='verify'&&tiles.length===0&&!verify.disabled){verify.click();return {phase:'acted'};}
        return {phase:'refused'};
      }
      return {phase:'challenge',fingerprint,tileCount:cells.length,selected,
        bounds:[bounds.left,bounds.top,bounds.right,bounds.bottom],viewport:[innerWidth,innerHeight]};
    }catch(_){}
  }
  return null;
})(__ACTION__, __TILES__, __EXPECTED__, __ACCOUNT__);
