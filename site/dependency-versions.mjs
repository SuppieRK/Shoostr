import {readFile} from 'node:fs/promises';

const modules = ['core', 'http', 'test', 'pac4j', 'micrometer', 'opentelemetry', 'openapi', 'mcp'];

export async function loadDependencyVersions(addWatchFile) {
    const entries = await Promise.all([...modules, 'bom'].map(module => loadModuleVersion(module, addWatchFile)));
    const [, bom] = entries.pop();
    return {libraries: Object.fromEntries(entries), bom};
}

async function loadModuleVersion(module, addWatchFile) {
    const path = new URL(`../${module}/build.gradle`, import.meta.url);
    addWatchFile(path);
    return [module, projectVersion(module, await readFile(path, 'utf8'))];
}

function projectVersion(module, source) {
    const declarations = source.match(/^[ \t]*version[ \t]*=[^\r\n]*/gm);
    const version = declarations?.length === 1 &&
        declarations[0].trim().match(/^version[ \t]*=[ \t]*(['"])([\w][\w.+-]*)\1[ \t]*(?:\/\/.*)?$/)?.[2];
    if (!version) throw new Error(`Expected one literal version assignment in ${module}/build.gradle`);
    return version;
}
