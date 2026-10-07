/* SPDX-License-Identifier: Apache-2.0 */
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {test} from 'node:test';
import assert from 'node:assert/strict';

const source=readFileSync(new URL('../play-services-core/src/main/assets/cyclon-challenge.js',import.meta.url),'utf8');
function fixture(count=9) {
  const visible={getClientRects:()=>[1]};
  const state={location:{origin:'https://www.google.com',pathname:'/android/uncertified/'},identity:'Google Account: Test (fixture@example.com)',clicks:[],verifies:0};
  const cells=Array.from({length:count},(_,i)=>({...visible,className:'rc-imageselect-tile',selected:false,
    classList:{contains:()=>cells[i].selected},querySelector:()=>({src:`https://www.google.com/image/${i}`}),click:()=>state.clicks.push(i)}));
  const puzzle={...visible,innerText:'Select all images with cars'};
  const verify={...visible,disabled:false,click:()=>state.verifies++};
  const frame={...visible,src:'https://www.google.com/recaptcha/api2/bframe?opaque=fixture',getBoundingClientRect:()=>({left:10,top:100,right:330,bottom:620}),
    contentDocument:{querySelector:s=>s==='#rc-imageselect'?puzzle:s==='#recaptcha-verify-button'?verify:null,querySelectorAll:()=>cells}};
  state.frames=[frame];
  state.document={querySelector:()=>({getAttribute:()=>state.identity}),querySelectorAll:()=>state.frames};
  state.run=(action=null,tiles=[],expected=null)=>runInNewContext(source.replace('__ACTION__',JSON.stringify(action)).replace('__TILES__',JSON.stringify(tiles))
    .replace('__EXPECTED__',JSON.stringify(expected)).replace('__ACCOUNT__',JSON.stringify('fixture@example.com')),{...state,innerWidth:400,innerHeight:800,URL});
  return {...state,state,cells,puzzle,verify,frame};
}
test('observes only a supported visible puzzle without acting',()=>{
  for(const count of [9,16]){const f=fixture(count),s=f.run();assert.equal(s.phase,'challenge');assert.equal(s.tileCount,count);assert.deepEqual(Array.from(s.bounds),[10,100,330,620]);assert.equal(f.state.clicks.length,0);assert.equal(f.state.verifies,0)}
  assert.equal(fixture(12).run(),null);
});
test('requires the exact account, origin and same-origin visible frame',()=>{
  const f=fixture();f.state.identity='Google Account: Other (other@example.com)';assert.equal(f.run(),null);
  f.state.identity='Google Account: Test (fixture@example.com)';f.state.location.origin='https://www.google.com.attacker.test';assert.equal(f.run(),null);
  f.state.location.origin='https://www.google.com';f.frame.src='https://www.google.com.attacker.test/recaptcha/api2/bframe';assert.equal(f.run(),null);
  f.frame.src='https://www.google.com/recaptcha/api2/bframe';f.frame.getClientRects=()=>[];assert.equal(f.run(),null);
});
test('stale revisions and invalid tile choices cannot act',()=>{
  const f=fixture(),snapshot=f.run();f.puzzle.innerText='A different puzzle';assert.equal(f.run('tiles',[1],snapshot.fingerprint).phase,'stale');assert.equal(f.state.clicks.length,0);
  const fresh=f.run();for(const tiles of [[9],[-1],[1,1],[1.5],[]])assert.equal(f.run('tiles',tiles,fresh.fingerprint).phase,'refused');
  f.cells[1].selected=true;const selected=f.run();assert.deepEqual(Array.from(selected.selected),[1]);assert.equal(f.run('tiles',[1],selected.fingerprint).phase,'refused');assert.equal(f.state.clicks.length,0);
});
test('only tile selection or enabled Verify can be dispatched',()=>{
  const f=fixture(),s=f.run();assert.equal(f.run('register',[],s.fingerprint).phase,'refused');assert.equal(f.run('verify',[1],s.fingerprint).phase,'refused');
  assert.equal(f.run('tiles',[0,4,8],s.fingerprint).phase,'acted');assert.deepEqual(f.state.clicks,[0,4,8]);
  f.verify.disabled=true;assert.equal(f.run('verify',[],f.run().fingerprint).phase,'refused');f.verify.disabled=false;
  assert.equal(f.run('verify',[],f.run().fingerprint).phase,'acted');assert.equal(f.state.verifies,1);
});
