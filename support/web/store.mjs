// מפתח הזיווג נשמר כ-CryptoKey שאינו ניתן לייצוא, ולא כסיסמה גלויה ב-localStorage.
const DATABASE = 'tv-family-support';
function database() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () =>
      request.result.createObjectStore('devices', { keyPath: 'device' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error('STORAGE_UNAVAILABLE'));
  });
}
async function transaction(mode, operation) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const tx = db.transaction('devices', mode);
    const request = operation(tx.objectStore('devices'));
    tx.oncomplete = () => {
      db.close();
      resolve(request.result);
    };
    tx.onerror = tx.onabort = () => {
      db.close();
      reject(new Error('STORAGE_UNAVAILABLE'));
    };
  });
}
export const savedDevices = () => transaction('readonly', (store) => store.getAll());
export const saveDevice = (device) => transaction('readwrite', (store) => store.put(device));
export const forgetDevice = (id) => transaction('readwrite', (store) => store.delete(id));
