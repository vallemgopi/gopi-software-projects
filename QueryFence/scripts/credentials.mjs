// Local owner administration. Only the token hash is sent to PostgreSQL.
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {mkdirSync,writeFileSync,existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
const [action,name,role='ANALYST',scopeText='schema:read,query:plan,query:results',originText='http://localhost:3000',hoursText='24',subject=name]=process.argv.slice(2);
const knownScopes=['schema:read','query:plan','query:review','query:execute','query:results','audit:read','evaluation:read','evaluation:run'];
function sql(value){return "'"+value.replaceAll("'","''")+"'";}
function psql(statement){const result=spawnSync('docker',['compose','exec','-T','db','psql','-v','ON_ERROR_STOP=1','-U','queryfence_owner','-d','queryfence'],{cwd:root,input:statement,encoding:'utf8',shell:false});if(result.status!==0)throw new Error('Database administration failed. Check Docker, migrations and role setup.');}
if(action==='revoke'){
 if(!/^[0-9a-f-]{36}$/.test(name||''))throw new Error('Supply the credential UUID.');
 psql(`UPDATE queryfence.credentials SET revoked=true WHERE id=${sql(name)}::uuid;`);console.log('Revoked credential:',name);
}else if(action==='create'){
 if(!/^[A-Za-z0-9_-]{1,60}$/.test(name||''))throw new Error('Name must be 1–60 letters, digits, underscores or hyphens.');
 if(!/^[A-Za-z0-9_-]{1,60}$/.test(subject||''))throw new Error('Subject must be 1–60 letters, digits, underscores or hyphens.');
 if(!['ANALYST','FINANCE','OPERATIONS'].includes(role))throw new Error('Unknown role.');
 const scopes=scopeText.split(',');if(!scopes.every(s=>knownScopes.includes(s)))throw new Error('Unknown scope.');
 const origins=originText==='none'?[]:originText.split(',');for(const o of origins){const u=new URL(o);if(!['http:','https:'].includes(u.protocol)||u.origin!==o)throw new Error('Use exact HTTP(S) origins without paths or wildcard.');}
 const hours=Number(hoursText);if(!Number.isFinite(hours)||hours<=0||hours>720)throw new Error('Expiry must be >0 and <=720 hours.');
 const target=path.join(root,'.secrets',name+'.json');if(existsSync(target))throw new Error('Credential file exists; choose a new name or revoke the old credential.');
 const id=randomUUID(),token=randomBytes(32).toString('base64url'),hash=createHash('sha256').update(token).digest('hex'),expiresAt=new Date(Date.now()+hours*3600000).toISOString();
 psql(`INSERT INTO queryfence.credentials(id,name,token_hash,role,scopes,origins,expires_at) VALUES (${sql(id)},${sql(subject)},${sql(hash)},${sql(role)},ARRAY[${scopes.map(sql).join(',')}]::text[],ARRAY[${origins.map(sql).join(',')}]::text[],${sql(expiresAt)});`);
 mkdirSync(path.join(root,'.secrets'),{recursive:true,mode:0o700});writeFileSync(target,JSON.stringify({id,token,role,scopes,origins,expiresAt},null,2),{mode:0o600,flag:'wx'});console.log('Credential saved once to',target,'; ID:',id,'; expires:',expiresAt);console.log('Keep this ignored file private; on Windows restrict its ACL. Never paste its token into source or logs.');
}else console.log('Usage: node scripts/credentials.mjs create NAME ROLE SCOPES ORIGINS HOURS\n       node scripts/credentials.mjs revoke UUID\nMCP: omit query:review; supply approval from a separate trusted human review channel.');
