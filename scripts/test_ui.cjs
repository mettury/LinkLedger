#!/usr/bin/env node
'use strict';

// Dependency-free reproduction of the UI's DOM/API simulation.
// Run from any directory with Node.js 18+: node scripts/test_ui.cjs
// This executes the actual app.js against a deliberately small fake DOM and
// mocked fetch. It does not test layout, real browser behavior, native HTML
// validation, clipboard permissions, or the running Spring Boot API.

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');

process.env.TZ = 'UTC';
const staticDirectory = path.resolve(__dirname, '../src/main/resources/static');
const html = fs.readFileSync(path.join(staticDirectory, 'index.html'), 'utf8');
const javascript = fs.readFileSync(path.join(staticDirectory, 'app.js'), 'utf8');
const css = fs.readFileSync(path.join(staticDirectory, 'styles.css'), 'utf8');
const timers = new Set();
const requests = [];
let passedGroups = 0;

function check(description, assertions) {
  assertions();
  passedGroups += 1;
  console.log(`PASS ${description}`);
}

class Element {
  constructor() {
    this.value = '';
    this.textContent = '';
    this.hidden = false;
    this.disabled = false;
    this.type = 'text';
    this.listeners = {};
    this.attributes = {};
    this.classList = { toggle() {}, add() {}, remove() {} };
    this.buttonLabel = { textContent: '' };
  }
  addEventListener(name, listener) { this.listeners[name] = listener; }
  setAttribute(name, value) { this.attributes[name] = value; }
  removeAttribute(name) { delete this.attributes[name]; }
  querySelector() { return this.buttonLabel; }
  setCustomValidity(value) { this.validationMessage = value; }
  reportValidity() { return !this.validationMessage; }
  focus() {}
  async trigger(name) { return this.listeners[name]({ preventDefault() {} }); }
}

const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
const elements = Object.fromEntries(ids.map((id) => [id, new Element()]));
elements['api-key'].value = 'local-demo-key';
elements['api-key'].type = 'password';
let remainingPostFailures = 1;
let storedLink = null;
let disabled = false;
let confirmation = false;

const sandbox = {
  document: {
    getElementById: (id) => elements[id],
    querySelectorAll: () => [],
  },
  window: {
    crypto: { randomUUID: () => crypto.randomUUID() },
    setTimeout(callback, delay) {
      const timer = setTimeout(callback, delay);
      timers.add(timer);
      return timer;
    },
    clearTimeout(timer) {
      clearTimeout(timer);
      timers.delete(timer);
    },
    confirm: () => confirmation,
    getSelection: () => null,
  },
  navigator: {}, // Exercise the unavailable-clipboard fallback.
  Intl, Date, URL, Uint8Array, Number, JSON, Array, String, Error, TypeError, AbortController,
  async fetch(url, options) {
    requests.push({ url, ...options });
    // Keep a request in flight long enough to test the repeated-submit guard.
    await new Promise((resolve) => setTimeout(resolve, 3));
    let status = 200;
    let data;
    if (options.headers['X-API-Key'] !== 'local-demo-key') {
      status = 401;
      data = { detail: 'Invalid key.', requestId: 'r-123' };
    } else if (options.method === 'POST') {
      if (remainingPostFailures-- > 0) {
        status = 503;
        data = { detail: 'Try again.' };
      } else {
        status = 201;
        const body = JSON.parse(options.body);
        storedLink = {
          code: body.customAlias,
          url: body.url,
          shortUrl: `http://localhost:8080/${body.customAlias}`,
          expiresAt: body.expiresAt,
          createdAt: '2026-10-06T12:00:00Z',
          status: 'ACTIVE',
        };
        data = storedLink;
      }
    } else if (options.method === 'DELETE') {
      status = 204;
      disabled = true;
    } else if (url.endsWith('/analytics')) {
      data = {
        code: storedLink.code,
        totalRedirects: 3,
        lastAccessedAt: null,
        measurement: 'Recorded GET resolutions; repeats and bots included; best effort.',
      };
    } else {
      data = { ...storedLink, status: disabled ? 'DISABLED' : 'ACTIVE' };
    }
    return {
      status,
      ok: status >= 200 && status < 300,
      text: async () => status === 204 ? '' : JSON.stringify(data),
    };
  },
};

async function run() {
  console.log('LinkLedger UI simulation (mock DOM/API; no browser or live-server coverage)');
  check('static IDs, accessible references, local assets, safe rendering and no credential persistence', () => {
    assert.equal(new Set(ids).size, ids.length);
    const references = [...html.matchAll(/\b(?:for|aria-labelledby|aria-describedby)="([^"]+)"/g)]
      .flatMap((match) => match[1].split(/\s+/));
    assert.ok(references.every((reference) => ids.includes(reference)));
    const assets = [...html.matchAll(/<(?:link|script)\b[^>]*\b(?:href|src)="([^"]+)"/g)].map((match) => match[1]);
    assert.deepEqual(assets.sort(), ['/app.js', '/styles.css', 'data:,'].sort());
    assert.ok(!/innerHTML|localStorage|sessionStorage/.test(javascript));
    assert.equal((css.match(/\{/g) || []).length, (css.match(/\}/g) || []).length);
    assert.match(html, /id="short-link"[^>]*rel="noopener noreferrer"/);
    assert.match(html, /id="open-link"[^>]*rel="noopener noreferrer"/);
  });

  vm.createContext(sandbox);
  vm.runInContext(javascript, sandbox, { filename: 'app.js' });
  elements['target-url'].value = 'https://example.com/?q=<script>alert(1)</script>';
  elements['custom-alias'].value = 'launch-notes';
  // Keep the expiration fixture in the future when this script is rerun later.
  const futureLocalTime = new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString().slice(0, 16);
  elements['expires-at'].value = futureLocalTime;

  await elements['create-form'].trigger('submit');
  check('HTTP 503 problem detail shown and form restored', () => {
    assert.match(elements['create-error'].textContent, /Try again/);
    assert.equal(elements['create-fields'].disabled, false);
  });

  const retry = elements['create-form'].trigger('submit');
  assert.equal(elements['create-fields'].disabled, true);
  await elements['create-form'].trigger('submit');
  await retry;
  const posts = requests.filter((request) => request.method === 'POST');
  check('unchanged retry preserves UUID idempotency key; repeated submit is ignored', () => {
    assert.equal(posts.length, 2);
    assert.equal(posts[0].headers['Idempotency-Key'], posts[1].headers['Idempotency-Key']);
    assert.match(posts[0].headers['Idempotency-Key'], /^[0-9a-f-]{36}$/);
  });
  check('create sends API key, URL, custom alias and local expiration converted to ISO UTC', () => {
    assert.equal(posts[1].headers['X-API-Key'], 'local-demo-key');
    const body = JSON.parse(posts[1].body);
    assert.equal(body.url, elements['target-url'].value);
    assert.equal(body.customAlias, 'launch-notes');
    assert.equal(body.expiresAt, `${futureLocalTime}:00.000Z`);
  });
  check('metadata and measured analytics displayed; destination assigned as text', () => {
    assert.equal(elements['redirect-count'].textContent, '3');
    assert.equal(elements['link-status'].textContent, 'Active');
    assert.equal(elements['destination-url'].textContent, elements['target-url'].value);
    assert.equal(elements['measurement-note'].textContent, 'Recorded GET resolutions; repeats and bots included; best effort.');
    assert.equal(elements['create-fields'].disabled, false);
  });

  await elements['disable-button'].trigger('click');
  check('cancelling confirmation sends no DELETE', () => {
    assert.equal(requests.filter((request) => request.method === 'DELETE').length, 0);
  });
  confirmation = true;
  await elements['disable-button'].trigger('click');
  check('confirmed disable handles HTTP 204 and disables the action', () => {
    assert.equal(requests.filter((request) => request.method === 'DELETE').length, 1);
    assert.equal(elements['link-status'].textContent, 'Disabled');
    assert.equal(elements['disable-button'].disabled, true);
  });

  elements['api-key'].value = 'wrong';
  await elements['inspect-form'].trigger('submit');
  check('authorization error includes key guidance and request ID; stale analytics cleared', () => {
    assert.match(elements['inspect-error'].textContent, /Check the API key/);
    assert.match(elements['inspect-error'].textContent, /r-123/);
    assert.equal(elements['redirect-count'].textContent, '—');
  });
  await elements['copy-button'].trigger('click');
  check('clipboard fallback gives manual-copy guidance', () => {
    assert.match(elements['result-feedback'].textContent, /Automatic copy is unavailable/);
  });
  await elements['toggle-key'].trigger('click');
  assert.equal(elements['api-key'].type, 'text');
  await elements['toggle-key'].trigger('click');
  check('API-key visibility toggles back to password', () => {
    assert.equal(elements['api-key'].type, 'password');
  });

  elements['api-key'].value = 'local-demo-key';
  elements['target-url'].value = 'javascript:alert(1)';
  const requestCountBeforeValidation = requests.length;
  await elements['create-form'].trigger('submit');
  check('non-HTTP destination rejected without an API request', () => {
    assert.match(elements['target-url'].validationMessage, /http/);
    assert.equal(requests.length, requestCountBeforeValidation);
  });
  elements['target-url'].value = 'https://example.com';
  elements['expires-at'].value = '2020-01-01T12:00';
  await elements['create-form'].trigger('submit');
  check('past expiration rejected without an API request', () => {
    assert.match(elements['expires-at'].validationMessage, /future/);
    assert.equal(requests.length, requestCountBeforeValidation);
  });
  console.log(`RESULT: ${passedGroups} check groups passed.`);
}

run().catch((error) => {
  console.error('FAIL', error);
  process.exitCode = 1;
}).finally(() => {
  for (const timer of timers) clearTimeout(timer);
});
