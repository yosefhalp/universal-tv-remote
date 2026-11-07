import { Connection } from './connection.mjs';
import { decodePairing, importSecret, randomId, randomSecret, validId } from './protocol.mjs';
import { savedDevices, saveDevice, forgetDevice } from './store.mjs';
import { normalizeCode } from './srp.mjs';
import { PointerSurface } from './pointer.mjs';

const $ = (id) => document.getElementById(id);
let pointerSupported = false;
const pointer = new PointerSurface({
  surface: $('screen'),
  image: $('frame'),
  cursor: $('pointer-cursor'),
  direct: $('pointer-direct'),
  pad: $('pointer-pad'),
  drag: $('pointer-drag'),
  click: $('pointer-click'),
  up: $('pointer-up'),
  down: $('pointer-down'),
  hint: $('pointer-hint'),
  enabled: () =>
    !!connection?.ready &&
    pointerSupported &&
    !paused &&
    lastFrame > 0 &&
    Date.now() - lastFrame < 4000 &&
    !document.hidden,
  send: (args) => action('pointer', args),
});
let connection,
  selected,
  frameTimer,
  reconnectTimer,
  generation = 0,
  lastFrame = 0,
  paused = false,
  reconnects = 0;
let expectedClose = false,
  pendingPair;
const messages = {
  POINTER_BUSY: 'המחווה הקודמת מסתיימת. אפשר לנסות שוב בעוד רגע.',
  POINTER_CANCELLED: 'Android הפסיק את המחווה. אפשר לנסות שוב.',
  STALE_GEOMETRY: 'המסך שינה כיוון או גודל. המתינו לתמונה החדשה.',
  NO_POINTER: 'יש להשלים את הרשאת הנגישות או את השליטה הנוספת בסטרימר.',
  BAD_POINTER: 'לא ניתן למפות את המיקום למסך הטלוויזיה.',
  OFFLINE: 'הסטרימר אינו מחובר כרגע. בדקו שהוא דלוק ומחובר לאינטרנט.',
  BUSY: 'מכשיר אחר כבר עוזר בטלוויזיה. סיימו שם את העזרה ונסו שוב.',
  DENIED: 'הגישה בוטלה או שקוד הזיווג אינו תקף. פתחו זיווג חדש בטלוויזיה.',
  EXPIRED: 'תוקף הזיווג הסתיים. פתחו קוד חדש בטלוויזיה.',
  PAIR_EXPIRED: 'תוקף הזיווג הסתיים. פתחו קוד חדש בטלוויזיה.',
  BAD_PAIRING: 'קישור הזיווג אינו תקין.',
  BAD_CODE: 'מזינים את שמונה הספרות שמופיעות במסך הזיווג בטלוויזיה.',
  CODE_NOT_FOUND: 'הקוד אינו זמין. בדקו את הספרות וודאו שמסך הזיווג פתוח בסטרימר מעודכן.',
  CODE_LIMIT: 'בוצעו יותר מדי ניסיונות. המתינו דקה ונסו שוב.',
  CODE_DENIED:
    'הקוד לא התקבל. בדקו את הספרות ונסו שוב. אחרי חמישה ניסיונות יש לפתוח קוד חדש בטלוויזיה או לסרוק QR.',
  NETWORK: 'לא הצלחנו להתחבר לשרת. בדקו את חיבור האינטרנט.',
  CONNECT_TIMEOUT: 'החיבור מתעכב. ודאו שהסטרימר מחובר ונסו שוב.',
  DISCONNECTED: 'החיבור נותק. מנסים להתחבר מחדש…',
  REQUEST_TIMEOUT: 'הסטרימר לא ענה לפעולה בזמן. אפשר לנסות שוב.',
  BAD_IDENTITY: 'לא ניתן לאמת את זהות הסטרימר. החיבור הופסק.',
  STORAGE_UNAVAILABLE: 'הדפדפן אינו מאפשר לשמור את הזיווג. נסו בדפדפן רגיל, מחוץ למצב פרטי.',
  NO_ACCESSIBILITY: 'יש להפעיל את שירות הנגישות של ״קרוב״ בהגדרות הסטרימר.',
  CAPTURE_PERMISSION: 'נדרש אישור שיתוף מסך בטלוויזיה. לחצו על בקשת שיתוף.',
  SECURE_CONTENT: 'האפליקציה בטלוויזיה מגינה על צילום המסך. אפשר להמשיך להשתמש בשלט.',
  CAPTURE_FAILED: 'התמונה אינה זמינה כרגע. כפתורי השלט והניגון זמינים.',
  CAPTURE_BUSY: 'ממתינים לעדכון התמונה…',
  NO_TEXT_FIELD: 'לא נמצא שדה כתיבה פתוח בטלוויזיה. פתחו קודם שדה חיפוש.',
  UNSUPPORTED: 'הפעולה אינה זמינה במסך או בדגם הזה.',
  BLOCKED: 'החיבור הושהה אחרי ניסיונות זיווג שגויים. נסו שוב בעוד מעט.',
  ADB_UNAVAILABLE: 'השליטה הנוספת אינה מחוברת. בדקו ניפוי שגיאות ברשת ואת אישור המפתח בטלוויזיה.',
  TOO_MANY_REQUESTS: 'רגע, מסיימים את הפעולות הקודמות.',
};
const explain = (error) =>
  messages[error?.message || error] || 'לא ניתן לבצע את הפעולה כרגע. נסו שוב.';
let noticeTimer;
function notice(text) {
  clearTimeout(noticeTimer);
  $('notice').textContent = text;
  $('notice').hidden = false;
  noticeTimer = setTimeout(() => {
    $('notice').hidden = true;
  }, 7000);
}
function status(text, live = false) {
  $('network').textContent = text;
  $('network').classList.toggle('live', live);
}
function clearFrame(text) {
  // חוזרים לשלט כשצילום נעלם, כדי להשאיר את כפתורי הניגון זמינים גם בתוכן מוגן.
  if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
  pointer.clear();
  $('frame').hidden = true;
  $('frame').removeAttribute('src');
  $('screen-message').hidden = false;
  $('screen-message').textContent = text;
  $('frame-status').textContent = text;
  lastFrame = 0;
}
async function confirmAction(title, text) {
  $('confirm-title').textContent = title;
  $('confirm-text').textContent = text;
  const dialog = $('confirm-dialog');
  dialog.returnValue = 'cancel';
  dialog.showModal();
  return new Promise((resolve) =>
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok'), { once: true }),
  );
}
function relayUrl() {
  return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}`;
}
async function showHome() {
  $('home').hidden = false;
  $('session').hidden = true;
  $('devices').replaceChildren();
  const devices = await savedDevices();
  $('empty').hidden = devices.length > 0;
  for (const device of devices) {
    const row = document.createElement('div');
    row.className = 'device';
    const info = document.createElement('div');
    info.className = 'device-info';
    const title = document.createElement('strong');
    title.textContent = device.name;
    const subtitle = document.createElement('small');
    subtitle.textContent = device.pending ? 'הזיווג ממתין להשלמה' : 'מכשיר שמור · חיבור בלחיצה';
    info.append(title, subtitle);
    const button = document.createElement('button');
    button.textContent = 'התחברות';
    button.className = 'primary';
    button.onclick = () => connectDevice(device);
    const remove = document.createElement('button');
    remove.className = 'quiet';
    remove.textContent = 'הסרה';
    remove.setAttribute('aria-label', `הסרת ${device.name} מהדפדפן`);
    remove.onclick = async () => {
      if (
        await confirmAction(
          'הסרה מהדפדפן',
          'ההסרה מוחקת את המפתח מהמכשיר הזה. לביטול ההרשאה גם בטלוויזיה, השתמשו במסך המכשירים המורשים.',
        )
      ) {
        await forgetDevice(device.device);
        await showHome();
      }
    };
    row.append(info, button, remove);
    $('devices').append(row);
  }
}
async function stop(show = true) {
  expectedClose = true;
  generation++;
  clearTimeout(frameTimer);
  clearTimeout(reconnectTimer);
  connection?.close();
  connection = null;
  selected = null;
  reconnects = 0;
  clearFrame('העזרה הסתיימה');
  status('מוכנים לעזור');
  expectedClose = false;
  if (show) await showHome();
}
async function connectDevice(device, retry = false) {
  if (!retry) {
    await stop(false);
    reconnects = 0;
    paused = false;
  }
  if (!retry) window.scrollTo({ top: 0, behavior: 'instant' });
  clearTimeout(reconnectTimer);
  selected = device;
  if (document.hidden) {
    setControls(false);
    status('העזרה ברקע');
    return;
  }
  const current = ++generation;
  expectedClose = true;
  connection?.close();
  expectedClose = false;
  status('מתחברים…');
  $('home').hidden = true;
  $('session').hidden = false;
  $('tv-name').textContent = device.name;
  clearFrame('מתחברים לסטרימר…');
  setControls(false);
  renderApps([]);
  $('controllers').replaceChildren();
  const active = new Connection({
    url: relayUrl(),
    device: device.device,
    controller: device.controller,
    secret: device.key,
    onClose: (reason) => {
      if (expectedClose || generation !== current) return;
      clearTimeout(frameTimer);
      setControls(false);
      clearFrame('החיבור נותק — התמונה אינה עדכנית');
      status('מנותק');
      // שחרור החלון הקודם עשוי להגיע לשרת רגע אחרי החיבור החדש. ממתינים בקצרה,
      // בלי לנתק בקר אחר שעדיין פעיל ובלי לנסות ללא סוף כשהטלוויזיה תפוסה.
      const retryable =
        reason === 'BUSY'
          ? reconnects < 3
          : !['DENIED', 'EXPIRED', 'BAD_IDENTITY', 'BLOCKED'].includes(reason);
      if (retryable && selected && !document.hidden && navigator.onLine) {
        reconnectTimer = setTimeout(
          () => connectDevice(device, true),
          Math.min(1000 * 2 ** reconnects++, 15000),
        );
      }
    },
  });
  connection = active;
  try {
    const info = await active.connect();
    if (generation !== current) return;
    if (device.pending) {
      device.pending = false;
      await saveDevice(device);
    }
    reconnects = 0;
    clearTimeout(noticeTimer);
    $('notice').hidden = true;
    status('מחוברים לטלוויזיה', true);
    setControls(true);
    // ניתוק זמני אינו מבטל השהיה שהמשתמש בחר לפניו.
    $('pause-screen').textContent = paused ? 'חידוש תמונה' : 'השהיית תמונה';
    $('pause-screen').setAttribute('aria-pressed', String(paused));
    $('capability-note').textContent =
      info.sdk < 33 && !info.localAdb
        ? 'בגרסה זו מומלץ להגדיר שליטה נוספת דרך ניפוי שגיאות מקומי כדי לאפשר חצים בכל האפליקציות.'
        : 'אפשר להשתמש גם בחיצי המקלדת, Enter ו־Escape. התמונה מיועדת לעזרה ולניווט.';
    $('capture-permission').hidden = info.captureMode !== 'permission';
    pointerSupported = info.pointer === true;
    $('android-remote-status').textContent = info.remoteStatus || '';
    if (paused) clearFrame('התמונה מושהית');
    else if (info.firstFrame && !document.hidden) {
      showFrame(info.firstFrame);
      frameTimer = setTimeout(() => captureLoop(current), Number($('quality').value));
    } else captureLoop(current);
    active
      .call('apps')
      .then((apps) => {
        if (generation === current) renderApps(apps);
      })
      .catch(() => {});
    refreshControllers().catch(() => {});
  } catch (error) {
    if (generation !== current) return;
    clearFrame(explain(error));
    notice(explain(error));
    status('לא מחובר');
    setControls(false);
    if (['DENIED', 'EXPIRED', 'BAD_IDENTITY', 'BLOCKED'].includes(error.message))
      clearTimeout(reconnectTimer);
  }
}
function setControls(enabled) {
  for (const button of document.querySelectorAll(
    '[data-key], #text-form button, #apps button, #adb-enable, #adb-disable, #adb-status, #capture-permission, #refresh-controllers, #forget',
  ))
    button.disabled = !enabled;
}
async function action(method, args) {
  try {
    return await connection.call(method, args);
  } catch (error) {
    notice(explain(error));
    return undefined;
  }
}
async function captureLoop(current) {
  if (current !== generation || !connection?.ready || paused || document.hidden) return;
  const began = performance.now();
  try {
    const frame = await connection.call('capture', {}, 5000);
    if (current !== generation || paused || document.hidden) return;
    showFrame(frame, Math.round(performance.now() - began));
  } catch (error) {
    if (current !== generation) return;
    if (error.message !== 'CAPTURE_BUSY' || Date.now() - lastFrame > 3000)
      clearFrame(explain(error));
    $('capture-permission').hidden = error.message !== 'CAPTURE_PERMISSION';
  }
  if (current === generation)
    frameTimer = setTimeout(
      () => captureLoop(current),
      Math.max(100, Number($('quality').value) - (performance.now() - began)),
    );
}
function showFrame(frame, elapsed) {
  if (
    !frame ||
    typeof frame.jpeg !== 'string' ||
    frame.jpeg.length > 1200000 ||
    !/^[A-Za-z0-9+/=]+$/.test(frame.jpeg)
  )
    throw new Error('BAD_FRAME');
  $('frame').src = `data:image/jpeg;base64,${frame.jpeg}`;
  $('frame').hidden = false;
  $('screen-message').hidden = true;
  lastFrame = Date.now();
  pointer.frame(frame);
  $('frame').onload = () => pointer.render();
  $('frame-status').textContent =
    elapsed === undefined ? 'תמונה מעודכנת' : `תמונה מעודכנת · ${elapsed} אלפיות שנייה`;
  $('capture-permission').hidden = true;
}
function renderApps(apps) {
  $('apps').replaceChildren();
  for (const app of Array.isArray(apps) ? apps.slice(0, 100) : []) {
    if (typeof app.id !== 'string' || typeof app.name !== 'string') continue;
    const button = document.createElement('button');
    button.textContent = app.name;
    button.onclick = () => action('launch', { package: app.id });
    $('apps').append(button);
  }
}
async function refreshControllers() {
  const current = generation;
  const controllers = await connection.call('controllers');
  if (current !== generation || !selected) return;
  $('controllers').replaceChildren();
  for (const item of controllers) {
    const row = document.createElement('div');
    row.className = 'controller';
    const name = document.createElement('span');
    name.textContent = item.name + (item.id === selected.controller ? ' (המכשיר הזה)' : '');
    row.append(name);
    if (item.id !== selected.controller) {
      const button = document.createElement('button');
      button.textContent = 'ביטול גישה';
      button.onclick = async () => {
        if (await confirmAction('ביטול גישה', `לבטל את הגישה של ${item.name}?`)) {
          await action('revoke', { controller: item.id });
          await refreshControllers();
        }
      };
      row.append(button);
    }
    $('controllers').append(row);
  }
}
async function pair(pairing) {
  const button = $('pair-form').querySelector('button');
  button.disabled = true;
  status('מזווגים בבטחה…');
  const temporary = new Connection({
    url: relayUrl(),
    device: pairing.device,
    controller: 'pair',
    secret: pairing.secret,
    code: pairing.code,
  });
  try {
    await temporary.connect();
    const secret = randomSecret(),
      controller = randomId();
    const record = {
      device: pairing.device,
      controller,
      key: await importSecret(secret),
      name: pairing.name,
      pending: true,
    };
    // קודם שומרים את המפתח המקומי: אם תשובת האישור תאבד, אפשר להתחבר שוב בלי לאבד את הזיווג.
    await saveDevice(record);
    try {
      await temporary.call('enroll', {
        controller,
        secret,
        name: $('controller-name').value.trim() || 'המכשיר שלי',
      });
    } catch (error) {
      if (!['REQUEST_TIMEOUT', 'DISCONNECTED'].includes(error.message)) {
        await forgetDevice(record.device);
        throw error;
      }
    }
    temporary.close();
    pendingPair = null;
    $('pair-link').value = '';
    $('pair-code').value = '';
    $('code-entry').hidden = false;
    $('qr-ready').hidden = true;
    $('legacy-pair').hidden = false;
    await connectDevice(record);
  } catch (error) {
    temporary.close();
    notice(explain(error));
    status('מוכנים לעזור');
  } finally {
    button.disabled = false;
  }
}
let pairingBusy = false;
$('pair-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (pairingBusy) return;
  pairingBusy = true;
  const button = $('pair-form').querySelector('button');
  button.disabled = true;
  try {
    const input = $('pair-link').value.trim();
    let token = pendingPair;
    if (input) {
      const url = new URL(input);
      if (url.origin !== location.origin) throw new Error('BAD_PAIRING');
      token = new URLSearchParams(url.hash.slice(1)).get('pair');
    }
    let pairing;
    if (token) pairing = decodePairing(token);
    else {
      const code = normalizeCode($('pair-code').value);
      // רק שתי ספרות האיתור מגיעות לשרת. הסוד נבדק ישירות מול הסטרימר דרך SRP.
      const response = await fetch(`/v1/pair/${code.slice(0, 2)}`, {
        cache: 'no-store',
        signal: AbortSignal.timeout(8000),
      });
      if (!response.ok) throw new Error(response.status === 429 ? 'CODE_LIMIT' : 'CODE_NOT_FOUND');
      const found = await response.json();
      if (
        !validId(found.device) ||
        !Number.isSafeInteger(found.expires) ||
        found.expires <= Date.now()
      )
        throw new Error('PAIR_EXPIRED');
      pairing = {
        device: found.device,
        name: String(found.name || 'הטלוויזיה').slice(0, 80),
        code,
      };
    }
    await pair(pairing);
  } catch (error) {
    notice(explain(error));
  } finally {
    pairingBusy = false;
    button.disabled = false;
  }
});
$('disconnect').onclick = () => stop().catch((error) => notice(explain(error)));
$('add-device').onclick = () => {
  $('pair-details').open = true;
  $('pair-code').focus();
};
for (const button of document.querySelectorAll('[data-key]'))
  button.onclick = () => action('key', { key: button.dataset.key });
$('text-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = $('remote-text').value;
  if (text && (await action('text', { text }))) $('remote-text').value = '';
});
$('pause-screen').onclick = () => {
  paused = !paused;
  $('pause-screen').setAttribute('aria-pressed', String(paused));
  $('pause-screen').textContent = paused ? 'חידוש תמונה' : 'השהיית תמונה';
  clearTimeout(frameTimer);
  if (paused) clearFrame('התמונה מושהית');
  else captureLoop(generation);
};
$('capture-permission').onclick = () => action('requestCapture');
$('adb-enable').onclick = async () => {
  if (await action('adbEnable'))
    notice('אם מופיעה בקשת ניפוי שגיאות בטלוויזיה, סמנו אישור קבוע ואשרו.');
};
$('adb-disable').onclick = async () => {
  if (await action('adbDisable')) $('android-remote-status').textContent = 'שליטה נוספת כבויה';
};
$('adb-status').onclick = async () => {
  const info = await action('info');
  if (info) $('android-remote-status').textContent = info.remoteStatus;
};
let updatePolling = false;
async function refreshUpdate() {
  if (updatePolling || !$('update-panel').open || document.hidden) return;
  const active = connection;
  if (!active?.ready) {
    $('update-status').textContent = 'ממתינים לחיבור מחדש לסטרימר…';
    $('update-check').disabled = $('update-install').disabled = true;
    return;
  }
  updatePolling = true;
  try {
    const info = await active.call('updateInfo');
    if (connection !== active) return;
    $('update-status').textContent = `גרסה מותקנת: ${info.version}. ${info.message}`;
    $('update-check').disabled = info.working || info.state === 'installing';
    $('update-install').disabled =
      $('update-check').disabled || !info.latest || info.state === 'current';
  } catch {
    $('update-status').textContent =
      'מצב העדכון אינו זמין. בגרסה ישנה יש להתקין תחילה APK שכולל עדכונים.';
    $('update-check').disabled = $('update-install').disabled = true;
  } finally {
    updatePolling = false;
  }
}
$('update-panel').addEventListener('toggle', refreshUpdate);
$('update-check').onclick = async () => {
  await action('updateCheck');
  await refreshUpdate();
};
$('update-install').onclick = async () => {
  await action('updateInstall');
  await refreshUpdate();
};
setInterval(refreshUpdate, 2500);
$('fullscreen').onclick = () => {
  if (document.fullscreenElement) document.exitFullscreen?.();
  else
    $('screen')
      .requestFullscreen?.()
      .catch(() => notice('מסך מלא אינו זמין בדפדפן הזה.'));
};
$('refresh-controllers').onclick = () =>
  refreshControllers().catch((error) => notice(explain(error)));
$('forget').onclick = async () => {
  if (
    !selected ||
    !(await confirmAction(
      'ביטול הגישה',
      'הזיווג יימחק מהטלוויזיה ומהמכשיר הזה. כדי להתחבר שוב יהיה צורך בזיווג חדש ליד הטלוויזיה.',
    ))
  )
    return;
  const id = selected.device;
  const result = await action('revoke', { controller: selected.controller });
  if (result) {
    await forgetDevice(id);
    await stop();
  }
};
document.addEventListener('keydown', (event) => {
  if (
    !connection?.ready ||
    /INPUT|TEXTAREA|SELECT/.test(event.target.tagName) ||
    $('confirm-dialog').open ||
    event.altKey ||
    event.ctrlKey ||
    event.metaKey
  )
    return;
  const key = {
    ArrowUp: 'UP',
    ArrowDown: 'DOWN',
    ArrowLeft: 'LEFT',
    ArrowRight: 'RIGHT',
    Enter: 'OK',
    Escape: 'BACK',
  }[event.key];
  if (key) {
    event.preventDefault();
    if (!event.repeat) action('key', { key });
  }
});
document.addEventListener('visibilitychange', () => {
  clearTimeout(frameTimer);
  if (document.hidden) {
    // חלון ברקע אינו תופס את הסטרימר. כך אפשר לפתוח את האפליקציה מהאייקון או ממכשיר אחר.
    generation++;
    clearTimeout(reconnectTimer);
    connection?.close('BACKGROUND');
    connection = null;
    setControls(false);
    clearFrame('העזרה הושהתה כשהחלון היה ברקע');
    status('העזרה ברקע');
  } else if (selected) connectDevice(selected, true);
});
setInterval(() => {
  if (lastFrame && !document.hidden && Date.now() - lastFrame > 4000)
    clearFrame('מחכים לתמונה מעודכנת…');
}, 1000);
window.addEventListener('offline', () => {
  connection?.close('NETWORK');
  notice('אין חיבור לאינטרנט במכשיר שלך.');
});
window.addEventListener('online', () => {
  if (selected && !connection?.ready) connectDevice(selected, true);
});
window.addEventListener('pagehide', () => {
  expectedClose = true;
  connection?.close();
});
window.addEventListener('pageshow', (event) => {
  if (event.persisted && selected) {
    expectedClose = false;
    connectDevice(selected, true);
  }
});
async function readPairingLink() {
  const params = new URLSearchParams(location.hash.slice(1));
  if (params.has('pair')) {
    pendingPair = params.get('pair');
    history.replaceState(null, '', location.pathname);
    const data = decodePairing(pendingPair);
    await stop();
    $('pair-details').open = true;
    $('code-entry').hidden = true;
    $('legacy-pair').hidden = true;
    $('qr-ready').hidden = false;
    $('qr-ready').textContent = `הזיווג של ${data.name} מוכן. נשאר רק ללחוץ על חיבור ושמירה.`;
    // בסריקת QR בטלפון מציגים מיד את פעולת האישור, בלי לחפש אותה מתחת למסך הפתיחה.
    $('pair-details').scrollIntoView({ block: 'center' });
    notice('לחצו על חיבור ושמירה כדי לאשר זיווג למכשיר הזה.');
  }
}
window.addEventListener('hashchange', () =>
  readPairingLink().catch((error) => notice(explain(error))),
);
try {
  if (!isSecureContext || !crypto.subtle)
    throw new Error('נדרש חיבור HTTPS כדי לשמור על הצפנת השליטה.');
  await showHome();
  await readPairingLink();
} catch (error) {
  notice(messages[error.message] || error.message);
}
if ('serviceWorker' in navigator && location.protocol === 'https:')
  navigator.serviceWorker.register('/sw.js?v=6').catch(() => {});
