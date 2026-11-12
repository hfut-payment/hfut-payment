import { execFileSync } from 'node:child_process';
import { copyFileSync, mkdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const [checkout, mode] = process.argv.slice(2);
if (!checkout || !['debug', 'release'].includes(mode)) {
  throw new Error('Usage: node scripts/stage_band_rpk.mjs BAND_CHECKOUT debug|release');
}
const root = fileURLToPath(new URL('..', import.meta.url));
const source = resolve(checkout);
const pin = readFileSync(resolve(root, 'band-source.sha'), 'utf8').trim();
const git = (...args) => execFileSync('git', ['-C', source, ...args], { encoding: 'utf8' }).trim();
if (!/^[0-9a-f]{40}$/.test(pin) || git('rev-parse', 'HEAD') !== pin ||
    git('status', '--porcelain', '--untracked-files=all')) {
  throw new Error('Use a clean band checkout at the commit in band-source.sha');
}
const band = readFileSync(resolve(root, 'app/src/main/kotlin/dev/codex/hfutcampus/device/band/BuiltInBand.kt'), 'utf8');
const version = band.match(/const val VERSION = "([^"]+)"/)?.[1];
const revision = Number(band.match(/const val REVISION = (\d+)/)?.[1]);
const manifest = JSON.parse(readFileSync(resolve(source, 'src/manifest.json'), 'utf8'));
if (!version || manifest.versionName !== version || manifest.versionCode !== revision) {
  throw new Error('Built-in band and RPK versions do not match');
}
const signer = readFileSync(resolve(root, 'scripts/release-certificate.sha256'), 'utf8').trim();
if (mode === 'release' && readFileSync(resolve(source, 'scripts/release-certificate.sha256'), 'utf8').trim() !== signer) {
  throw new Error('Band and Android release certificates must match');
}
const output = resolve(source, 'dist/app-embedding');
execFileSync(process.execPath, ['scripts/package-rpk.cjs', mode, output, version], {
  cwd: source, stdio: 'inherit'
});
const assetDir = resolve(root, 'app/src/withBand/assets');
mkdirSync(assetDir, { recursive: true });
const name = 'HFUT-Campus-Band-' + version;
copyFileSync(resolve(output, name + (mode === 'debug' ? '-debug' : '') + '.rpk'), resolve(assetDir, name + '.rpk'));
