import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {downloadFile} from '../download.mjs';

const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const version='file_'+'a'.repeat(64);
function source(data,calls){return async(name,args)=>{calls.push(args);assert.equal(name,'android.pull');if(args.fileId)assert.equal(args.fileId,version);const bytes=data.subarray(args.offset,args.offset+args.length),next=args.offset+bytes.length;return {outcome:'succeeded',fileId:version,totalBytes:data.length,bytes:bytes.length,offset:args.offset,nextOffset:next,eof:next===data.length,sha256:sha(bytes),dataBase64:bytes.toString('base64')};};}
function fixture(t){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-download-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));return {remote:'/sdcard/Download/file.bin',local:path.join(dir,'file.bin'),device:'phone_test',chunkSize:4096};}

test('large download resumes verified bytes after disconnect and publishes exact file',async t=>{
  const opts=fixture(t),data=crypto.randomBytes(17001),calls=[],read=source(data,calls);
  await assert.rejects(downloadFile(async(...args)=>{if(calls.length===2)throw new Error('offline');return read(...args);},opts),/offline/);
  assert.equal(fs.existsSync(opts.local),false);assert.equal(fs.statSync(opts.local+'.backstage-part').size,8192);
  calls.length=0;const result=await downloadFile(read,opts);
  assert.equal(calls[0].offset,8192);assert.equal(result.resumedBytes,8192);assert.equal(result.sha256,sha(data));assert.deepEqual(fs.readFileSync(opts.local),data);
  assert.deepEqual(fs.readdirSync(path.dirname(opts.local)),['file.bin']);
  await assert.rejects(downloadFile(read,opts),/already exists/);
});
test('empty file downloads with one bounded request',async t=>{
  const opts=fixture(t),calls=[];const result=await downloadFile(source(Buffer.alloc(0),calls),opts);assert.equal(result.bytes,0);assert.equal(calls.length,1);assert.equal(fs.statSync(opts.local).size,0);
});
test('changed file, corrupt chunk and corrupt partial never publish mixed bytes',async t=>{
  const opts=fixture(t),data=crypto.randomBytes(9001),calls=[],read=source(data,calls);
  await assert.rejects(downloadFile(async(...args)=>{const row=await read(...args);return calls.length===2?{...row,fileId:'file_'+'b'.repeat(64)}:row;},opts),/changed/);
  assert.equal(fs.existsSync(opts.local),false);
  await assert.rejects(downloadFile(async(...args)=>({...await read(...args),sha256:'0'.repeat(64)}),opts),/integrity/);
  fs.writeFileSync(opts.local+'.backstage-part',Buffer.alloc(4096));
  await assert.rejects(downloadFile(read,opts),/partial file changed/);
});
test('download identity, symlinks and racing destination preserve existing files',async t=>{
  const opts=fixture(t),data=crypto.randomBytes(9001),calls=[],read=source(data,calls);
  await assert.rejects(downloadFile(async(...args)=>{if(calls.length)throw new Error('offline');return read(...args);},opts),/offline/);
  await assert.rejects(downloadFile(read,{...opts,device:'different'}),/different file or device/);
  await assert.rejects(downloadFile(async(...args)=>{const row=await read(...args);if(row.eof)fs.writeFileSync(opts.local,'keep');return row;},opts),{code:'EEXIST'});
  assert.equal(fs.readFileSync(opts.local,'utf8'),'keep');
  const another={...opts,local:path.join(path.dirname(opts.local),'other')};fs.symlinkSync(opts.local,another.local+'.backstage-part');
  await assert.rejects(downloadFile(read,another),/partial file already exists/);assert.equal(fs.readFileSync(opts.local,'utf8'),'keep');
});
