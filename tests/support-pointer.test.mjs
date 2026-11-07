import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fittedRect, pointIn, pointerRequest } from '../support/web/pointer.mjs';

test('מיפוי מגע מתחשב בשוליים ובמסך מלא', () => {
  const box = { left: 10, top: 20, width: 400, height: 400 };
  const rect = fittedRect(box, 1920, 1080);
  assert.deepEqual(rect, { left: 10, top: 107.5, width: 400, height: 225 });
  assert.deepEqual(pointIn(rect, 210, 220), [0.5, 0.5]);
  assert.equal(pointIn(rect, 210, 50, true), null);
  assert.deepEqual(pointIn(rect, 900, 900), [1, 1]);
  const wide = fittedRect({ left: 0, top: 0, width: 1000, height: 300 }, 1280, 720);
  assert.deepEqual(pointIn(wide, 500, 150), [0.5, 0.5]);
});
test('בקשת מצביע דורשת תמונה עם ממדי סטרימר תקינים', () => {
  assert.equal(pointerRequest({ width: 1280, height: 720 }, 'tap', [[0.5, 0.5]]), null);
  assert.equal(
    pointerRequest({ displayWidth: 1920, displayHeight: 1080, rotation: 5 }, 'tap', [[0.5, 0.5]]),
    null,
  );
  const request = pointerRequest(
    { displayWidth: 1920, displayHeight: 1080, rotation: 0 },
    'drag',
    [
      [0, 0],
      [1, 1],
    ],
    100000,
  );
  assert.equal(request.duration, 1500);
  assert.equal(request.displayWidth, 1920);
});
