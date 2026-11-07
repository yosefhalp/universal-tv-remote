// רק התקדמות הרשימה נשמרת כאן. המדריך אינו מקבל מפתחות, קודי זיווג או פרטי סטרימר.
const checks = [...document.querySelectorAll('[data-check]')];
function updateProgress() {
  const count = checks.filter((input) => input.checked).length;
  document.querySelector('#check-progress').textContent =
    `${count} מתוך ${checks.length} בדיקות הושלמו בדפדפן הזה`;
}
for (const input of checks) {
  try {
    input.checked = localStorage.getItem(`karov-guide-1:${input.dataset.check}`) === 'yes';
  } catch {
    /* הרשימה פועלת גם ללא אחסון מקומי. */
  }
  input.addEventListener('change', () => {
    try {
      localStorage.setItem(`karov-guide-1:${input.dataset.check}`, input.checked ? 'yes' : 'no');
    } catch {
      /* מצב פרטי עשוי למנוע שמירה. */
    }
    updateProgress();
  });
}
updateProgress();
let toastTimer;
for (const button of document.querySelectorAll('[data-copy]')) {
  button.addEventListener('click', async () => {
    const status = document.querySelector('#copy-status');
    try {
      await navigator.clipboard.writeText(document.getElementById(button.dataset.copy).textContent);
      status.textContent = 'הועתק ללוח במכשיר הזה';
    } catch {
      status.textContent = 'ההעתקה לא התאפשרה. אפשר לסמן את הטקסט ולהעתיק ידנית.';
    }
    status.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      status.hidden = true;
    }, 4000);
  });
}
