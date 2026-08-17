// Post-generation fixes for the openapi-generator output.
//
// Run automatically by `npm run gen-api-client`. Both changes are to the
// generated package.json, which the generator overwrites every time.
import { readFileSync, writeFileSync } from 'node:fs';

const file = 'gen/fam-api/package.json';
const pkg = JSON.parse(readFileSync(file, 'utf8'));

// 1. axios becomes a peer dependency.
//
// TypeScript treats axios types as distinct when they resolve through
// different module paths - the frontend's node_modules versus the client's -
// even at identical versions. Declaring it a peer makes the frontend the single
// owner and the types line up.
if (pkg.dependencies?.axios) {
  pkg.peerDependencies = { axios: pkg.dependencies.axios };
  delete pkg.dependencies;
}

// 2. Don't compile the client.
//
// The generator ships `prepare: npm run build`, which runs tsc with
// `declaration: true` and points main/typings at dist/. Nothing needs that
// output: the package is consumed through a symlink by Vite and vue-tsc, both
// of which read the TypeScript directly, and the frontend type-checks and
// builds with no dist/ present.
//
// It is also the step that breaks CI. npm runs `prepare` for a file:
// dependency during `npm ci` even with --ignore-scripts, and in that install
// context tsc resolves the generated package's own pinned @types/node 12
// alongside modern axios, which fails declaration emit with
//   TS2527: The inferred type of 'createRequestFunction' references an
//   inaccessible 'unique symbol' type.
// The same command succeeds locally, where the types hoist differently - so
// this cannot be caught before it reaches CI. Removing the build removes the
// class of problem rather than chasing the type error.
delete pkg.scripts;
delete pkg.devDependencies;
pkg.main = './index.ts';
pkg.module = './index.ts';
pkg.types = './index.ts';
delete pkg.typings;

writeFileSync(file, JSON.stringify(pkg, null, 2) + '\n');
console.log('postgen: axios pinned as a peer dependency, build step removed');
