// Copy node icons next to their compiled .js, since tsc only emits TypeScript
// output. n8n resolves `icon: 'file:quack.svg'` relative to the node file.
import { cpSync, mkdirSync } from 'node:fs';

mkdirSync('dist/nodes/QuackOnDemand', { recursive: true });
cpSync('nodes/QuackOnDemand/qod.svg', 'dist/nodes/QuackOnDemand/qod.svg');
console.log('copied node icons to dist');