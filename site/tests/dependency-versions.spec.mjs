import {expect, test} from '@playwright/test';
import {fileURLToPath} from 'node:url';
import {loadDependencyVersions} from '../dependency-versions.mjs';

const modules = ['core', 'http', 'test', 'pac4j', 'micrometer', 'opentelemetry', 'openapi', 'mcp'];

test('documentation versions load from the watched Gradle files without network access', async () => {
    const originalFetch = globalThis.fetch;
    const watched = [];
    globalThis.fetch = () => { throw new Error('Version loading must use the checkout'); };

    try {
        const versions = await loadDependencyVersions(path => watched.push(fileURLToPath(path)));
        expect(Object.keys(versions.libraries)).toEqual(modules);
        expect(versions.bom).toBeTruthy();
        expect(watched).toEqual([...modules, 'bom'].map(module =>
            fileURLToPath(new URL(`../../${module}/build.gradle`, import.meta.url))));
    } finally {
        globalThis.fetch = originalFetch;
    }
});
