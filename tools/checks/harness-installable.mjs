// What may become an install button, checked against the real harness rather than a copy.
//
// The harness has no test runner, so this pulls the validator's own source out of the file and
// drives it against a temporary workspace. Worth the trick: this is the one artifact whose button
// asks Android to install software, and every case below is a way that could go wrong quietly --
// a path outside the folder that reaches the phone, a directory named .apk, a symlink out of the
// box, a refusal with nothing in it for the agent to act on.
//
//   node tools/checks/harness-installable.mjs guest/harness/box-claude-harness.mjs
import { readFileSync, mkdirSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
const src = readFileSync(process.argv[2], 'utf8');
const grab = (name) => {
  const start = src.indexOf(`function ${name}(`);
  if (start < 0) throw new Error(`missing ${name}`);
  let i = src.indexOf('{', start), depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break; } }
  }
  return src.slice(start, i);
};

const root = `${process.cwd()}/ws`;
rmSync(root, { recursive: true, force: true });
mkdirSync(`${root}/shared/out`, { recursive: true });
mkdirSync(`${root}/build`, { recursive: true });
writeFileSync(`${root}/shared/app.apk`, 'x');
writeFileSync(`${root}/shared/App.APK`, 'x');
writeFileSync(`${root}/shared/out/box.apk`, 'x');
writeFileSync(`${root}/shared/notes.md`, 'x');
writeFileSync(`${root}/build/elsewhere.apk`, 'x');
mkdirSync(`${root}/shared/folder.apk`, { recursive: true });

const mod = new Function('realpathSync','statSync','WORKSPACE_ON_DISK', `
  const WORKSPACE = '/workspace/';
  const CREDENTIALS = '/workspace/.config';
  const SHARED = WORKSPACE + 'shared/';
  const mediaType = () => 'application/octet-stream';
  ${grab('showable')}
  ${grab('installable')}
  return installable;`);
const { realpathSync, statSync } = await import('node:fs');
const installable = mod(realpathSync, statSync, root + '/');

let pass = 0, fail = 0;
const check = (label, got, want) => {
  const ok = got === want;
  ok ? pass++ : fail++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${ok ? '' : `  (got ${JSON.stringify(got)}, want ${JSON.stringify(want)})`}`);
};
const path = (r) => r.artifact?.guestPath ?? null;

check('an apk in shared is offered', path(installable('/workspace/shared/app.apk')), '/workspace/shared/app.apk');
check('a nested one too', path(installable('/workspace/shared/out/box.apk')), '/workspace/shared/out/box.apk');
check('case does not matter', path(installable('/workspace/shared/App.APK')), '/workspace/shared/App.APK');
check('not an apk is refused', path(installable('/workspace/shared/notes.md')), null);
check('outside shared is refused', path(installable('/workspace/build/elsewhere.apk')), null);
check('a directory named .apk is refused', path(installable('/workspace/shared/folder.apk')), null);
check('a file that does not exist is refused', path(installable('/workspace/shared/ghost.apk')), null);
check('a relative path is refused', path(installable('shared/app.apk')), null);
check('a non-string is refused', path(installable(undefined)), null);

// A symlink out of the workspace must not become an install button.
symlinkSync('/etc/hosts', `${root}/shared/escape.apk`);
check('a symlink out of the box is refused', path(installable('/workspace/shared/escape.apk')), null);

// Every refusal has to say something, or the agent cannot act on it.
const silent = ['/workspace/shared/notes.md', '/workspace/build/elsewhere.apk', 'shared/app.apk']
  .filter((p) => !installable(p).refusal);
check('every refusal explains itself', silent.length, 0);

rmSync(root, { recursive: true, force: true });
console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
