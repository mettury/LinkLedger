(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const ui = {
    createForm: $('create-form'), createFields: $('create-fields'), createButton: $('create-button'),
    createError: $('create-error'), url: $('target-url'), alias: $('custom-alias'), expires: $('expires-at'),
    key: $('api-key'), toggleKey: $('toggle-key'), inspectForm: $('inspect-form'), code: $('inspect-code'),
    inspectButton: $('inspect-button'), inspectError: $('inspect-error'), count: $('redirect-count'),
    lastAccessed: $('last-accessed'), metricCaption: $('metric-caption'), measurement: $('measurement-note'),
    refresh: $('refresh-button'), empty: $('result-empty'), content: $('result-content'), status: $('link-status'),
    notice: $('result-notice'), shortLink: $('short-link'), destination: $('destination-url'),
    detailCode: $('detail-code'), created: $('detail-created'), expiry: $('detail-expires'),
    copy: $('copy-button'), open: $('open-link'), disable: $('disable-button'), feedback: $('result-feedback'), toast: $('toast'),
  };
  let pendingCreate = null;
  let creating = false;
  let inspecting = false;
  let disabling = false;
  let selectedLink = null;
  let inspectedCode = null;
  let toastTimer;
  const numberFormatter = new Intl.NumberFormat();
  const dateFormatter = new Intl.DateTimeFormat(undefined, {
    year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
  const defaultMeasurement = 'Recorded GET resolutions; repeats and bots included; best effort. HEAD requests are not counted.';

  function setFeedback(element, message, isError = true) {
    element.textContent = message || '';
    element.hidden = !message;
    element.classList.toggle('error-feedback', Boolean(message && isError));
  }
  function notify(message) {
    window.clearTimeout(toastTimer);
    ui.toast.textContent = message;
    ui.toast.hidden = false;
    toastTimer = window.setTimeout(() => { ui.toast.hidden = true; }, 4000);
  }
  function formatDate(value, empty = 'No expiration') {
    if (!value) return empty;
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? 'Unavailable' : dateFormatter.format(parsed);
  }
  function apiKey() {
    const key = ui.key.value.trim();
    if (!key) throw new Error('Enter your API key in the API access panel.');
    return key;
  }
  function newIdempotencyKey() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
    // randomUUID may be unavailable on a non-secure local-network origin.
    if (!window.crypto || !window.crypto.getRandomValues) throw new Error('A browser with secure random number support is required.');
    const bytes = window.crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  async function request(path, options = {}) {
    const controller = new AbortController();
    let timedOut = false;
    const timeout = window.setTimeout(() => { timedOut = true; controller.abort(); }, 15000);
    try {
      const response = await fetch(path, {
        method: options.method || 'GET',
        headers: {
          Accept: 'application/json',
          'X-API-Key': options.key || apiKey(),
          ...(options.body ? { 'Content-Type': 'application/json' } : {}),
          ...(options.idempotencyKey ? { 'Idempotency-Key': options.idempotencyKey } : {}),
        },
        ...(options.body ? { body: JSON.stringify(options.body) } : {}),
        signal: controller.signal,
        cache: 'no-store',
        credentials: 'same-origin',
      });
      const text = response.status === 204 ? '' : await response.text();
      let data = null;
      if (text) {
        try { data = JSON.parse(text); } catch (_) { /* Never render an HTML error body. */ }
      }
      if (!response.ok) {
        const detail = data && (data.detail || data.title);
        let message = typeof detail === 'string' ? detail : `The request failed (HTTP ${response.status}).`;
        if (response.status === 401 || response.status === 403) message += ' Check the API key in the API access panel.';
        if (data && typeof data.requestId === 'string') message += ` Request ID: ${data.requestId}`;
        throw new Error(message);
      }
      if (response.status !== 204 && (!data || typeof data !== 'object')) throw new Error('The server returned an unexpected response. Check that the API is running.');
      return { data, status: response.status };
    } catch (error) {
      if (timedOut) throw new Error('The request timed out. You can try again. An unchanged create request will reuse its idempotency key.');
      if (error instanceof TypeError) throw new Error('Could not reach the API. Check that the server is running, then try again.');
      throw error;
    } finally {
      window.clearTimeout(timeout);
    }
  }
  function safeWebUrl(value) {
    try {
      const url = new URL(value);
      return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null;
    } catch (_) { return null; }
  }
  function linkState(link) {
    const status = String(link.status || 'UNKNOWN').toUpperCase();
    if (status === 'DISABLED' || status === 'EXPIRED') return status;
    if (link.expiresAt && new Date(link.expiresAt).getTime() <= Date.now()) return 'EXPIRED';
    return status;
  }
  function busy() { return creating || inspecting || disabling; }
  function updateControls() {
    const blocked = busy();
    ui.createFields.disabled = blocked;
    ui.inspectButton.disabled = blocked;
    ui.code.disabled = blocked;
    ui.refresh.disabled = blocked;
    ui.key.disabled = blocked;
    ui.createButton.classList.toggle('loading', creating);
    ui.createButton.querySelector('.button-label').textContent = creating ? 'Creating…' : 'Shorten link';
    ui.createForm.setAttribute('aria-busy', String(creating));
    ui.inspectForm.setAttribute('aria-busy', String(inspecting));
    ui.inspectButton.classList.toggle('loading', inspecting);
    ui.inspectButton.setAttribute('aria-label', inspecting ? 'Loading link details' : 'Inspect link');
    const disabled = selectedLink && linkState(selectedLink) === 'DISABLED';
    ui.disable.disabled = blocked || !selectedLink || disabled;
    ui.disable.textContent = disabling ? 'Disabling…' : disabled ? 'Link disabled' : 'Disable link';
  }
  function renderLink(link, notice) {
    if (!link || typeof link.code !== 'string' || typeof link.url !== 'string' || !safeWebUrl(link.shortUrl)) {
      throw new Error('The server returned incomplete link details.');
    }
    selectedLink = link;
    const status = linkState(link);
    ui.empty.hidden = true;
    ui.content.hidden = false;
    ui.status.hidden = false;
    ui.status.className = `status-pill ${status.toLowerCase().replace(/[^a-z]/g, '')}`;
    ui.status.textContent = status === 'ACTIVE' ? 'Active' : status === 'DISABLED' ? 'Disabled' : status === 'EXPIRED' ? 'Expired' : status;
    ui.notice.textContent = notice;
    ui.shortLink.textContent = link.shortUrl;
    ui.shortLink.href = safeWebUrl(link.shortUrl);
    ui.open.href = safeWebUrl(link.shortUrl);
    ui.destination.textContent = link.url;
    ui.detailCode.textContent = link.code;
    ui.created.textContent = formatDate(link.createdAt, 'Unavailable');
    ui.expiry.textContent = formatDate(link.expiresAt);
    setFeedback(ui.feedback, '');
    updateControls();
  }
  function resetAnalytics(caption = 'Look up a link to load its activity') {
    inspectedCode = null;
    ui.count.textContent = '—';
    ui.metricCaption.textContent = caption;
    ui.lastAccessed.textContent = 'No activity loaded';
    ui.measurement.textContent = defaultMeasurement;
    ui.refresh.hidden = true;
  }
  async function inspect(code, options = {}) {
    inspecting = true;
    updateControls();
    setFeedback(ui.inspectError, '');
    resetAnalytics('Loading recorded activity…');
    try {
      const key = options.key || apiKey();
      const encodedCode = encodeURIComponent(code);
      const [linkResponse, analyticsResponse] = await Promise.all([
        request(`/api/v1/urls/${encodedCode}`, { key }),
        request(`/api/v1/urls/${encodedCode}/analytics`, { key }),
      ]);
      if (!options.preserveResult) renderLink(linkResponse.data, 'Link details loaded from the API.');
      const analytics = analyticsResponse.data;
      if (typeof analytics.totalRedirects !== 'number' || !Number.isFinite(analytics.totalRedirects) || analytics.totalRedirects < 0) {
        throw new Error('The server returned incomplete analytics.');
      }
      inspectedCode = code;
      ui.count.textContent = numberFormatter.format(analytics.totalRedirects);
      ui.metricCaption.textContent = `For /${code} · repeats and bots included`;
      ui.lastAccessed.textContent = formatDate(analytics.lastAccessedAt, 'No GET resolutions recorded');
      ui.measurement.textContent = typeof analytics.measurement === 'string' ? analytics.measurement : defaultMeasurement;
      ui.refresh.hidden = false;
    } catch (error) {
      setFeedback(ui.inspectError, error.message);
      resetAnalytics('Activity could not be loaded');
    } finally {
      inspecting = false;
      updateControls();
    }
  }
  ui.url.addEventListener('input', () => ui.url.setCustomValidity(''));
  ui.expires.addEventListener('input', () => ui.expires.setCustomValidity(''));
  ui.createForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy()) return;
    setFeedback(ui.createError, '');
    ui.url.setCustomValidity('');
    ui.expires.setCustomValidity('');
    const destination = ui.url.value.trim();
    if (!safeWebUrl(destination)) {
      ui.url.setCustomValidity('Enter a complete http:// or https:// URL.');
      ui.url.reportValidity();
      return;
    }
    let expiresAt = null;
    if (ui.expires.value) {
      const date = new Date(ui.expires.value);
      if (Number.isNaN(date.getTime()) || date.getTime() <= Date.now()) {
        ui.expires.setCustomValidity('Choose an expiration in the future.');
        ui.expires.reportValidity();
        return;
      }
      expiresAt = date.toISOString();
    }
    if (!ui.createForm.reportValidity()) return;
    const body = { url: destination };
    if (ui.alias.value.trim()) body.customAlias = ui.alias.value.trim();
    if (expiresAt) body.expiresAt = expiresAt;
    const fingerprint = JSON.stringify(body);
    let key;
    try {
      key = apiKey();
      if (!pendingCreate || pendingCreate.fingerprint !== fingerprint) pendingCreate = { fingerprint, idempotencyKey: newIdempotencyKey() };
    } catch (error) {
      setFeedback(ui.createError, error.message);
      return;
    }
    creating = true;
    updateControls();
    try {
      const response = await request('/api/v1/urls', { method: 'POST', body, key, idempotencyKey: pendingCreate.idempotencyKey });
      renderLink(response.data, response.status === 200 ? 'Existing result returned safely from your earlier request.' : 'Your short link is ready to share.');
      pendingCreate = null;
      ui.code.value = response.data.code;
      notify(response.status === 200 ? 'Your earlier create request was safely replayed.' : 'Short link created.');
      await inspect(response.data.code, { preserveResult: true, key });
    } catch (error) {
      setFeedback(ui.createError, error.message);
    } finally {
      creating = false;
      updateControls();
    }
  });
  ui.inspectForm.addEventListener('submit', (event) => {
    event.preventDefault();
    if (busy() || !ui.inspectForm.reportValidity()) return;
    return inspect(ui.code.value.trim());
  });
  ui.refresh.addEventListener('click', () => {
    if (inspectedCode && !busy()) return inspect(inspectedCode);
  });
  ui.toggleKey.addEventListener('click', () => {
    const show = ui.key.type === 'password';
    ui.key.type = show ? 'text' : 'password';
    ui.toggleKey.textContent = show ? 'Hide' : 'Show';
    ui.toggleKey.setAttribute('aria-label', show ? 'Hide API key' : 'Show API key');
    ui.toggleKey.setAttribute('aria-pressed', String(show));
  });
  ui.copy.addEventListener('click', async () => {
    if (!selectedLink) return;
    try {
      if (!navigator.clipboard || !navigator.clipboard.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(selectedLink.shortUrl);
      notify('Short link copied.');
    } catch (_) {
      const selection = window.getSelection();
      if (selection) {
        const range = document.createRange();
        range.selectNodeContents(ui.shortLink);
        selection.removeAllRanges();
        selection.addRange(range);
      }
      setFeedback(ui.feedback, 'Automatic copy is unavailable in this browser. Select the short URL and copy it manually.', false);
      ui.shortLink.focus();
    }
  });
  ui.disable.addEventListener('click', async () => {
    if (!selectedLink || busy() || linkState(selectedLink) === 'DISABLED') return;
    const link = selectedLink;
    if (!window.confirm(`Disable /${link.code}? Future requests to this short link will no longer redirect. This interface cannot reactivate it.`)) return;
    disabling = true;
    updateControls();
    setFeedback(ui.feedback, '');
    try {
      await request(`/api/v1/urls/${encodeURIComponent(link.code)}`, { method: 'DELETE' });
      renderLink({ ...link, status: 'DISABLED' }, 'This link is disabled. Requests to it will no longer redirect.');
      notify(`Link /${link.code} disabled.`);
    } catch (error) {
      setFeedback(ui.feedback, error.message);
    } finally {
      disabling = false;
      updateControls();
    }
  });
  document.querySelectorAll('.nav-link').forEach((link) => {
    link.addEventListener('click', () => {
      document.querySelectorAll('.nav-link').forEach((item) => { item.classList.remove('active'); item.removeAttribute('aria-current'); });
      link.classList.add('active');
      link.setAttribute('aria-current', 'location');
    });
  });
  updateControls();
})();
