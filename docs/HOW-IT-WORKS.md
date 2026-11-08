# איך זה עובד

[English](HOW-IT-WORKS.en.md) | **עברית**

## פלטפורמות נתמכות

האפליקציה מדברת עם כל פלטפורמה דרך דרייבר ב-`src/main/drivers`. כל דרייבר מציע את אותן פעולות ומצהיר על `capabilities` ועל `buttons`. הממשק מסתיר את מה שהטלוויזיה המחוברת לא מציעה. בפרויקט המקורי תועדה בדיקה מול מכשיר LG אמיתי; דרייבר Android TV הופעל גם בכלי אבחון מול Xiaomi פיזי. אין בכך בדיקה מלאה של ממשק המחשב מול Xiaomi. בדיקות אפליקציית קרוב, המותקנת בסטרימר, מתועדות בנפרד ב-[support/VERIFY.md](../support/VERIFY.md).

| פלטפורמה | חיבור | צימוד | סוד שנשמר |
|---|---|---|---|
| LG webOS | WebSocket, פורטים 3001 ו-3000 | אישור בטלוויזיה או PIN | Client key |
| Samsung Tizen | WebSocket, פורטים 8002 ו-8001 | אישור בטלוויזיה | Token |
| Android TV, Google TV | TLS עם protobuf: פורט 6467 לצימוד, 6466 לשליטה | קוד בן 6 תווים בטלוויזיה | תעודה שהאפליקציה יוצרת |
| Roku | HTTP, פורט 8060 | אין | אין |
| Vizio SmartCast | HTTPS, פורטים 7345 ו-9000 | קוד בטלוויזיה | Token |
| Sony BRAVIA | HTTP: JSON-RPC ו-IRCC SOAP | מפתח שמוגדר בטלוויזיה | מפתח משותף |

- **זיהוי:** ב"זיהוי אוטומטי" האפליקציה בודקת בכתובת את התשובה או הפורטים של כל פלטפורמה, לפי הסדר Roku, Samsung, LG, Vizio, Android TV, Sony. טלוויזיה שמורה מדלגת על זה.
- **חיפוש ברשת:** האפליקציה שולחת חיפושי SSDP עבור LG, Samsung, Roku ו-Sony, ושאילתות mDNS עבור Android TV ו-Vizio, מכל מתאמי הרשת.
- **צימוד Android TV:** הסוד הוא גיבוב SHA-256 של מפתחות שתי התעודות ושל הקוד. הבייט הראשון שלו חייב להתאים לבייט הראשון של הקוד, וכך טעות הקלדה נתפסת לפני ששולחים משהו.
- **אפליקציות ב-Android TV:** הפרוטוקול לא מאפשר לקבל רשימת אפליקציות, ולכן האפליקציה מציעה אפליקציות נפוצות דרך קישורי אפליקציה.
- **זיהוי ניתוק:** ב-Roku, Vizio ו-Sony אין חיבור פתוח, ולכן האפליקציה שואלת אותן שוב ושוב, ו-3 כשלים ברצף נחשבים לניתוק.

כל מה שמופיע בהמשך מתאר את הדרייבר של LG, אלא אם כתוב אחרת.

## התמונה הכללית

```text
 Windows PC                                   LG TV (webOS)
┌───────────────────────────────┐            ┌──────────────────────┐
│ Renderer: השלט והמסך          │            │                      │
│   src/renderer                │            │ SSAP       :3001 wss │
│        │ IPC (פעולות מוגדרות) │            │ ערוץ פקודות          │
│        ▼                      │   wss      │                      │
│ Main: חיבור, תצוגה, אחסון     │◄──────────►│ Pointer socket       │
│   src/main/main.js            │            │ כפתורים ועכבר        │
│   src/main/webos.js           │   http     │                      │
│   src/main/store.js           │◄───────────│ צילום מסך (קובץ)     │
└───────────────────────────────┘            └──────────────────────┘
```

האפליקציה בנויה ב-Electron. כל התקשורת עם הטלוויזיה נמצאת בתהליך הראשי, והממשק יכול להפעיל רק רשימה סגורה של פעולות.

## שלושה ערוצים

### 1. פקודות (SSAP)

הודעות JSON ב-WebSocket מאובטח על פורט 3001, עם מעבר ל-`ws` על פורט 3000 אם צריך. לטלוויזיה תעודה בחתימה עצמית, ולכן אי אפשר לאמת אותה.

```json
{ "type": "request", "id": "req_1", "uri": "ssap://audio/setVolume", "payload": { "volume": 20 } }
```

| פעולה | URI |
|---|---|
| רשימת אפליקציות | `ssap://com.webos.applicationManager/listLaunchPoints` |
| פתיחת אפליקציה | `ssap://com.webos.applicationManager/launch` |
| מקורות | `ssap://tv/getExternalInputList`, `ssap://tv/switchInput` |
| עוצמת קול | `ssap://audio/getVolume`, `setVolume`, `setMute` |
| הקלדה | `ssap://com.webos.service.ime/insertText`, `deleteCharacters`, `sendEnterKey` |
| כיבוי | `ssap://system/turnOff` |
| כיבוי מסך בלבד | `ssap://com.webos.service.tvpower/power/turnOffScreen` |

### 2. כפתורים ועכבר (Pointer socket)

הקריאה `ssap://com.webos.service.networkinput/getPointerInputSocket` מחזירה כתובת של WebSocket נוסף, שבו עוברות הודעות טקסט:

```text
type:button
name:HOME

type:move
dx:5
dy:-3
down:0

type:click

type:scroll
dx:0
dy:-1

```

התנועה יחסית, ולכן אין פקודה של "לחץ על נקודה X,Y". האפליקציה משתמשת ב-Pointer Lock של הדפדפן כדי לקבל תנועת עכבר גולמית, ושולחת אותה מקובצת כל 16 מילישניות.

#### הסמן שעל התמונה

צילומי המסך לא כוללים את סמן הטלוויזיה, ואין API שמחזיר את מיקומו. לכן האפליקציה מעריכה אותו:

1. בכניסה ללכידה היא דוחפת את הסמן לפינה השמאלית העליונה, וכך מתחילה ממיקום ידוע.
2. לכל תנועה היא מחשבת מרחק לפי `gain = 0.5 + 0.25 × מהירות`, כי הטלוויזיה מאיצה את הסמן לפי מהירות.
3. המיקום נעצר בקצוות המסך, וכך סטייה מתוקנת כשמגיעים לקצה.

הנוסחה נמדדה על OLED55C6 במהירויות איטית ובינונית: 2 יחידות לאירוע נתנו יחס של כ-1, ו-10 יחידות נתנו כ-3. במהירויות גבוהות וגם בדגמים אחרים היא הערכה בלבד.

### 3. תצוגה (צילומי מסך)

`ssap://tv/executeOneShot` מחזירה `imageUri`, ואז האפליקציה מורידה את התמונה ומציגה אותה. זו לולאה: צילום, הורדה, הצגה, והצילום הבא. לכן הקצב מוגבל למה שהטלוויזיה מספיקה, וזו לא הזרמת וידאו.

בטלוויזיה שנבדקה התקבלו 960×540 בכ-3–4 תמונות בשנייה, כולל ערוץ טלוויזיה חי.

הטלוויזיה מחזירה כתובות עם ה-IP שלה ברשת הביתית. האפליקציה מחליפה אותן בכתובת שאליה התחברה, כך שגם מעבר ל-VPN היא פונה לאותה טלוויזיה ולא למכשיר אחר.

## צימוד

```text
App                                     TV
 │ register (חתום, עם client-key אם יש)  │
 ├──────────────────────────────────────►│
 │  403 blacklisted certificate          │  קושחות חדשות
 │◄──────────────────────────────────────┤
 │ register מחדש, בלי החתימה             │
 ├──────────────────────────────────────►│
 │  pairingType: PROMPT                  │
 │◄──────────────────────────────────────┤
 │                                       │  המשתמש מאשר בשלט
 │  registered + client-key              │
 │◄──────────────────────────────────────┤
```

- **החתימה:** ספריות קוד פתוח משתמשות בחתימה ציבורית ישנה. קושחות חדשות חוסמות אותה, אז האפליקציה מנסה קודם איתה, כדי לתמוך בטלוויזיות ישנות, ואחר כך בלעדיה. בלי החתימה הטלוויזיה עשויה להעניק פחות הרשאות.
- **האישור:** בכל מקרה בעל הטלוויזיה מאשר על המסך.
- **המפתח:** `client-key` נשמר מוצפן ב-DPAPI בקובץ `tvs.json` בתיקיית הנתונים של האפליקציה. מפתח שנדחה גורם לצימוד מחדש.
- **צימוד בקוד PIN:** האפליקציה נרשמת עם `pairingType: PIN` ושולחת את הקוד אל `ssap://pairing/setPin`.

## איתור הטלוויזיה והדלקתה

- **איתור:** חיפוש SSDP ב-UDP פורט 1900 אחרי `urn:lge-com:service:webos-second-screen:1`. עובד רק ברשת המקומית.
- **הדלקה:** חבילת Wake-on-LAN רגילה, בשידור ב-UDP פורט 9, אל כתובות ה-MAC שנשמרו בחיבור קודם.

## חיבור יציב

- ping כל 10 שניות מזהה חיבור מת, למשל כשה-VPN נופל.
- ניסיונות חיבור מחדש מתרחקים בהדרגה מ-2 שניות עד 30 שניות.
- תצוגה שנכשלת 3 פעמים ברצף נעצרת עם הודעה, והשליטה ממשיכה.

## אבטחה

- בממשק `contextIsolation` ו-`sandbox` פעילים, ו-`nodeIntegration` כבוי.
- מדיניות CSP מחמירה: בלי סקריפטים חיצוניים, ותמונות רק כ-`data:`.
- שמות אפליקציות מהטלוויזיה מוצגים כטקסט ולא כ-HTML, ויש על כך בדיקה.
- שמות כפתורים נבדקים מול רשימה סגורה, כדי שלא יוזרקו שדות לפרוטוקול.
- הורדת התמונה מוגבלת ל-20MB ולסוג תמונה בלבד.

## מבנה הקוד

| קובץ | תפקיד |
|---|---|
| `src/main/drivers/index.js` | רשימת הפלטפורמות והזיהוי האוטומטי |
| `src/main/drivers/common.js` | מה שמשותף לדרייברים |
| `src/main/drivers/discovery.js` | חיפוש ברשת |
| `src/main/drivers/webos.js` | LG |
| `src/main/drivers/samsung.js` | Samsung |
| `src/main/drivers/androidtv.js` | Android TV ו-Google TV |
| `src/main/drivers/roku.js` | Roku |
| `src/main/drivers/vizio.js` | Vizio |
| `src/main/drivers/sony.js` | Sony |
| `src/main/manifest.js` | ההרשאות שהאפליקציה מבקשת מטלוויזיות LG |
| `src/main/main.js` | חלון, חיבור מחדש, לולאת תצוגה, IPC |
| `src/main/store.js` | שמירת טלוויזיות ומפתחות |
| `src/main/preload.js` | הגשר בין הממשק לתהליך הראשי |
| `src/renderer/` | הממשק |
| `src/renderer/i18n.js` | הטקסטים בעברית ובאנגלית |
| `install.ps1` | מתקין ה-PowerShell בפקודה אחת |
| `tests/fake-tv.js` | סימולטור טלוויזיית LG |
| `tests/fake-brands.js` | סימולטורים של שאר המותגים |
| `tests/brands.test.js` | בדיקות פרוטוקול לשאר המותגים |
| `tests/e2e-brands.js` | בדיקת האפליקציה המלאה עם שאר המותגים |
| `tests/packaged-smoke.js` | בדיקת הגרסה הארוזה |
| `tests/webos.test.js` | בדיקות פרוטוקול |
| `tests/e2e.js` | בדיקת האפליקציה המלאה |
| `tests/real-tv-check.js` | בדיקה מול טלוויזיה אמיתית |
| `tests/real-pointer-probe.js` | השוואת הסמן המשוער לטלוויזיה אמיתית |
| `tests/docs-screenshots.js` | יצירת צילומי התיעוד |

## מקורות

ה-API לא מתועד רשמית על ידי LG, וההתנהגות עלולה להשתנות בעדכון קושחה. אותו סיכון קיים גם ב-Samsung, Android TV ו-Vizio. הפרוטוקולים מבוססים על:

- [samsung-tv-ws-api](https://github.com/xchwarze/samsung-tv-ws-api)
- [androidtvremote2](https://github.com/tronikos/androidtvremote2)
- [Roku External Control Protocol](https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md)
- [pyvizio](https://github.com/raman325/pyvizio) ו-[הערות על ה-API של Vizio](https://github.com/exiva/Vizio_SmartCast_API)
- [pybravia](https://github.com/Drafteed/pybravia)
- [aiowebostv](https://pypi.org/project/aiowebostv/)
- [lgtvremote-cli](https://pypi.org/project/lgtvremote-cli/)
- [תיעוד קהילתי של צילום המסך](https://gist.github.com/Informatic/1983f2e501444cf1cbd182e50820d6c1)
- [הדיווח על חסימת החתימה ב-Home Assistant](https://github.com/home-assistant/core/issues/179669)
