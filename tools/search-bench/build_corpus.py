import json
import pathlib
import random
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "visual-search"))

import cases_hand as H
import personal
from gallery_queries import Q

OUT = HERE / "corpus"
R = random.Random(77)
DEV = re.compile(r"[\u0900-\u097F]")
PLACEHOLDER = re.compile(r"⟦[^⟧]*⟧")
WORD = re.compile(r"[\w\u0900-\u097F]+", re.UNICODE)
STOP = set("""a an the of to in on for and or with by at from is are was were be been my me i you your our we it its this that
these those how what when where why which who whom do does did can could will would should shall may might about into over
under than then so as if not no yes up down out off all any some each very more most much many few one two three get got
show find give tell looking look need want pdf doc file files photo picture screenshot document mera meri mere ka ki ke ko
se par mein hai hain tha thi aur wala wali kya kab kaise kyon""".split())
HI_LATIN = {"mein", "ki", "ka", "ke", "ko", "se", "par", "pe", "wala", "wali", "wale", "aur", "hai", "hain", "kya", "kab",
            "kyon", "kaise", "bimari", "mausam", "barish", "bijli", "paani", "dawai", "pichhle", "mahine", "chai", "kheti",
            "bhukamp", "aata", "shaadi", "ration", "ticket", "bill"}
MEANINGLESS = ["scan_{n}.pdf", "document ({k}).pdf", "IMG_{n}.pdf", "download ({k}).pdf", "notes_{k}.pdf", "file_{n}.pdf",
               "Doc{n}.docx", "New Microsoft Word Document ({k}).docx"]


def lang_of(q):
    if DEV.search(q):
        return "hi"
    words = set(re.findall(r"[a-z]+", q.lower()))
    return "hi-latn" if words & {"mein", "ki", "ka", "ke", "ko", "se", "par", "kya", "kab", "kyon", "kaise", "bimari",
                                  "bijli", "paani", "dawai", "pichhle", "mahine", "aata", "shaadi", "chai", "kheti",
                                  "bhukamp", "barish", "mausam", "is"} - {"is"} else "en"


def wiki_items():
    arts = json.loads((OUT / "wiki.json").read_text(encoding="utf-8"))
    items = []
    for n, a in enumerate(arts):
        text = re.sub(r"\n{3,}", "\n\n", re.sub(r"=+\s*([^=\n]+?)\s*=+", r"\1", a["text"]))
        paras = [p.strip() for p in text.split("\n") if len(p.strip()) > 40]
        pages, cur = [], ""
        for p in paras:
            if len(cur) + len(p) > 1200 and cur:
                pages.append(cur.strip())
                cur = ""
                if len(pages) == 3:
                    break
            cur += p + "\n"
        if cur.strip() and len(pages) < 3:
            pages.append(cur.strip())
        meaningful = R.random() < 0.5
        word = R.random() < 0.2
        if meaningful:
            name = f"{a['title']} notes.{'docx' if word else 'pdf'}"
        else:
            name = R.choice(MEANINGLESS).format(n=R.randrange(1000, 99999), k=R.randrange(1, 40))
            word = name.endswith(".docx")
        items.append({"id": f"w-{a['lang']}-{n:03d}", "kind": "wiki", "type": "WORD" if word else "PDF", "name": name,
                      "lang": a["lang"], "date": {"monthsAgo": R.randrange(0, 12), "day": R.randrange(1, 28)},
                      "pages": pages, "facts": {}, "noisy": False, "group": "wiki", "wiki": a["asked"],
                      "source": f"https://{a['lang']}.wikipedia.org/w/index.php?oldid={a['revid']}"})
    return items


def photo_items():
    gallery = pathlib.Path(r"D:\amar-vs\device\gallery")
    files = sorted(p.name for p in gallery.glob("*.jpg"))
    items = []
    for f in files:
        tid = f[len("IMG_"):-len(".jpg")]
        items.append({"id": f"photo-{tid}", "kind": "photo", "type": "PHOTO", "name": f, "lang": "zxx",
                      "date": {"monthsAgo": R.randrange(0, 12), "day": R.randrange(1, 28)}, "pages": [""], "facts": {},
                      "noisy": False, "group": "photo", "image": f})
    return items


CONFUSE = [("l", "1"), ("I", "l"), ("rn", "m"), ("m", "rn"), ("e", "c"), ("O", "0"), ("a", "o"), ("i", "l")]


def noised(text, rnd):
    def word(m):
        w = m.group(0)
        if len(w) < 4 or not re.fullmatch(r"[A-Za-z]+", w) or rnd.random() > 0.04:
            return w
        for a, b in rnd.sample(CONFUSE, len(CONFUSE)):
            if a in w:
                i = w.index(a)
                return w[:i] + b + w[i + len(a):]
        i = rnd.randrange(len(w))
        return w[:i] + w[i + 1:]
    out, last = [], 0
    for m in PLACEHOLDER.finditer(text):
        out.append(re.sub(r"[A-Za-z]+", word, text[last:m.start()]))
        out.append(m.group(0))
        last = m.end()
    out.append(re.sub(r"[A-Za-z]+", word, text[last:]))
    return "".join(out)


def words_of(text):
    return [w.lower() for w in WORD.findall(PLACEHOLDER.sub(" ", text))]


def content(q):
    return [w for w in words_of(q) if len(w) >= 3 and w not in STOP]


def shared_words(q, items):
    qs = set(content(q))
    have = set()
    for it in items:
        have |= set(words_of(" ".join(it["pages"]) + " " + it["name"]))
    stems = {w[:5] for w in have if len(w) >= 5}
    return sorted(w for w in qs if w in have or (len(w) >= 5 and w[:5] in stems))


def main():
    personal.build()
    items = wiki_items() + personal.ITEMS + photo_items()
    for it in personal.ITEMS:
        it.setdefault("source", "invented")
    by_id = {it["id"]: it for it in items}
    wiki = {it["wiki"]: it["id"] for it in items if it.get("wiki")}
    by_kind = {}
    for it in items:
        by_kind.setdefault(it["kind"], []).append(it["id"])

    def clean(it):
        return " ".join(it["pages"])

    def resolve(spec):
        ids = []
        for k in spec.get("kinds", []):
            ids += by_kind.get(k, [])
        for t in spec.get("wiki", []):
            if t in wiki:
                ids.append(wiki[t])
        w = spec.get("items_where")
        if w:
            for it in items:
                if it["kind"] != w["kind"]:
                    continue
                if "lang" in w and it["lang"] != w["lang"]:
                    continue
                if "contains" in w and w["contains"].lower() not in clean(it).lower():
                    continue
                if "contains_any" in w and not any(c.lower() in clean(it).lower() for c in w["contains_any"]):
                    continue
                ids.append(it["id"])
        return sorted(set(ids))

    def wiki_group(title):
        out = [title]
        for en, hi in H.SAME_TOPIC.items():
            if title == en:
                out.append(hi)
            if title == hi:
                out.append(en)
        return [wiki[t] for t in out if t in wiki]

    cases = []

    def case(slice_, q, rel, extra=None):
        if rel is not None and not rel:
            print("no relevant items:", slice_, q, file=sys.stderr)
            return
        c = {"id": f"{slice_}-{sum(1 for x in cases if x['slice'] == slice_) + 1:03d}", "slice": slice_, "query": q,
             "lang": lang_of(q), "relevant": {i: 2 for i in rel} if rel else {}, "none": rel is None}
        if extra:
            c.update(extra)
        cases.append(c)

    for title, (p, x) in H.WIKI_EN.items():
        if title not in wiki:
            continue
        rel = wiki_group(title)
        case("paraphrase", p, rel, {"shared": shared_words(p, [by_id[i] for i in rel])})
        if x:
            case("cross_language", x, rel)
    for title, (p, x) in H.WIKI_HI.items():
        if title not in wiki:
            continue
        rel = wiki_group(title)
        case("paraphrase", p, rel, {"shared": shared_words(p, [by_id[i] for i in rel])})
        if x:
            case("cross_language", x, rel)
    for q, spec in H.PERSONAL_PARAPHRASE:
        rel = resolve(spec)
        case("paraphrase", q, rel, {"shared": shared_words(q, [by_id[i] for i in rel])})
    for q, spec in H.PERSONAL_CROSS:
        case("cross_language", q, resolve(spec))
    for q, spec in H.LONG:
        case("long_natural", q, resolve(spec))
    for q, spec in H.AMBIGUOUS:
        rel = resolve(spec)
        case("ambiguous", q, rel, {"readings": spec})
    for q in H.NONE:
        case("no_answer", q, None)

    for q, spec in H.HINGLISH:
        case("hinglish", q, resolve(spec), {"lang": "hi-latn"})
    for q, spec in H.HINGLISH_HELDOUT:
        case("hinglish_heldout", q, resolve(spec), {"lang": "hi-latn"})

    exact_from = [it for it in items if it["kind"] != "photo"]
    R.shuffle(exact_from)
    texts = {it["id"]: " ".join(words_of(clean(it))) for it in items if it["kind"] != "photo"}
    made = 0
    for it in exact_from:
        if made >= 80:
            break
        ws = words_of(clean(it))
        pairs = [(a, b) for a, b in zip(ws, ws[1:]) if len(a) >= 4 and len(b) >= 4 and a not in STOP and b not in STOP
                 and not a.isdigit() and not b.isdigit()]
        R.shuffle(pairs)
        for a, b in pairs:
            phrase = f"{a} {b}"
            hits = [i for i, t in texts.items() if f" {phrase} " in f" {t} "]
            if hits == [it["id"]]:
                case("exact", phrase, hits)
                made += 1
                break

    TYPOS = [("electrcity bill", {"kinds": ["electricity_bill"]}), ("insurence polcy", {"kinds": ["insurance_policy"]}),
             ("corvnata payslip", {"kinds": ["salary_slip"]}), ("salary slipp", {"kinds": ["salary_slip"]}),
             ("boarding pas", {"kinds": ["boarding_pass"]}), ("aadhar card", {"kinds": ["national_id"]}),
             ("pharmcy bill", {"kinds": ["pharmacy_bill"]}), ("restraunt bill", {"kinds": ["restaurant_bill"]}),
             ("prescripton", {"kinds": ["prescription"]}), ("warrenty card", {"kinds": ["warranty_card"]}),
             ("cholestrol report", {"kinds": ["medical_report"], "wiki": ["Cholesterol"]}),
             ("photosynthesys", {"wiki": ["Photosynthesis", "प्रकाश संश्लेषण"]}), ("mitochondira", {"wiki": ["Mitochondrion"]}),
             ("tuberclosis", {"wiki": ["Tuberculosis"]}), ("dengu fever", {"wiki": ["Dengue fever", "डेंगू"]}),
             ("himalyas", {"wiki": ["Himalayas", "हिमालय"]}), ("bitcon", {"wiki": ["Bitcoin"]}), ("blockchian", {"wiki": ["Blockchain"]}),
             ("machne learning", {"wiki": ["Machine learning"]}), ("refrigrator", {"wiki": ["Refrigerator"]}),
             ("bycicle", {"wiki": ["Bicycle"]}), ("compund interest", {"wiki": ["Compound interest"]}),
             ("inflaton", {"wiki": ["Inflation"]}), ("vaccinaton", {"wiki": ["Vaccination"]}), ("hypertention", {"wiki": ["Hypertension"]}),
             ("maleria", {"wiki": ["Malaria"]}), ("olympcs", {"wiki": ["Olympic Games"]}), ("chandrayan 3", {"wiki": ["Chandrayaan-3"]}),
             ("constituton of india", {"wiki": ["Constitution of India", "भारत का संविधान"]}), ("panchayti raj", {"wiki": ["Panchayati raj in India"]}),
             ("shivalk power", {"kinds": ["electricity_bill"]}), ("skyhopp airlines", {"kinds": ["flight_ticket", "boarding_pass"]})]
    for q, spec in TYPOS:
        case("typos", q, resolve(spec))

    ENTITIES = ["Ramesh Kirana Store", "Shivalik Power", "Nimbus Telecom", "SkyHop Airlines", "Galaxy Cinemas", "Corvanta",
                "Kavach General Insurance", "Saffron Tandoor", "Udupi Sagar", "CarePlus Pharmacy", "Apna Medical Hall",
                "FreshBasket", "TechWorld", "Coolbreeze", "RoadStar Travels", "RideNow", "ShopKart", "Bazaarly",
                "Bharat Sahakari Bank", "St. Xavier's", "Govind Ballabh College", "Dr Rao", "Lotus Residency", "SkyLink Fibre",
                "Daily Herald", "Sharma Electricals", "Shree Ganesh Dairy", "Metro Auto Stand", "Sunrise Tiffin Service",
                "Om Sai Hardware", "Suraksha Health Insurance", "Jeevan Rekha", "Narmada Vidyut Vitaran Nigam", "नर्मदा विद्युत",
                "Cafe Mocha Hut", "Fresh Fruits Corner", "City Laundry", "Anil Vegetables", "Bharat Petroleum", "Shah Jahan"]
    for e in ENTITIES:
        rel = [it["id"] for it in items if it["kind"] != "photo" and e.lower() in clean(it).lower()]
        if e == "Narmada Vidyut Vitaran Nigam":
            rel = by_kind["electricity_bill"][6:]
        case("entity", e, rel)

    seen = set()
    for it in personal.ITEMS:
        idn = it["facts"].get("ID_NUMBER")
        if not idn or idn in seen or len(seen) >= 22:
            continue
        seen.add(idn)
        rel = [x["id"] for x in items if x["kind"] != "photo" and idn in clean(x)]
        case("number_fact", idn, rel, {"fact": {"kind": "ID_NUMBER", "value": idn}})

    def amount_items(lo, hi, kinds=None):
        return sorted(x["id"] for x in personal.ITEMS if "AMOUNT" in x["facts"] and lo <= x["facts"]["AMOUNT"] <= hi
                      and (kinds is None or x["kind"] in kinds))

    AMOUNTS = [("1,18,702.10", amount_items(118702, 118703)), ("118702", amount_items(118702, 118703)),
               ("1.18 lakh balance", amount_items(118702, 118703)), ("68500", amount_items(68500, 68500)),
               ("68,500 fee", amount_items(68500, 68500)), ("₹25,000", amount_items(25000, 25000, ["upi_payment"])),
               ("25000 payment", amount_items(25000, 25000, ["upi_payment"])),
               ("payment around ₹25,000", amount_items(23750, 26250, ["upi_payment"])),
               ("payments under 100", amount_items(0, 99.99, ["upi_payment"])),
               ("32 lakh loan", ["p-loan_sanction-01"]), ("9.6 lakh salary", by_kind["job_offer"] + by_kind["offer_letter"]),
               ("114900", amount_items(114900, 114900)), ("1,14,900", amount_items(114900, 114900)),
               ("18,000 rent", amount_items(18000, 18000) + by_kind["rental_agreement"]), ("2355.15", amount_items(2355, 2356)),
               ("84,213 net pay", amount_items(84213, 84213)), ("903 cylinder", amount_items(903, 903)),
               ("12,000 school fee", amount_items(12000, 12000)), ("14,230 premium", amount_items(14230, 14230)),
               ("1179.82", amount_items(1179, 1180)), ("560 movie", amount_items(560, 560, ["movie_ticket"])),
               ("1150 bus fare", amount_items(1150, 1150)), ("bill above 2000", amount_items(2000.01, 10 ** 9, ["electricity_bill"])),
               ("payment between 1000 and 2000", amount_items(1000, 2000, ["upi_payment"])),
               ("ढाई हज़ार का भुगतान", amount_items(2350, 2650, ["upi_payment"])),
               ("23,480 emi", by_kind["bank_alert"] + by_kind["bank_statement"]),
               ("27971", by_kind["loan_sanction"]), ("rs 612", amount_items(612, 612))]
    for q, rel in AMOUNTS:
        case("number_fact", q, sorted(set(rel)))

    def by_months(kinds, months, types=None):
        return sorted(x["id"] for x in personal.ITEMS if (kinds is None or x["kind"] in kinds or any(x["kind"].startswith(k) for k in kinds if k.endswith("_")))
                      and (types is None or x["type"] in types) and x["date"].get("monthsAgo") in months)

    BILLS = ["electricity_bill", "mobile_bill", "broadband_bill", "water_bill", "restaurant_bill", "pharmacy_bill", "grocery_receipt"]
    TEMPORAL = [("electricity bill last month", ["electricity_bill"], [1]), ("last month's electricity bill", ["electricity_bill"], [1]),
                ("electricity bill 2 months ago", ["electricity_bill"], [2]), ("electricity bill from 3 months ago", ["electricity_bill"], [3]),
                ("pichhle mahine ka bijli bill", ["electricity_bill"], [1]), ("पिछले महीने का बिजली बिल", ["electricity_bill"], [1]),
                ("electricity bill ⟦M:m=2⟧", ["electricity_bill"], [2]), ("⟦M:m=4⟧ electricity bill", ["electricity_bill"], [4]),
                ("salary slip last month", ["salary_slip"], [1]), ("payslip ⟦M:m=2⟧", ["salary_slip"], [2]),
                ("पिछले महीने की सैलरी स्लिप", ["salary_slip"], [1]), ("mobile bill last month", ["mobile_bill"], [1]),
                ("mobile bill 3 months ago", ["mobile_bill"], [3]), ("train ticket last month", ["train_ticket"], [1]),
                ("train tickets this month", ["train_ticket"], [0]), ("rail ticket 2 months ago", ["train_ticket"], [2]),
                ("payments last month", ["upi_payment"], [1]), ("payments this month", ["upi_payment"], [0]),
                ("upi payments 2 months ago", ["upi_payment"], [2]), ("pichhle mahine ki payments", ["upi_payment"], [1]),
                ("restaurant bill last month", ["restaurant_bill"], [1]), ("grocery bill this month", ["grocery_receipt"], [0]),
                ("flight ticket last month", ["flight_ticket"], [1]), ("cab rides last month", ["cab_receipt"], [1]),
                ("chats from last month", ["chat_"], [1]), ("orders this month", ["order_confirmation"], [0]),
                ("school fee receipt ⟦M:m=1⟧", ["school_fee_receipt"], [1]), ("news from last month", ["news_"], [1]),
                ("otp messages this month", ["otp_sms"], [0]), ("is mahine ke bill", BILLS, [0]),
                ("bills from last month", BILLS, [1]), ("bills 2 months ago", BILLS, [2]),
                ("payments ⟦M:m=3⟧", ["upi_payment"], [3]), ("पिछले महीने के भुगतान", ["upi_payment"], [1]),
                ("do mahine pehle ka train ticket", ["train_ticket"], [2]), ("teen mahine pehle ki payments", ["upi_payment"], [3]),
                ("pharmacy bill last month", ["pharmacy_bill"], [1]), ("movie ticket this month", ["movie_ticket"], [0]),
                ("bus ticket last month", ["bus_ticket"], [1]), ("fuel receipt 2 months ago", ["fuel_receipt"], [2])]
    for q, kinds, months in TEMPORAL:
        case("temporal", q, by_months(kinds, months), {"period": {"monthsAgo": months}})

    for target, en, hi, latn, facets in Q:
        rel = [f"photo-{target}"]
        if rel[0] not in by_id:
            continue
        for q, lg in ((en, "en"), (hi, "hi"), (latn, "hi-latn")):
            case("visual", q, rel, {"lang": lg, "facets": facets})

    rnd = random.Random(5)
    stored = []
    for it in items:
        row = dict(it)
        row["stored"] = [noised(p, rnd) for p in it["pages"]] if it["noisy"] else it["pages"]
        stored.append(row)
    (OUT / "bench_corpus.jsonl").write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in stored) + "\n", encoding="utf-8")
    (OUT / "bench_cases.jsonl").write_text("\n".join(json.dumps(c, ensure_ascii=False) for c in cases) + "\n", encoding="utf-8")

    from collections import Counter
    print("items", len(items), Counter(it["type"] for it in items))
    print("cases", len(cases), dict(Counter(c["slice"] for c in cases)))
    print("langs", dict(Counter((c["slice"], c["lang"]) for c in cases if c["slice"] in ("paraphrase", "cross_language", "visual", "temporal"))))
    para = [c for c in cases if c["slice"] == "paraphrase"]
    print("paraphrase shared words:", Counter(len(c["shared"]) for c in para))


if __name__ == "__main__":
    main()
