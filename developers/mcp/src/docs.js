// Offline access to the bundled developer docs (docs.json, built by scripts/bundle-docs.js).
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

let cache;

/** @returns {{ path: string, title: string, description: string, url: string, body: string }[]} */
export function loadDocs() {
	if (!cache) {
		const file = join(here, '..', 'docs.json');
		try {
			cache = JSON.parse(readFileSync(file, 'utf8')).docs;
		} catch {
			throw new Error('docs.json is missing. Run `npm run bundle-docs` in developers/mcp.');
		}
	}
	return cache;
}

export function getDoc(path) {
	const wanted = normalizePath(path);
	return loadDocs().find((d) => d.path === wanted) ?? null;
}

/** Accepts `android/banner`, `/android/banner/`, `android/banner.md` or a full docs URL. */
export function normalizePath(path) {
	return String(path)
		.trim()
		.replace(/^https?:\/\/[^/]+/, '')
		.replace(/\.md$/, '')
		.replace(/^\/+|\/+$/g, '') || 'index';
}

const tokenize = (text) =>
	String(text)
		.toLowerCase()
		.split(/[^a-z0-9_]+/)
		.filter((t) => t.length > 1);

/** Splits a page into sections at `##` headings so results point at the relevant part. */
function sections(doc) {
	const parts = doc.body.split(/^(?=## )/m);
	return parts.map((text, i) => {
		const heading = i === 0 ? doc.title : text.match(/^## (.+)$/m)?.[1] ?? doc.title;
		return { doc, heading, text };
	});
}

/**
 * Simple ranked keyword search over page sections (term frequency with title and heading boosts).
 * Exact identifiers such as `testForceNoFill` or `package_mismatch` match strongly.
 */
export function searchDocs(query, limit = 5) {
	const terms = [...new Set(tokenize(query))];
	if (terms.length === 0) return [];
	const results = [];
	for (const doc of loadDocs()) {
		for (const section of sections(doc)) {
			const body = section.text.toLowerCase();
			const title = `${doc.title} ${doc.description}`.toLowerCase();
			const heading = section.heading.toLowerCase();
			let score = 0;
			let matched = 0;
			for (const term of terms) {
				const count = body.split(term).length - 1;
				const inTitle = title.includes(term);
				const inHeading = heading.includes(term);
				if (count || inTitle || inHeading) matched++;
				score += Math.min(count, 8) + (inTitle ? 4 : 0) + (inHeading ? 3 : 0);
			}
			if (matched === 0) continue;
			score *= matched / terms.length;
			results.push({ score, section });
		}
	}
	results.sort((a, b) => b.score - a.score);
	const seen = new Set();
	const out = [];
	for (const { score, section } of results) {
		const key = `${section.doc.path}#${section.heading}`;
		if (seen.has(key)) continue;
		seen.add(key);
		out.push({
			path: section.doc.path,
			title: section.doc.title,
			section: section.heading,
			url: section.doc.url,
			score: Math.round(score * 10) / 10,
			excerpt: excerpt(section.text, terms),
		});
		if (out.length >= limit) break;
	}
	return out;
}

function excerpt(text, terms, size = 600) {
	const lower = text.toLowerCase();
	const first = Math.min(...terms.map((t) => lower.indexOf(t)).filter((i) => i >= 0));
	const start = Number.isFinite(first) ? Math.max(0, first - 150) : 0;
	const slice = text.slice(start, start + size).trim();
	return (start > 0 ? '... ' : '') + slice + (start + size < text.length ? ' ...' : '');
}
