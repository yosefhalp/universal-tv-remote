const $ = (id) => document.getElementById(id);
const errors = {
  INVALID_CREDENTIALS: 'האימייל או שם המשתמש והסיסמה לא התקבלו.',
  BAD_CREDENTIALS: 'שם המשתמש או הסיסמה לא התקבלו.',
  USERNAME_TAKEN: 'שם המשתמש כבר קיים. בחרו שם אחר.',
  BAD_USERNAME: 'שם המשתמש צריך להכיל 3–32 אותיות, ספרות, נקודה, מקף או קו תחתון.',
  BAD_PASSWORD: 'הסיסמה צריכה להכיל 8–128 תווים.',
  INSTALLATION_PASSWORD_REQUIRED: 'ליצירת מנהל נדרשת סיסמת ההתקנה.',
  INSTALLATION_PASSWORD_INVALID: 'סיסמת ההתקנה לא התקבלה.',
  RATE_LIMIT: 'בוצעו יותר מדי ניסיונות. המתינו מעט ונסו שוב.',
  MUST_CHANGE_PASSWORD: 'יש להחליף את הסיסמה לפני השימוש בטלוויזיות.',
  ACCOUNT_DENIED: 'הגישה לחשבון הסתיימה. התחברו שוב.',
  INVALID_USERNAME: 'יש להזין כתובת אימייל תקינה או שם משתמש באורך 3–32 תווים.',
  INVALID_PASSWORD: 'הסיסמה צריכה להכיל 8–128 תווים.',
  INSTALLATION_REQUIRED: 'סיסמת ההתקנה נדרשת ליצירת המנהל הראשון. בדקו את הסיסמה ונסו שוב.',
  PASSWORD_CHANGE_REQUIRED: 'יש להחליף את הסיסמה לפני השימוש בטלוויזיות.',
  LIMIT: 'בוצעו יותר מדי ניסיונות או שהגעתם למגבלת החשבון. המתינו מעט ונסו שוב.',
  UNAUTHORIZED: 'הכניסה לחשבון הסתיימה. התחברו שוב.',
  FORBIDDEN: 'אין לחשבון הרשאה לפעולה הזו.',
  DEVICE_NOT_FOUND: 'הטלוויזיה אינה רשומה בשרת. בדקו שהיא מחוברת ועודכנה.',
  STALE_ACCOUNT: 'החשבון השתנה בחלון אחר. הפרטים עודכנו ואפשר לנסות שוב.',
  ACCOUNT_UPDATE_REQUIRED: 'יש לעדכן את קרוב בטלוויזיה לפני סנכרון לחשבון. הזיווג המקומי נשמר.',
  ACCOUNT_LINK_PENDING: 'האישור עדיין לא הופיע בחשבון. הזיווג המקומי נשמר; אפשר לרענן את הרשימה או לנסות שוב.',
  ACCOUNT_LINK_EXPIRED: 'הבקשה לסנכרון פגה. לחצו שוב על התחברות וסנכרון.',
  ACCOUNT_SELECTION_CHANGED: 'הטלוויזיה המחוברת השתנתה. בחרו שוב את הטלוויזיה לסנכרון.',
  OFFLINE: 'הטלוויזיה אינה מחוברת כרגע. בדקו שהיא דלוקה ומחוברת לאינטרנט.',
  BUSY: 'מכשיר אחר שולט בטלוויזיה כרגע. סיימו שם את העזרה ונסו שוב.',
  DISCONNECTED: 'לא נוצר חיבור לטלוויזיה. הזיווג המקומי נשמר ואפשר לנסות שוב.',
  NETWORK: 'לא ניתן להגיע לשרת. הזיווג המקומי והרשימה השמורה נשארים במכשיר.',
  CONTROLLER_LIMIT: 'רשימת המכשירים המורשים בטלוויזיה מלאה. בטלוויזיה או מתוך חיבור קיים בטלו הרשאה של מכשיר ישן, ואז נסו לסנכרן שוב.',
  ACCOUNT_LINK_FAILED: 'הטלוויזיה לא אישרה את הסנכרון. הזיווג המקומי נשמר; אפשר לנסות שוב.',
  ACCOUNT_LINK_BUSY: 'בקשת סנכרון קודמת עדיין מסתיימת. המתינו מעט ונסו שוב.',
};
export function accountMessage(error) {
  return errors[error.message] || 'לא ניתן להשלים את הפעולה. בדקו את הפרטים ונסו שוב.';
}
export function accountDeviceSummary(devices) {
  return devices.length
    ? `בחשבון יש ${devices.length} טלוויזיות. הרשימה מוצגת גם במסך הבית.`
    : 'אין עדיין טלוויזיות בחשבון. כדי להוסיף טלוויזיה ששמורה בדפדפן הזה, בחרו סנכרון טלוויזיה לחשבון.';
}

// רק הרשאה שנשמרה ברשימת החשבון מאפשרת להציג הצלחה, גם אם אישור הסטרימר מתעכב.
export async function linkAccountTelevision({ client, device, getActive, connect, onProgress = () => {}, sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)), now = Date.now, pollTimeout = 30000 }) {
  const accountGeneration = client.generation, token = client.token;
  const guardAccount = () => {
    if (!token || client.token !== token || client.generation !== accountGeneration || client.user?.mustChangePassword) throw new Error('STALE_ACCOUNT');
  };
  const sameSelection = (state) => state?.device && !state.device.account && state.device.device === device.device && state.device.controller === device.controller;
  guardAccount();
  if (device.account) throw new Error('ACCOUNT_SELECTION_CHANGED');
  onProgress(`מתחברים אל ${device.name} באמצעות הזיווג המקומי…`);
  if (!sameSelection(getActive()) || !getActive().connection?.ready) await connect(device);
  guardAccount();
  const state = getActive(), active = state.connection;
  if (!sameSelection(state) || !active?.ready) throw new Error('DISCONNECTED');
  if (!state.supported) throw new Error('ACCOUNT_UPDATE_REQUIRED');
  const guardSelection = () => {
    guardAccount();
    if (!sameSelection(getActive())) throw new Error('ACCOUNT_SELECTION_CHANGED');
  };
  onProgress('מכינים הרשאה לחשבון וממתינים לאישור הטלוויזיה…');
  let prepared = await client.prepare(device.device, device.name);
  guardSelection();
  if (!prepared.linked && prepared.expiresAt <= now()) prepared = await client.prepare(device.device, device.name);
  guardSelection();
  if (!prepared.linked) {
    if (!Number.isFinite(prepared.expiresAt) || prepared.expiresAt <= now()) throw new Error('ACCOUNT_LINK_EXPIRED');
    if (getActive().connection !== active || getActive().generation !== state.generation || !active.ready) throw new Error('ACCOUNT_SELECTION_CHANGED');
    try {
      await active.call('grantAccount', { ticket: prepared.ticket, controller: prepared.controller, secret: prepared.secret, name: device.name }, 14000);
    } catch (error) {
      if (!['REQUEST_TIMEOUT', 'DISCONNECTED'].includes(error.message)) throw error;
    }
  }
  const controller = prepared.linked ? prepared.record?.controller : prepared.controller;
  const deadline = now() + pollTimeout;
  for (let attempt = 0; attempt < 21; attempt++) {
    guardSelection();
    if (attempt > 0 && now() >= deadline) break;
    let timer, readSucceeded = false;
    try {
      // מגבילים גם בקשת רשת איטית: תשובה מאוחרת אינה משמשת להכרזת הצלחה כאן.
      await Promise.race([
        client.sync(),
        new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('ACCOUNT_LINK_PENDING')), Math.max(1, deadline - now())); }),
      ]);
      readSucceeded = true;
    } catch (error) {
      if (!['NETWORK', 'UNAVAILABLE'].includes(error.message)) throw error;
    } finally { clearTimeout(timer); }
    guardSelection();
    const durable = readSucceeded && client.devices.find((item) => item.device === device.device && item.controller === controller);
    if (durable) return durable;
    if (now() >= deadline || attempt === 20) break;
    onProgress('ממתינים שהטלוויזיה תופיע בחשבון. אין צורך לסגור את החלון…');
    await sleep(Math.min(1500, deadline - now()));
  }
  throw new Error('ACCOUNT_LINK_PENDING');
}

export function accountUI(client, { onLogout, onSync, onLink, localDevices = async () => [] }) {
  let resetUser;
  let displayedToken;
  let busy = false;
  const message = (text) => {
    $('account-message').textContent = text;
  };
  async function run(action) {
    if (busy) return;
    busy = true;
    const controls = [...$('account-dialog').querySelectorAll('button')];
    controls.forEach((button) => {
      button.disabled = true;
    });
    message('ממתינים לתשובת השרת…');
    try {
      const result = await action();
      message(typeof result === 'string' ? result : '');
    } catch (error) {
      message(accountMessage(error));
    } finally {
      busy = false;
      $('account-dialog').querySelectorAll('button').forEach((button) => {
        button.disabled = false;
      });
      render();
    }
  }
  function render() {
    const user = client.user;
    if (displayedToken !== client.token) {
      $('account-users').replaceChildren();
      displayedToken = client.token;
      $('account-local-picker').hidden = true;
      $('account-local-devices').replaceChildren();
    }
    $('account-guest').hidden = !!user;
    $('account-member').hidden = !user;
    $('account-open').textContent = user ? `חשבון: ${user.username}` : 'החשבון שלי';
    $('account-link').hidden = !user || user.mustChangePassword;
    if (!user) {
      $('account-users').replaceChildren();
      return;
    }
    $('account-user').textContent = `${user.username}${user.role === 'admin' ? ' · מנהל' : ''}`;
    $('account-forced').hidden = !user.mustChangePassword;
    $('account-sync').hidden = user.mustChangePassword;
    $('account-refresh').hidden = user.mustChangePassword;
    $('account-device-status').hidden = user.mustChangePassword;
    $('account-device-status').textContent = accountDeviceSummary(client.devices);
    if (user.mustChangePassword) $('account-local-picker').hidden = true;
    $('account-admin').hidden = user.role !== 'admin' || user.mustChangePassword;
    if (user.mustChangePassword) $('account-password-details').open = true;
  }
  async function users() {
    const result = await client.users();
    $('account-users').replaceChildren();
    for (const user of result.users) {
      const row = document.createElement('div');
      row.className = 'account-user-row';
      const title = document.createElement('p');
      title.textContent = `${user.username} · ${user.role === 'admin' ? 'מנהל' : 'משתמש'} · ${user.disabled ? 'חסום' : 'פעיל'} · ${user.deviceCount} טלוויזיות`;
      row.append(title);
      if (user.id !== client.user.id) {
        const reset = document.createElement('button');
        reset.textContent = 'איפוס סיסמה';
        reset.onclick = () => {
          resetUser = user;
          $('account-reset-user').textContent = `איפוס עבור ${user.username}`;
          $('account-reset-form').reset();
          $('account-reset-message').textContent = '';
          $('account-reset-dialog').showModal();
        };
        row.append(reset);
        if (user.role !== 'admin') {
          const state = document.createElement('button');
          state.className = 'quiet';
          state.textContent = user.disabled ? 'ביטול חסימה' : 'חסימת משתמש';
          state.onclick = () =>
            run(async () => {
              await client.state(user.id, !user.disabled);
              await users();
            });
          row.append(state);
        }
      }
      $('account-users').append(row);
    }
  }
  async function open() {
    render();
    if (!$('account-dialog').open) $('account-dialog').showModal();
    await run(async () => {
      if (client.user) {
        await client.refresh();
        if (client.user?.role === 'admin' && !client.user.mustChangePassword) await users();
        return client.user?.mustChangePassword ? 'יש לבחור סיסמה חדשה לפני סנכרון טלוויזיות.' : accountDeviceSummary(client.devices);
      } else {
        const result = await client.status();
        $('account-setup').hidden = $('account-installation-wrap').hidden = !result.setupRequired;
        $('account-login').hidden = result.setupRequired;
        $('account-register').textContent = result.setupRequired
          ? 'יצירת מנהל ראשון'
          : 'יצירת חשבון';
        return 'נכנסים לחשבון כדי לשמור טלוויזיות ולפתוח אותן גם במכשירים נוספים.';
      }
    });
  }
  $('account-open').onclick = open;
  $('account-close').onclick = () => $('account-dialog').close();
  $('account-dialog').addEventListener('close', () => {
    $('account-auth').reset();
    $('account-password-form').reset();
  });
  $('account-auth').onsubmit = (event) => {
    event.preventDefault();
    const register = event.submitter?.value === 'register';
    const body = { username: $('account-username').value, password: $('account-password').value };
    if (register && !$('account-installation-wrap').hidden)
      body.installationPassword = $('account-installation').value;
    run(async () => {
      await (register ? client.register(body) : client.login(body));
      $('account-auth').reset();
      if (client.user?.role === 'admin' && !client.user.mustChangePassword) await users();
      return client.user?.mustChangePassword ? 'יש להחליף את הסיסמה לפני שימוש בטלוויזיות.' : accountDeviceSummary(client.devices);
    });
  };
  $('account-password-form').onsubmit = (event) => {
    event.preventDefault();
    const body = {
      currentPassword: $('account-current').value,
      newPassword: $('account-new').value,
    };
    run(async () => {
      await client.password(body);
      $('account-password-form').reset();
      return 'הסיסמה החדשה נשמרה.';
    });
  };
  async function chooseLocal() {
    if (!client.token || client.user?.mustChangePassword) return 'יש להיכנס לחשבון ולבחור סיסמה תקפה לפני סנכרון.';
    const generation = client.generation, token = client.token;
    const devices = await localDevices();
    if (generation !== client.generation || token !== client.token) throw new Error('STALE_ACCOUNT');
    $('account-local-picker').hidden = false;
    $('account-local-devices').replaceChildren();
    for (const device of devices) {
      const row = document.createElement('div'); row.className = 'account-user-row';
      const name = document.createElement('p'); name.textContent = device.name;
      const button = document.createElement('button'); button.className = 'primary';
      button.textContent = 'התחברות וסנכרון'; button.disabled = busy;
      button.onclick = () => run(async () => {
        if (generation !== client.generation || token !== client.token) throw new Error('STALE_ACCOUNT');
        const durable = await onLink(device, message);
        if (generation !== client.generation || token !== client.token) throw new Error('STALE_ACCOUNT');
        if (!durable || !client.devices.some((item) => item.device === durable.device && item.controller === durable.controller)) throw new Error('ACCOUNT_LINK_PENDING');
        await onSync();
        return `${durable.name} נשמרה בחשבון וזמינה לאחר כניסה גם בטלפון ובטאבלט נוספים.`;
      });
      row.append(name, button); $('account-local-devices').append(row);
    }
    $('account-local-empty').hidden = devices.length > 0;
    return devices.length ? 'בחרו את הטלוויזיה. התחברות וסנכרון ישתמשו בזיווג הקיים וישמרו אותו.' : 'אין טלוויזיה מקומית בדפדפן הזה. סרקו QR או חברו בקוד, או פתחו את החשבון במכשיר שכבר זיווגתם.';
  }
  $('account-sync').onclick = () => run(chooseLocal);
  $('account-refresh').onclick = () =>
    run(async () => {
      await client.refresh();
      await onSync();
      return client.user?.mustChangePassword ? 'יש להחליף סיסמה לפני גישה לטלוויזיות.' : accountDeviceSummary(client.devices);
    });
  $('account-logout').onclick = () =>
    run(async () => {
      try {
        await client.logout();
      } finally {
        await onLogout();
      }
      return 'יצאתם מהחשבון. זיווגים מקומיים קודמים נשארו בדפדפן.';
    });
  $('account-users-refresh').onclick = () => run(users);
  $('account-link').onclick = async () => { await open(); await run(chooseLocal); };
  $('account-reset-cancel').onclick = () => $('account-reset-dialog').close();
  $('account-reset-dialog').addEventListener('close', () => {
    $('account-reset-form').reset();
    resetUser = null;
  });
  $('account-reset-form').onsubmit = async (event) => {
    event.preventDefault();
    const button = event.submitter;
    button.disabled = true;
    try {
      await client.reset(resetUser.id, $('account-reset-password').value);
      $('account-reset-dialog').close();
      await users();
      message('הסיסמה אופסה והחיבורים הישנים נותקו.');
    } catch (error) {
      $('account-reset-message').textContent = accountMessage(error);
    } finally {
      button.disabled = false;
    }
  };
  render();
  return { render, open };
}
