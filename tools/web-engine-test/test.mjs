/*
 * Structural self-test for the in-browser injection engine. Builds a sample
 * plugin jar, runs the JS injector against the real emitted assets
 * (docs/engine/pluginpulse-core.jar + wrapper-template.class), and asserts the
 * output is wired the same way the Java reference engine wires it.
 *
 *   node test.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const JSZip = require('jszip');
globalThis.JSZip = JSZip;

const here = dirname(fileURLToPath(import.meta.url));
const engine = resolve(here, '../../docs/engine');
const CP = require(resolve(engine, 'constant-pool.js'));
const PPI = require(resolve(engine, 'injector.js'));

let failures = 0;
function ok(cond, msg) {
  if (cond) { console.log('  ok  - ' + msg); }
  else { console.error('  FAIL- ' + msg); failures++; }
}

function utf8Strings(classBytes) {
  const parsed = CP.parseClass(classBytes);
  const dec = new TextDecoder('latin1');
  return parsed.entries.filter((e) => e.tag === 1).map((e) => dec.decode(e.data));
}

async function buildSampleJar(main) {
  const zip = new JSZip();
  zip.file('plugin.yml', 'name: Sample\nversion: "1.0.0"\nmain: ' + main + '\napi-version: "1.20"\n');
  // A resource so we can confirm unrelated entries survive.
  zip.file('config.yml', 'hello: world\n');
  return zip.generateAsync({ type: 'uint8array' });
}

async function main() {
  const coreJar = new Uint8Array(readFileSync(resolve(engine, 'pluginpulse-core.jar')));
  const wrapperTemplate = new Uint8Array(readFileSync(resolve(engine, 'wrapper-template.class')));
  const assets = { coreJar, wrapperTemplate };

  const jar = await buildSampleJar('com.example.demo.DemoPlugin');
  const opts = {
    modrinth: 'demo-slug', permission: 'demo.admin', commandRoot: '/demo',
    mode: 'notify', contact: 'me@example.com', checkIntervalHours: 6,
  };
  const out = await PPI.injectJar(jar, opts, assets);
  const zip = await JSZip.loadAsync(out);
  const names = Object.keys(zip.files);

  const pkg = 'com/example/demo';
  ok(names.includes(pkg + '/DemoPlugin__Pulse.class'), 'wrapper class present at ' + pkg + '/DemoPlugin__Pulse.class');
  ok(names.includes(pkg + '/pluginpulse/PluginPulse.class'), 'core relocated under target package');
  ok(!names.some((n) => n.startsWith('io/github/darkstarworks/pluginpulse/')), 'no core class left at the original path');
  ok(names.includes('config.yml'), 'unrelated resources preserved');
  ok(names.includes('pluginpulse.yml'), 'pluginpulse.yml added');

  const desc = await zip.file('plugin.yml').async('string');
  ok(/main:\s*com\.example\.demo\.DemoPlugin__Pulse/.test(desc), 'main: re-pointed to the wrapper');

  const pulseYml = await zip.file('pluginpulse.yml').async('string');
  ok(pulseYml.includes('modrinth: demo-slug'), 'pluginpulse.yml carries the source');
  ok(pulseYml.includes('command-root: /demo'), 'pluginpulse.yml carries command-root');

  // Wrapper Utf8 constants fully substituted (no placeholders left).
  const wrapper = await zip.file(pkg + '/DemoPlugin__Pulse.class').async('uint8array');
  const ws = utf8Strings(wrapper);
  ok(ws.some((s) => s === pkg + '/DemoPlugin__Pulse'), 'wrapper this_class substituted');
  ok(ws.some((s) => s === pkg + '/DemoPlugin'), 'wrapper superclass = target main');
  ok(ws.some((s) => s === pkg + '/pluginpulse/PluginPulse'), 'wrapper references relocated PluginPulse');
  ok(!ws.some((s) => s.includes('PP__MAIN__PLACEHOLDER') || s.includes('PP__PULSE__PLACEHOLDER')),
    'no placeholder tokens remain in the wrapper');

  // Relocated core class carries no reference to the old package.
  const pp = await zip.file(pkg + '/pluginpulse/PluginPulse.class').async('uint8array');
  const ps = utf8Strings(pp);
  ok(!ps.some((s) => s.includes('io/github/darkstarworks/pluginpulse') || s.includes('io.github.darkstarworks.pluginpulse')),
    'relocated PluginPulse has no old-package references');
  ok(ps.some((s) => s.includes(pkg + '/pluginpulse')), 'relocated PluginPulse references the new package');

  // isFinal detection sanity: a non-final wrapper reads as non-final.
  ok(!CP.isFinal(wrapper), 'generated wrapper is not final');

  // definalize: a final class (as Kotlin emits every plugin main) is cleared so
  // the wrapper can subclass it, and the constant pool is left untouched.
  const forced = CP.parseClass(wrapperTemplate);
  forced.tail[1] |= 0x10; // force ACC_FINAL on
  const finalBytes = CP.serializeClass(forced);
  ok(CP.isFinal(finalBytes), 'test fixture reads as final before definalize');
  const cleared = CP.definalize(finalBytes, ['onEnable', 'onDisable']);
  ok(!CP.isFinal(cleared), 'definalize clears the class final flag');
  ok(CP.parseClass(cleared).entries.length === forced.entries.length,
    'definalize leaves the constant pool unchanged');

  console.log(failures === 0 ? '\nALL PASSED' : '\n' + failures + ' FAILED');
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
