import assert from 'node:assert/strict';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {parseFrontmatter} from '@astrojs/markdown-remark';
import starlightLlmsTxt from 'starlight-llms-txt';
import {routeExample} from './src/homepage-example.mjs';

const separator = '\n\n<!-- shoostr-guide -->\n\n';
const decimal = new Intl.NumberFormat('en-US');

export function agentDocs(sidebar, siteRoot, description) {
    const [overview, start, ...topics] = sidebar;
    const [installation, ...startItems] = start.items;
    const groups = [{label: start.label, items: [installation, overview, ...startItems]}, ...topics];
    const slugs = groups.flatMap(group => group.items.map(item => item.slug || 'index'));
    assert.equal(new Set(slugs).size, slugs.length, 'The agent index needs unique guide slugs');

    return {
        plugin: starlightLlmsTxt({promote: slugs, pageSeparator: separator}),
        integration: {
            name: 'shoostr-agent-docs',
            hooks: {'astro:build:done': ({dir}) => publish(dir, siteRoot, description, groups, slugs)},
        },
    };
}

async function publish(dir, siteRoot, description, groups, slugs) {
    const guides = await Promise.all(slugs.map(slug => guideMetadata(siteRoot, slug)));
    const fullFile = new URL('llms-full.txt', dir);
    const smallFile = new URL('llms-small.txt', dir);
    const full = addSources(await readFile(fullFile, 'utf8'), guides);
    const small = addSources(await readFile(smallFile, 'utf8'), guides);
    const fullText = full.join('\n\n') + '\n';
    const smallText = small.join('\n\n') + '\n';
    const index = indexText(siteRoot, description, groups, guides, fullText, smallText);

    await Promise.all(guides.map(async ({path}, i) => {
        await mkdir(new URL('.', new URL(path, dir)), {recursive: true});
        await writeFile(new URL(path, dir), full[i] + '\n');
    }));
    await Promise.all([
        writeFile(fullFile, fullText),
        writeFile(smallFile, smallText),
        writeFile(new URL('llms.txt', dir), index),
    ]);
}

async function guideMetadata(siteRoot, slug) {
    const source = new URL(`./src/content/docs/${slug}.mdx`, import.meta.url);
    const {frontmatter} = parseFrontmatter(await readFile(source, 'utf8'));
    const {title, description, hero} = frontmatter;
    assert(title && description, `Missing title or description in ${slug}.mdx`);

    const url = new URL(slug === 'index' ? './' : `${slug}/`, siteRoot);
    const path = `llms/${slug}.md`;
    return {slug, title, description, url, path, heading: `# ${hero?.title || title}`};
}

function addSources(exportText, guides) {
    const [preamble, ...pages] = exportText.trimEnd().split(separator);
    assert.match(preamble, /^<SYSTEM>[^\n]*<\/SYSTEM>$/, 'Unexpected Starlight export preamble');
    assert.equal(pages.length, guides.length, 'The Starlight export and sidebar must cover the same guides');

    return guides.map(({slug, heading, url}, i) => {
        const [actualHeading, introduction, ...body] = pages[i].split('\n\n');
        assert.equal(actualHeading, heading, `Unexpected export heading for ${slug}`);

        const content = [heading, `Source: ${url}`, introduction];
        if (slug === 'index') content.push(`\`\`\`java\n${routeExample}\n\`\`\``);
        return [...content, ...body].join('\n\n');
    });
}

function indexText(siteRoot, description, groups, guides, full, small) {
    const bySlug = new Map(guides.map(guide => [guide.slug, guide]));
    const sections = groups.map(group => indexSection(group, bySlug, siteRoot));

    return [
        '# Shoostr',
        `> ${description}`,
        'Open a guide or fetch its Markdown. Resolve relative links in Markdown against the guide’s Source URL.',
        ...sections,
        '## Optional',
        [
            `- [Complete documentation](${new URL('llms-full.txt', siteRoot)}): ${byteCount(full)} with all guides and code examples.`,
            `- [Abridged documentation](${new URL('llms-small.txt', siteRoot)}): ${byteCount(small)} of condensed guides with code examples.`,
        ].join('\n'),
    ].join('\n\n') + '\n';
}

function indexSection({label, items}, bySlug, siteRoot) {
    const links = items.map(({slug}) => {
        const guide = bySlug.get(slug || 'index');
        return `- [${guide.title}](${guide.url}): ${guide.description} [Markdown](${new URL(guide.path, siteRoot)})`;
    });
    return `## ${label}\n\n${links.join('\n')}`;
}

function byteCount(text) {
    return `${decimal.format(Buffer.byteLength(text))} bytes`;
}
