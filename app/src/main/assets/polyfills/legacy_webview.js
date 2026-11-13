// Polyfills for built-ins newer than the car's system WebView (Chromium 95). Sites ship code that calls them directly
// (Disney+'s player calls AbortSignal#throwIfAborted before every request), and on an old WebView that TypeError is
// often swallowed, leaving players half-initialized. Each one is defined only when missing.
(function () {
  'use strict';
  function define(target, name, value) {
    if (!target || typeof target[name] === 'function') return;
    try {
      Object.defineProperty(target, name, { value: value, writable: true, configurable: true, enumerable: false });
    } catch (_) {}
  }
  function toObject(value) {
    if (value == null) throw new TypeError('Cannot convert undefined or null to object');
    return Object(value);
  }
  function lengthOf(o) {
    var n = Math.trunc(Number(o.length)) || 0;
    return Math.min(Math.max(n, 0), Number.MAX_SAFE_INTEGER);
  }

  // AbortSignal (Chrome 100 / 103 / 116)
  if (typeof AbortSignal !== 'undefined') {
    define(AbortSignal.prototype, 'throwIfAborted', function () {
      if (this.aborted) {
        throw this.reason !== undefined ? this.reason : new DOMException('signal is aborted without reason', 'AbortError');
      }
    });
    define(AbortSignal, 'timeout', function (ms) {
      var controller = new AbortController();
      setTimeout(function () {
        controller.abort(new DOMException('signal timed out', 'TimeoutError'));
      }, ms);
      return controller.signal;
    });
    define(AbortSignal, 'any', function (signals) {
      var controller = new AbortController();
      var list = Array.from(signals);
      for (var i = 0; i < list.length; i++) {
        if (list[i].aborted) {
          controller.abort(list[i].reason);
          return controller.signal;
        }
      }
      list.forEach(function (signal) {
        signal.addEventListener('abort', function () {
          if (!controller.signal.aborted) controller.abort(signal.reason);
        }, { once: true });
      });
      return controller.signal;
    });
  }

  // Array (Chrome 97 / 110)
  define(Array.prototype, 'findLast', function (predicate, thisArg) {
    var o = toObject(this);
    for (var i = lengthOf(o) - 1; i >= 0; i--) {
      if (predicate.call(thisArg, o[i], i, o)) return o[i];
    }
    return undefined;
  });
  define(Array.prototype, 'findLastIndex', function (predicate, thisArg) {
    var o = toObject(this);
    for (var i = lengthOf(o) - 1; i >= 0; i--) {
      if (predicate.call(thisArg, o[i], i, o)) return i;
    }
    return -1;
  });
  define(Array.prototype, 'toSorted', function (compare) {
    return Array.prototype.slice.call(toObject(this)).sort(compare);
  });
  define(Array.prototype, 'toReversed', function () {
    return Array.prototype.slice.call(toObject(this)).reverse();
  });
  define(Array.prototype, 'toSpliced', function () {
    var copy = Array.prototype.slice.call(toObject(this));
    Array.prototype.splice.apply(copy, arguments);
    return copy;
  });
  define(Array.prototype, 'with', function (index, value) {
    var copy = Array.prototype.slice.call(toObject(this));
    var i = Math.trunc(Number(index)) || 0;
    if (i < 0) i += copy.length;
    if (i < 0 || i >= copy.length) throw new RangeError('Invalid index : ' + index);
    copy[i] = value;
    return copy;
  });

  // groupBy (Chrome 117)
  define(Object, 'groupBy', function (items, callback) {
    var out = Object.create(null);
    var i = 0;
    for (var item of items) {
      var key = callback(item, i++);
      (out[key] || (out[key] = [])).push(item);
    }
    return out;
  });
  define(Map, 'groupBy', function (items, callback) {
    var out = new Map();
    var i = 0;
    for (var item of items) {
      var key = callback(item, i++);
      if (!out.has(key)) out.set(key, []);
      out.get(key).push(item);
    }
    return out;
  });

  // Promise.withResolvers (Chrome 119)
  define(Promise, 'withResolvers', function () {
    var result = {};
    result.promise = new this(function (resolve, reject) {
      result.resolve = resolve;
      result.reject = reject;
    });
    return result;
  });

  // Set methods (Chrome 122); "other" must be set-like (has/keys).
  function keysOf(other) {
    return typeof other.keys === 'function' ? Array.from(other.keys()) : Array.from(other);
  }
  define(Set.prototype, 'union', function (other) {
    var out = new Set(this);
    keysOf(other).forEach(function (v) { out.add(v); });
    return out;
  });
  define(Set.prototype, 'intersection', function (other) {
    var out = new Set();
    this.forEach(function (v) { if (other.has(v)) out.add(v); });
    return out;
  });
  define(Set.prototype, 'difference', function (other) {
    var out = new Set();
    this.forEach(function (v) { if (!other.has(v)) out.add(v); });
    return out;
  });
  define(Set.prototype, 'symmetricDifference', function (other) {
    var self = this;
    var out = new Set(this);
    keysOf(other).forEach(function (v) { if (self.has(v)) out.delete(v); else out.add(v); });
    return out;
  });
  define(Set.prototype, 'isSubsetOf', function (other) {
    var ok = true;
    this.forEach(function (v) { if (!other.has(v)) ok = false; });
    return ok;
  });
  define(Set.prototype, 'isSupersetOf', function (other) {
    var self = this;
    return keysOf(other).every(function (v) { return self.has(v); });
  });
  define(Set.prototype, 'isDisjointFrom', function (other) {
    var ok = true;
    this.forEach(function (v) { if (other.has(v)) ok = false; });
    return ok;
  });

  // Element#checkVisibility (Chrome 105)
  if (typeof Element !== 'undefined') {
    define(Element.prototype, 'checkVisibility', function (options) {
      if (!this.isConnected) return false;
      for (var e = this; e; e = e.parentElement) {
        var style = getComputedStyle(e);
        if (style.display === 'none') return false;
        if (options && (options.opacityProperty || options.checkOpacity) && style.opacity === '0') return false;
      }
      var own = getComputedStyle(this);
      if (options && (options.visibilityProperty || options.checkVisibilityCSS) && own.visibility !== 'visible') return false;
      return true;
    });
  }
})();
