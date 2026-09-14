import { DurableObject } from "cloudflare:workers";
import { active, DAY, digest, MAX_BLOB, retention, secret, validID } from "./rules";

interface Env {
  VAULT: DurableObjectNamespace<Vault>;
  BLOBS: R2Bucket;
  BOOTSTRAP_TOKEN: string;
}

type Row = Record<string, SqlStorageValue>;
type JSONObject = Record<string, any>;

interface Enrollment {
  id: string;
  token: string;
  hash: string;
  fingerprint: string;
}

const MAX_JSON_BODY = 64_000;
const DEFAULT_RETENTION_DAYS = 10;
const INVITATION_LIFETIME = 5 * 60_000;
/** Longer than the maximum retention, so an offline client can never resurrect a deleted item. */
const TOMBSTONE_LIFETIME = 366 * DAY;
/** Unreferenced blobs younger than this may belong to an upload that is still finishing. */
const ORPHAN_BLOB_AGE = 60 * 60_000;
/** AES-GCM output is at least a 12-byte nonce plus a 16-byte tag. */
const MIN_SEALED_SIZE = 28;
const ALARM_SOON = 60_000;
const ALARM_IDLE = 60 * 60_000;
const ITEM_PATH = /^\/v1\/items\/([a-zA-Z0-9_-]{16,100})$/;

const SCHEMA = `
  CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY CHECK(id=1), days INTEGER NOT NULL, revision INTEGER NOT NULL, keyId TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, token TEXT NOT NULL UNIQUE, publicKey TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS keys (deviceId TEXT NOT NULL, keyId TEXT NOT NULL, envelope TEXT NOT NULL, PRIMARY KEY(deviceId,keyId));
  CREATE TABLE IF NOT EXISTS invitations (token TEXT PRIMARY KEY, expires INTEGER NOT NULL, keyId TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS items (id TEXT PRIMARY KEY, deviceId TEXT NOT NULL, keyId TEXT NOT NULL, createdAt INTEGER NOT NULL, size INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS deleted (id TEXT PRIMARY KEY, expires INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS garbage (id TEXT PRIMARY KEY);
  CREATE TABLE IF NOT EXISTS enrollments (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL);
`;

class HTTPError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

function fail(message: string, status = 400): never {
  throw new HTTPError(message, status);
}

const json = (body: unknown, status = 200) => Response.json(body, { status });
const ok = () => json({ ok: true });

function bearerToken(request: Request): string {
  return request.headers.get("Authorization")?.replace(/^Bearer /, "") || "";
}

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
      if (size > limit) {
        await reader.cancel();
        fail("Request too large.", 413);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

async function readJSONObject(request: Request): Promise<JSONObject> {
  const value = JSON.parse(new TextDecoder().decode(await readBody(request, MAX_JSON_BODY)));
  if (!value || typeof value !== "object" || Array.isArray(value)) fail("Expected a JSON object.");
  return value;
}

/** Validates a wrapped key (base64 P-256 point, 12-byte nonce, 32-byte key plus tag) and serializes it for storage. */
function serializeEnvelope(value: any): string {
  const valid = value
    && typeof value.ephemeralPublicKey === "string" && value.ephemeralPublicKey.length === 88
    && typeof value.nonce === "string" && value.nonce.length === 16
    && typeof value.ciphertext === "string" && value.ciphertext.length === 64;
  if (!valid) fail("Invalid key envelope.");
  return JSON.stringify(value);
}

function validateRegistration(body: JSONObject): string {
  if (typeof body.name !== "string" || !body.name.trim() || body.name.length > 80) fail("Give the device a name.");
  if (typeof body.publicKey !== "string" || body.publicKey.length !== 88 || !validID(body.keyId)) fail("Invalid device key.");
  return serializeEnvelope(body.envelope);
}

function canonical(value: any): any {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  }
  return value;
}

async function parseEnrollment(body: JSONObject, path: string): Promise<Enrollment> {
  if (!validID(body.enrollmentId) || typeof body.deviceToken !== "string" || !/^[a-f0-9]{64}$/.test(body.deviceToken)) {
    fail("Update Zap to enroll this device.");
  }
  return {
    id: body.enrollmentId,
    token: body.deviceToken,
    hash: await digest(body.deviceToken),
    fingerprint: await digest(JSON.stringify([path, canonical(body)])),
  };
}

export default {
  fetch(request: Request, env: Env) {
    return env.VAULT.get(env.VAULT.idFromName("personal")).fetch(request);
  },
};

export class Vault extends DurableObject<Env> {
  private sql: SqlStorage;
  private uploads = new Set<string>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(SCHEMA);
  }

  async fetch(request: Request): Promise<Response> {
    try {
      return await this.route(request);
    } catch (error) {
      if (error instanceof HTTPError) return json({ error: error.message }, error.status);
      if (error instanceof SyntaxError) return json({ error: "Invalid JSON." }, 400);
      console.error("Request failed", error instanceof Error ? error.name : "unknown");
      return json({ error: "Sync failed. Try again." }, 500);
    }
  }

  webSocketMessage(socket: WebSocket, message: string | ArrayBuffer) {
    if (message === "ping") socket.send("pong");
  }

  private async route(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    if (path === "/health") return json({ service: "zap" });
    if (path === "/v1/bootstrap" && method === "POST") return this.bootstrap(request);
    if (path === "/v1/pair" && method === "POST") return this.pair(request);

    const device = this.first("SELECT * FROM devices WHERE token=?", await digest(bearerToken(request)));
    if (!device) fail("Device is not connected. Pair it again.", 401);
    // A registered device implies an initialized deployment.
    const settings = this.settings()!;

    if (path === "/v1/events" && request.headers.get("Upgrade")?.toLowerCase() === "websocket") return this.openEvents(device);
    if (path === "/v1/sync" && method === "GET") return this.snapshot(url, device, settings);
    if (path === "/v1/invitations" && method === "POST") return this.createInvitation(settings);
    if (path === "/v1/settings" && method === "PUT") return this.updateSettings(request);
    if (path === "/v1/rotate" && method === "POST") return this.rotate(request, device, settings);
    if (path === "/v1/devices/me" && method === "DELETE") return this.disconnect(device);
    if (path === "/v1/items" && method === "DELETE") return this.clearItems();

    const itemID = path.match(ITEM_PATH)?.[1];
    if (itemID && method === "GET") return this.download(itemID, settings);
    if (itemID && method === "DELETE") return this.deleteItem(itemID);
    if (itemID && method === "PUT") return this.upload(request, itemID, device, settings);

    return json({ error: "Not found." }, 404);
  }

  private async bootstrap(request: Request): Promise<Response> {
    const body = await readJSONObject(request);
    const envelope = validateRegistration(body);
    const enrollment = await parseEnrollment(body, "/v1/bootstrap");
    const credentials = { deviceId: enrollment.id, token: enrollment.token, keyId: body.keyId };
    if (this.alreadyEnrolled(enrollment)) return json(credentials);

    const setupToken = this.env.BOOTSTRAP_TOKEN;
    // Compare digests so the comparison time does not depend on the secret.
    if (!setupToken || await digest(bearerToken(request)) !== await digest(setupToken)) fail("Invalid setup token.", 401);

    this.ctx.storage.transactionSync(() => {
      if (this.alreadyEnrolled(enrollment)) return;
      if (this.settings()) fail("This deployment is already initialized.", 409);
      this.sql.exec("INSERT INTO settings VALUES(1,?,1,?)", DEFAULT_RETENTION_DAYS, body.keyId);
      this.insertDevice(enrollment, body, envelope);
    });
    await this.scheduleAlarm();
    return json(credentials);
  }

  private async pair(request: Request): Promise<Response> {
    const body = await readJSONObject(request);
    const envelope = validateRegistration(body);
    const invitationHash = await digest(String(body.token));
    const enrollment = await parseEnrollment(body, "/v1/pair");
    const credentials = { deviceId: enrollment.id, token: enrollment.token, keyId: body.keyId };
    if (this.alreadyEnrolled(enrollment)) return json(credentials);

    this.ctx.storage.transactionSync(() => {
      if (this.alreadyEnrolled(enrollment)) return;
      const invitation = this.first("SELECT * FROM invitations WHERE token=?", invitationHash);
      const unusable = !invitation
        || Number(invitation.expires) < Date.now()
        || invitation.keyId !== body.keyId
        || this.settings()?.keyId !== body.keyId;
      if (unusable) fail("Pairing code expired. Create a new one on Mac.", 401);

      this.sql.exec("DELETE FROM invitations WHERE token=?", invitationHash);
      this.insertDevice(enrollment, body, envelope);
      // Older keys let the new device read history saved before the last rotation.
      for (const [keyId, wrapped] of Object.entries(body.historyEnvelopes ?? {})) {
        if (!validID(keyId)) fail("Invalid history key.");
        this.sql.exec("INSERT OR IGNORE INTO keys VALUES(?,?,?)", enrollment.id, keyId, serializeEnvelope(wrapped));
      }
    });
    this.broadcastChange();
    return json(credentials);
  }

  /** True when this exact request already succeeded, so its original response can be repeated. */
  private alreadyEnrolled(enrollment: Enrollment): boolean {
    const previous = this.first("SELECT fingerprint FROM enrollments WHERE id=?", enrollment.id);
    if (!previous) return false;
    if (previous.fingerprint !== enrollment.fingerprint) fail("Enrollment changed. Use the original request.", 409);
    if (!this.deviceExists(enrollment.id)) fail("This enrollment was revoked. Create a new pairing code.", 401);
    return true;
  }

  private insertDevice(enrollment: Enrollment, body: JSONObject, envelope: string) {
    this.sql.exec("INSERT INTO devices VALUES(?,?,?,?)", enrollment.id, body.name, enrollment.hash, body.publicKey);
    this.sql.exec("INSERT INTO keys VALUES(?,?,?)", enrollment.id, body.keyId, envelope);
    this.sql.exec("INSERT INTO enrollments VALUES(?,?)", enrollment.id, enrollment.fingerprint);
  }

  private openEvents(device: Row): Response {
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [String(device.id)]);
    return new Response(null, { status: 101, webSocket: client });
  }

  private snapshot(url: URL, device: Row, settings: Row): Response {
    const cursor = Number(url.searchParams.get("cursor") || -1);
    const keys = this.rows("SELECT keyId,envelope FROM keys WHERE deviceId=?", device.id)
      .map(key => ({ keyId: key.keyId, envelope: JSON.parse(String(key.envelope)) }));
    const devices = this.rows("SELECT id,name,publicKey FROM devices ORDER BY name");
    const items = cursor === settings.revision
      ? null
      : this.rows("SELECT * FROM items WHERE createdAt>? ORDER BY createdAt DESC", Date.now() - Number(settings.days) * DAY);
    return json({ cursor: settings.revision, days: settings.days, keyId: settings.keyId, keys, devices, items });
  }

  private async createInvitation(settings: Row): Promise<Response> {
    const token = secret();
    this.sql.exec("INSERT INTO invitations VALUES(?,?,?)", await digest(token), Date.now() + INVITATION_LIFETIME, settings.keyId);
    return json({ token });
  }

  private async updateSettings(request: Request): Promise<Response> {
    let days: number;
    try {
      days = retention((await readJSONObject(request)).days);
    } catch {
      fail("History must be between 1 and 365 days.");
    }
    this.sql.exec("UPDATE settings SET days=? WHERE id=1", days);
    this.broadcastChange();
    // Expire anything outside the new window now rather than at the next alarm.
    await this.alarm();
    return ok();
  }

  private async rotate(request: Request, device: Row, settings: Row): Promise<Response> {
    const { keyId, removeDevice, envelopes } = await readJSONObject(request);
    if (!validID(keyId) || keyId === settings.keyId || removeDevice === device.id) fail("Invalid device removal.");

    this.ctx.storage.transactionSync(() => {
      if (!this.deviceExists(removeDevice)) fail("Device not found.", 404);
      for (const remaining of this.rows("SELECT id FROM devices WHERE id<>?", removeDevice)) {
        const envelope = serializeEnvelope(envelopes?.[String(remaining.id)]);
        this.sql.exec("INSERT OR REPLACE INTO keys VALUES(?,?,?)", remaining.id, keyId, envelope);
      }
      this.deleteDevice(removeDevice);
      this.sql.exec("UPDATE settings SET keyId=? WHERE id=1", keyId);
    });
    for (const socket of this.ctx.getWebSockets(removeDevice)) socket.close(1008, "Device removed");
    this.broadcastChange();
    return ok();
  }

  private async disconnect(device: Row): Promise<Response> {
    const lastDevice = !this.first("SELECT id FROM devices WHERE id<>?", device.id);
    this.ctx.storage.transactionSync(() => {
      this.deleteDevice(device.id);
      if (lastDevice) {
        this.removeAllItems();
        this.sql.exec("DELETE FROM settings");
      }
    });
    for (const socket of this.ctx.getWebSockets(String(device.id))) socket.close(1008, "Device disconnected");
    if (!lastDevice) this.broadcastChange();
    await this.scheduleAlarm();
    return ok();
  }

  /** Outstanding invitations carry the current keys' id, so any membership change voids them. */
  private deleteDevice(id: SqlStorageValue) {
    this.sql.exec("DELETE FROM devices WHERE id=?", id);
    this.sql.exec("DELETE FROM keys WHERE deviceId=?", id);
    this.sql.exec("DELETE FROM invitations");
  }

  private async clearItems(): Promise<Response> {
    this.removeAllItems();
    this.broadcastChange();
    await this.scheduleAlarm();
    return ok();
  }

  private async download(id: string, settings: Row): Promise<Response> {
    const item = this.first("SELECT * FROM items WHERE id=?", id);
    if (!item || !active(Number(item.createdAt), Number(settings.days))) fail("Item expired or was deleted.", 404);
    const blob = await this.env.BLOBS.get(id);
    if (!blob) fail("Content unavailable. Try again.", 503);
    return new Response(blob.body, { headers: { "Content-Type": "application/octet-stream", "Cache-Control": "no-store" } });
  }

  private async deleteItem(id: string): Promise<Response> {
    this.removeItem(id);
    this.broadcastChange();
    await this.scheduleAlarm();
    return ok();
  }

  private async upload(request: Request, id: string, device: Row, settings: Row): Promise<Response> {
    if (this.isTombstoned(id)) fail("Item was deleted.", 410);
    if (this.uploads.has(id) || this.first("SELECT id FROM garbage WHERE id=?", id)) fail("Item is busy. Retry shortly.", 409);
    this.uploads.add(id);
    try {
      const createdAt = Number(request.headers.get("X-Created-At"));
      const keyId = request.headers.get("X-Key-Id") || "";
      if (!active(createdAt, Number(settings.days))) fail("Item has expired.", 410);
      if (keyId !== settings.keyId) fail("Refresh encryption keys and retry.", 409);
      if (this.first("SELECT id FROM items WHERE id=?", id)) return ok();

      const length = Number(request.headers.get("Content-Length"));
      if (!length || length > MAX_BLOB || length < MIN_SEALED_SIZE || !request.body) fail("Content is too large or empty.", 413);
      // The fixed-length stream errors if the body does not match Content-Length.
      const stream = new FixedLengthStream(length);
      const stored = this.env.BLOBS.put(id, stream.readable);
      const received = await request.body.pipeTo(stream.writable).then(() => true, () => false);
      if (!received) {
        await stored.catch(() => {});
        fail("Invalid content size.", 413);
      }
      await stored;

      // Other requests ran while the blob was written; recheck everything that could invalidate it.
      const current = this.settings();
      const stale = this.isTombstoned(id)
        || current?.keyId !== keyId
        || !this.deviceExists(device.id)
        || !active(createdAt, Number(current?.days));
      if (stale) {
        this.sql.exec("INSERT OR IGNORE INTO garbage VALUES(?)", id);
        await this.scheduleAlarm();
        fail("State changed. Refresh and retry.", 409);
      }

      this.sql.exec("INSERT OR IGNORE INTO items VALUES(?,?,?,?,?)", id, device.id, keyId, createdAt, length);
      this.broadcastChange();
      return ok();
    } finally {
      this.uploads.delete(id);
    }
  }

  private removeItem(id: string) {
    this.sql.exec("INSERT OR REPLACE INTO deleted VALUES(?,?)", id, Date.now() + TOMBSTONE_LIFETIME);
    this.sql.exec("INSERT OR IGNORE INTO garbage VALUES(?)", id);
    this.sql.exec("DELETE FROM items WHERE id=?", id);
  }

  private removeAllItems() {
    for (const item of this.rows("SELECT id FROM items")) this.removeItem(String(item.id));
  }

  async alarm() {
    const settings = this.settings();
    if (settings) this.expireItems(settings);
    this.sql.exec("DELETE FROM invitations WHERE expires<?", Date.now());
    this.sql.exec("DELETE FROM deleted WHERE expires<?", Date.now());
    await this.collectGarbage();
    const scanIncomplete = await this.deleteOrphanBlobs();

    const garbagePending = this.rows("SELECT id FROM garbage LIMIT 1").length > 0;
    // An abandoned deployment stops waking up once its storage is collected.
    if (settings || garbagePending || scanIncomplete) {
      await this.ctx.storage.setAlarm(Date.now() + (garbagePending ? ALARM_SOON : ALARM_IDLE));
    }
  }

  private expireItems(settings: Row) {
    const expired = this.rows("SELECT id FROM items WHERE createdAt<=?", Date.now() - Number(settings.days) * DAY);
    for (const item of expired) this.removeItem(String(item.id));
    if (expired.length) this.broadcastChange();
  }

  private async collectGarbage() {
    for (const row of this.rows("SELECT id FROM garbage LIMIT 100")) {
      if (this.uploads.has(String(row.id))) continue;
      await this.env.BLOBS.delete(String(row.id));
      this.sql.exec("DELETE FROM garbage WHERE id=?", row.id);
    }
  }

  private async deleteOrphanBlobs(): Promise<boolean> {
    const cursor = await this.ctx.storage.get<string>("blobScanCursor");
    const page = await this.env.BLOBS.list({ limit: 1000, cursor });
    for (const blob of page.objects) {
      const orphaned = blob.uploaded.getTime() < Date.now() - ORPHAN_BLOB_AGE
        && !this.uploads.has(blob.key)
        && !this.first("SELECT id FROM items WHERE id=?", blob.key);
      if (!orphaned) continue;
      this.sql.exec("INSERT OR IGNORE INTO garbage VALUES(?)", blob.key);
      await this.env.BLOBS.delete(blob.key);
      this.sql.exec("DELETE FROM garbage WHERE id=?", blob.key);
    }
    if (page.truncated) await this.ctx.storage.put("blobScanCursor", page.cursor);
    else await this.ctx.storage.delete("blobScanCursor");
    return page.truncated;
  }

  private async scheduleAlarm() {
    await this.ctx.storage.setAlarm(Date.now() + ALARM_SOON);
  }

  private broadcastChange() {
    this.sql.exec("UPDATE settings SET revision=revision+1 WHERE id=1");
    for (const socket of this.ctx.getWebSockets()) {
      try {
        socket.send('{"type":"changed"}');
      } catch {
        socket.close();
      }
    }
  }

  private rows(query: string, ...args: SqlStorageValue[]): Row[] {
    return this.sql.exec(query, ...args).toArray();
  }

  private first(query: string, ...args: SqlStorageValue[]): Row | undefined {
    return this.rows(query, ...args)[0];
  }

  private settings(): Row | undefined {
    return this.first("SELECT * FROM settings WHERE id=1");
  }

  private deviceExists(id: SqlStorageValue): boolean {
    return this.first("SELECT id FROM devices WHERE id=?", id) !== undefined;
  }

  private isTombstoned(id: string): boolean {
    return this.first("SELECT id FROM deleted WHERE id=?", id) !== undefined;
  }
}
