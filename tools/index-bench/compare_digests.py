"""Compares two digests line by line: which columns differ, how many items."""
import json, sys, collections
def load(p):
    d={}
    for l in open(p,encoding='utf-8'):
        l=l.strip()
        if l:
            j=json.loads(l); d[j['key']]=j
    return d
a,b=load(sys.argv[1]),load(sys.argv[2])
print(f"{sys.argv[1].split('/')[-1]}: {len(a)} items, {sys.argv[2].split('/')[-1]}: {len(b)} items")
only_a=set(a)-set(b); only_b=set(b)-set(a)
if only_a or only_b: print(" only in first:",len(only_a),sorted(only_a)[:5]," only in second:",len(only_b),sorted(only_b)[:5])
c=collections.Counter(); same=0
for k in set(a)&set(b):
    diff=[f for f in a[k] if a[k][f]!=b[k].get(f)]
    if diff:
        for f in diff: c[f]+=1
    else: same+=1
print(f" identical items: {same} of {len(set(a)&set(b))}; differing columns: {dict(c)}")
