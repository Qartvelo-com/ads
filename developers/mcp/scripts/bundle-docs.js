#!/usr/bin/env node
// Bundles the developer docs (../src/content/docs) into docs.json so the MCP server works offline
// and from npm without the docs site. Run automatically before `npm pack` / `npm publish`.
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const docsRoot = join(here, '..', '..', 'src', 'content', 'docs');
const out = join(here, '..', 'docs.json');
const site = 'https://developers.qartvelo.com';

function walk(dir) {
	return readdirSync(dir).flatMap((name) => {
		const path = join(dir, name);
		if (statSync(path).isDirectory()) return walk(path);
		return /\.mdx?$/.test(name) ? [path] : [];
	});
}

function parse(file) {
	const raw = readFileSync(file, 'utf8');
	const match = raw.match(/^---\n([\s\S]*?)\n---\n?/);
	const front = match ? match[1] : '';
	const field = (key) => {
		const m = front.match(new RegExp(`^${key}:\\s*(.+)$`, 'm'));
		return m ? m[1].trim().replace(/^['"]|['"]$/g, '') : '';
	};
	const body = (match ? raw.slice(match[0].length) : raw)
		.replace(/^import .+ from .+;?\s*$/gm, '')
		.trim();
	const id = relative(docsRoot, file).split(sep).join('/').replace(/\.mdx?$/, '');
	const path = id === 'index' ? 'index' : id.replace(/\/index$/, '');
	return {
		path,
		title: field('title'),
		description: field('description'),
		url: path === 'index' ? `${site}/` : `${site}/${path}/`,
		body,
	};
}

const docs = walk(docsRoot).map(parse).sort((a, b) => a.path.localeCompare(b.path));
writeFileSync(out, JSON.stringify({ generatedAt: new Date().toISOString(), site, docs }, null, '\t') + '\n');
console.log(`Bundled ${docs.length} pages into ${relative(process.cwd(), out)}`);
