import unicodedata

CONSONANTS = {
    'क': "k", 'ख': "kh", 'ग': "g", 'घ': "gh", 'ङ': "n",
    'च': "ch", 'छ': "chh", 'ज': "j", 'झ': "jh", 'ञ': "n",
    'ट': "t", 'ठ': "th", 'ड': "d", 'ढ': "dh", 'ण': "n",
    'त': "t", 'थ': "th", 'द': "d", 'ध': "dh", 'न': "n",
    'प': "p", 'फ': "ph", 'ब': "b", 'भ': "bh", 'म': "m",
    'य': "y", 'र': "r", 'ल': "l", 'व': "v", 'श': "sh", 'ष': "sh", 'स': "s", 'ह': "h",
    '\u0958': "q", '\u0959': "kh", '\u095a': "g", '\u095b': "z", '\u095c': "r", '\u095d': "rh", '\u095e': "f", '\u095f': "y",
}
VOWELS = {'अ': "a", 'आ': "aa", 'इ': "i", 'ई': "ee", 'उ': "u", 'ऊ': "oo", 'ऋ': "ri",
          'ए': "e", 'ऐ': "ai", 'ओ': "o", 'औ': "au", 'ऑ': "o"}
SIGNS = {'ा': "aa", 'ि': "i", 'ी': "ee", 'ु': "u", 'ू': "oo", 'ृ': "ri",
         'े': "e", 'ै': "ai", 'ो': "o", 'ौ': "au", 'ॉ': "o"}
VIRAMA = '्'
NUKTA = '़'
NASAL = {'ं', 'ँ'}
VISARGA = 'ः'
FOLDS = [("chh", "ch"), ("aa", "a"), ("ee", "i"), ("ii", "i"), ("oo", "u"), ("uu", "u"), ("w", "v"), ("ph", "f"),
         ("q", "k"), ("z", "j"), ("sh", "s"), ("ey", "e"), ("y", "i"), ("ai", "e"), ("au", "o")]


def romanise(word):
    chars = [c for c in word if c != NUKTA]
    units = []
    i = 0
    while i < len(chars):
        c = chars[i]
        cons = CONSONANTS.get(c)
        if cons is not None:
            nxt = chars[i + 1] if i + 1 < len(chars) else None
            if nxt == VIRAMA:
                units.append((cons, False)); i += 1
            elif nxt is not None and nxt in SIGNS:
                units.append((cons + SIGNS[nxt], False)); i += 1
            else:
                units.append((cons + "a", True))
        elif c in VOWELS:
            units.append((VOWELS[c], False))
        elif c in NASAL:
            units.append(("n", False))
        elif c == VISARGA:
            units.append(("h", False))
        elif c in SIGNS:
            units.append((SIGNS[c], False))
        i += 1
    out = ""
    for k, (text, schwa) in enumerate(units):
        last = k == len(units) - 1
        nxt = units[k + 1] if k + 1 < len(units) else None
        next_has_vowel = nxt is not None and (not nxt[1] and any(ch in "aeiou" for ch in nxt[0]))
        after_vowel = bool(out) and out[-1] in "aeiou"
        drop = schwa and ((last and len(units) > 1) or (after_vowel and next_has_vowel and k > 0))
        out += text[:-1] if drop else text
    return out


def fold(word):
    w = word.lower()
    for a, b in FOLDS:
        w = w.replace(a, b)
    sb = []
    for ch in w:
        if not sb or sb[-1] != ch or ch in "aeiou":
            sb.append(ch)
    s = "".join(sb)
    if len(s) > 3 and s.endswith("h") and s[-2] in "aeiou":
        s = s[:-1]
    return s


def key(native):
    return fold(romanise(unicodedata.normalize("NFC", native)))
