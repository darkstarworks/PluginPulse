/* Browser glue for the PluginPulse web tool. All work happens locally; the jar
 * never leaves the page. Requires jszip.min.js, constant-pool.js, injector.js. */
(function () {
  'use strict';

  let assets = null;
  let selectedFile = null;

  const $ = (id) => document.getElementById(id);

  // Global "plugins future-proofed" tally, hosted on esmp.fun. Purely cosmetic:
  // the page reads it on load and bumps it once per successful Generate. Every
  // call is best-effort — no jar data is ever sent, and any failure just leaves
  // the counter hidden rather than surfacing an error.
  const PULSE_URL = 'https://api.esmp.fun/v2/pulse';
  let pulseCount = null;

  function renderCounter(n) {
    if (typeof n !== 'number' || !isFinite(n)) return;
    pulseCount = n;
    const el = $('pulseCounter');
    if (!el) return;
    el.textContent = '';
    const strong = document.createElement('strong');
    strong.textContent = n.toLocaleString();
    el.appendChild(strong);
    el.appendChild(document.createTextNode(' plugins future-proofed'));
    el.hidden = false;
  }

  async function loadCounter() {
    try {
      const d = await (await fetch(PULSE_URL, { method: 'GET' })).json();
      if (d && typeof d.count === 'number') renderCounter(d.count);
    } catch (_) { /* counter is optional — stay hidden on failure */ }
  }

  async function bumpCounter() {
    // Reflect the tick immediately, then reconcile with the server's value.
    if (pulseCount != null) renderCounter(pulseCount + 1);
    try {
      const d = await (await fetch(PULSE_URL, { method: 'POST' })).json();
      if (d && typeof d.count === 'number') renderCounter(d.count);
    } catch (_) { /* best-effort */ }
  }

  async function loadAssets() {
    if (assets) return assets;
    const [core, tpl] = await Promise.all([
      fetch('engine/pluginpulse-core.jar').then((r) => r.arrayBuffer()),
      fetch('engine/wrapper-template.class').then((r) => r.arrayBuffer()),
    ]);
    assets = { coreJar: new Uint8Array(core), wrapperTemplate: new Uint8Array(tpl) };
    return assets;
  }

  function status(msg, kind) {
    const el = $('status');
    el.textContent = msg;
    el.className = 'status ' + (kind || '');
  }

  /** Plain-English notice. { title, lines:[], kind:'err'|'warn'|'ok', timeout }. */
  function toast({ title, lines, kind, timeout }) {
    const wrap = $('toasts');
    if (!wrap) return;
    const el = document.createElement('div');
    el.className = 'toast ' + (kind || '');
    if (title) {
      const h = document.createElement('strong');
      h.className = 'toast-title';
      h.textContent = title;
      el.appendChild(h);
    }
    (lines || []).forEach((line) => {
      const p = document.createElement('div');
      p.className = 'toast-line';
      p.textContent = line;
      el.appendChild(p);
    });
    const close = document.createElement('button');
    close.className = 'toast-x';
    close.type = 'button';
    close.textContent = '×';
    close.setAttribute('aria-label', 'Dismiss');
    el.appendChild(close);
    wrap.appendChild(el);
    requestAnimationFrame(() => el.classList.add('show'));
    const hide = () => { el.classList.remove('show'); setTimeout(() => el.remove(), 250); };
    const timer = setTimeout(hide, timeout || 10000);
    close.addEventListener('click', () => { clearTimeout(timer); hide(); });
  }

  function readOptions() {
    // Order the filled-in sources by their chosen priority (#1 first). Ties fall
    // back to the on-screen order (modrinth, github, hangar).
    const prio = (id) => parseInt($(id + '-priority').value, 10) || 9;
    const sourceOrder = [
      { k: 'modrinth', v: $('modrinth').value.trim(), p: prio('modrinth'), d: 0 },
      { k: 'github', v: $('github').value.trim(), p: prio('github'), d: 1 },
      { k: 'hangar', v: $('hangar').value.trim(), p: prio('hangar'), d: 2 },
    ].filter((s) => s.v).sort((a, b) => a.p - b.p || a.d - b.d).map((s) => s.k);
    return {
      modrinth: $('modrinth').value.trim(),
      github: $('github').value.trim(),
      hangar: $('hangar').value.trim(),
      sourceOrder: sourceOrder,
      permission: $('permission').value.trim(),
      commandRoot: $('commandRoot').value.trim(),
      mode: $('mode').value,
      contact: $('contact').value.trim(),
      track: $('track').value.trim(),
      checkIntervalHours: $('interval').value ? parseInt($('interval').value, 10) : null,
      upgrade: $('upgrade').checked,
    };
  }

  function onFile(e) {
    selectedFile = e.target.files[0] || null;
    $('previewOut').textContent = '';
    if (selectedFile) status('Selected ' + selectedFile.name + '.', '');
  }

  async function fileBytes() {
    return new Uint8Array(await selectedFile.arrayBuffer());
  }

  async function preview() {
    if (!selectedFile) {
      toast({ kind: 'err', title: 'No jar chosen yet', lines: ['Pick a plugin .jar in step 1 first.'] });
      return;
    }
    try {
      status('Reading jar…', '');
      const info = await window.PPInjector.inspectJar(await fileBytes());
      const authors = info.authors && info.authors.length ? info.authors.join(', ') : 'not listed in the jar';
      $('previewOut').textContent =
        'Plugin      : ' + (info.name || '(name not in descriptor)') + '\n' +
        'Version     : ' + (info.version || '(not listed)') + '\n' +
        'Author(s)   : ' + authors + '\n' +
        'Main class  : ' + info.main + '\n' +
        'Descriptor  : ' + info.descriptor + '\n' +
        'Updatable by this tool : Yes' +
        (info.finalMain ? '\nNote        : main class is final (typical for Kotlin) — the tool clears that flag so the updater can attach' : '') +
        (info.alreadyInjected ? '\nAlready has PluginPulse : Yes' : '');

      if (info.alreadyInjected) {
        status('This jar already has PluginPulse.', 'warn');
        toast({
          kind: 'warn',
          title: 'This jar already has PluginPulse',
          lines: [
            'To change its update settings, tick "Re-inject a jar that already has PluginPulse" in step 3, then Generate.',
          ],
        });
      } else {
        status('Looks good — fill in the fields and Generate.', 'ok');
      }
    } catch (err) {
      status('Preview failed.', 'err');
      if (/no plugin\.yml/i.test(err.message)) {
        toast({
          kind: 'err',
          title: 'That doesn\'t look like a Paper/Spigot plugin',
          lines: [
            'No plugin.yml or paper-plugin.yml with a main class was found inside the jar.',
            'Make sure you picked the plugin jar itself — not a library, a source archive, or a mod for a different platform.',
          ],
        });
      } else {
        toast({
          kind: 'err',
          title: 'Couldn\'t read that jar',
          lines: [err.message, 'If it is a valid plugin jar, try re-downloading it — the file may be corrupted.'],
        });
      }
    }
  }

  function download(bytes, name) {
    const blob = new Blob([bytes], { type: 'application/java-archive' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  async function generate() {
    if (!selectedFile) {
      toast({ kind: 'err', title: 'No jar chosen yet', lines: ['Pick a plugin .jar in step 1 first.'] });
      return;
    }
    if (!$('rights').checked) {
      toast({ kind: 'err', title: 'One box left to tick',
        lines: ['Confirm in step 4 that you have the right to modify and redistribute this jar.'] });
      return;
    }
    const opts = readOptions();
    if (!opts.modrinth && !opts.github && !opts.hangar) {
      toast({ kind: 'err', title: 'No update source given',
        lines: ['Fill in at least one of Modrinth, GitHub, or Hangar in step 2 so the plugin knows where to look.'] });
      return;
    }
    try {
      status('Generating…', '');
      const a = await loadAssets();
      const out = await window.PPInjector.injectJar(await fileBytes(), opts, a);
      const base = selectedFile.name.replace(/\.jar$/i, '');
      download(out, base + '-pulse.jar');
      status('Done — downloaded ' + base + '-pulse.jar.', 'ok');
      toast({ kind: 'ok', title: 'Updated jar downloaded',
        lines: [base + '-pulse.jar is in your downloads.', 'Test it on your own server before sharing it — the tool can\'t confirm the jar boots.'] });
      bumpCounter();
    } catch (err) {
      status('Could not process this jar.', 'err');
      toast({ kind: 'err', title: 'Couldn\'t generate the jar',
        lines: [err.message, 'Try Preview jar first to check the plugin can be processed.'] });
    }
  }

  // Keep the three priority dropdowns a distinct 1/2/3 permutation: picking a
  // number another source already holds swaps that source onto the old value,
  // so two sources can never share a priority.
  function setupPriorities() {
    const selects = ['modrinth-priority', 'github-priority', 'hangar-priority'].map($);
    const prev = new Map(selects.map((s) => [s, s.value]));
    selects.forEach((sel) => {
      sel.addEventListener('change', () => {
        const clash = selects.find((o) => o !== sel && o.value === sel.value);
        if (clash) clash.value = prev.get(sel);
        selects.forEach((s) => prev.set(s, s.value));
      });
    });
  }

  window.addEventListener('DOMContentLoaded', () => {
    $('file').addEventListener('change', onFile);
    $('preview').addEventListener('click', preview);
    $('generate').addEventListener('click', generate);
    setupPriorities();
    loadCounter();
  });
})();
