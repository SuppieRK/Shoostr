import {spawn} from 'node:child_process';
import {access, cp, readFile} from 'node:fs/promises';
import {extname, resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';

const checkout = fileURLToPath(new URL('../', import.meta.url));

export async function publishJavadoc({dir}, libraries) {
    await generateJavadoc(libraries);
    await Promise.all(Object.entries(libraries).map(async ([module, version]) => {
        const source = javadocDirectory(module);
        const output = new URL(`javadoc/${module}/${version}/`, dir);
        await access(resolve(source, 'index.html'));
        await cp(source, output, {recursive: true});
    }));
}

export function serveJavadoc({server, logger}, base, libraries) {
    const prefix = `${base.replace(/\/$/, '')}/javadoc/`;
    let generation;

    server.middlewares.use(async (request, response, next) => {
        if (request.method !== 'GET' && request.method !== 'HEAD') return next();
        // Astro strips the base from req.url before this middleware runs.
        const pathname = (request.originalUrl ?? request.url).split('?')[0];
        if (!pathname.startsWith(prefix)) return next();

        const [module, version, ...segments] = pathname.slice(prefix.length).split('/');
        if (!Object.hasOwn(libraries, module) || libraries[module] !== version) return next();

        const root = javadocDirectory(module);
        const path = resolve(root, segments.join('/') || 'index.html');
        if (!path.startsWith(root + sep)) return next();

        try {
            generation ??= generateJavadoc(libraries).catch(error => {
                generation = undefined;
                throw error;
            });
            await generation;
        } catch (error) {
            logger.error(String(error));
            response.statusCode = 503;
            response.end('Could not generate Javadocs. Check the Java 21 setup and Gradle output.');
            return;
        }

        let contents;
        try {
            contents = await readFile(path);
        } catch (error) {
            if (['ENOENT', 'EISDIR', 'ENOTDIR'].includes(error.code)) return next();
            return next(error);
        }
        response.setHeader('Content-Type', javadocContentType(path));
        response.setHeader('Content-Length', contents.byteLength);
        response.end(request.method === 'HEAD' ? undefined : contents);
    });
}

function generateJavadoc(libraries) {
    const tasks = Object.keys(libraries).map(module => `:${module}:javadoc`);
    const windows = process.platform === 'win32';
    const command = windows ? process.env.ComSpec ?? 'cmd.exe' : './gradlew';
    const argumentsToRun = windows ? ['/d', '/c', 'gradlew.bat', ...tasks] : tasks;

    return new Promise((accept, reject) => {
        const child = spawn(command, [...argumentsToRun, '--console=plain'], {cwd: checkout, stdio: 'inherit'});
        child.once('error', reject);
        child.once('exit', (code, signal) => {
            if (code === 0) accept();
            else reject(new Error(`Gradle Javadoc generation failed (${signal ?? `exit ${code}`})`));
        });
    });
}

function javadocDirectory(module) {
    return resolve(checkout, module, 'build', 'docs', 'javadoc');
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
