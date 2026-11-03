/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.web

object WebScripts {
    val DRM_L3_ENFORCER_JS = """
        (function() {
            try {
                const host = window.location.hostname;
                if (host && (
                    host === 'youtube.com' || host.endsWith('.youtube.com') ||
                    host === 'googlevideo.com' || host.endsWith('.googlevideo.com') ||
                    host === 'youtube-nocookie.com' || host.endsWith('.youtube-nocookie.com')
                )) {
                    return;
                }
            } catch (_) {}

            if (typeof navigator === 'undefined' || !navigator.requestMediaKeySystemAccess) {
                return;
            }

            const patchKey = typeof Symbol !== 'undefined' && Symbol.for ? Symbol.for('__aab_drm_l3_enforced__') : '__aab_drm_l3_enforced__';
            if (navigator[patchKey]) return;

            const originalRequest = navigator.requestMediaKeySystemAccess;
            const patchedRequest = function requestMediaKeySystemAccess(keySystem, configs) {
                if (keySystem === 'com.widevine.alpha' && configs) {
                    try {
                        const newConfigs = Array.from(configs).map(config => {
                            const newConfig = Object.assign({}, config);
                            if (config.videoCapabilities) {
                                newConfig.videoCapabilities = Array.from(config.videoCapabilities).map(cap => {
                                    const newCap = Object.assign({}, cap);
                                    if (newCap.robustness && typeof newCap.robustness === 'string' && newCap.robustness.startsWith('HW_SECURE')) {
                                        newCap.robustness = 'SW_SECURE_DECODE';
                                    }
                                    return newCap;
                                });
                            }
                            if (config.audioCapabilities) {
                                newConfig.audioCapabilities = Array.from(config.audioCapabilities).map(cap => {
                                    const newCap = Object.assign({}, cap);
                                    if (newCap.robustness && typeof newCap.robustness === 'string' && newCap.robustness.startsWith('HW_SECURE')) {
                                        newCap.robustness = 'SW_SECURE_DECODE';
                                    }
                                    return newCap;
                                });
                            }
                            return newConfig;
                        });
                        return originalRequest.call(navigator, keySystem, newConfigs).catch(function() {
                            return originalRequest.call(navigator, keySystem, configs);
                        });
                    } catch (_) {
                        return originalRequest.call(navigator, keySystem, configs);
                    }
                }
                return originalRequest.call(navigator, keySystem, configs);
            };

            try {
                Object.defineProperty(patchedRequest, 'name', { value: 'requestMediaKeySystemAccess' });
                patchedRequest.toString = function() {
                    return 'function requestMediaKeySystemAccess() { [native code] }';
                };
            } catch (_) {}

            try {
                Object.defineProperty(navigator, patchKey, { value: true, enumerable: false, configurable: false });
                Object.defineProperty(navigator, 'requestMediaKeySystemAccess', {
                    value: patchedRequest,
                    writable: true,
                    configurable: true
                });
            } catch (_) {
                navigator.requestMediaKeySystemAccess = patchedRequest;
            }
        })();
    """.trimIndent()

    // Adapted from FermataX (YoutubeScripts ADBLOCK_CSS + NETWORK_ADBLOCK). Must run at document start so the
    // ytInitialPlayerResponse trap is installed before YouTube's inline script assigns it. Shorts shelves are
    // deliberately not hidden, and nothing here mutes, seeks or pauses the video.
    val YOUTUBE_AD_BLOCK_JS = """
        (function() {
          if (window.__aaBrowserYouTubeAdBlock) return;
          window.__aaBrowserYouTubeAdBlock = true;

          var cssId = 'aabrowser-youtube-adblock-css';
          var css = '.video-ads, .ytp-ad-player-overlay, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ' +
              'ytm-compact-promoted-video-renderer, ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ' +
              'ytm-companion-slot, #player-ads, #masthead-ad, .ytp-ad-module, .ytp-ad-overlay-container, ' +
              'ad-slot-renderer, [class*="ad-showing"] .ad-interrupting { display: none !important; } ' +
              '.html5-video-player.ad-showing video, video.ad-showing, .ad-showing .html5-main-video { opacity: 0 !important; }';
          function injectCss() {
            if (document.getElementById(cssId)) return;
            var head = document.head || document.documentElement;
            if (!head) return;
            var s = document.createElement('style');
            s.id = cssId;
            s.textContent = css;
            head.appendChild(s);
          }
          injectCss();
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', injectCss);

          var AD_FIELDS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'adParams'];
          var AD_RENDERERS = [
            'adSlotRenderer', 'displayAdRenderer', 'promotedVideoRenderer',
            'compactPromotedVideoRenderer', 'promotedSparklesWebRenderer',
            'promotedSparklesTextSearchRenderer', 'inFeedAdLayoutRenderer',
            'adsEngagementPanelRenderer', 'playerLegacyDesktopWatchAdsRenderer',
            'searchPyvRenderer', 'bannerPromoRenderer', 'statementBannerRenderer'
          ];
          function isAdItem(node) {
            if (!node || typeof node !== 'object') return false;
            for (var i = 0; i < AD_RENDERERS.length; i++) {
              if (Object.prototype.hasOwnProperty.call(node, AD_RENDERERS[i])) return true;
            }
            return false;
          }
          function strip(node, depth) {
            if (depth > 30 || !node || typeof node !== 'object') return node;
            if (Array.isArray(node)) {
              var out = [];
              for (var i = 0; i < node.length; i++) {
                if (!isAdItem(node[i])) out.push(strip(node[i], depth + 1));
              }
              return out;
            }
            for (var f = 0; f < AD_FIELDS.length; f++) {
              if (Object.prototype.hasOwnProperty.call(node, AD_FIELDS[f])) delete node[AD_FIELDS[f]];
            }
            for (var k in node) {
              if (Object.prototype.hasOwnProperty.call(node, k)) node[k] = strip(node[k], depth + 1);
            }
            return node;
          }
          function cleanBody(text) {
            if (typeof text !== 'string') return text;
            var hit = false;
            for (var i = 0; i < AD_FIELDS.length && !hit; i++) {
              if (text.indexOf('"' + AD_FIELDS[i] + '"') !== -1) hit = true;
            }
            for (var j = 0; j < AD_RENDERERS.length && !hit; j++) {
              if (text.indexOf('"' + AD_RENDERERS[j] + '"') !== -1) hit = true;
            }
            if (!hit) return text;
            try { return JSON.stringify(strip(JSON.parse(text), 0)); }
            catch (e) { return text; }
          }
          function guardGlobal(name) {
            var stored;
            try {
              if (Object.prototype.hasOwnProperty.call(window, name)) stored = strip(window[name], 0);
              Object.defineProperty(window, name, {
                configurable: true, enumerable: true,
                get: function() { return stored; },
                set: function(v) { stored = (v && typeof v === 'object') ? strip(v, 0) : v; }
              });
            } catch (e) {
              try { window[name] = strip(window[name], 0); } catch (e2) {}
            }
          }
          guardGlobal('ytInitialPlayerResponse');

          // Only the player endpoints are rewritten; browse/next (used by the Mixes tab) pass through untouched.
          var API = /\/youtubei\/v1\/(player|get_watch)/;
          var nativeFetch = window.fetch;
          if (nativeFetch) {
            window.fetch = function() {
              var args = arguments;
              var url = '';
              try {
                var a0 = args[0];
                url = (a0 && a0.url) ? a0.url : String(a0);
              } catch (e) { url = ''; }
              var p = nativeFetch.apply(this, args);
              if (!API.test(url)) return p;
              return p.then(function(res) {
                if (!res.ok) return res;
                return res.clone().text().then(function(body) {
                  var cleaned = cleanBody(body);
                  if (cleaned === body) return res;
                  return new Response(cleaned, { status: res.status, statusText: res.statusText, headers: res.headers });
                }).catch(function() { return res; });
              });
            };
          }
          try {
            var open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
              this.__aaBrowserUrl = url;
              return open.apply(this, arguments);
            };
            var send = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send = function() {
              var xhr = this;
              if (xhr.__aaBrowserUrl && API.test(String(xhr.__aaBrowserUrl))) {
                xhr.addEventListener('readystatechange', function() {
                  if (xhr.readyState !== 4) return;
                  try {
                    if (typeof xhr.responseText !== 'string') return;
                    var cleaned = cleanBody(xhr.responseText);
                    if (cleaned !== xhr.responseText) {
                      Object.defineProperty(xhr, 'responseText', { value: cleaned, configurable: true });
                      Object.defineProperty(xhr, 'response', { value: cleaned, configurable: true });
                    }
                  } catch (e) {}
                });
              }
              return send.apply(this, arguments);
            };
          } catch (e) {}
        })();
    """.trimIndent()

    val YOUTUBE_AD_BLOCK_ORIGINS = setOf("https://youtube.com", "https://*.youtube.com")
}
