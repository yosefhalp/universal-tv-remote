import { base64 } from './protocol.mjs';

const messages = {
  FILE_STORAGE_UNAVAILABLE: 'יעד האחסון אינו זמין. בדקו שהכונן מחובר ורעננו את הרשימה.',
  FILE_NO_SPACE: 'אין די מקום פנוי ביעד שבחרתם.',
  FILE_BUSY: 'פעולת קבצים אחרת עדיין מתבצעת בטלוויזיה.',
  FILE_INVALID: 'פרטי הקובץ או היעד אינם תקינים. רעננו ובחרו שוב.',
  FILE_CHANGED: 'הקובץ השתנה מאז שהוצג. רעננו את הרשימה ובחרו שוב.',
  FILE_INCOMPLETE: 'ההעברה לא הושלמה.',
  FILE_WRITE_FAILED: 'הטלוויזיה לא הצליחה לשמור את הקובץ.',
  FILE_READ_FAILED: 'לא ניתן היה לקרוא את הקובץ או את תיקיית היעד.',
  FILE_PERMISSION: 'אין הרשאה לפעולה. בדקו את היעד וההרשאות בטלוויזיה.',
  FILE_NO_PICKER: 'בחירת תיקייה אינה זמינה בטלוויזיה הזאת.',
  FILE_NO_VIEWER: 'אין בטלוויזיה אפליקציה שיכולה לפתוח את הקובץ.',
  FILE_TOO_LARGE: 'הקובץ גדול מהגודל שהטלוויזיה מקבלת.',
  DISCONNECTED: 'החיבור נותק. חזרו למסך הקבצים אחרי החיבור מחדש.',
  UNSUPPORTED: 'כדי להשתמש בקבצים יש לעדכן את קרוב בסטרימר.',
  REQUEST_TIMEOUT: 'לא התקבל אישור מהטלוויזיה. בדקו את מצב הקבצים לפני ניסיון נוסף.',
};

function size(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return 'לא ידוע';
  const units = ['בתים', 'KB', 'MB', 'GB', 'TB'];
  let unit = 0;
  while (bytes >= 1024 && unit < units.length - 1) {
    bytes /= 1024;
    unit++;
  }
  return `${new Intl.NumberFormat('he', { maximumFractionDigits: unit ? 1 : 0 }).format(bytes)} ${units[unit]}`;
}

// שולחים לפי סדר ומתקדמים לפי אישורים רצופים, עד לגודל החלון שהטלוויזיה מאפשרת.
export async function sendFile(call, destination, file, transfer) {
  let id;
  let finished = false;
  let failure;
  const pending = [];
  const check = () => {
    if (transfer.cancelled) throw new Error('FILE_CANCELLED');
    if (failure) throw failure;
  };
  const acknowledge = async () => {
    const offset = await pending.shift();
    check();
    transfer.progress(offset);
  };
  try {
    const response = await call('fileBegin', { destination, name: file.name, bytes: file.size, mime: file.type || 'application/octet-stream' });
    id = response.transfer;
    const chunkBytes = response.chunkBytes;
    const windowSize = Number.isSafeInteger(response.window) && response.window > 0
      ? Math.min(response.window, 4) : 1;
    if (typeof id !== 'string' || !Number.isSafeInteger(chunkBytes) || chunkBytes < 1 || chunkBytes > 98304) throw new Error('FILE_INVALID');
    let offset = 0;
    let lastChunkAt = -Infinity;
    while (offset < file.size) {
      check();
      if (pending.length >= windowSize) await acknowledge();
      const end = Math.min(file.size, offset + chunkBytes);
      const bytes = new Uint8Array(await file.slice(offset, end).arrayBuffer());
      // משאירים מרווח בקצב החיבור גם לפקודות השלט ולתמונות.
      while (performance.now() - lastChunkAt < 22) {
        await new Promise((resolve) => setTimeout(resolve, Math.ceil(22 - (performance.now() - lastChunkAt))));
      }
      check();
      const data = base64(bytes);
      lastChunkAt = performance.now();
      const request = call('fileChunk', { transfer: id, offset, data })
        .then((ack) => {
          if (ack.received !== end) throw new Error('FILE_INCOMPLETE');
          return end;
        })
        .catch((error) => { failure ??= error; });
      pending.push(request);
      offset = end;
    }
    while (pending.length) await acknowledge();
    check();
    transfer.finishing();
    const result = await call('fileFinish', { transfer: id });
    finished = true;
    return result;
  } finally {
    // ממתינים לכל מה שכבר נשלח לפני בקשת ביטול, גם כשאישור מוקדם נכשל.
    await Promise.all(pending);
    if (typeof id === 'string' && !finished) {
      // השרת מנקה קובץ חלקי אם החיבור כבר נסגר לפני הביטול.
      try { await call('fileCancel', { transfer: id }); } catch {}
    }
  }
}

export function filesUI() {
  const $ = (id) => document.getElementById(`files-${id}`);
  const dialog = $('dialog');
  if (!dialog) return { attach() {}, detach() {} };
  let connection;
  let busy = false;
  let upload;
  let destinations = [];
  let rows = [];
  let picked = new Set();
  let nextOffset = 0;
  let hasMore = false;
  let confirmIds;
  let canPickFolder = false;
  const destination = () => destinations.find((item) => item.id === $('destination').value);
  const status = (message) => { $('status').textContent = message; };

  function controls() {
    const target = destination();
    const locked = busy || !!confirmIds;
    $('close').disabled = busy;
    $('destination').disabled = locked || !destinations.length;
    $('refresh').disabled = locked;
    $('folder').hidden = !canPickFolder;
    $('folder-help').hidden = canPickFolder;
    $('folder').disabled = locked;
    $('settings').disabled = locked;
    $('source').disabled = locked || !target?.writable;
    $('send').disabled = locked || !target?.writable || !$('source').files.length;
    $('cancel').hidden = !upload;
    $('cancel').disabled = !upload || upload.cancelled || upload.committing;
    $('more').hidden = !hasMore;
    $('more').disabled = locked;
    $('delete').disabled = locked || !picked.size;
    $('confirm').hidden = !confirmIds;
    for (const element of $('list').querySelectorAll('button, input')) {
      element.disabled = locked || (element.type === 'checkbox' && !element.checked && picked.size >= 20);
    }
  }

  function drawRows() {
    const list = document.createDocumentFragment();
    for (const item of rows) {
      const row = document.createElement('li');
      const detail = document.createElement('div');
      const name = document.createElement('bdi');
      name.textContent = item.name;
      const bytes = document.createElement('small');
      bytes.textContent = size(item.bytes);
      detail.append(name, bytes);
      if (item.canDelete) {
        const box = document.createElement('input');
        box.type = 'checkbox';
        box.checked = picked.has(item.id);
        box.setAttribute('aria-label', `בחירת ${item.name} למחיקה`);
        box.onchange = () => {
          if (box.checked) picked.add(item.id);
          else picked.delete(item.id);
          controls();
        };
        row.append(box);
      }
      row.append(detail);
      if (item.canOpen) {
        const open = document.createElement('button');
        open.type = 'button';
        open.className = 'quiet';
        open.textContent = 'פתיחה בטלוויזיה';
        open.setAttribute('aria-label', `פתיחת ${item.name} בטלוויזיה`);
        open.onclick = () => run(async (call) => {
          await call('fileOpen', { destination: destination().id, id: item.id });
          status('בקשת הפתיחה נשלחה לטלוויזיה.');
        });
        row.append(open);
      }
      list.append(row);
    }
    $('list').replaceChildren(list);
  }

  function clearRows() {
    rows = [];
    picked.clear();
    nextOffset = 0;
    hasMore = false;
    drawRows();
  }

  async function listPage(call) {
    const target = destination();
    if (!target) return;
    if (target.needsAccess) {
      status(canPickFolder
        ? 'כדי להציג קבצים מהיעד הזה, בחרו תיקייה בטלוויזיה ואז רעננו.'
        : 'אין תיקייה זמינה ליעד הזה. אפשר לבדוק את האחסון בטלוויזיה.');
      return;
    }
    const { files, hasMore: more, truncated } = await call('fileList', { destination: target.id, offset: nextOffset });
    const known = new Set(rows.map((item) => item.id));
    rows.push(...files.filter((item) => !known.has(item.id)));
    nextOffset += files.length;
    hasMore = !!more && files.length > 0;
    drawRows();
    const count = rows.length ? `מוצגים ${rows.length} קבצים.` : 'לא נמצאו קבצים בחלק שנסרק.';
    status(truncated ? `${count} הסריקה חלקית וייתכן שיש קבצים נוספים ביעד.`
      : rows.length ? count : 'אין קבצים להצגה ביעד הזה.');
  }

  function describeDestination() {
    const target = destination();
    $('space').textContent = target
      ? `${target.detail ? `${target.detail} · ` : ''}פנוי: ${size(target.freeBytes)} מתוך ${size(target.totalBytes)}${target.needsAccess ? ' · נדרשת בחירת תיקייה בטלוויזיה' : target.writable ? '' : ' · שמירה אינה זמינה ביעד הזה'}`
      : 'אין יעד אחסון זמין.';
  }

  async function refresh(call) {
    const previous = $('destination').value;
    clearRows();
    const result = await call('fileDestinations', {});
    destinations = result.destinations;
    canPickFolder = result.folderPicker === true;
    $('destination').replaceChildren();
    for (const item of destinations) {
      const option = document.createElement('option');
      option.value = item.id;
      option.textContent = `${item.kind === 'usb' ? 'USB' : 'אחסון פנימי'} · ${item.name}`;
      $('destination').append(option);
    }
    if (destinations.some((item) => item.id === previous)) $('destination').value = previous;
    describeDestination();
    if (destination()) await listPage(call);
    else status('אין יעד זמין. אפשר לבדוק את האחסון או לבחור תיקייה בטלוויזיה.');
  }

  async function run(action) {
    if (busy || !connection?.ready || !dialog.open) return;
    const active = connection;
    busy = true;
    controls();
    const call = async (method, args) => {
      if (active !== connection || !active.ready) throw new Error('DISCONNECTED');
      const value = await active.call(method, args, 20000);
      if (active !== connection) throw new Error('DISCONNECTED');
      return value;
    };
    try { await action(call); }
    catch (error) {
      if (active === connection) status(error.message === 'FILE_CANCELLED' ? 'ההעברה בוטלה.' : messages[error.message] || 'פעולת הקבצים לא הושלמה.');
    } finally {
      if (active === connection) {
        busy = false;
        controls();
      }
    }
  }

  $('open').onclick = () => {
    if (!connection?.ready || dialog.open) return;
    dialog.showModal();
    status('טוענים יעדי אחסון…');
    run(refresh);
  };
  $('close').onclick = () => { if (!busy) dialog.close(); };
  dialog.addEventListener('cancel', (event) => {
    if (busy) event.preventDefault();
    else if (confirmIds) { event.preventDefault(); dismissConfirm(); }
  });
  dialog.addEventListener('close', () => {
    if (dialog.open) return;
    confirmIds = undefined;
    $('source').value = '';
    $('progress').hidden = true;
    clearRows();
    controls();
  });
  $('source').onchange = controls;
  $('refresh').onclick = () => run(refresh);
  $('destination').onchange = () => run(async (call) => {
    clearRows();
    describeDestination();
    await listPage(call);
  });
  $('more').onclick = () => run(listPage);
  $('folder').onclick = () => run(async (call) => {
    try {
      await call('fileChooseFolder', {});
      status('בחרו תיקייה בטלוויזיה. אחרי האישור חזרו לכאן ולחצו על רענון.');
    } catch (error) {
      if (error.message !== 'FILE_NO_PICKER') throw error;
      status('בחירת התיקייה לא נפתחה. נסו לפתוח את קרוב בטלוויזיה ולבחור תיקייה משם, או בדקו את הגדרות האחסון.');
    }
  });
  $('settings').onclick = () => run(async (call) => {
    try {
      await call('fileStorageSettings', {});
      status('בקשת פתיחת הגדרות האחסון נשלחה לטלוויזיה.');
    } catch (error) {
      if (error.message !== 'UNSUPPORTED') throw error;
      status('הטלוויזיה לא פתחה את הגדרות האחסון מרחוק. פתחו אותן דרך ההגדרות בטלוויזיה.');
    }
  });
  $('upload').onsubmit = (event) => {
    event.preventDefault();
    const file = $('source').files[0];
    const target = destination();
    if (!file || !target?.writable || confirmIds) return;
    run(async (call) => {
      const active = connection;
      const progress = $('progress');
      let announced = -1;
      progress.max = Math.max(1, file.size);
      progress.value = 0;
      progress.hidden = false;
      upload = {
        cancelled: false,
        committing: false,
        progress(received) {
          progress.value = received;
          const step = Math.floor((received / file.size) * 10);
          if (step === announced) return;
          announced = step;
          status(`נשלחו ${size(received)} מתוך ${size(file.size)}.`);
        },
        finishing() {
          this.committing = true;
          controls();
          status('הטלוויזיה משלימה את שמירת הקובץ…');
        },
      };
      controls();
      status('מכינים את השליחה…');
      try {
        const saved = await sendFile(call, target.id, file, upload);
        $('source').value = '';
        const result = `${saved.name} נשמר בטלוויזיה (${size(saved.bytes)}).`;
        status(result);
        try { await refresh(call); } catch { if (active === connection) status(`${result} אפשר לרענן את הרשימה ידנית.`); return; }
        status(result);
      } finally {
        if (active === connection) {
          upload = undefined;
          progress.hidden = true;
        }
      }
    });
  };
  $('cancel').onclick = () => {
    if (!upload || upload.committing) return;
    upload.cancelled = true;
    controls();
    status('מבטלים את ההעברה…');
  };

  function dismissConfirm() {
    confirmIds = undefined;
    controls();
    $('delete').focus();
  }
  $('delete').onclick = () => {
    const target = destination();
    if (!target || !picked.size || busy) return;
    confirmIds = [...picked];
    $('confirm-text').textContent = `למחוק לצמיתות ${confirmIds.length} קבצים שנבחרו מתוך ״${target.name}״? הפעולה אינה ניתנת לביטול.`;
    controls();
    $('delete-cancel').focus();
  };
  $('delete-cancel').onclick = dismissConfirm;
  $('delete-confirm').onclick = () => {
    const target = destination();
    if (!target || !confirmIds || busy) return;
    const ids = confirmIds;
    const active = connection;
    confirmIds = undefined;
    run(async (call) => {
      status('מוחקים את הקבצים שנבחרו…');
      clearRows();
      const { removed, failed, freedBytes } = await call('fileDelete', { destination: target.id, ids });
      const result = `נמחקו ${removed} קבצים. לא נמחקו: ${failed}. התפנו ${size(freedBytes)}.`;
      status(result);
      try { await refresh(call); } catch { if (active === connection) status(`${result} אפשר לרענן את הרשימה ידנית.`); return; }
      status(result);
      $('refresh').focus();
    });
  };

  return {
    attach(active, supported) {
      connection = supported ? active : undefined;
      $('open').hidden = !connection;
    },
    detach() {
      if (upload) upload.cancelled = true;
      upload = undefined;
      connection = undefined;
      busy = false;
      destinations = [];
      $('destination').replaceChildren();
      canPickFolder = false;
      $('open').hidden = true;
      if (dialog.open) dialog.close();
    },
  };
}
