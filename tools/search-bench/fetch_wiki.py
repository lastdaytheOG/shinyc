import json
import pathlib
import sys
import time

import requests

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "corpus" / "wiki.json"
UA = {"User-Agent": "SearchBenchCorpus/1.0 (offline search benchmark; public articles only)"}

EN = [
    "Graphics processing unit", "CPU cache", "Transmission Control Protocol", "Photosynthesis", "Mitochondrion",
    "Monsoon of South Asia", "Goods and Services Tax (India)", "Income tax in India", "Unified Payments Interface",
    "Reserve Bank of India", "Inflation", "Compound interest", "Mutual fund", "Public Provident Fund (India)",
    "Employees' Provident Fund Organisation", "Life insurance", "Health insurance", "Vaccination", "Diabetes",
    "Hypertension", "Dengue fever", "Malaria", "Tuberculosis", "Cardiopulmonary resuscitation", "Yoga",
    "Constitution of India", "Fundamental rights in India", "Lok Sabha", "Panchayati raj in India",
    "Green Revolution in India", "Indus Valley Civilisation", "Mughal Empire", "Ashoka", "Quit India Movement",
    "Indian Space Research Organisation", "Chandrayaan-3", "Black hole", "Solar eclipse", "Climate change",
    "Ozone depletion", "El Niño–Southern Oscillation", "Earthquake", "Tsunami", "Water cycle", "Plate tectonics",
    "DNA", "CRISPR", "Evolution", "Antimicrobial resistance", "Vitamin D", "Cholesterol", "Sleep", "Meditation",
    "Cricket", "Indian Premier League", "Chess", "Olympic Games", "Taj Mahal", "Himalayas", "Ganges",
    "Thar Desert", "Western Ghats", "Bengal tiger", "Western honey bee", "Coffee", "Tea", "Rice", "Wheat",
    "Solar power", "Electric vehicle", "Lithium-ion battery", "Blockchain", "Machine learning",
    "Transformer (deep learning architecture)", "Cloud computing", "Linux", "Python (programming language)", "SQL",
    "Compiler", "Virtual memory", "Public-key cryptography", "Phishing", "Multi-factor authentication",
    "Credit score", "Mortgage loan", "Stock market", "BSE SENSEX", "Bitcoin", "Gross domestic product",
    "Electric battery", "Refrigerator", "Air conditioning", "Car", "Bicycle", "Railway signalling",
]

EXTRA_EN = [
    "Random-access memory", "Hard disk drive", "Solid-state drive", "Wi-Fi", "Bluetooth", "5G", "Smartphone", "Android (operating system)",
    "Email", "World Wide Web", "Search engine", "Database", "Spreadsheet", "Operating system", "Computer virus", "Firewall (computing)",
    "Password", "Encryption", "Data compression", "JPEG", "Portable Document Format", "Unicode", "Algorithm", "Sorting algorithm",
    "Binary search algorithm", "Hash table", "Recursion", "Object-oriented programming", "Java (programming language)", "JavaScript",
    "Kotlin (programming language)", "Git", "Open-source software", "Artificial intelligence", "Neural network (machine learning)",
    "Computer vision", "Speech recognition", "Robotics", "Internet of things", "Semiconductor", "Transistor", "Integrated circuit",
    "Moore's law", "Quantum mechanics", "Gravity", "Electricity", "Magnetism", "Light", "Sound", "Heat", "Atom", "Periodic table",
    "Water", "Oxygen", "Carbon dioxide", "Human brain", "Heart", "Liver", "Kidney", "Blood", "Immune system", "Influenza", "Asthma",
    "Obesity", "Nutrition", "Protein", "Exercise", "Ayurveda", "Agriculture in India", "Sugarcane", "Cotton", "Mango", "Banana",
    "Spice", "Indian cuisine", "Biryani", "Dosa", "Bollywood", "Indian classical music", "Diwali", "Holi", "Mahatma Gandhi",
    "Jawaharlal Nehru", "B. R. Ambedkar", "Indian Railways", "Mumbai", "Delhi", "Bengaluru", "Kolkata", "Chennai", "Kerala", "Rajasthan",
    "Goa", "Punjab, India", "Banking in India", "Demonetisation in India", "Aadhaar", "Digital India", "Passport", "Visa (document)",
]

HI = [
    "मानसून", "प्रकाश संश्लेषण", "भारत का संविधान", "गंगा नदी", "ताज महल", "हिमालय", "आयुर्वेद", "क्रिकेट", "योग",
    "डेंगू", "मधुमेह", "भारतीय रिज़र्व बैंक", "वस्तु एवं सेवा कर (भारत)", "आधार (पहचान पत्र)", "चन्द्रयान-३",
    "भारतीय अन्तरिक्ष अनुसंधान संगठन", "जलवायु परिवर्तन", "भूकम्प", "महात्मा गांधी", "भगत सिंह", "प्रेमचंद",
    "दीपावली", "होली", "रामायण", "शतरंज", "संगणक", "अंतरजाल", "सौर ऊर्जा", "बाघ", "चाय",
]


EXTRA_HI = ["कंप्यूटर वायरस", "मोबाइल फ़ोन", "रेलगाड़ी", "आम", "गन्ना", "कपास", "मसाला", "बॉलीवुड", "जवाहरलाल नेहरू",
            "भीमराव आम्बेडकर", "मुम्बई", "दिल्ली", "केरल", "राजस्थान", "गोवा", "पंजाब", "भारतीय रेल", "बैंक", "रक्त",
            "हृदय", "मलेरिया", "क्षयरोग", "पोषण", "प्रोटीन", "व्यायाम", "बिजली", "चुम्बकत्व", "प्रकाश", "ध्वनि", "परमाणु"]


def fetch(lang, title):
    for attempt in range(6):
        r = get(lang, title)
        if r.status_code != 429:
            break
        time.sleep(10 * (attempt + 1))
    r.raise_for_status()
    page = r.json()["query"]["pages"][0]
    if page.get("missing"):
        return None
    return {"lang": lang, "title": page["title"], "revid": page["revisions"][0]["revid"],
            "text": page.get("extract", "")}


def get(lang, title):
    return requests.get(
        f"https://{lang}.wikipedia.org/w/api.php",
        params={"action": "query", "prop": "extracts|revisions", "explaintext": 1, "rvprop": "ids",
                "titles": title, "format": "json", "redirects": 1, "formatversion": 2},
        headers=UA, timeout=30,
    )


def main():
    have = {}
    if OUT.exists():
        for a in json.loads(OUT.read_text(encoding="utf-8")):
            have[(a["lang"], a["asked"])] = a
    out = []
    for lang, titles in (("en", EN), ("hi", HI), ("en", EXTRA_EN), ("hi", EXTRA_HI)):
        for t in titles:
            a = have.get((lang, t))
            if a is None:
                a = fetch(lang, t)
                time.sleep(2.0)
                if a is None or len(a["text"]) < 1500:
                    print("skip", lang, t, file=sys.stderr)
                    continue
                a["asked"] = t
            out.append(a)
            OUT.parent.mkdir(exist_ok=True)
            OUT.write_text(json.dumps(out + [v for k, v in have.items() if v not in out], ensure_ascii=False, indent=0), encoding="utf-8")
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=0), encoding="utf-8")
    print(len(out), "articles", sum(len(a["text"]) for a in out), "chars")


if __name__ == "__main__":
    main()
