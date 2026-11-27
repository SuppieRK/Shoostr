import {defineConfig} from 'astro/config';
import starlight from '@astrojs/starlight';
import {fileURLToPath} from 'node:url';
import * as pagefind from 'pagefind';
import {loadDependencyVersions} from './dependency-versions.mjs';
import {publishJavadoc, serveJavadoc} from './publish-javadoc.mjs';
import {agentDocs} from './agent-docs.mjs';
import sidebar from './sidebar.mjs';

const site = process.env.SITE_URL;
const base = process.env.BASE_PATH || '/';
const socialImagePath = `${base.replace(/\/$/, '')}/shoostr-social.png`;
const socialImage = site ? new URL(socialImagePath, site).href : socialImagePath;
const socialImageAlt = 'Shoostr, a Java web framework for HTTP APIs, with a Java route example.';
const siteRoot = site && new URL(`${base.replace(/\/$/, '')}/`, site);
const description = 'Shoostr is a Java 21 web framework on Jetty for HTTP APIs.';
const documentationExports = siteRoot && agentDocs(sidebar, siteRoot, description);

async function buildSearchIndex({dir}) {
    try {
        const {index} = checked(await pagefind.createIndex({includeCharacters: '/+'}));
        if (!index) throw new Error('Pagefind did not create a search index.');

        checked(await index.addDirectory({path: fileURLToPath(dir)}));

        checked(await index.writeFiles({outputPath: fileURLToPath(new URL('./pagefind/', dir))}));
    } finally {
        await pagefind.close();
    }
}

function checked(response) {
    if (response.errors.length) throw new Error(response.errors.join('\n'));
    return response;
}

function shoostrVersions() {
    let versions;
    const gradleFiles = [];
    return {
        name: 'shoostr-versions',
        hooks: {
            'astro:config:setup': async ({addWatchFile, command, updateConfig}) => {
                if (command !== 'dev' && command !== 'build') return;
                versions = await loadDependencyVersions(path => {
                    addWatchFile(path);
                    gradleFiles.push(fileURLToPath(path));
                });
                updateConfig({vite: {define: {SHOOSTR_VERSIONS: JSON.stringify(versions)}}});
            },
            'astro:build:done': options => publishJavadoc(options, versions.libraries),
            'astro:server:setup': options => {
                options.server.watcher.add(gradleFiles);
                serveJavadoc(options, base, versions.libraries);
            },
        },
    };
}

export default defineConfig({
    site,
    base,
    trailingSlash: 'always',
    integrations: [
        starlight({
            title: 'Shoostr',
            description,
            defaultLocale: 'root',
            locales: {root: {label: 'English', lang: 'en'}},
            favicon: '/favicon.svg',
            plugins: documentationExports ? [documentationExports.plugin] : [],
            social: [
                {icon: 'github', label: 'GitHub', href: 'https://github.com/SuppieRK/Shoostr'},
                {icon: 'seti:maven', label: 'Maven Central', href: 'https://central.sonatype.com/artifact/io.github.suppierk/shoostr-core'},
            ],
            head: [
                {tag: 'meta', attrs: {property: 'og:image', content: socialImage}},
                {tag: 'meta', attrs: {property: 'og:image:type', content: 'image/png'}},
                {tag: 'meta', attrs: {property: 'og:image:width', content: '1200'}},
                {tag: 'meta', attrs: {property: 'og:image:height', content: '630'}},
                {tag: 'meta', attrs: {property: 'og:image:alt', content: socialImageAlt}},
                {tag: 'meta', attrs: {name: 'twitter:image', content: socialImage}},
                {tag: 'meta', attrs: {name: 'twitter:image:alt', content: socialImageAlt}},
                {
                    tag: 'script',
                    attrs: {
                        type: 'module',
                        src: 'https://static.cloudflareinsights.com/beacon.min.js',
                        'data-cf-beacon': JSON.stringify({token: '0936c0fc0abd4ecbbc6e3bbe1d0051c4'}),
                    },
                },
            ],
            customCss: ['./src/styles/brand.css'],
            components: {
                Head: './src/components/SearchHead.astro',
                Hero: './src/components/HomepageHero.astro',
                LanguageSelect: './src/components/LanguageSelect.astro',
                Sidebar: './src/components/Sidebar.astro',
                PageFrame: './src/components/PageFrame.astro',
            },
            sidebar,
        }),
        {
            name: 'shoostr-search-index',
            hooks: {'astro:build:done': buildSearchIndex},
        },
        shoostrVersions(),
        ...(documentationExports ? [documentationExports.integration] : []),
    ],
});
