import { test } from "node:test";
import assert from "node:assert/strict";
import { active, DAY, retention, validID } from "../src/rules";
test("retention and upload age bound offline resurrection", () => {
  const now = 2_000_000_000_000;
  assert.equal(active(now - 10 * DAY, 10, now), false);
  assert.equal(active(now - 10 * DAY + 1, 10, now), true);
  assert.equal(active(now + 300_001, 10, now), false);
  for (const value of [0, 366, 1.5, "10", null]) assert.throws(() => retention(value));
  assert.equal(retention(365), 365);
  assert.equal(validID("../../secret"), false);
});
