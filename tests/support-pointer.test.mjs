import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fittedRect, pointIn, pointerRequest, PointerSurface } from '../support/web/pointer.mjs';

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

test('ללא תמיכה בגרירה נשלחות לחיצות וגלילה בלבד, גם כשהיכולת משתנה באמצע', async () => {
  const sent = [];
  let canDrag = false;
  const pointer = Object.assign(Object.create(PointerSurface.prototype), {
    geometry: { displayWidth: 1920, displayHeight: 1080, rotation: 0 },
    ready: () => true,
    canDrag: () => canDrag,
    send: async (request) => sent.push(request),
  });
  await pointer.submit('drag', [[0.1, 0.1], [0.8, 0.8]]);
  await pointer.submit('tap', [[0.5, 0.5]]);
  await pointer.submit('scroll', [[0.5, 0.5]], 300, 0, 0.65);
  assert.deepEqual(sent.map((request) => request.kind), ['tap', 'scroll']);
  canDrag = true;
  await pointer.submit('drag', [[0.1, 0.1], [0.8, 0.8]]);
  assert.equal(sent.at(-1).kind, 'drag');
  canDrag = false;
  await pointer.submit('drag', [[0.1, 0.1], [0.8, 0.8]]);
  assert.equal(sent.length, 3);
});

test('יכולת ללא גרירה מבטלת דריכה קודמת, משביתה רק גרירה ומציגה הסבר', () => {
  const element = () => ({
    setAttribute() {}, classList: { toggle() {} }, style: {},
    getBoundingClientRect: () => ({ left: 0, top: 0 }),
  });
  const pointer = Object.assign(Object.create(PointerSurface.prototype), {
    direct: element(), pad: element(), drag: element(), click: element(),
    up: element(), down: element(), surface: element(), cursor: element(), hint: {},
    mode: 'pad', armed: true, canDrag: () => false, ready: () => true,
    rect: () => ({ left: 0, top: 0, width: 100, height: 100 }), position: [0.5, 0.5],
  });
  pointer.render();
  assert.equal(pointer.armed, false);
  assert.equal(pointer.drag.disabled, true);
  assert.equal(pointer.click.disabled, false);
  assert.equal(pointer.up.disabled, false);
  assert.equal(pointer.down.disabled, false);
  assert.match(pointer.hint.textContent, /דורשות הפעלת שליטה נוספת/);
});
