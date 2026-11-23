import test from 'node:test';
import assert from 'node:assert/strict';
import {validRedirect,fetchClientMetadata} from '../oauth-clients.mjs';
test('redirects allow native and cloud clients but reject unsafe destinations',async()=>{
 for(const url of ['http://localhost:8787/callback','http://127.0.0.1:3333/oauth/callback','http://[::1]:4242/callback','https://www.cursor.com/agents/mcp/oauth/callback','https://chatgpt.com/connector_platform_oauth_redirect','https://example.com/return?client=a'])assert.equal(validRedirect(url),true,url);
 for(const url of ['file:///tmp/a','http://example.com/callback','https://user:pass@example.com/callback','https://example.com/cb#fragment','https://127.0.0.1/cb','https://[::1]/cb','https://10.0.0.1/cb','https://169.254.169.254/cb','https://[::ffff:127.0.0.1]/cb','https://service.local/cb','https://foo.localhost/cb'])assert.equal(validRedirect(url),false,url);
 for(const id of ['https://127.0.0.1/client.json','https://169.254.169.254/client.json','http://localhost:9999/client.json','https://example.com/','https://example.com/client.json?secret=x','https://example.com:8443/client.json','https://localhost/client.json'])await assert.rejects(fetchClientMetadata(id));
});
test('native metadata callbacks allow ephemeral ports without relaxing host, path or query checks',async()=>{
 const {redirectMatches,callbackOriginMatches}=await import('../oauth-clients.mjs');
 const registered='http://127.0.0.1/callback/0MjfHrZS1dUr';
 assert.equal(validRedirect(registered),true);
 assert.equal(redirectMatches(registered,'http://127.0.0.1:34281/callback/0MjfHrZS1dUr'),true);
 for(const requested of ['http://localhost:34281/callback/0MjfHrZS1dUr','http://127.0.0.1:34281/other','http://127.0.0.1:34281/callback/0MjfHrZS1dUr?extra=1','https://127.0.0.1:34281/callback/0MjfHrZS1dUr','http://127.0.0.2:34281/callback/0MjfHrZS1dUr'])assert.equal(redirectMatches(registered,requested),false,requested);
 assert.equal(redirectMatches('https://cloud.example/cb','https://cloud.example:444/cb'),false);
 assert.equal(callbackOriginMatches(registered,'http://127.0.0.1:34281'),true);assert.equal(callbackOriginMatches(registered,'http://127.0.0.2:34281'),false);
});
