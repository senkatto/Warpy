// The native shell reuses the tested controller, without a browser or a layout engine.
// This small retained tree holds labels, input values and event handlers only.
globalThis.window = globalThis;
const nativeElements = new Map();
const nativeIds = new Map();
let nativeIdsDirty = true;
let nativeTreeVersion = 0;
let nativeDirty = true;
let nativeSequence = 1;
const nativeTimers = new Map();
const nativePending = new Map();
const nativeEvents = new Map();
const nativeStreams = new Map();
const markNativeDirty = () => { nativeDirty = true; };
const markNativeTreeDirty = () => { nativeIdsDirty = true; nativeTreeVersion++; markNativeDirty(); };

class NativeEvent {
  constructor(type, fields = {}) { this.type = type; Object.assign(this, fields); }
  preventDefault() { this.defaultPrevented = true; }
  stopPropagation() { this.stopped = true; }
}
globalThis.Event = NativeEvent;
globalThis.DOMException = class extends Error {
  constructor(message, name) { super(message); this.name = name; }
};
class NativeEventTarget {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, fn, options) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push({ fn, once: options?.once });
  }
  removeEventListener(type, fn) {
    this.listeners.set(type, (this.listeners.get(type) || []).filter(entry => entry.fn !== fn));
  }
  dispatchEvent(event) {
    event.target ||= this;
    event.currentTarget = this;
    this[`on${event.type}`]?.(event);
    for (const entry of [...(this.listeners.get(event.type) || [])]) {
      entry.fn(event);
      if (entry.once) this.removeEventListener(event.type, entry.fn);
    }
    if (event.type === 'click' && !event.stopped) this.parentNode?.dispatchEvent(event);
    markNativeDirty();
    return !event.defaultPrevented;
  }
}
class NativeClassList {
  constructor(element) { this.element = element; }
  contains(value) { return this.element.className.split(/\s+/).includes(value); }
  add(...values) { this.element.className = [...new Set([...this.element.className.split(/\s+/).filter(Boolean), ...values])].join(' '); }
  remove(...values) { this.element.className = this.element.className.split(/\s+/).filter(value => !values.includes(value)).join(' '); }
  toggle(value, force) {
    const add = force === undefined ? !this.contains(value) : force;
    if (add) this.add(value); else this.remove(value);
    return add;
  }
}
function nativeMatches(element, selector) {
  if (!element.tagName) return false;
  const tag = selector.match(/^[\w-]+/)?.[0];
  if (tag && element.tagName.toLowerCase() !== tag.toLowerCase()) return false;
  for (const match of selector.matchAll(/#([\w-]+)|\.([\w-]+)|\[([\w-]+)(?:=["']?([^\]"']*)["']?)?\]/g)) {
    if (match[1] && element.id !== match[1]) return false;
    if (match[2] && !element.classList.contains(match[2])) return false;
    if (match[3] && !(match[3] in element.attributes)) return false;
    if (match[4] !== undefined && element.getAttribute(match[3]) !== match[4]) return false;
  }
  return true;
}
function nativeSelectorMatches(element, selector) {
  const parts = selector.trim().split(/\s+/);
  if (!nativeMatches(element, parts.pop())) return false;
  for (let parent = element.parentNode; parts.length; parent = parent?.parentNode) {
    if (!parent) return false;
    if (nativeMatches(parent, parts.at(-1))) parts.pop();
  }
  return true;
}
class NativeElement extends NativeEventTarget {
  constructor(tag, attrs = {}) {
    super(); this.tagName = tag.toUpperCase(); this.attributes = { ...attrs };
    this.children = []; this.parentNode = null; this.uid = nativeSequence++;
    nativeElements.set(this.uid, this);
    this.classList = new NativeClassList(this);
    this.style = new Proxy({}, { set: (target, key, value) => { target[key] = value; markNativeDirty(); return true; } });
    this.dataset = {};
    for (const [key, value] of Object.entries(attrs)) {
      if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, ch) => ch.toUpperCase())] = value;
    }
    this.value = attrs.value || ''; this.checked = 'checked' in attrs;
    this.disabled = 'disabled' in attrs; this.type = attrs.type || '';
    this.placeholder = attrs.placeholder || ''; this.src = attrs.src || '';
    this.readOnly = 'readonly' in attrs;
    if (attrs.style) for (const declaration of attrs.style.split(';')) {
      const [key, ...value] = declaration.split(':');
      if (key.trim()) this.style[key.trim().replace(/-([a-z])/g, (_, ch) => ch.toUpperCase())] = value.join(':').trim();
    }
    this.scrollTop = 0;
  }
  get id() { return this.attributes.id || ''; }
  set id(value) { this.setAttribute('id', value); }
  get className() { return this.attributes.class || ''; }
  set className(value) { if (this.attributes.class !== value) nativeTreeVersion++; this.attributes.class = value; markNativeDirty(); }
  get value() { return this.nativeValue || ''; }
  set value(value) { this.nativeValue = String(value ?? ''); markNativeDirty(); }
  get textContent() { return this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this.replaceChildren(new NativeText(String(value))); }
  get innerHTML() { return this.children.map(nativeMarkup).join(''); }
  set innerHTML(value) { this.replaceChildren(...nativeParseMarkup(String(value))); }
  get childNodes() { return this.children; }
  get firstChild() { return this.children[0] || null; }
  get parentElement() { return this.parentNode; }
  get isConnected() { return this === document || Boolean(this.parentNode?.isConnected); }
  appendChild(child) {
    if (child.parentNode) child.remove();
    child.parentNode = this; this.children.push(child); markNativeTreeDirty(); return child;
  }
  append(...children) { for (const child of children) this.appendChild(typeof child === 'string' ? new NativeText(child) : child); }
  replaceChildren(...children) {
    for (const child of this.children) child.parentNode = null;
    this.children = []; this.append(...children); markNativeTreeDirty();
  }
  remove() {
    if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this);
    this.parentNode = null; markNativeTreeDirty();
  }
  removeChild(child) { this.children = this.children.filter(value => value !== child); child.parentNode = null; markNativeTreeDirty(); return child; }
  setAttribute(key, value) {
    if (this.attributes[key] !== String(value)) nativeTreeVersion++;
    this.attributes[key] = String(value);
    if (key === 'id') nativeIdsDirty = true;
    if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, ch) => ch.toUpperCase())] = String(value);
    markNativeDirty();
  }
  getAttribute(key) { return this.attributes[key] ?? null; }
  querySelectorAll(selector) {
    const found = []; const alternatives = selector.split(',');
    const visit = node => { for (const child of node.children || []) {
      if (alternatives.some(value => nativeSelectorMatches(child, value))) found.push(child);
      visit(child);
    } }; visit(this); return found;
  }
  querySelector(selector) {
    const alternatives = selector.split(',');
    const visit = node => { for (const child of node.children || []) {
      if (alternatives.some(value => nativeSelectorMatches(child, value))) return child;
      const found = visit(child); if (found) return found;
    } return null; };
    return visit(this);
  }
  closest(selector) {
    if (selector.split(',').some(value => nativeSelectorMatches(this, value))) return this;
    return this.parentNode?.closest(selector) || null;
  }
  getBoundingClientRect() { return this.nativeRect || { left: 0, top: 0, width: 200, height: 40, right: 200, bottom: 40 }; }
  focus() { globalThis.__nativeSetFocus?.(this.uid); this.dispatchEvent(new NativeEvent('focus')); }
  matches(selector) { return selector === ':hover' ? this.uid === nativeHover : nativeSelectorMatches(this, selector); }
  getContext() {
    if (!this.nativeCanvas) this.nativeCanvas = new NativeCanvas();
    return this.nativeCanvas;
  }
}
class NativeText {
  constructor(text) { this.textContent = text; this.parentNode = null; }
  remove() { this.parentNode?.removeChild(this); }
}
function nativeFromTree(tree) {
  if ('text' in tree) return new NativeText(tree.text);
  const element = new NativeElement(tree.tag, tree.attrs);
  for (const child of tree.children) element.appendChild(nativeFromTree(child));
  if (tree.tag === 'select') element.value = element.children[0]?.attributes.value || '';
  return element;
}
function nativeMarkup(node) {
  if (!node.tagName) return node.textContent;
  return `<${node.tagName.toLowerCase()} ${Object.entries(node.attributes).map(([key, value]) => `${key}="${String(value).replace(/"/g, '&quot;')}"`).join(' ')}>${node.innerHTML}</${node.tagName.toLowerCase()}>`;
}
function nativeParseMarkup(markup) {
  const root = new NativeElement('fragment'); const stack = [root];
  for (const match of markup.matchAll(/<\/?[\w:-]+\b[^>]*>|[^<]+/g)) {
    const token = match[0];
    if (token.startsWith('</')) { if (stack.length > 1) stack.pop(); }
    else if (token.startsWith('<')) {
      const tag = token.match(/^<([\w:-]+)/)[1]; const attrs = {};
      for (const a of token.slice(tag.length + 1).matchAll(/([\w:-]+)(?:\s*=\s*"([^"]*)"|\s*=\s*'([^']*)')?/g)) attrs[a[1]] = a[2] ?? a[3] ?? '';
      const element = new NativeElement(tag, attrs); stack.at(-1).appendChild(element);
      if (!['input', 'img', 'br'].includes(tag) && !token.endsWith('/>')) stack.push(element);
    } else if (token.trim()) stack.at(-1).appendChild(new NativeText(token.trim()));
  }
  return [...root.children];
}
globalThis.document = nativeFromTree(__warpyTree);
document.getElementById = id => {
  if (nativeIdsDirty) {
    nativeIds.clear();
    const visit = node => { for (const child of node.children || []) {
      if (child.id && !nativeIds.has(child.id)) nativeIds.set(child.id, child);
      visit(child);
    } }; visit(document);
    nativeIdsDirty = false;
  }
  return nativeIds.get(String(id)) || null;
};
document.createElement = tag => new NativeElement(tag);
document.createElementNS = (_, tag) => new NativeElement(tag);
document.createTextNode = text => new NativeText(text);
document.documentElement = document.querySelector('html');
document.body = document.querySelector('body');
document.hidden = false;
globalThis.devicePixelRatio = 1;
globalThis.innerWidth = 420; globalThis.innerHeight = 720;
const nativeWindowEvents = new NativeEventTarget();
globalThis.addEventListener = (...args) => nativeWindowEvents.addEventListener(...args);
globalThis.removeEventListener = (...args) => nativeWindowEvents.removeEventListener(...args);
globalThis.performance = { now: () => __nativeNow() };
globalThis.console = Object.fromEntries(['log', 'warn', 'error'].map(name => [name, (...args) => __nativeLog(args.map(String).join(' '))]));
globalThis.setTimeout = (fn, delay = 0, ...args) => {
  const id = nativeSequence++; nativeTimers.set(id, { fn, args, at: performance.now() + Math.max(0, delay), interval: 0 }); return id;
};
globalThis.setInterval = (fn, delay, ...args) => {
  const id = setTimeout(fn, delay, ...args); nativeTimers.get(id).interval = Math.max(1, delay); return id;
};
globalThis.clearTimeout = globalThis.clearInterval = id => nativeTimers.delete(id);
globalThis.requestAnimationFrame = fn => setTimeout(() => { if (!document.hidden) fn(performance.now()); }, 33);
globalThis.cancelAnimationFrame = clearTimeout;
function nativeInvoke(command, args = {}) {
  return new Promise((resolve, reject) => {
    const id = nativeSequence++; nativePending.set(id, { resolve, reject }); __nativeInvoke(id, command, JSON.stringify(args));
  });
}
globalThis.__nativeResolve = (id, success, value) => {
  const entry = nativePending.get(id); if (!entry) return;
  nativePending.delete(id); entry[success ? 'resolve' : 'reject'](success ? value : new Error(String(value))); markNativeDirty();
};
globalThis.__nativeEvent = (name, payload) => {
  for (const fn of nativeEvents.get(name) || []) fn({ payload }); markNativeDirty();
};
const nativeWindow = {
  minimize: () => __nativeWindow('minimize'), close: () => __nativeWindow('hide'),
  show: () => __nativeWindow('show'), unminimize: () => __nativeWindow('show'),
  setFocus: () => __nativeWindow('show'), startDragging: () => {},
};
globalThis.__TAURI__ = {
  core: { invoke: nativeInvoke }, window: { getCurrentWindow: () => nativeWindow },
  event: { listen: async (name, fn) => {
    if (!nativeEvents.has(name)) nativeEvents.set(name, []); nativeEvents.get(name).push(fn);
    return () => nativeEvents.set(name, nativeEvents.get(name).filter(value => value !== fn));
  } },
  notification: { isPermissionGranted: async () => true, sendNotification: value => { void nativeInvoke('native_notification', value); } },
};
globalThis.navigator = { language: 'ru-RU', languages: ['ru-RU'],
  clipboard: { readText: () => nativeInvoke('native_clipboard_read'), writeText: text => nativeInvoke('native_clipboard_write', { text }) },
};
globalThis.crypto = { randomUUID: () => __nativeUuid() };
globalThis.TextEncoder = class { encode(value) { const binary = unescape(encodeURIComponent(String(value))); return Uint8Array.from(binary, ch => ch.charCodeAt(0)); } };
globalThis.TextDecoder = class { decode(value) { return decodeURIComponent(escape(Array.from(value, byte => String.fromCharCode(byte)).join(''))); } };
globalThis.atob = value => __nativeBase64(String(value), false);
globalThis.btoa = value => __nativeBase64(String(value), true);
globalThis.URLSearchParams = class {
  constructor(value = '') { this.items = String(value).replace(/^\?/, '').split('&').filter(Boolean).map(item => { const at = item.indexOf('='); return [item.slice(0, at < 0 ? undefined : at), at < 0 ? '' : item.slice(at + 1)].map(value => decodeURIComponent(value.replace(/\+/g, ' '))); }); }
  get(key) { return this.items.find(item => item[0] === key)?.[1] ?? null; }
  has(key) { return this.items.some(item => item[0] === key); }
  set(key, value) { this.items = this.items.filter(item => item[0] !== key); this.items.push([key, String(value)]); }
  append(key, value) { this.items.push([key, String(value)]); }
  toString() { return this.items.map(item => item.map(encodeURIComponent).join('=')).join('&'); }
  [Symbol.iterator]() { return this.items[Symbol.iterator](); }
};
globalThis.URL = class {
  constructor(value) { const parsed = JSON.parse(__nativeUrl(String(value))); if (!parsed) throw new TypeError('Invalid URL'); Object.assign(this, parsed); this.searchParams = new URLSearchParams(this.search); }
  get href() { return this.base + (this.hash ? '#' + String(this.hash).replace(/^#/, '') : ''); }
  toString() { return this.href; }
};
globalThis.AbortController = class {
  constructor() { this.signal = new NativeEventTarget(); this.signal.aborted = false; }
  abort() { if (this.signal.aborted) return; this.signal.aborted = true; this.signal.dispatchEvent(new NativeEvent('abort')); }
};
globalThis.fetch = (url, options = {}) => new Promise((resolve, reject) => {
  if (options.signal?.aborted) { reject(new DOMException('Aborted', 'AbortError')); return; }
  const id = nativeSequence++;
  const stream = { resolve, reject, chunks: [], waiter: null, done: false, error: null, total: 0 };
  nativeStreams.set(id, stream);
  options.signal?.addEventListener('abort', () => { __nativeFetchCancel(id); stream.error = new DOMException('Aborted', 'AbortError'); stream.reject(stream.error); stream.waiter?.reject(stream.error); nativeStreams.delete(id); }, { once: true });
  __nativeFetch(id, String(url), 0);
});
globalThis.__nativeFetchEvent = (id, event) => {
  const stream = nativeStreams.get(id); if (!stream) return;
  if (event.error) { stream.error = new Error(event.error); stream.reject(stream.error); stream.waiter?.reject(stream.error); nativeStreams.delete(id); return; }
  if (event.status) {
    const read = () => {
      if (stream.error) return Promise.reject(stream.error);
      if (stream.chunks.length) return Promise.resolve({ done: false, value: { length: stream.chunks.shift() } });
      if (stream.done) { nativeStreams.delete(id); return Promise.resolve({ done: true }); }
      return new Promise((resolve, reject) => { stream.waiter = { resolve, reject }; });
    };
    stream.resolve({ ok: event.status >= 200 && event.status < 300, status: event.status,
      body: { getReader: () => ({ read }) }, arrayBuffer: async () => { while (!(await read()).done) {} return new ArrayBuffer(stream.total); } });
  }
  if (event.bytes) { stream.total += event.bytes; if (stream.waiter) { stream.waiter.resolve({ done: false, value: { length: event.bytes } }); stream.waiter = null; } else stream.chunks.push(event.bytes); }
  if (event.done) { stream.done = true; if (stream.waiter) { stream.waiter.resolve({ done: true }); stream.waiter = null; } }
};
globalThis.XMLHttpRequest = class {
  constructor() { this.upload = {}; this.id = nativeSequence++; this.status = 0; }
  open(_, url) { this.url = url; }
  send(payload) { nativeStreams.set(this.id, { xhr: this }); __nativeFetch(this.id, this.url, payload.length); }
  abort() { __nativeFetchCancel(this.id); nativeStreams.delete(this.id); this.onabort?.(); }
};
const nativeDownloadEvent = __nativeFetchEvent;
globalThis.__nativeFetchEvent = (id, event) => {
  const xhr = nativeStreams.get(id)?.xhr;
  if (!xhr) return nativeDownloadEvent(id, event);
  if (event.loaded) xhr.upload.onprogress?.({ loaded: event.loaded });
  if (event.status) xhr.status = event.status;
  if (event.error) { nativeStreams.delete(id); xhr.onerror?.(); }
  else if (event.done) { nativeStreams.delete(id); xhr.onload?.(); }
};
class NativeCanvas {
  constructor() { this.ops = []; this.path = []; this.fillStyle = '#fff'; this.strokeStyle = '#fff'; this.lineWidth = 1; }
  scale() {} clearRect() { this.ops = []; markNativeDirty(); }
  beginPath() { this.path = []; }
  arc(x, y, r) { this.path.push({ kind: 'ellipse', x: x - r, y: y - r, w: r * 2, h: r * 2 }); }
  moveTo(x, y) { this.point = { x, y }; }
  lineTo(x, y) { this.path.push({ kind: 'line', x: this.point.x, y: this.point.y, w: x, h: y }); this.point = { x, y }; }
  fill() { this.ops.push(...this.path.map(op => ({ ...op, color: this.fillStyle }))); markNativeDirty(); }
  stroke() { this.ops.push(...this.path.map(op => ({ ...op, color: this.strokeStyle, stroke: this.lineWidth }))); markNativeDirty(); }
}
globalThis.__nativeVisibility = visible => {
  document.hidden = !visible;
  if (visible) for (const timer of nativeTimers.values()) {
    if (['updateUptime', 'updateNetworkMetrics', 'updatePingMetric'].includes(timer.fn.name)) timer.at = performance.now();
  }
  document.dispatchEvent(new NativeEvent('visibilitychange')); markNativeDirty();
};
globalThis.__nativeTick = () => {
  const now = performance.now();
  for (const [id, timer] of [...nativeTimers]) {
    if (timer.at > now || !nativeTimers.has(id)) continue;
    const visibleOnly = ['updateUptime', 'updateNetworkMetrics', 'updatePingMetric'].includes(timer.fn.name);
    if (timer.interval) timer.at = now + (document.hidden && visibleOnly ? 60000 : document.hidden && timer.fn.name === 'checkStatus' ? 10000 : timer.interval);
    else nativeTimers.delete(id);
    if (!document.hidden || !visibleOnly) {
      try { timer.fn(...timer.args); } catch (error) { console.error(error.stack || error); }
    }
  }
};
globalThis.__nativeNextWake = () => {
  const next = Math.min(...[...nativeTimers.values()].map(timer => timer.at));
  return Math.max(1, Math.min(60000, next - performance.now()));
};
