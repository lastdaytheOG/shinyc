"""Row-by-row comparison of a version-13 vault and the same vault after the upgrade to 14."""
import sqlite3, sys, os, shutil, tempfile, collections
def open_copy(folder):
    d=tempfile.mkdtemp(prefix='cmp')
    for f in ('vault.db','vault.db-wal','vault.db-shm'):
        if os.path.exists(os.path.join(folder,f)): shutil.copy(os.path.join(folder,f), os.path.join(d,f))
    c=sqlite3.connect(os.path.join(d,'vault.db')); c.row_factory=sqlite3.Row
    return c
old=open_copy(sys.argv[1]); new=open_copy(sys.argv[2])
print('versions:', old.execute('pragma user_version').fetchone()[0], '->', new.execute('pragma user_version').fetchone()[0])
print('integrity:', new.execute('pragma integrity_check').fetchone()[0], '| foreign keys broken:', len(new.execute('pragma foreign_key_check').fetchall()))
problems=[]
o={r['id']:r for r in old.execute('select rowid as rid,* from vault_items')}
n={r['id']:r for r in new.execute('select rowid as rid,* from vault_items')}
print('items:', len(o), '->', len(n), '| same ids:', set(o)==set(n), '| same rowids:', all(o[i]['rid']==n[i]['rid'] for i in o))
CANON={'pdf','word','excel','epub','screenshot','photo','video','audio','link','text','file'}
moved=0; bracket_bodies=0; renamed=collections.Counter()
same_cols=[c for c in o[next(iter(o))].keys() if c not in ('ocrText','tags','itemType')]
for i,a in o.items():
    b=n[i]
    rebuilt = b['ocrText'] + ('\n['+b['tags']+']' if b['tags'] else '')
    if rebuilt != a['ocrText']: problems.append(('text+tags do not rebuild the old text', i))
    if b['tags']: moved+=1
    if '\n[' in b['ocrText']: bracket_bodies+=1
    if b['itemType'] not in CANON: problems.append(('type not on the list', i, b['itemType']))
    if a['itemType']!=b['itemType']: renamed[(a['itemType'],b['itemType'])]+=1
    if a['itemType'].lower()!=b['itemType'] and (a['itemType'],b['itemType']) not in {('dev_manual','photo')}: problems.append(('unexpected rename', i, a['itemType'], b['itemType']))
    for c in same_cols:
        if a[c]!=b[c]: problems.append(('column changed', i, c, a[c], b[c]))
    if b['qrPayload'] is not None: problems.append(('qr payload where there was none', i))
print('rows whose tags moved to the tags column:', moved, '| pages that keep a line starting with a bracket:', bracket_bodies)
print('types renamed:', dict(renamed))
print('types now:', {r[0]:r[1] for r in new.execute('select itemType,count(*) from vault_items group by itemType')})
# every other table byte-for-byte
tables=[r[0] for r in old.execute("select name from sqlite_master where type='table' and name not like 'vault_fts%' and name not like 'sqlite_%' and name not in ('vault_items','room_master_table','android_metadata')")]
for t in tables:
    ra=[tuple(r) for r in old.execute(f'select * from "{t}" order by 1,2')]
    rb=[tuple(r) for r in new.execute(f'select * from "{t}" order by 1,2')]
    if t=='vault_metadata':
        changed=[(x,y) for x,y in zip(ra,rb) if x!=y]
        print(f'  {t}: {len(ra)} rows; changed: {[(x[1][:8],x[2],x[3],"->",y[3]) for x,y in changed]}')
        if len(ra)!=len(rb) or any(x[2]!='SOURCE_TYPE' or x[:3]+x[4:]!=y[:3]+y[4:] for x,y in changed): problems.append(('metadata changed beyond SOURCE_TYPE',))
    else:
        print(f'  {t}: {len(ra)} rows; identical: {ra==rb}')
        if ra!=rb: problems.append(('table changed', t))
# documents
docs={r['id']:r for r in new.execute('select * from documents')}
groups=collections.defaultdict(list)
for r in n.values():
    if r['parentDocumentId']: groups[r['parentDocumentId']].append(r)
print('documents:', len(docs), '| documents that have pieces:', len(groups))
if set(docs)!=set(groups): problems.append(('documents do not match the pieces',))
for d,rs in groups.items():
    first=min(rs,key=lambda r:r['chunkIndex']); doc=docs.get(d)
    if not doc: continue
    want=(first['uri'],first['sourceFile'],first['itemType'],first['contentHash'],None,len(rs),min(r['timestamp'] for r in rs))
    got=(doc['uri'],doc['name'],doc['itemType'],doc['contentHash'],doc['pageCount'],doc['chunkCount'],doc['addedAt'])
    if want!=got: problems.append(('document record wrong', d, want, got))
for d in sorted(docs.values(), key=lambda r:r['name']): print(f"   {d['name'][:44]:44s} {d['itemType']:5s} pieces={d['chunkCount']:4d} pages={d['pageCount']}")
# full-text index: tags gone, page words still there
fts=lambda c,w: c.execute("select count(*) from vault_fts where vault_fts match ?", (w,)).fetchone()[0]
for w in ('government','monetary','billing','document','dividend','silberschatz','handbook'):
    print(f"   full-text '{w}': {fts(old,w)} -> {fts(new,w)}")
print('indexes on vault_items:', [r[1] for r in new.execute("pragma index_list('vault_items')")])
print('triggers:', [r[0] for r in new.execute("select name from sqlite_master where type='trigger'")])
print('PROBLEMS:', len(problems)); [print('  ',p) for p in problems[:20]]
