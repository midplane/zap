# Zap wire contract

All endpoints except `/health` require a bearer credential. `/v1/bootstrap` accepts the deployment's bootstrap credential exactly once; `/v1/pair` instead accepts a one-use invitation token in its body. Native clients require HTTPS except loopback addresses during development.

All base64 uses the standard padded alphabet. Identifiers are UUID strings. Times are Unix milliseconds. Text is UTF-8. Content plaintext is JSON `{kind: "text"|"image", text?, png?, source, createdAt}`. PNG is base64. Content is AES-256-GCM with item ID UTF-8 as AAD. The binary body is `nonce[12] || ciphertext || tag[16]`.

Devices have P-256 agreement keys. Public keys are base64 uncompressed X9.63 points (65 bytes). Envelopes are `{ephemeralPublicKey, nonce, ciphertext}`; ciphertext includes the 16-byte GCM tag. Derive a 32-byte AES wrapping key from ephemeral ECDH using HKDF-SHA256, empty salt, and UTF-8 info `zap-key`. The key ID is GCM AAD. Encrypted plaintext is the 32-byte group key.

- `POST /v1/bootstrap`: `{enrollmentId, deviceToken, name, publicKey, keyId, envelope}` → `{deviceId, token, keyId}`.
- `POST /v1/invitations`: → `{token}` valid for five minutes.
- QR: `zap://pair#<base64url JSON>` containing `{url, token, keyId, keys: {keyId: base64Key}}`. The fragment keeps keys out of HTTP requests. Treat the entire QR as a secret.
- `POST /v1/pair`: `{enrollmentId, deviceToken, token, name, publicKey, keyId, envelope, historyEnvelopes}` → device credentials. Envelopes wrap the QR's keys for the joining device. Never send raw keys.
- `GET /v1/sync?cursor=N`: `{cursor, days, keyId, keys:[{keyId,envelope}], devices:[{id,name,publicKey}], items:[{id,deviceId,keyId,createdAt,size}]|null}`. An unchanged revision returns null items; a changed revision returns an authoritative metadata snapshot. Clients retain unsent captures, remove absent previously synced captures, and download only missing payloads. Do not advance the cursor until the snapshot is applied successfully.
- `PUT /v1/items/:id`: binary encrypted content; `X-Key-Id`, `X-Created-At`, and `Content-Length` headers. Idempotent by ID. Expired/deleted IDs return 410; rotation requires refreshing keys and re-encrypting after 409.
- `GET /v1/items/:id`: binary encrypted content.
- `DELETE /v1/items/:id`: idempotent deletion, including captures still queued offline.
- `DELETE /v1/items`: clear committed history. Clients delete their own queued captures separately.
- `PUT /v1/settings`: `{days}`.
- `POST /v1/rotate`: `{removeDevice, keyId, envelopes: {remainingDeviceId: envelope}}`; atomically revoke, install new key envelopes, and invalidate invitations.
- `DELETE /v1/devices/me`: a disconnecting device revokes its own credential and key envelopes, and invalidates outstanding invitations. The group key is not rotated, so a device that cannot cooperate must still be removed with `/v1/rotate` from another device. When the last device leaves, committed history is cleared and the deployment returns to its uninitialized state so `/v1/bootstrap` can set it up again.
- `GET /v1/events`: authenticated WebSocket; `{"type":"changed"}` requests a sync. `ping` receives `pong`. Reconnect with backoff and fetch state on connection.

Clients push a pending clear before queued deletions, then reconcile and upload pending captures. Clearing history uses `DELETE /v1/items` alone; it tombstones every committed item, so clients do not also queue per-item deletions. They preserve local pending retention/clear actions across restarts. Retention uses original capture time. Tombstones last 366 days; all valid queued content expires within 365 days. Server storage recovery/backups may outlive application retention; retention is not a claim of forensic erasure. New versions ship together; there is no protocol negotiation or compatibility layer.

Before enrollment, clients persist the complete request, group keys, a random UUID `enrollmentId`, and a cryptographically random 32-byte lowercase hex `deviceToken` in their encrypted identity. The server uses these as the device ID and bearer credential, storing only credential/request hashes. Retrying an identical committed enrollment returns the same credentials even after invitation consumption or expiry; possession of the original request proves knowledge of the device credential. Changed requests return 409 and revoked enrollments return 401. Enrollment receipts survive device removal and vault reset to prevent replay from re-enrolling a revoked device. Native clients resume pending enrollment after restart and only replace it with connected state after the final secure save succeeds. Deploy the updated backend before using these clients; older enrollment requests without these fields are rejected.
