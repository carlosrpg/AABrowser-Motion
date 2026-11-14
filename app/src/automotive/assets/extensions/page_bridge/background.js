// WebView's addDocumentStartJavaScript for GeckoView: the app sends {type: "register", key, code, matches} or
// {type: "unregister", key}; the code then runs in the page's own world before the page's scripts, on every load.
// Injected by the browser, so the page's CSP does not apply (it would block an eval of the same code).
const NATIVE_APP = "motionPageScripts";
const registered = new Map();

function connect() {
    let port;
    try {
        port = browser.runtime.connectNative(NATIVE_APP);
    } catch (e) {
        setTimeout(connect, 1000);
        return;
    }
    port.onMessage.addListener(async (msg) => {
        if (!msg || !msg.key) return;
        const old = registered.get(msg.key);
        registered.delete(msg.key);
        if (old) {
            try { await old.unregister(); } catch (_) {}
        }
        if (msg.type !== "register") return;
        try {
            registered.set(msg.key, await browser.contentScripts.register({
                js: [{ code: msg.code }],
                matches: msg.matches,
                runAt: "document_start",
                world: "MAIN"
            }));
        } catch (e) {
            port.postMessage({ type: "error", key: msg.key, message: String(e) });
        }
    });
    port.onDisconnect.addListener(() => setTimeout(connect, 1000));
}

connect();
