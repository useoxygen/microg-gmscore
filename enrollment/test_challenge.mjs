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
    fading:false,loaded:true,
    classList:{contains:c=>c==='rc-imageselect-dynamic-selected'?cells[i].fading:cells[i].selected},
    querySelector:()=>({src:`https://www.google.com/image/${i}`}),querySelectorAll:()=>[{complete:cells[i].loaded,naturalWidth:cells[i].loaded?100:0}],
    click:()=>state.clicks.push(i),
    getBoundingClientRect:()=>{const side=Math.sqrt(count),w=300/side;return {left:10+(i%side)*w,top:120+Math.floor(i/side)*w,width:w,height:w}}}));
  const puzzle={...visible,innerText:'Select all images with cars'};
  const verify={...visible,disabled:false,click:()=>state.verifies++};
  const frame={...visible,src:'https://www.google.com/recaptcha/api2/bframe?opaque=fixture',getBoundingClientRect:()=>({left:10,top:100,right:330,bottom:620}),
    contentDocument:{querySelector:s=>s==='#rc-imageselect'?puzzle:s==='#recaptcha-verify-button'?verify:s==='.rc-imageselect-instructions'?state.instructions:s==='#recaptcha-reload-button'?state.reload:null,querySelectorAll:()=>cells}};
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
  const f=fixture(),snapshot=f.run();
  for(const label of ['Google Account: Other (other@example.com)',
    'Google Account: Name (fixture@example.com) (other@example.com)',
    'Google Account: Name (fixture@example.com) extra text',
    'Google Account: Name (fixture@example.com), alert (other@example.com)']) {
    f.state.identity=label;assert.equal(f.run(),null);
    assert.equal(f.run('tiles',[0],snapshot.fingerprint),null);assert.equal(f.state.clicks.length,0);
  }
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
test('observes the puzzle when Google appends an account alert',()=>{
  const f=fixture();f.state.identity='Google Account: Test  \n(fixture@example.com), Important account alert';
  assert.equal(f.run().phase,'challenge');
});
test('reports each tile rectangle in page coordinates for index labels',()=>{
  const s=fixture(16).run();assert.equal(s.tileRects.length,16);
  assert.deepEqual(Array.from(s.tileRects[0]),[20,220,75,75]);assert.deepEqual(Array.from(s.tileRects[5]),[95,295,75,75]);
});
test('waits while replacement tiles fade or load, but still lets a pending action through',()=>{
  const f=fixture(),snapshot=f.run();assert.equal(snapshot.phase,'challenge');
  f.cells[2].fading=true;assert.equal(f.run().phase,'settling');
  f.cells[2].fading=false;f.cells[5].loaded=false;assert.equal(f.run().phase,'settling');
  f.cells[5].loaded=true;assert.equal(f.run().phase,'challenge');assert.equal(f.state.clicks.length,0);
});
test('Google\'s transient error text and frame movement do not make a pending action stale',()=>{
  const f=fixture();f.state.instructions={innerText:'Select all images with cars'};
  f.puzzle.innerText='Select all images with cars';const snapshot=f.run();
  f.puzzle.innerText='Select all images with cars Please try again.';
  f.frame.getBoundingClientRect=()=>({left:10,top:140,right:330,bottom:660});
  assert.equal(f.run('tiles',[1],snapshot.fingerprint).phase,'acted');assert.deepEqual(f.state.clicks,[1]);
  f.state.instructions={innerText:'Select all images with bicycles'};
  assert.equal(f.run('tiles',[2],snapshot.fingerprint).phase,'stale');assert.deepEqual(f.state.clicks,[1]);
});
test('waits while a replacement photo or the whole grid is still fading in',()=>{
  const f=fixture();let opacity='1',running=false;
  const img={complete:true,naturalWidth:100,parentElement:null};
  f.cells.forEach(c=>{c.querySelectorAll=()=>[img]});
  const target={getAnimations:()=>running?[{playState:'running'}]:[]};
  const d=f.frame.contentDocument,query=d.querySelector;
  d.querySelector=s=>s==='#rc-imageselect-target'?target:query(s);
  d.defaultView={getComputedStyle:()=>({opacity})};d.body={};
  assert.equal(f.run().phase,'challenge');
  opacity='0.4';assert.equal(f.run().phase,'settling');opacity='1';
  running=true;assert.equal(f.run().phase,'settling');running=false;
  assert.equal(f.run().phase,'challenge');
});
test('reload asks for a different puzzle only through the visible enabled reload button',()=>{
  const f=fixture();let reloads=0;
  f.state.reload={getClientRects:()=>[1],classList:{contains:()=>false},click:()=>reloads++};
  const snapshot=f.run();
  assert.equal(f.run('reload',[1],snapshot.fingerprint).phase,'refused');assert.equal(reloads,0);
  assert.equal(f.run('reload',[],snapshot.fingerprint).phase,'acted');assert.equal(reloads,1);
  f.state.reload.classList={contains:c=>c==='rc-button-disabled'};
  assert.equal(f.run('reload',[],snapshot.fingerprint).phase,'refused');assert.equal(reloads,1);
  assert.equal(f.state.clicks.length,0);assert.equal(f.state.verifies,0);
});
