import { test } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { DAY } from "../src/rules";

test("vault pairing, retries, deletion, expiry, and revocation", async () => {
  const bundle = await build({ entryPoints: ["src/index.ts"], bundle: true, write: false, format: "esm", platform: "neutral", external: ["cloudflare:workers"] });
  const mf = new Miniflare({ modules: true, script: bundle.outputFiles[0].text, compatibilityDate: "2025-09-01", durableObjects: { VAULT: { className: "Vault", useSQLite: true } }, r2Buckets: ["BLOBS"], bindings: { BOOTSTRAP_TOKEN: "test-setup" } });
  try {
    const envelope = { ephemeralPublicKey: "A".repeat(88), nonce: "A".repeat(16), ciphertext: "A".repeat(64) };
    const keyId = crypto.randomUUID();
    const registration = { name: "Mac", keyId, publicKey: "A".repeat(88), envelope };
    const call = (path: string, method = "GET", token = "", body?: unknown, headers = {}) => mf.dispatchFetch("https://zap.test" + path, { method, headers: { Authorization: `Bearer ${token}`, ...headers }, body: body === undefined ? undefined : body instanceof Uint8Array ? body : JSON.stringify(body) });
    assert.equal((await call("/v1/sync")).status, 401);
    const boot = await call("/v1/bootstrap", "POST", "test-setup", registration);
    assert.equal(boot.status, 200);
    const mac = await boot.json() as any;
    assert.equal((await call("/v1/bootstrap", "POST", "test-setup", registration)).status, 409);
    const invitation = await (await call("/v1/invitations", "POST", mac.token)).json() as any;
    const pairBody = { ...registration, name: "Android", token: invitation.token };
    const phone = await (await call("/v1/pair", "POST", "", pairBody)).json() as any;
    assert.ok(phone.token);
    assert.equal((await call("/v1/pair", "POST", "", pairBody)).status, 401);
    const id = crypto.randomUUID(), blob = crypto.getRandomValues(new Uint8Array(64));
    const headers = { "X-Key-Id": keyId, "X-Created-At": String(Date.now()), "Content-Length": "64" };
    assert.equal((await call(`/v1/items/${id}`, "PUT", mac.token, blob, headers)).status, 200);
    assert.equal((await call(`/v1/items/${id}`, "PUT", mac.token, blob, headers)).status, 200);
    const state = await (await call("/v1/sync", "GET", phone.token)).json() as any;
    assert.equal(state.items.length, 1);
    assert.deepEqual(new Uint8Array(await (await call(`/v1/items/${id}`, "GET", phone.token)).arrayBuffer()), blob);
    assert.equal((await (await call(`/v1/sync?cursor=${state.cursor}`, "GET", phone.token)).json() as any).items, null);
    assert.equal((await call(`/v1/items/${id}`, "DELETE", phone.token)).status, 200);
    assert.equal((await call(`/v1/items/${id}`, "PUT", mac.token, blob, headers)).status, 410);
    const oldID = crypto.randomUUID();
    assert.equal((await call(`/v1/items/${oldID}`, "PUT", mac.token, blob, { ...headers, "X-Created-At": String(Date.now() - 11 * DAY) })).status, 410);
    const freshID = crypto.randomUUID();
    await call(`/v1/items/${freshID}`, "PUT", mac.token, blob, { ...headers, "X-Created-At": String(Date.now() - 2 * DAY) });
    await call("/v1/settings", "PUT", mac.token, { days: 1 });
    assert.equal((await call(`/v1/items/${freshID}`, "GET", mac.token)).status, 404);
    const nextKey = crypto.randomUUID();
    assert.equal((await call("/v1/rotate", "POST", mac.token, { removeDevice: phone.deviceId, keyId: nextKey, envelopes: { [mac.deviceId]: envelope } })).status, 200);
    assert.equal((await call("/v1/sync", "GET", phone.token)).status, 401);
    assert.equal((await call(`/v1/items/${crypto.randomUUID()}`, "PUT", mac.token, blob, headers)).status, 409);
  } finally { await mf.dispose(); }
});
