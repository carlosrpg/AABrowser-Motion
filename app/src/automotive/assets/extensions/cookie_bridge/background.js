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
        if (!msg) return;
        try {
            if (msg.type === "dump") {
                const cookies = await browser.cookies.getAll({});
                port.postMessage({ type: "cookies", id: msg.id, cookies: cookies });
            } else if (msg.type === "import") {
                port.postMessage({ type: "imported", id: msg.id, count: await importCookies(msg.cookies || []) });
            }
        } catch (e) {
            port.postMessage({ type: "error", id: msg.id, message: String(e) });
        }
    });

    port.onDisconnect.addListener(() => setTimeout(connect, 1000));
}

// WebView only exposes name=value per URL. A cookie Gecko already has keeps its own attributes and only takes the
// WebView's (fresher, rotated) value; a missing one is created on the site's parent domain (host-only for __Host-).
async function importCookies(list) {
    let count = 0;
    for (const c of list) {
        try {
            const existing = await browser.cookies.getAll({ url: c.url, name: c.name });
            if (existing.length) {
                for (const e of existing) {
                    if (e.value === c.value) continue;
                    await browser.cookies.set({
                        url: (e.secure ? "https://" : "http://") + e.domain.replace(/^\./, "") + e.path,
                        name: e.name, value: c.value, path: e.path,
                        domain: e.hostOnly ? undefined : e.domain,
                        secure: e.secure, httpOnly: e.httpOnly, sameSite: e.sameSite,
                        expirationDate: e.session ? undefined : e.expirationDate
                    });
                    count++;
                }
            } else {
                const host = new URL(c.url).hostname;
                await browser.cookies.set({
                    url: c.url, name: c.name, value: c.value, path: "/", secure: true,
                    domain: c.name.startsWith("__Host-") ? undefined : "." + host.split(".").slice(-2).join("."),
                    expirationDate: Math.floor(Date.now() / 1000) + 365 * 24 * 3600
                });
                count++;
            }
        } catch (e) {
            console.warn("cookie import failed for " + c.name + ": " + e);
        }
    }
    return count;
}

connect();
