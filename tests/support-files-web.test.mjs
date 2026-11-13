import assert from 'node:assert/strict';
import test from 'node:test';
import { sendFile } from '../support/web/files.mjs';

function source(contents) {
  const file = new File([contents], 'בדיקה.txt', { type: 'text/plain' });
  const slices = [];
  const original = file.slice.bind(file);
  file.slice = (start, end) => {
    slices.push([start, end]);
    return original(start, end);
  };
  return { file, slices };
}

test('העברה קוראת ושולחת מקטעים רצופים, ומסיימת גם קובץ ריק', async () => {
  for (const contents of ['abcdefg', '']) {
    const { file, slices } = source(contents);
    const calls = [];
    const progress = [];
    const call = async (method, args) => {
      calls.push(method);
      if (method === 'fileBegin') {
        assert.equal(args.bytes, contents.length);
        return { transfer: 'test', chunkBytes: 3, window: 4 };
      }
      if (method === 'fileChunk') {
        const chunk = Buffer.from(args.data, 'base64url').toString();
        assert.equal(chunk, contents.slice(args.offset, args.offset + 3));
        return { received: args.offset + chunk.length };
      }
      assert.equal(method, 'fileFinish');
      return { name: file.name, bytes: file.size };
    };
    await sendFile(call, 'internal', file, { progress: (n) => progress.push(n), finishing() {} });
    assert.deepEqual(slices, contents ? [[0, 3], [3, 6], [6, 7]] : []);
    assert.deepEqual(progress, contents ? [3, 6, 7] : []);
    assert.deepEqual(calls, ['fileBegin', ...(contents ? ['fileChunk', 'fileChunk', 'fileChunk'] : []), 'fileFinish']);
  }
});

test('ביטול או אישור שגוי שולחים ביטול יחיד ללא ניסיון כתיבה חוזר', async () => {
  for (const cancel of [true, false]) {
    const { file, slices } = source('abcdef');
    const calls = [];
    const transfer = { progress() {}, finishing() {} };
    const call = async (method) => {
      calls.push(method);
      if (method === 'fileBegin') return { transfer: 'test', chunkBytes: 3 };
      if (method === 'fileChunk') {
        if (cancel) transfer.cancelled = true;
        return { received: cancel ? 3 : 2 };
      }
      assert.equal(method, 'fileCancel');
      return true;
    };
    await assert.rejects(sendFile(call, 'internal', file, transfer), cancel ? /FILE_CANCELLED/ : /FILE_INCOMPLETE/);
    assert.deepEqual(slices, [[0, 3]]);
    assert.deepEqual(calls, ['fileBegin', 'fileChunk', 'fileCancel']);
  }
});

test('חלון מלא שומר על סדר וממתין לכל המנות שנשלחו לפני ביטול', async () => {
  const { file } = source('abcdefghijklmnop');
  const transfer = { progress() {}, finishing() {} };
  const starts = [];
  const responses = [];
  let settled = 0;
  let cancels = 0;
  const call = async (method, args) => {
    if (method === 'fileBegin') return { transfer: 'test', chunkBytes: 2, window: 99 };
    if (method === 'fileChunk') {
      assert.equal(args.offset, starts.length * 2);
      starts.push(performance.now());
      const reply = new Promise((resolve) => responses.push(() => resolve({ received: args.offset + 2 })));
      if (responses.length === 4) {
        transfer.cancelled = true;
        for (const resolve of responses.toReversed()) setTimeout(resolve, 10);
      }
      const value = await reply;
      settled++;
      return value;
    }
    assert.equal(method, 'fileCancel');
    assert.equal(settled, 4);
    cancels++;
    return true;
  };
  await assert.rejects(sendFile(call, 'internal', file, transfer), /FILE_CANCELLED/);
  assert.equal(starts.length, 4);
  assert.equal(cancels, 1);
  for (let i = 1; i < starts.length; i++) assert.ok(starts[i] - starts[i - 1] >= 21.9);
});
