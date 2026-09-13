import { DurableObject } from "cloudflare:workers";
import { active, DAY, digest, MAX_BLOB, retention, secret, validID } from "./rules";
interface Env { VAULT: DurableObjectNamespace<Vault>; BLOBS: R2Bucket; BOOTSTRAP_TOKEN: string }
type Row = Record<string, SqlStorageValue>;
const json = (body: unknown, status = 200) => Response.json(body, { status });
class HTTPError extends Error { constructor(message: string, readonly status: number) { super(message); } }
function fail(message: string, status = 400): never { throw new HTTPError(message, status); }
async function readBody(request: Request, limit: number): Promise<Uint8Array> {
  const reader = request.body?.getReader();
  if (!reader) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > limit) { await reader.cancel(); fail("Request too large.", 413); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.byteLength; }
  return result;
}
export default { fetch(request: Request, env: Env) { return env.VAULT.get(env.VAULT.idFromName("personal")).fetch(request); } };

export class Vault extends DurableObject<Env> {
  private sql: SqlStorage;
  private uploads = new Set<string>();
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env); this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY CHECK(id=1), days INTEGER NOT NULL, revision INTEGER NOT NULL, keyId TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, token TEXT NOT NULL UNIQUE, publicKey TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS keys (deviceId TEXT NOT NULL, keyId TEXT NOT NULL, envelope TEXT NOT NULL, PRIMARY KEY(deviceId,keyId));
      CREATE TABLE IF NOT EXISTS invitations (token TEXT PRIMARY KEY, expires INTEGER NOT NULL, keyId TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS items (id TEXT PRIMARY KEY, deviceId TEXT NOT NULL, keyId TEXT NOT NULL, createdAt INTEGER NOT NULL, size INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS deleted (id TEXT PRIMARY KEY, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS garbage (id TEXT PRIMARY KEY);
      CREATE TABLE IF NOT EXISTS enrollments (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL);
    `);
  }
  private rows(query: string, ...args: SqlStorageValue[]): Row[] { return this.sql.exec(query, ...args).toArray(); }
  private settings() { return this.rows("SELECT * FROM settings WHERE id=1")[0]; }
  private changed() {
    this.sql.exec("UPDATE settings SET revision=revision+1 WHERE id=1");
    for (const socket of this.ctx.getWebSockets()) { try { socket.send('{"type":"changed"}'); } catch { socket.close(); } }
  }
  private async schedule() { await this.ctx.storage.setAlarm(Date.now() + 60_000); }
  private envelope(v: any): string {
    if (!v || typeof v.ephemeralPublicKey !== "string" || v.ephemeralPublicKey.length !== 88 || typeof v.nonce !== "string" || v.nonce.length !== 16 || typeof v.ciphertext !== "string" || v.ciphertext.length !== 64) fail("Invalid key envelope.");
    return JSON.stringify(v);
  }
  private registration(b: Record<string, any>) {
    if (typeof b.name !== "string" || !b.name.trim() || b.name.length > 80) fail("Give the device a name.");
    if (typeof b.publicKey !== "string" || b.publicKey.length !== 88 || !validID(b.keyId)) fail("Invalid device key.");
    return this.envelope(b.envelope);
  }
  private async enrollment(b: Record<string, any>, path: string) {
    if (!validID(b.enrollmentId) || typeof b.deviceToken !== "string" || !/^[a-f0-9]{64}$/.test(b.deviceToken)) fail("Update Zap to enroll this device.");
    const canonical = (value: any): any => Array.isArray(value) ? value.map(canonical) : value && typeof value === "object" ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
    return { id: b.enrollmentId as string, token: b.deviceToken as string, hash: await digest(b.deviceToken), fingerprint: await digest(JSON.stringify([path, canonical(b)])) };
  }
  private replay(enrollment: { id: string; fingerprint: string }): boolean {
    const previous = this.rows("SELECT fingerprint FROM enrollments WHERE id=?", enrollment.id)[0];
    if (!previous) return false;
    if (previous.fingerprint !== enrollment.fingerprint) fail("Enrollment changed. Use the original request.", 409);
    if (!this.rows("SELECT id FROM devices WHERE id=?", enrollment.id)[0]) fail("This enrollment was revoked. Create a new pairing code.", 401);
    return true;
  }
  async fetch(request: Request): Promise<Response> {
    try { return await this.route(request); }
    catch (error) {
      if (error instanceof HTTPError) return json({ error: error.message }, error.status);
      if (error instanceof SyntaxError) return json({ error: "Invalid JSON." }, 400);
      console.error("Request failed", error instanceof Error ? error.name : "unknown");
      return json({ error: "Sync failed. Try again." }, 500);
    }
  }
  private async route(request: Request): Promise<Response> {
    const url = new URL(request.url), path = url.pathname, method = request.method;
    if (path === "/health") return json({ service: "zap" });
    const body = async (): Promise<Record<string, any>> => {
      const value = JSON.parse(new TextDecoder().decode(await readBody(request, 64_000)));
      if (!value || typeof value !== "object" || Array.isArray(value)) fail("Expected a JSON object.");
      return value;
    };
    if (path === "/v1/bootstrap" && method === "POST") {
      const b = await body(), envelope = this.registration(b), enrollment = await this.enrollment(b, path);
      const { id, token, hash, fingerprint } = enrollment;
      if (this.replay(enrollment)) return json({ deviceId: id, token, keyId: b.keyId });
      const provided = request.headers.get("Authorization")?.replace(/^Bearer /, "") || "";
      if (!this.env.BOOTSTRAP_TOKEN || await digest(provided) !== await digest(this.env.BOOTSTRAP_TOKEN)) fail("Invalid setup token.", 401);
      this.ctx.storage.transactionSync(() => {
        if (this.replay(enrollment)) return;
        if (this.settings()) fail("This deployment is already initialized.", 409);
        this.sql.exec("INSERT INTO settings VALUES(1,10,1,?)", b.keyId);
        this.sql.exec("INSERT INTO devices VALUES(?,?,?,?)", id, b.name, hash, b.publicKey);
        this.sql.exec("INSERT INTO keys VALUES(?,?,?)", id, b.keyId, envelope);
        this.sql.exec("INSERT INTO enrollments VALUES(?,?)", id, fingerprint);
      });
      await this.schedule(); return json({ deviceId: id, token, keyId: b.keyId });
    }
    if (path === "/v1/pair" && method === "POST") {
      const b = await body(), envelope = this.registration(b), invitationHash = await digest(String(b.token)), enrollment = await this.enrollment(b, path);
      const { id, token, hash, fingerprint } = enrollment;
      if (this.replay(enrollment)) return json({ deviceId: id, token, keyId: b.keyId });
      this.ctx.storage.transactionSync(() => {
        if (this.replay(enrollment)) return;
        const invite = this.rows("SELECT * FROM invitations WHERE token=?", invitationHash)[0];
        if (!invite || Number(invite.expires) < Date.now() || invite.keyId !== b.keyId || this.settings().keyId !== b.keyId) fail("Pairing code expired. Create a new one on Mac.", 401);
        this.sql.exec("DELETE FROM invitations WHERE token=?", invitationHash);
        this.sql.exec("INSERT INTO devices VALUES(?,?,?,?)", id, b.name, hash, b.publicKey);
        this.sql.exec("INSERT INTO keys VALUES(?,?,?)", id, b.keyId, envelope);
        if (b.historyEnvelopes) for (const [keyId, wrapped] of Object.entries(b.historyEnvelopes)) {
          if (!validID(keyId)) fail("Invalid history key.");
          this.sql.exec("INSERT OR IGNORE INTO keys VALUES(?,?,?)", id, keyId, this.envelope(wrapped));
        }
        this.sql.exec("INSERT INTO enrollments VALUES(?,?)", id, fingerprint);
      });
      this.changed(); return json({ deviceId: id, token, keyId: b.keyId });
    }
    const token = request.headers.get("Authorization")?.replace(/^Bearer /, "") || "";
    const device = this.rows("SELECT * FROM devices WHERE token=?", await digest(token))[0];
    if (!device) fail("Device is not connected. Pair it again.", 401);
    const settings = this.settings();
    if (path === "/v1/events" && request.headers.get("Upgrade")?.toLowerCase() === "websocket") {
      const pair = new WebSocketPair(); this.ctx.acceptWebSocket(pair[1], [String(device.id)]);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (path === "/v1/sync" && method === "GET") {
      const cursor = Number(url.searchParams.get("cursor") || -1);
      return json({ cursor: settings.revision, days: settings.days, keyId: settings.keyId,
        keys: this.rows("SELECT keyId,envelope FROM keys WHERE deviceId=?", device.id).map(k => ({ keyId: k.keyId, envelope: JSON.parse(String(k.envelope)) })),
        devices: this.rows("SELECT id,name,publicKey FROM devices ORDER BY name"),
        items: cursor === settings.revision ? null : this.rows("SELECT * FROM items WHERE createdAt>? ORDER BY createdAt DESC", Date.now() - Number(settings.days) * DAY) });
    }
    if (path === "/v1/invitations" && method === "POST") {
      const token = secret(); this.sql.exec("INSERT INTO invitations VALUES(?,?,?)", await digest(token), Date.now() + 300_000, settings.keyId); return json({ token });
    }
    if (path === "/v1/settings" && method === "PUT") {
      let days: number; try { days = retention((await body()).days); } catch { fail("History must be between 1 and 365 days."); }
      this.sql.exec("UPDATE settings SET days=? WHERE id=1", days); this.changed(); await this.alarm(); return json({ ok: true });
    }
    if (path === "/v1/rotate" && method === "POST") {
      const b = await body();
      if (!validID(b.keyId) || b.keyId === settings.keyId || b.removeDevice === device.id) fail("Invalid device removal.");
      this.ctx.storage.transactionSync(() => {
        if (!this.rows("SELECT id FROM devices WHERE id=?", b.removeDevice)[0]) fail("Device not found.", 404);
        for (const d of this.rows("SELECT id FROM devices WHERE id<>?", b.removeDevice)) this.sql.exec("INSERT OR REPLACE INTO keys VALUES(?,?,?)", d.id, b.keyId, this.envelope(b.envelopes?.[String(d.id)]));
        this.sql.exec("DELETE FROM devices WHERE id=?", b.removeDevice);
        this.sql.exec("DELETE FROM keys WHERE deviceId=?", b.removeDevice);
        this.sql.exec("DELETE FROM invitations"); this.sql.exec("UPDATE settings SET keyId=? WHERE id=1", b.keyId);
      });
      for (const socket of this.ctx.getWebSockets(b.removeDevice)) socket.close(1008, "Device removed");
      this.changed(); return json({ ok: true });
    }
    if (path === "/v1/devices/me" && method === "DELETE") {
      const last = !this.rows("SELECT id FROM devices WHERE id<>?", device.id).length;
      this.ctx.storage.transactionSync(() => {
        this.sql.exec("DELETE FROM devices WHERE id=?", device.id);
        this.sql.exec("DELETE FROM keys WHERE deviceId=?", device.id);
        this.sql.exec("DELETE FROM invitations");
        if (last) { for (const item of this.rows("SELECT id FROM items")) this.remove(String(item.id)); this.sql.exec("DELETE FROM settings"); }
      });
      for (const socket of this.ctx.getWebSockets(String(device.id))) socket.close(1008, "Device disconnected");
      if (!last) this.changed();
      await this.schedule(); return json({ ok: true });
    }
    if (path === "/v1/items" && method === "DELETE") {
      for (const item of this.rows("SELECT id FROM items")) this.remove(String(item.id));
      this.changed(); await this.schedule(); return json({ ok: true });
    }
    const match = path.match(/^\/v1\/items\/([a-zA-Z0-9_-]{16,100})$/);
    if (match) {
      const id = match[1];
      if (method === "GET") {
        const item = this.rows("SELECT * FROM items WHERE id=?", id)[0];
        if (!item || !active(Number(item.createdAt), Number(settings.days))) fail("Item expired or was deleted.", 404);
        const blob = await this.env.BLOBS.get(id); if (!blob) fail("Content unavailable. Try again.", 503);
        return new Response(blob.body, { headers: { "Content-Type": "application/octet-stream", "Cache-Control": "no-store" } });
      }
      if (method === "DELETE") { this.remove(id); this.changed(); await this.schedule(); return json({ ok: true }); }
      if (method === "PUT") {
        if (this.rows("SELECT id FROM deleted WHERE id=?", id)[0]) fail("Item was deleted.", 410);
        if (this.uploads.has(id) || this.rows("SELECT id FROM garbage WHERE id=?", id).length) fail("Item is busy. Retry shortly.", 409);
        this.uploads.add(id);
        try {
          const createdAt = Number(request.headers.get("X-Created-At")), keyId = request.headers.get("X-Key-Id") || "";
          if (!active(createdAt, Number(settings.days))) fail("Item has expired.", 410);
          if (keyId !== settings.keyId) fail("Refresh encryption keys and retry.", 409);
          if (this.rows("SELECT id FROM items WHERE id=?", id)[0]) return json({ ok: true });
          const length = Number(request.headers.get("Content-Length"));
          if (!length || length > MAX_BLOB || length < 28 || !request.body) fail("Content is too large or empty.", 413);
          const stream = new FixedLengthStream(length), upload = this.env.BLOBS.put(id, stream.readable);
          const sent = await request.body.pipeTo(stream.writable).then(() => true, () => false);
          if (!sent) { await upload.catch(() => {}); fail("Invalid content size.", 413); }
          await upload;
          if (this.rows("SELECT id FROM deleted WHERE id=?", id)[0] || this.settings().keyId !== keyId || !this.rows("SELECT id FROM devices WHERE id=?", device.id)[0] || !active(createdAt, Number(this.settings().days))) {
            this.sql.exec("INSERT OR IGNORE INTO garbage VALUES(?)", id); await this.schedule(); fail("State changed. Refresh and retry.", 409);
          }
          this.sql.exec("INSERT OR IGNORE INTO items VALUES(?,?,?,?,?)", id, device.id, keyId, createdAt, length);
          this.changed(); return json({ ok: true });
        } finally { this.uploads.delete(id); }
      }
    }
    return json({ error: "Not found." }, 404);
  }
  private remove(id: string) {
    this.sql.exec("INSERT OR REPLACE INTO deleted VALUES(?,?)", id, Date.now() + 366 * DAY);
    this.sql.exec("INSERT OR IGNORE INTO garbage VALUES(?)", id); this.sql.exec("DELETE FROM items WHERE id=?", id);
  }
  async alarm() {
    const settings = this.settings();
    if (settings) {
      const expired = this.rows("SELECT id FROM items WHERE createdAt<=?", Date.now() - Number(settings.days) * DAY);
      for (const item of expired) this.remove(String(item.id));
      if (expired.length) this.changed();
    }
    this.sql.exec("DELETE FROM invitations WHERE expires<?", Date.now()); this.sql.exec("DELETE FROM deleted WHERE expires<?", Date.now());
    for (const row of this.rows("SELECT id FROM garbage LIMIT 100")) {
      if (this.uploads.has(String(row.id))) continue;
      await this.env.BLOBS.delete(String(row.id)); this.sql.exec("DELETE FROM garbage WHERE id=?", row.id);
    }
    const cursor = await this.ctx.storage.get<string>("blobScanCursor");
    const page = await this.env.BLOBS.list({ limit: 1000, cursor });
    for (const blob of page.objects) {
      if (blob.uploaded.getTime() < Date.now() - 3_600_000 && !this.uploads.has(blob.key) && !this.rows("SELECT id FROM items WHERE id=?", blob.key)[0]) {
        this.sql.exec("INSERT OR IGNORE INTO garbage VALUES(?)", blob.key);
        await this.env.BLOBS.delete(blob.key);
        this.sql.exec("DELETE FROM garbage WHERE id=?", blob.key);
      }
    }
    if (page.truncated) await this.ctx.storage.put("blobScanCursor", page.cursor);
    else await this.ctx.storage.delete("blobScanCursor");
    const pending = this.rows("SELECT id FROM garbage LIMIT 1").length > 0;
    // An abandoned deployment stops waking up once its storage is collected.
    if (settings || pending || page.truncated) await this.ctx.storage.setAlarm(Date.now() + (pending ? 60_000 : 3_600_000));
  }
  webSocketMessage(socket: WebSocket, message: string | ArrayBuffer) { if (message === "ping") socket.send("pong"); }
}
