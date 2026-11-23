import test from 'node:test';
import assert from 'node:assert/strict';
import {acceptResultCounter} from '../backstage.mjs';

test('results from parallel calls may arrive out of order but never twice',()=>{
  const link={lastResultCounter:'10',recentResultCounters:[]};
  assert.equal(acceptResultCounter(link,'12'),true);
  assert.equal(acceptResultCounter(link,'11'),true,'an earlier result arriving late is accepted');
  assert.equal(acceptResultCounter(link,'11'),false,'the same result is never accepted twice');
  assert.equal(acceptResultCounter(link,'12'),false);
  assert.equal(link.lastResultCounter,'12');
  assert.equal(acceptResultCounter(link,'13'),true);
});
test('results older than the window are refused',()=>{
  const link={lastResultCounter:'5000',recentResultCounters:[]};
  assert.equal(acceptResultCounter(link,'3000'),false);
  assert.equal(acceptResultCounter(link,'4500'),true);
});
test('a link saved before the window existed stays strictly ordered until it records results',()=>{
  const link={lastResultCounter:'7'};
  assert.equal(acceptResultCounter(link,'7'),false);
  assert.equal(acceptResultCounter(link,'6'),false);
  assert.equal(acceptResultCounter(link,'8'),true);
  assert.equal(acceptResultCounter(link,'7'),false,'7 was already used before the window was recorded');
});
