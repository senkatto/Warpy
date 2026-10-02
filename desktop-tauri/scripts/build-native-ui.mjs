import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import vm from 'node:vm';

const root = fileURLToPath(new URL('../', import.meta.url));
const html = await readFile(`${root}src/index.html`, 'utf8');
const decode = value => value.replace(/&(?:amp|lt|gt|quot|apos|nbsp);/g, entity => ({
  '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'", '&nbsp;': ' ',
})[entity]);
const tree = { tag: 'document', attrs: {}, children: [] };
const stack = [tree];
const voidTags = new Set(['meta', 'link', 'img', 'input', 'br', 'hr']);
for (const match of html.replace(/<!--[\s\S]*?-->/g, '').matchAll(/<\/?[\w:-]+\b[^>]*>|[^<]+/g)) {
  const token = match[0];
  if (token.startsWith('</')) {
    if (stack.length > 1) stack.pop();
  } else if (token.startsWith('<')) {
    const tag = token.match(/^<([\w:-]+)/)[1].toLowerCase();
    const attrs = {};
    for (const a of token.slice(tag.length + 1).matchAll(/([\w:-]+)(?:\s*=\s*"([^"]*)"|\s*=\s*'([^']*)')?/g)) {
      attrs[a[1]] = decode(a[2] ?? a[3] ?? '');
    }
    const node = { tag, attrs, children: [] };
    stack.at(-1).children.push(node);
    if (!voidTags.has(tag) && !token.endsWith('/>')) stack.push(node);
  } else if (token.trim()) {
    stack.at(-1).children.push({ text: decode(token.trim()) });
  }
}
await mkdir(`${root}src-tauri/native-generated`, { recursive: true });
const prelude = `globalThis.__warpyTree = ${JSON.stringify(tree)};\n`;
const shim = await readFile(`${root}src/native/dom.js`, 'utf8');
const qr = await readFile(`${root}src/qrcode.min.js`, 'utf8');
const scene = await readFile(`${root}src/native/scene.js`, 'utf8');
const bundle = await build({
  entryPoints: [`${root}src/index.js`], bundle: true, write: false,
  format: 'iife', target: 'es2020', minify: false,
  footer: { js: scene },
});
await writeFile(`${root}src-tauri/native-generated/ui.js`, prelude + shim + '\n' + qr + '\n' + bundle.outputFiles[0].text);
// Static artwork and strings for the Rust client. No scripts are evaluated by
// that client; the legacy JavaScript bundle remains a migration test oracle.
const index = await readFile(`${root}src/index.js`, 'utf8');
const config = await readFile(`${root}src/vpn-config.js`, 'utf8');
const literal = (source, name) => vm.runInNewContext(`(${source.match(new RegExp(`const ${name} = ([\\s\\S]*?^\\s*[\\]}]);`, 'm'))[1]})`);
const elements = {};
const markup = node => node.text ?? `<${node.tag} ${Object.entries(node.attrs).map(([key, value]) => `${key}="${value.replace(/"/g, '&quot;')}"`).join(' ')}>${node.children.map(markup).join('')}</${node.tag}>`;
const text = node => node.text ?? node.children.map(text).join('');
const find = (node, predicate) => predicate(node) ? node : node.children?.map(child => find(child,predicate)).find(Boolean);
function collect(node) {
  if (node.attrs?.id) {
    const svg = find(node,child => child.tag === 'svg');
    const strings = {};
    for (const child of node.children || []) {
      for (const name of (child.attrs?.class || '').split(' ').filter(Boolean)) strings[name] = text(child);
    }
    elements[node.attrs.id] = { text:text(node), svg:svg ? markup(svg) : '', strings };
  }
  node.children?.forEach(collect);
}
collect(tree);
const logo = find(tree,node => node.attrs?.class === 'logo');
elements.logo = {svg:markup(find(logo,node => node.tag === 'svg'))};
elements['language-setting-chevron'] = {svg:markup(find(tree,node => node.attrs?.class === 'language-setting-chevron'))};
for (const kind of ['down','up','ping']) {
  const row = find(tree,node => node.attrs?.class?.split(' ').includes(kind==='down'?'download-row':kind==='up'?'upload-row':'ping-row'));
  elements[`speedtest-icon-${kind}`] = {svg:markup(find(row,node => node.tag === 'svg'))};
}
await writeFile(`${root}src-tauri/native-generated/client-assets.json`,JSON.stringify({
  translations:literal(index,'T'), countries:Object.entries(literal(index,'COUNTRY_MAP')), elements,
  ads:literal(config,'AD_DOMAINS'), browsers:literal(config,'QUIC_BROWSER_PROCESSES'),
  flowDomains:literal(config,'flowDomains'), flowSuffixes:literal(config,'flowDomainSuffixes'),
  protectedDomains:literal(config,'PROTECTED_FLOW_DOMAINS'),
  contract:JSON.parse(await readFile(`${root}../shared/core-contract.json`,'utf8')),
}));
console.log('Rust client artwork generated; legacy controller retained only for comparison tests.');
