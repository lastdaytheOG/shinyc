"""Compare two search snapshots line by line and say what differs."""
import json, sys, collections
a=[json.loads(l) for l in open(sys.argv[1],encoding='utf-8')]
b=[json.loads(l) for l in open(sys.argv[2],encoding='utf-8')]
assert len(a)==len(b), (len(a),len(b))
kinds=collections.Counter(); shown=0
for x,y in zip(a,b):
    assert (x['q'],x['chip'])==(y['q'],y['chip'])
    if x==y: kinds['identical']+=1; continue
    what=[]
    if x['items']!=y['items']:
        what.append('order' if sorted(x['items'])==sorted(y['items']) else 'items')
    if x['similarOnly']!=y['similarOnly']: what.append('similarOnly')
    if x['cards']!=y['cards']: what.append('cards')
    kinds['+'.join(what)]+=1
    if shown<int(sys.argv[3]) if len(sys.argv)>3 else 12:
        shown+=1
        print(f"--- q={x['q']!r} chip={x['chip']} differs in: {what}")
        ia,ib=x['items'],y['items']
        if ia!=ib:
            print('   only before:', [i[:22]+'…'+i[-10:] for i in ia if i not in ib][:6]); print('   only after: ', [i[:22]+'…'+i[-10:] for i in ib if i not in ia][:6])
            if sorted(ia)==sorted(ib): print('   same set, order differs; first differing rank:', next(k for k,(p,q) in enumerate(zip(ia,ib)) if p!=q))
        ca={c['id']:c for c in x['cards']}; cb={c['id']:c for c in y['cards']}
        for k in ca:
            if k in cb and ca[k]!=cb[k]:
                print('   card', k[:30], {f:(ca[k][f],cb[k][f]) for f in ca[k] if ca[k][f]!=cb[k][f]}); break
print('RESULT:', dict(kinds), 'of', len(a))
