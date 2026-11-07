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
  NETWORK: 'לא ניתן להגיע לשרת. הטלוויזיות השמורות נשארות במכשיר.',
  INVALID_USERNAME: 'יש להזין כתובת אימייל תקינה או שם משתמש באורך 3–32 תווים.',
  INVALID_PASSWORD: 'הסיסמה צריכה להכיל 8–128 תווים.',
  INSTALLATION_REQUIRED: 'סיסמת ההתקנה נדרשת ליצירת המנהל הראשון. בדקו את הסיסמה ונסו שוב.',
  PASSWORD_CHANGE_REQUIRED: 'יש להחליף את הסיסמה לפני השימוש בטלוויזיות.',
  LIMIT: 'בוצעו יותר מדי ניסיונות או שהגעתם למגבלת החשבון. המתינו מעט ונסו שוב.',
  UNAUTHORIZED: 'הכניסה לחשבון הסתיימה. התחברו שוב.',
  FORBIDDEN: 'אין לחשבון הרשאה לפעולה הזו.',
  DEVICE_NOT_FOUND: 'הטלוויזיה אינה רשומה בשרת. בדקו שהיא מחוברת ועודכנה.',
  STALE_ACCOUNT: 'החשבון השתנה בחלון אחר. הפרטים עודכנו ואפשר לנסות שוב.',
};
export function accountMessage(error) {
  return errors[error.message] || 'לא ניתן להשלים את הפעולה. בדקו את הפרטים ונסו שוב.';
}
export function accountUI(client, { onLogout, onSync, onLink }) {
  let resetUser;
  let displayedToken;
  const message = (text) => {
    $('account-message').textContent = text;
  };
  async function run(action) {
    const controls = [...$('account-dialog').querySelectorAll('button')];
    controls.forEach((button) => {
      button.disabled = true;
    });
    message('ממתינים לתשובת השרת…');
    try {
      await action();
      message('הפעולה הושלמה.');
    } catch (error) {
      message(accountMessage(error));
    } finally {
      controls.forEach((button) => {
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
      } else {
        const result = await client.status();
        $('account-setup').hidden = $('account-installation-wrap').hidden = !result.setupRequired;
        $('account-login').hidden = result.setupRequired;
        $('account-register').textContent = result.setupRequired
          ? 'יצירת מנהל ראשון'
          : 'יצירת חשבון';
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
    });
  };
  $('account-sync').onclick = () =>
    run(async () => {
      await client.refresh();
      await onSync();
    });
  $('account-logout').onclick = () =>
    run(async () => {
      try {
        await client.logout();
      } finally {
        await onLogout();
      }
    });
  $('account-users-refresh').onclick = () => run(users);
  $('account-link').onclick = onLink;
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
