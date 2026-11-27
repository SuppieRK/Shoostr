import {expect, test} from '@playwright/test';
import {loadDependencyVersions} from '../dependency-versions.mjs';

const version = '2000.01.1.0';

for (const failures of [1, 2]) {
    const behavior = failures === 1 ? 'recovers on retry' : 'reports a second failure';
    test(`release metadata lookup ${behavior}`, async () => {
        const originalFetch = globalThis.fetch;
        const firstFailure = new TypeError('first connection failed');
        const retryFailure = new TypeError('retry connection failed');
        let testRequests = 0;

        globalThis.fetch = async url => {
            const artifact = new URL(url).pathname.split('/').at(-2);
            if (artifact === 'shoostr-test' && ++testRequests <= failures) {
                throw testRequests === 1 ? firstFailure : retryFailure;
            }
            return metadata(artifact);
        };

        try {
            const lookup = loadDependencyVersions(() => {});
            if (failures === 1) {
                expect((await lookup).libraries.test).toBe(version);
            } else {
                await expect(lookup).rejects.toMatchObject({
                    message: 'Cannot look up shoostr-test releases on Maven Central',
                    cause: retryFailure,
                });
            }
            expect(testRequests).toBe(2);
        } finally {
            globalThis.fetch = originalFetch;
        }
    });
}

function metadata(artifact) {
    return new Response(`<metadata><groupId>io.github.suppierk</groupId><artifactId>${artifact}</artifactId>
        <versioning><release>${version}</release><versions><version>${version}</version></versions></versioning>
        </metadata>`);
}
