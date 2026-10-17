/* SPDX-License-Identifier: Apache-2.0 */
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import {test} from 'node:test';
import assert from 'node:assert/strict';

const script = readFileSync(new URL('../play-services-core/src/main/assets/cyclon-enrollment.js', import.meta.url), 'utf8');
const id = '8123456789012345678';
const account = 'fixture@example.com';
function fixture() {
  class Input {
    constructor() { this._value = ''; this.events = []; this.maxLength = 19; this.scrolls = 0; }
    get value() { return this._value; } set value(v) { this._value = v; }
    getAttribute() { return '\\d*'; }
    getClientRects() { return [1]; }
    dispatchEvent(e) { this.events.push(e.type); }
    scrollIntoView() { this.scrolls++; }
  }
  const input = new Input(), response = {value:''};
  const visible = text => ({innerText:text, getClientRects:()=>[1]});
  const state = {input, response, messages:[], ids:[], clicks:0, frames:[], window:{}, now:1e12,
    location:{origin:'https://www.google.com',pathname:'/android/uncertified/'},
    label:'Google Account: Fixture (' + account + ')'};
  state.button = {...visible('Register'), type:'submit', getAttribute:()=>null, disabled:false, click:()=>state.clicks++};
  state.document = {
    querySelector: selector => selector.includes('aria-label') ? {getAttribute:()=>state.label} : response,
    querySelectorAll: selector => selector === 'input' ? [input] : selector === 'iframe' ? state.frames :
      selector === 'button' ? [state.button] : selector.includes('button') ? [] : selector.includes('listItem') ? state.ids.map(visible) : state.messages.map(visible)
  };
  state.run = (submit=false, suppliedId=id, rechecks=0) => runInNewContext(script.replace('__CYCLON_ID__',JSON.stringify(suppliedId))
    .replace('__CYCLON_ACCOUNT__',JSON.stringify(account)).replace('__CYCLON_SUBMIT__',String(submit))
    .replace('__CYCLON_RECHECKS__',String(rechecks)),
    {...state, HTMLInputElement:Input, Event:class {constructor(type){this.type=type}}, URL, Date:{now:()=>state.now}});
  return state;
}
test('preserves 64-bit decimal ID and fills without submitting before verification',()=>{
  const f=fixture(); assert.equal(f.run(),'verification'); assert.equal(f.input.value,id);
  assert.equal(f.input.scrolls,1); f.run(); assert.equal(f.input.scrolls,1);
  assert.equal(f.input.readOnly,true); assert.equal(f.clicks,0); assert.deepEqual(f.input.events,['input','change']);
});
test('explicit admission submits once; repeated polls cannot register twice',()=>{
  const f=fixture(); f.response.value='opaque token stays in page';
  assert.equal(f.button.getAttribute('type'),null); // The live Google button has no attribute.
  assert.equal(f.run(),'ready'); assert.equal(f.clicks,0);
  assert.equal(f.run(true),'submitting'); f.run(true); assert.equal(f.clicks,1);
});
test('only the actual ID in the registered list is acceptance',()=>{
  const f=fixture(); f.messages=['Device registered.']; assert.notEqual(f.run(),'accepted');
  f.ids=['7123456789012345678']; assert.notEqual(f.run(),'accepted');
  f.ids=[id]; assert.equal(f.run(),'accepted'); assert.equal(f.clicks,0);
});
test('saved IDs can omit input padding without losing any 64-bit precision',()=>{
  const f=fixture(), padded='0812345678901234567';
  f.ids=['812345678901234568']; assert.notEqual(f.run(false,padded),'accepted');
  f.ids=['8.12345678901234567e17']; assert.notEqual(f.run(false,padded),'accepted');
  f.ids=['812345678901234567']; assert.equal(f.run(false,padded),'accepted');
});
test('different account, lookalike origin and invalid IDs cannot write',()=>{
  const f=fixture(); f.response.value='opaque';
  for (const label of ['Google Account: Other (other@example.com)',
    'Google Account: Name (fixture@example.com) (other@example.com)',
    'Google Account: Name (fixture@example.com) extra text',
    'Google Account: Name (fixture@example.com), alert (other@example.com)']) {
    f.label=label; assert.equal(f.run(true),'account_mismatch');
    assert.equal(f.input.value,''); assert.equal(f.clicks,0);
  }
  f.location.origin='https://www.google.com.attacker.example'; assert.equal(f.run(true),'outside');
  f.location.origin='https://www.google.com'; assert.equal(f.run(true,'0'.repeat(19)),'invalid_id'); assert.equal(f.clicks,0); assert.equal(f.input.scrolls,0);
});
test('checkbox is attempted once and failures stay interactive',()=>{
  const f=fixture(); let clicked=0;
  f.frames=[{src:'https://www.google.com/recaptcha/api2/anchor',dataset:{},contentDocument:{
    querySelector:()=>({getAttribute:()=> 'false',classList:{contains:()=>false},click:()=>clicked++})}}];
  f.run(); f.run(false,id,3); assert.equal(clicked,1); assert.equal(f.clicks,0);
});
function expiring(f) {
  const anchor={src:'https://www.google.com/recaptcha/api2/anchor',dataset:{cyclonAttempted:'true'},scrolls:0,clicks:0,expired:true,
    ignoreClicks:false,getClientRects:()=>[1],scrollIntoView(){anchor.scrolls++}};
  anchor.contentDocument={querySelector:()=>({getAttribute:()=>'false',classList:{contains:c=>c==='recaptcha-checkbox-expired'&&anchor.expired},
    click:()=>{anchor.clicks++;if(!anchor.ignoreClicks)anchor.expired=false}})};
  // Google hides an expired challenge with visibility and opacity on an ancestor; its frame keeps a layout box.
  const container={parentElement:null};
  const page={defaultView:{getComputedStyle:n=>n===container&&anchor.expired?{visibility:'hidden',opacity:'0'}:{visibility:'visible',opacity:'1'}}};
  const puzzle={getClientRects:()=>[1]};
  const bframe={src:'https://www.google.com/recaptcha/api2/bframe',dataset:{},getClientRects:()=>[1],ownerDocument:page,
    parentElement:container,contentDocument:{querySelector:()=>puzzle}};
  f.frames=[anchor,bframe];
  return {anchor,bframe};
}
test('a hidden expired challenge is not reported as a visible puzzle',()=>{
  const f=fixture(), {anchor}=expiring(f);
  assert.equal(f.run(false,id,0),'expired'); assert.equal(anchor.clicks,0);
});
test('expired checkbox is re-checked within the native allowance only',()=>{
  const f=fixture(), {anchor}=expiring(f);
  assert.equal(f.run(false,id,2),'challenge'); assert.equal(anchor.clicks,1); assert.equal(anchor.scrolls,1);
  anchor.expired=true; assert.equal(f.run(false,id,2),'verification'); assert.equal(anchor.clicks,1); // Google may still answer
  anchor.expired=false; f.run(false,id,2); anchor.expired=true;
  assert.equal(f.run(false,id,2),'challenge'); assert.equal(anchor.clicks,2);
  anchor.expired=false; f.run(false,id,2); anchor.expired=true;
  assert.equal(f.run(false,id,2),'expired'); assert.equal(anchor.clicks,2);
  assert.equal(f.run(false,id,3),'challenge'); assert.equal(anchor.clicks,3); assert.equal(f.clicks,0);
});
test('a re-check Google ignores is retried after the wait, then reported expired',()=>{
  const f=fixture(), {anchor}=expiring(f); anchor.ignoreClicks=true;
  assert.equal(f.run(false,id,2),'verification'); assert.equal(anchor.clicks,1);
  f.now+=9000; assert.equal(f.run(false,id,2),'verification'); assert.equal(anchor.clicks,1);
  f.now+=1000; assert.equal(f.run(false,id,2),'verification'); assert.equal(anchor.clicks,2);
  f.now+=10000; assert.equal(f.run(false,id,2),'expired'); assert.equal(anchor.clicks,2);
  assert.equal(f.run(false,id,3),'verification'); assert.equal(anchor.clicks,3);
});
test('Google rejection is distinct from CAPTCHA completion',()=>{
  const f=fixture(); f.response.value='opaque'; f.messages=['Uh oh. Something went wrong. Please try again.'];
  assert.equal(f.run(true),'rejected'); assert.equal(f.clicks,0);
});
test('accepts the intended account when Google appends an account alert',()=>{
  const f=fixture(); f.label='Google Account: Cora Liss  \n(' + account + '), Important account alert';
  assert.equal(f.run(),'verification');
});
