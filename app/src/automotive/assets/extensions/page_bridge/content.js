// WebView's evaluateJavascript for GeckoView (see GeckoPage): the app sends {id, code} and gets back the JSON of the
// code's value. Page CSP (YouTube's) blocks the page's own eval, so the code runs here, in this isolated context,
// with the page's window, document, navigator and location in scope as waived wrappers, so page-defined APIs
// (YouTube's player methods, ytcfg) are reachable. Page code may not call functions defined here back: the app's
// scripts iterate page lists with Array.from and skip what needs that (they test for __motionGecko).
var __motionGecko = true;
const page = window.wrappedJSObject;
const NATIVE_APP = "motionPage";

function run(code) {
    const fn = new Function("window", "document", "navigator", "location", "return eval(arguments[4]);");
    return fn(page, page.document, page.navigator, page.location, code);
}

function onMessage(msg) {
    if (!msg || msg.type !== "eval") return;
    let result = "null";
    let error = null;
    try {
        const value = run(msg.code);
        if (value !== undefined) result = JSON.stringify(value) ?? "null";
    } catch (e) {
        error = String(e);
    }
    if (msg.reply) port.postMessage({ type: "result", id: msg.id, result: result, error: error });
}

// The app registers its listener per tab; retry briefly in case this page started before it did.
let port = null;
let attempts = 0;
function connect() {
    port = browser.runtime.connectNative(NATIVE_APP);
    port.onMessage.addListener(onMessage);
    port.onDisconnect.addListener(() => {
        port = null;
        if (++attempts < 10) setTimeout(connect, 500);
    });
}
connect();
