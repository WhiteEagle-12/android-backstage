import test from 'node:test';
import assert from 'node:assert/strict';
import Ajv from 'ajv';
import {strictCatalogue as catalogue} from '../server.mjs';
test('location, media, file integrity, and package fields remain strictly validated',()=>{
 const valid=n=>new Ajv({strict:false}).compile(catalogue.find(t=>t.name==='android_'+n).inputSchema);
 assert.equal(valid('device')({operation:'location',maxAgeMs:5000,accuracyMeters:25,timeoutMs:20000}),true);
 assert.equal(valid('device')({operation:'media',action:'seek',requestId:'seek_1',package:'com.example',positionMs:12345}),true);
 assert.equal(valid('device')({operation:'media',action:'volume',requestId:'m'}),false);
 assert.equal(valid('packages')({package:'com.example',fields:['versionName']}),true);
 assert.equal(valid('packages')({fields:['unknown']}),false);
 assert.equal(valid('install')({requestId:'i',source:{artifactId:'art_1'}}),true);
 assert.equal(valid('install')({requestId:'i',source:{url:'ftp://bad'}}),false);
});
