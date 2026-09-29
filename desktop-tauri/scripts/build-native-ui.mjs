import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

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
console.log('Native UI generated from the existing Warpy controller and profile modules.');
