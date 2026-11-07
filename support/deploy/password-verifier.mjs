import { passwordVerifier } from '../server/enrollment.mjs';

// כלי פנימי: קלט דרך stdin בלבד, בלי סיסמה בארגומנטים או בקובץ טקסט.
let input = '';
for await (const chunk of process.stdin) {
  input += chunk.toString('utf8');
  if (input.length > 2048) throw new Error('INVALID_PASSWORD');
}
const password = JSON.parse(input);
process.stdout.write(await passwordVerifier(password));
