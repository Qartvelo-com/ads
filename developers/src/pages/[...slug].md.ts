/**
 * Serves every docs page as plain Markdown at `/<slug>.md` (for example `/android/banner.md`), so
 * AI assistants and agents can read a single page without HTML. MDX component tags are kept as-is;
 * they are readable as text.
 */
import type { APIRoute, GetStaticPaths } from 'astro';
import { getCollection, type CollectionEntry } from 'astro:content';

export const getStaticPaths: GetStaticPaths = async () => {
	const docs = await getCollection('docs');
	return docs.map((entry) => ({
		params: { slug: entry.id === 'index' ? 'index' : entry.id.replace(/\/index$/, '') },
		props: { entry },
	}));
};

export const GET: APIRoute<{ entry: CollectionEntry<'docs'> }> = ({ props, site }) => {
	const { entry } = props;
	const slug = entry.id === 'index' ? '' : entry.id;
	const url = new URL(`/${slug}`, site).href;
	const body = (entry.body ?? '')
		// Drop MDX imports; they mean nothing outside the site.
		.replace(/^import .+ from .+;?\s*$/gm, '')
		.trim();
	const text = [
		`# ${entry.data.title}`,
		'',
		entry.data.description ? `> ${entry.data.description}\n` : '',
		`Source: ${url}`,
		'',
		body,
		'',
	].join('\n');
	return new Response(text, { headers: { 'Content-Type': 'text/markdown; charset=utf-8' } });
};
