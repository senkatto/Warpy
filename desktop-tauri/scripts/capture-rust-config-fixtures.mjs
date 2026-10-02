// Run existing regression cases against the legacy implementation once and
// retain their inputs/results as independent golden cases for the Rust port.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {spawnSync} from 'node:child_process';
const root=fileURLToPath(new URL('../',import.meta.url));
const output=`${root}../.artifacts/rust-client-migration`;
await mkdir(output,{recursive:true});
await mkdir(`${root}test/fixtures`,{recursive:true});
for (const suite of [
  {module:'vpn-config',fixture:'rust-config',names:['parseProfileLink','profileShareLink','buildSingBoxConfig','buildSelectableSingBoxConfig','buildRuntimeSingBoxConfig']},
  {module:'subscription',fixture:'rust-imports',names:['parseSubscriptionPayload','subscriptionProfileKey','subscriptionProfilesEqual','subscriptionRefreshDue','subscriptionDisplayName','replaceSubscriptionProfiles','findProfileIndexAfterSubscriptionUpdate']},
]) {
const original=pathToFileURL(`${root}src/${suite.module}.js`).href;
const destination=`${root}test/fixtures/${suite.fixture}.json`;
const names=suite.names;
await writeFile(`${output}/capture.mjs`, `import * as impl from ${JSON.stringify(original)};
export * from ${JSON.stringify(original)};
import {writeFileSync} from 'node:fs';
const cases=new Map();
process.on('exit',()=>writeFileSync(${JSON.stringify(destination)},JSON.stringify([...cases.values()])));
const capture=(name,args)=>{const key=JSON.stringify([name,args]);const retain=key.length<100000;try{const result=impl[name](...args);if(retain)cases.set(key,{name,args,result});return result;}catch(error){if(retain)cases.set(key,{name,args,error:true});throw error;}};
${names.map(name=>`export const ${name}=(...args)=>capture('${name}',args);`).join('\n')}`);
let tests=await readFile(`${root}test/${suite.module}.test.js`,'utf8');
tests=tests.replace(`../src/${suite.module}.js`,pathToFileURL(`${output}/capture.mjs`).href);
tests=tests.replace(/from '(\.\.\/src\/[^']+)'/g,(_,path)=>`from '${pathToFileURL(`${root}test/${path}`).href}'`);
await writeFile(`${output}/cases.test.mjs`,tests);
const result=spawnSync(process.execPath,['--test',`${output}/cases.test.mjs`],{cwd:root,encoding:'utf8'});
await writeFile(`${output}/${suite.fixture}-capture.log`,result.stdout+result.stderr);
if(result.status!==0)throw new Error('Legacy fixture tests failed; see .artifacts/rust-client-migration/fixture-capture.log');
console.log(`Captured ${JSON.parse(await readFile(destination,'utf8')).length} legacy ${suite.module} cases.`);
}
