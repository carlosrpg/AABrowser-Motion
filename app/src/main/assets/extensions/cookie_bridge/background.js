// Native side: GeckoEngine registers the "motionCookies" message delegate. The background page can start
// before that delegate exists, so reconnect until the app accepts the port.
const NATIVE_APP = "motionCookies";

function connect() {
    let port;
    try {
        port = browser.runtime.connectNative(NATIVE_APP);
    } catch (e) {
        setTimeout(connect, 1000);
        return;
    }

    port.onMessage.addListener(async (msg) => {
        if (!msg || msg.type !== "dump") return;
        try {
            const cookies = await browser.cookies.getAll({});
            port.postMessage({ type: "cookies", id: msg.id, cookies: cookies });
        } catch (e) {
            port.postMessage({ type: "error", id: msg.id, message: String(e) });
        }
    });

    port.onDisconnect.addListener(() => setTimeout(connect, 1000));
}

connect();
