/* SPDX-License-Identifier: Apache-2.0 */
(function(action, tiles, expected, account) {
  'use strict';
  if(location.origin !== 'https://www.google.com' || location.pathname !== '/android/uncertified/')return null;
  const identity=document.querySelector('[aria-label^="Google Account:"]');
  const accountLabel=identity&&identity.getAttribute('aria-label');
  const signedIn=typeof accountLabel==='string'&&/\(([^()]*)\)(?:,[^()]*)?\s*$/.exec(accountLabel);
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
      // Clicked tiles in replacement puzzles fade out and load a new image; wait until every tile has settled
      // so the model never judges a half-swapped grid and the action is not refused as stale.
      // New photos also fade in after loading, and a new grid fades in as a whole: wait for those transitions too.
      const view=d.defaultView, target=d.querySelector('#rc-imageselect-target');
      const animating=e=>!!e?.getAnimations&&e.getAnimations({subtree:true}).some(a=>a.playState==='running');
      const faded=e=>{for(let n=e;n&&n!==d.body;n=n.parentElement){if(view?.getComputedStyle&&parseFloat(view.getComputedStyle(n).opacity)<0.99)return true}return false};
      if(!action&&(animating(target)||cells.some(c=>c.classList.contains('rc-imageselect-dynamic-selected')||
          Array.from(c.querySelectorAll('img')).some(img=>!img.complete||img.naturalWidth===0||faded(img)))))return {phase:'settling'};
      const selected=cells.flatMap((c,i)=>c.classList.contains('rc-imageselect-tileselected')?[i]:[]);
      const bounds=frame.getBoundingClientRect();
      // Fingerprint stays in native code. No frame URL, image URL or puzzle text is exported.
      // Only what defines the puzzle: its instruction, each tile's image and state, and Verify. Google's transient
      // error text and the frame's position change on their own and must not make a pending action stale.
      const instruction=(d.querySelector('.rc-imageselect-instructions')||puzzle).innerText;
      const fingerprint=JSON.stringify([instruction,cells.map(c=>[c.className,c.querySelector('img')?.src]),verify.disabled]);
      if(action) {
        if(fingerprint!==expected)return {phase:'stale'};
        if(action==='tiles'&&Array.isArray(tiles)&&tiles.length>0&&tiles.length<=16&&
            new Set(tiles).size===tiles.length&&tiles.every(i=>Number.isInteger(i)&&i>=0&&i<cells.length&&!selected.includes(i))) {
          for(const i of tiles)cells[i].click();
          return {phase:'acted'};
        }
        if(action==='verify'&&tiles.length===0&&!verify.disabled){verify.click();return {phase:'acted'};}
        // A different puzzle, as a person would request for an unreadable one. It never submits anything.
        const reload=d.querySelector('#recaptcha-reload-button');
        if(action==='reload'&&tiles.length===0&&visible(reload)&&!reload.classList.contains('rc-button-disabled')){reload.click();return {phase:'acted'};}
        return {phase:'refused'};
      }
      // Tile rectangles in page coordinates let native code print each index on the captured image.
      const tileRects=cells.map(c=>{const r=c.getBoundingClientRect();return [bounds.left+r.left,bounds.top+r.top,r.width,r.height]});
      return {phase:'challenge',fingerprint,tileCount:cells.length,selected,tileRects,
        bounds:[bounds.left,bounds.top,bounds.right,bounds.bottom],viewport:[innerWidth,innerHeight]};
    }catch(_){}
  }
  return null;
})(__ACTION__, __TILES__, __EXPECTED__, __ACCOUNT__);
