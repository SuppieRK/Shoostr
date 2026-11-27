import {readFile} from 'node:fs/promises';
import {setTimeout as delay} from 'node:timers/promises';

const modules = ['core', 'http', 'test', 'pac4j', 'micrometer', 'opentelemetry', 'openapi', 'mcp'];

export async function loadDependencyVersions(addWatchFile) {
    const entries = await Promise.all([...modules, 'bom'].map(module => loadModuleVersion(module, addWatchFile)));
    const [, bomVersions] = entries.pop();
    const libraries = Object.fromEntries(entries.map(([module, versions]) => [module, versions.published]));
    return {libraries, bom: bomVersions.published ?? bomVersions.configured, bomPublished: !!bomVersions.published};
}

async function loadModuleVersion(module, addWatchFile) {
    const path = new URL(`../${module}/build.gradle`, import.meta.url);
    addWatchFile(path);
    const configured = projectVersion(module, await readFile(path, 'utf8'));
    return [module, {configured, published: await publishedVersion(module, configured)}];
}

function projectVersion(module, source) {
    const declarations = source.match(/^[ \t]*version[ \t]*=[^\r\n]*/gm);
    const version = declarations?.length === 1 &&
        declarations[0].trim().match(/^version[ \t]*=[ \t]*(['"])([\w][\w.+-]*)\1[ \t]*(?:\/\/.*)?$/)?.[2];
    if (!version) throw new Error(`Expected one literal version assignment in ${module}/build.gradle`);
    return version;
}

async function publishedVersion(module, configuredVersion) {
    const artifact = `shoostr-${module}`;
    const url = `https://repo.maven.apache.org/maven2/io/github/suppierk/${artifact}/maven-metadata.xml`;
    const response = await fetchMetadata(url).catch(error => {
        throw new Error(`Cannot look up ${artifact} releases on Maven Central`, {cause: error});
    });
    if (module === 'bom' && response.status === 404) return undefined;
    if (!response.ok) throw new Error(`Cannot look up ${artifact} releases on Maven Central: HTTP ${response.status}`);

    const metadata = await response.text();
    const release = metadata.match(/<release>\s*(\d{4}\.\d{2}\.\d{1,2}\.\d+)\s*<\/release>/)?.[1];
    const versions = metadata.match(/<versions>([\s\S]*?)<\/versions>/)?.[1];
    if (!metadata.includes('<groupId>io.github.suppierk</groupId>') || !metadata.includes(`<artifactId>${artifact}</artifactId>`)
        || !release || !versions?.includes(`<version>${release}</version>`)) {
        throw new Error(`Invalid Maven Central release metadata for ${artifact}`);
    }
    return /^\d{4}\.\d{2}\.\d{1,2}\.\d+$/.test(configuredVersion) && versions.includes(`<version>${configuredVersion}</version>`)
        ? configuredVersion : release;
}

function fetchMetadata(url) {
    const fetchOnce = () => fetch(url, {redirect: 'error', signal: AbortSignal.timeout(30_000)});
    return fetchOnce().catch(async () => {
        await delay(250);
        return fetchOnce();
    });
}
