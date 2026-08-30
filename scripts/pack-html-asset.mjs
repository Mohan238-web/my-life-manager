import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const root = path.resolve(import.meta.dirname, '..');
const app = path.join(root, 'corex-v9.13.249', 'app');
const htmlPath = path.join(app, 'src', 'main', 'assets', 'index.html');
const partsDir = path.join(app, 'src', 'main', 'assets_parts');
const prefix = 'index.html.gz.b64.part-';
const chunkSize = 30000;
const packed = zlib.gzipSync(fs.readFileSync(htmlPath), {level: 9}).toString('base64');

for (const name of fs.readdirSync(partsDir)) {
  if (name.startsWith(prefix)) fs.rmSync(path.join(partsDir, name));
}
for (let offset = 0, index = 0; offset < packed.length; offset += chunkSize, index += 1) {
  const name = `${prefix}${String(index).padStart(2, '0')}`;
  fs.writeFileSync(path.join(partsDir, name), packed.slice(offset, offset + chunkSize));
}

console.log(`Packed ${fs.statSync(htmlPath).size} HTML bytes into ${Math.ceil(packed.length / chunkSize)} parts`);
