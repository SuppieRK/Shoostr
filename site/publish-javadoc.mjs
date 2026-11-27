import {createHash} from 'node:crypto';
import {mkdir, writeFile} from 'node:fs/promises';
import {dirname, extname, resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {unzipSync} from 'fflate';

export async function publishJavadoc({dir}, libraries) {
    await Promise.all(Object.entries(libraries).map(async ([module, version]) => {
        const output = fileURLToPath(new URL(`javadoc/${module}/${version}/`, dir));
        await writeJavadoc(output, await loadJavadoc(module, version));
    }));
}

export function serveJavadoc({server, logger}, base, libraries) {
    const prefix = `${base.replace(/\/$/, '')}/javadoc/`;
    const pending = new Map();

    server.middlewares.use(async (request, response, next) => {
        if (request.method !== 'GET' && request.method !== 'HEAD') return next();
        // Astro strips the base from req.url before this middleware runs.
        const pathname = (request.originalUrl ?? request.url).split('?')[0];
        if (!pathname.startsWith(prefix)) return next();

        const [module, version, ...segments] = pathname.slice(prefix.length).split('/');
        if (!Object.hasOwn(libraries, module) || libraries[module] !== version) return next();

        const name = segments.join('/') || 'index.html';
        if (!pending.has(module)) {
            pending.set(module, loadJavadoc(module, version).catch(error => {
                pending.delete(module);
                throw error;
            }));
        }

        let files;
        try {
            files = await pending.get(module);
        } catch (error) {
            logger.error(String(error));
            response.statusCode = 503;
            response.end('Released Javadoc is temporarily unavailable.');
            return;
        }

        if (!Object.hasOwn(files, name) || name.endsWith('/')) return next();
        const contents = files[name];
        response.setHeader('Content-Type', javadocContentType(name));
        response.setHeader('Content-Length', contents.byteLength);
        response.end(request.method === 'HEAD' ? undefined : contents);
    });
}

function javadocContentType(name) {
    switch (extname(name)) {
        case '.html': return 'text/html; charset=utf-8';
        case '.css': return 'text/css; charset=utf-8';
        case '.js': return 'text/javascript; charset=utf-8';
        case '.svg': return 'image/svg+xml';
        case '.png': return 'image/png';
        default: return 'text/plain; charset=utf-8';
    }
}

async function loadJavadoc(module, version) {
    const artifact = `shoostr-${module}`;
    const archive = `https://repo.maven.apache.org/maven2/io/github/suppierk/${artifact}/${version}/${artifact}-${version}-javadoc.jar`;
    const options = {redirect: 'error', signal: AbortSignal.timeout(30_000)};
    const [response, sidecar] = await Promise.all([fetch(archive, options), fetch(`${archive}.sha1`, options)]);
    if (!response.ok) throw new Error(`Cannot download ${artifact} Javadoc: HTTP ${response.status}`);
    if (!sidecar.ok) throw new Error(`Cannot download ${artifact} Javadoc checksum: HTTP ${sidecar.status}`);

    const bytes = new Uint8Array(await response.arrayBuffer());
    const checksum = (await sidecar.text()).trim().toLowerCase();
    if (!/^[a-f0-9]{40}$/.test(checksum) || createHash('sha1').update(bytes).digest('hex') !== checksum) {
        throw new Error(`Checksum mismatch for ${artifact} Javadoc ${version}`);
    }

    const files = unzipSync(bytes);
    if (!Object.hasOwn(files, 'index.html')) throw new Error(`Missing Javadoc index for ${artifact}`);
    return files;
}

async function writeJavadoc(output, files) {
    const root = resolve(output);
    for (const [name, contents] of Object.entries(files)) {
        if (name.endsWith('/')) continue;
        const path = resolve(root, name);
        if (!path.startsWith(root + sep)) throw new Error(`Javadoc path escapes its directory: ${name}`);
        await mkdir(dirname(path), {recursive: true});
        await writeFile(path, contents);
    }
}
