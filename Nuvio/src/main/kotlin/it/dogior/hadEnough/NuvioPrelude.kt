package it.dogior.hadEnough

// GENERATED from tools/nuvio_prelude.js - do not edit by hand.
// JavaScript prelude executed by Rhino before every Nuvio scraper:
// console, Promise, setTimeout/setInterval, fetch, atob/btoa, module/exports and the scraper driver.
internal const val NUVIO_PRELUDE = """
// Nuvio scraper runtime prelude for Rhino.
// Provides: globalThis/global/self, console, Promise, setTimeout/setInterval, fetch, atob/btoa, module/exports.
// Native hooks supplied by Kotlin: __nativeFetch, __nativeBytes, __nativeLog.
// Event loop is driven from Kotlin through __drain, __runTimers, __nextDelay, __isDone, __getJson, __getError.

var globalThis = this;
var global = this;
var self = this;
var window = this;
var module = { exports: {} };
var exports = module.exports;

// ---------------------------------------------------------------- console
var console = (function () {
  function fmt(args) {
    var out = [];
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      if (typeof a === 'string') out.push(a);
      else if (a instanceof Error) out.push(String(a && a.message ? a.message : a));
      else {
        try { out.push(JSON.stringify(a)); } catch (e) { out.push(String(a)); }
      }
    }
    return out.join(' ');
  }
  function mk(level) { return function () { try { __nativeLog(level, fmt(arguments)); } catch (e) { } }; }
  return { log: mk('d'), info: mk('i'), debug: mk('d'), warn: mk('w'), error: mk('e') };
})();

// ---------------------------------------------------------------- microtasks + Promise
var __queue = [];
function __enqueue(fn) { __queue.push(fn); }
function __drain() {
  var n = 0;
  while (__queue.length) {
    var f = __queue.shift();
    try { f(); } catch (e) { }
    if (++n > 1000000) break;
  }
  return n;
}

(function () {
  function MyPromise(executor) {
    if (!(this instanceof MyPromise)) throw new TypeError('Promise must be called with new');
    if (typeof executor !== 'function') throw new TypeError('Promise resolver is not a function');
    var self = this;
    self._s = 0;
    self._v = undefined;
    self._h = [];
    var called = false;
    function res(v) { if (called) return; called = true; settleResolve(self, v); }
    function rej(e) { if (called) return; called = true; settleReject(self, e); }
    try { executor(res, rej); } catch (e) { rej(e); }
  }

  function settleResolve(p, v) {
    if (v === p) { settleReject(p, new TypeError('Chaining cycle detected for promise')); return; }
    if (v !== null && (typeof v === 'object' || typeof v === 'function')) {
      var then;
      try { then = v.then; } catch (e) { settleReject(p, e); return; }
      if (typeof then === 'function') {
        var done = false;
        __enqueue(function () {
          try {
            then.call(v,
              function (x) { if (done) return; done = true; settleResolve(p, x); },
              function (e) { if (done) return; done = true; settleReject(p, e); });
          } catch (e) { if (!done) { done = true; settleReject(p, e); } }
        });
        return;
      }
    }
    p._s = 1; p._v = v; flush(p);
  }

  function settleReject(p, e) { p._s = 2; p._v = e; flush(p); }

  function flush(p) {
    var hs = p._h; p._h = [];
    for (var i = 0; i < hs.length; i++) (function (h) { __enqueue(function () { handle(p, h); }); })(hs[i]);
  }

  function handle(p, h) {
    var cb = p._s === 1 ? h.onF : h.onR;
    if (typeof cb !== 'function') { if (p._s === 1) h.resolve(p._v); else h.reject(p._v); return; }
    var r;
    try { r = cb(p._v); } catch (e) { h.reject(e); return; }
    h.resolve(r);
  }

  MyPromise.prototype.then = function (onF, onR) {
    var self = this;
    return new MyPromise(function (resolve, reject) {
      var h = { onF: onF, onR: onR, resolve: resolve, reject: reject };
      if (self._s === 0) self._h.push(h);
      else __enqueue(function () { handle(self, h); });
    });
  };
  MyPromise.prototype['catch'] = function (onR) { return this.then(undefined, onR); };
  MyPromise.prototype['finally'] = function (fn) {
    return this.then(
      function (v) { return MyPromise.resolve(typeof fn === 'function' ? fn() : undefined).then(function () { return v; }); },
      function (e) { return MyPromise.resolve(typeof fn === 'function' ? fn() : undefined).then(function () { throw e; }); });
  };

  MyPromise.resolve = function (v) { if (v instanceof MyPromise) return v; return new MyPromise(function (r) { r(v); }); };
  MyPromise.reject = function (e) { return new MyPromise(function (_, rj) { rj(e); }); };

  function toArr(it) { return Array.prototype.slice.call(it); }

  MyPromise.all = function (items) {
    return new MyPromise(function (resolve, reject) {
      var arr = toArr(items), n = arr.length, out = new Array(n), left = n;
      if (n === 0) { resolve(out); return; }
      arr.forEach(function (x, i) {
        MyPromise.resolve(x).then(function (v) { out[i] = v; if (--left === 0) resolve(out); }, reject);
      });
    });
  };
  MyPromise.allSettled = function (items) {
    return new MyPromise(function (resolve) {
      var arr = toArr(items), n = arr.length, out = new Array(n), left = n;
      if (n === 0) { resolve(out); return; }
      arr.forEach(function (x, i) {
        MyPromise.resolve(x).then(
          function (v) { out[i] = { status: 'fulfilled', value: v }; if (--left === 0) resolve(out); },
          function (e) { out[i] = { status: 'rejected', reason: e }; if (--left === 0) resolve(out); });
      });
    });
  };
  MyPromise.race = function (items) {
    return new MyPromise(function (resolve, reject) {
      toArr(items).forEach(function (x) { MyPromise.resolve(x).then(resolve, reject); });
    });
  };
  MyPromise.any = function (items) {
    return new MyPromise(function (resolve, reject) {
      var arr = toArr(items), n = arr.length, errs = new Array(n), left = n;
      if (n === 0) { reject(new Error('All promises were rejected')); return; }
      arr.forEach(function (x, i) {
        MyPromise.resolve(x).then(resolve, function (e) { errs[i] = e; if (--left === 0) reject(new Error('All promises were rejected')); });
      });
    });
  };

  globalThis.Promise = MyPromise;
})();

// ---------------------------------------------------------------- timers
var __timers = [];
var __tid = 1;
function setTimeout(fn, ms) {
  var args = Array.prototype.slice.call(arguments, 2);
  var id = __tid++;
  __timers.push({ id: id, due: Date.now() + Math.max(0, Number(ms) || 0), fn: fn, args: args, every: -1 });
  return id;
}
function setInterval(fn, ms) {
  var args = Array.prototype.slice.call(arguments, 2);
  var id = __tid++;
  var every = Math.max(1, Number(ms) || 1);
  __timers.push({ id: id, due: Date.now() + every, fn: fn, args: args, every: every });
  return id;
}
function clearTimeout(id) {
  for (var i = 0; i < __timers.length; i++) if (__timers[i].id === id) { __timers.splice(i, 1); return; }
}
var clearInterval = clearTimeout;

function __nextDelay() {
  if (!__timers.length) return -1;
  var min = __timers[0].due;
  for (var i = 1; i < __timers.length; i++) if (__timers[i].due < min) min = __timers[i].due;
  return Math.max(0, min - Date.now());
}
function __runTimers() {
  var now = Date.now();
  var due = [], rest = [];
  for (var i = 0; i < __timers.length; i++) (__timers[i].due <= now ? due : rest).push(__timers[i]);
  __timers = rest;
  due.sort(function (a, b) { return a.due - b.due || a.id - b.id; });
  for (var j = 0; j < due.length; j++) {
    var t = due[j];
    if (t.every > 0) { t.due = Date.now() + t.every; __timers.push(t); }
    try { if (typeof t.fn === 'function') t.fn.apply(null, t.args); } catch (e) { }
    __drain();
  }
  return due.length;
}

// ---------------------------------------------------------------- fetch
var __fetchCbs = {};
var __fid = 1;

function __makeResponse(id, status, statusText, url, hdrs, text) {
  var lower = {};
  for (var k in hdrs) if (Object.prototype.hasOwnProperty.call(hdrs, k)) lower[String(k).toLowerCase()] = hdrs[k];
  var headers = {
    get: function (k) { k = String(k).toLowerCase(); return Object.prototype.hasOwnProperty.call(lower, k) ? lower[k] : null; },
    has: function (k) { return Object.prototype.hasOwnProperty.call(lower, String(k).toLowerCase()); },
    forEach: function (cb) { for (var n in lower) if (Object.prototype.hasOwnProperty.call(lower, n)) cb(lower[n], n); }
  };
  var resp = {
    ok: status >= 200 && status < 300,
    status: status,
    statusText: statusText || '',
    url: url,
    redirected: false,
    type: 'basic',
    headers: headers,
    bodyUsed: false,
    text: function () { return Promise.resolve(text); },
    json: function () { return new Promise(function (res, rej) { try { res(JSON.parse(text)); } catch (e) { rej(e); } }); },
    arrayBuffer: function () { return Promise.resolve(__nativeBytes(id)); },
    clone: function () { return __makeResponse(id, status, statusText, url, hdrs, text); }
  };
  return resp;
}

function __fetchDone(id, status, statusText, url, headersJson, text, err) {
  var cb = __fetchCbs[id];
  if (!cb) return;
  delete __fetchCbs[id];
  if (err) { cb.reject(new TypeError('Network request failed: ' + err)); return; }
  var hdrs = {};
  try { hdrs = JSON.parse(headersJson) || {}; } catch (e) { }
  cb.resolve(__makeResponse(id, status, statusText, url, hdrs, text));
}

function fetch(input, init) {
  return new Promise(function (resolve, reject) {
    var url = typeof input === 'string' ? input : (input && input.url ? input.url : String(input));
    init = init || {};
    var headers = {};
    var h = init.headers;
    if (h) {
      if (Array.isArray(h)) { h.forEach(function (p) { headers[p[0]] = String(p[1]); }); }
      else if (typeof h.forEach === 'function') { h.forEach(function (v, k) { headers[k] = String(v); }); }
      else { for (var k in h) if (Object.prototype.hasOwnProperty.call(h, k)) headers[k] = String(h[k]); }
    }
    var body = null;
    if (init.body !== undefined && init.body !== null) body = typeof init.body === 'string' ? init.body : String(init.body);
    var id = __fid++;
    __fetchCbs[id] = { resolve: resolve, reject: reject };
    try {
      __nativeFetch(id, String(url), String(init.method || 'GET').toUpperCase(), JSON.stringify(headers), body, String(init.redirect || 'follow'));
    } catch (e) {
      delete __fetchCbs[id];
      reject(new TypeError('Network request failed: ' + (e && e.message ? e.message : e)));
    }
  });
}

// ---------------------------------------------------------------- base64
(function () {
  var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  if (typeof globalThis.btoa !== 'function') {
    globalThis.btoa = function (input) {
      var str = String(input), out = '';
      for (var i = 0; i < str.length; i += 3) {
        var a = str.charCodeAt(i), b = str.charCodeAt(i + 1), c = str.charCodeAt(i + 2);
        if (a > 255 || b > 255 || c > 255) throw new Error('InvalidCharacterError');
        var n = (a << 16) | ((b || 0) << 8) | (c || 0);
        out += chars.charAt((n >> 18) & 63) + chars.charAt((n >> 12) & 63) +
          (i + 1 < str.length ? chars.charAt((n >> 6) & 63) : '=') +
          (i + 2 < str.length ? chars.charAt(n & 63) : '=');
      }
      return out;
    };
  }
  if (typeof globalThis.atob !== 'function') {
    globalThis.atob = function (input) {
      var str = String(input).replace(/[\t\n\f\r ]+/g, '').replace(/=+${'$'}/, '');
      var out = '', bits = 0, acc = 0;
      for (var i = 0; i < str.length; i++) {
        var v = chars.indexOf(str.charAt(i));
        if (v < 0) throw new Error('InvalidCharacterError');
        acc = (acc << 6) | v; bits += 6;
        if (bits >= 8) { bits -= 8; out += String.fromCharCode((acc >> bits) & 255); }
      }
      return out;
    };
  }
})();

// ---------------------------------------------------------------- scraper driver
var __state = { done: false, json: null, error: null };
function __isDone() { return __state.done === true; }
function __getJson() { return __state.json === null ? null : String(__state.json); }
function __getError() { return __state.error === null ? null : String(__state.error); }
function __fail(msg) { __state.error = String(msg); __state.done = true; }

function __start(tmdbId, mediaType, season, episode) {
  var fn = null;
  if (typeof globalThis.getStreams === 'function') fn = globalThis.getStreams;
  else if (module && module.exports && typeof module.exports.getStreams === 'function') fn = module.exports.getStreams;
  if (!fn) { __fail('getStreams non trovato nello scraper'); return; }
  var p;
  try { p = fn(tmdbId, mediaType, season, episode); } catch (e) { __fail(e && e.message ? e.message : e); return; }
  Promise.resolve(p).then(function (r) {
    try {
      var list = Array.isArray(r) ? r : (r && Array.isArray(r.streams) ? r.streams : []);
      __state.json = JSON.stringify(list);
    } catch (e) { __state.error = 'serializzazione: ' + (e && e.message ? e.message : e); }
    __state.done = true;
  }, function (e) { __fail(e && e.message ? e.message : e); });
}

"""
