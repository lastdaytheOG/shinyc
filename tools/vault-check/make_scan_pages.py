"""Makes pictures of scanned pages whose words are known, to measure how a scanned PDF is read.

    python make_scan_pages.py <folder of sample PDFs> <output folder> [--seed N]

Writes N.jpg (what the app would render a page into, made to look scanned) and N.txt (what the
page says, in the form ocr_score.py reads). The words never come from an OCR engine:

  page_*   pages of real PDFs that have a text layer, which is the truth. A page mostly covered by a
           picture is skipped: its text layer may itself be someone's OCR.
  bill_*   bills, statements and payment slips with invented names and numbers, built so that
           numbers stand inside other numbers (450 and 1450, 12 and 120), the way they do on real ones.
  hindi_*  pages drawn from plain text files, in Hindi and in Hindi with English: text layers of Hindi
           PDFs are mostly old-font letters ("राजनीǓतक"), which are no truth for anything.
  card_*   a few invented lines, the way an ID card is: too few words for the first two reads to be
           trusted, so these are the pages that go on to the full set of reads.
  col_*    two columns of short lines of plain text.
"""
import argparse
import io
import os
import random
import re
import unicodedata

import fitz
import numpy as np
from PIL import Image, ImageFilter

DPI = 150
TEXT_LAYER_PDFS = {'2': 8, '4': 8, '5': 8, '9': 6, '13': 6, '1': 10, '7': 8}
FONT_FILE = r'C:\Windows\Fonts\Nirmala.ttc'
A4 = fitz.Rect(0, 0, 595, 842)


def scanned(picture, rng, heavy):
    """A clean render made to look like a page that went through a scanner or a phone camera."""
    img = picture.convert('L')
    angle = rng.uniform(-1.4, 1.4) if heavy else rng.uniform(-0.6, 0.6)
    img = img.rotate(angle, resample=Image.BICUBIC, fillcolor=245)
    img = img.filter(ImageFilter.GaussianBlur(1.0 if heavy else 0.5))
    a = np.asarray(img, dtype=np.float32)
    if heavy:
        a = 40 + a * 0.72
    noise = np.random.default_rng(rng.randrange(1 << 30)).normal(0, 11 if heavy else 4, a.shape)
    a = np.clip(a + noise, 0, 255).astype(np.uint8)
    out = io.BytesIO()
    Image.fromarray(a).convert('RGB').save(out, 'JPEG', quality=45 if heavy else 78)
    return out.getvalue()


def render(page):
    pix = page.get_pixmap(matrix=fitz.Matrix(DPI / 72, DPI / 72), colorspace=fitz.csRGB, alpha=False)
    return Image.frombytes('RGB', (pix.width, pix.height), pix.samples)


def picture_share(page):
    area = abs(page.rect)
    covered = 0.0
    for info in page.get_image_info():
        covered += abs(fitz.Rect(info['bbox']) & page.rect)
    return covered / area if area else 0


def text_layer_is_sound(page):
    """False for a layer in an old Hindi font's letters, or with vowel signs out of their place."""
    text = page.get_text('text')
    wrong_letters = len(re.findall('[Ā-ͯ]', text))
    misplaced = len(re.findall(r'(?:^|\s)[ा-ौ]', text))
    return wrong_letters <= len(text) * 0.002 and misplaced <= 2


def text_lines(page):
    lines = [re.sub(r'\s+', ' ', l).strip() for l in page.get_text('text').splitlines()]
    return [unicodedata.normalize('NFC', l) for l in lines if l]


def pages_of_pdfs(folder, out, rng):
    made = 0
    for name, wanted in TEXT_LAYER_PDFS.items():
        doc = fitz.open(os.path.join(folder, name + '.pdf'))
        usable = []
        for i in range(doc.page_count):
            page = doc[i]
            words = len(page.get_text('text').split())
            if 40 <= words <= 700 and picture_share(page) < 0.4 and text_layer_is_sound(page):
                usable.append(i)
        rng.shuffle(usable)
        for n, i in enumerate(sorted(usable[:wanted])):
            page = doc[i]
            base = f'page_{name}_{i + 1:03d}'
            with open(os.path.join(out, base + '.jpg'), 'wb') as f:
                f.write(scanned(render(page), rng, heavy=n % 3 == 2))
            with io.open(os.path.join(out, base + '.txt'), 'w', encoding='utf-8') as f:
                f.write('\n'.join(text_lines(page)) + '\n')
            made += 1
        print(f'{name}.pdf: {doc.page_count} pages, {len(usable)} usable, took {min(wanted, len(usable))}')
    return made


# ── Invented documents ────────────────────────────────────────────────────────────────────────

SHOPS = ['Sunrise Traders', 'Kaveri Stores', 'Lotus Medical Hall', 'Himalaya Hardware', 'Blue Tiger Books', 'Asha Electricals']
PEOPLE = ['Meera Joshi', 'Arun Pillai', 'Farah Khan', 'Dev Malhotra', 'Lata Bose', 'Ravi Menon']
ITEMS = ['Copper wire', 'LED bulb', 'Notebook', 'Ceiling fan', 'Tablet strip', 'Water filter', 'Cement bag', 'Ink bottle',
         'Door lock', 'Rice packet', 'Extension board', 'Paint tin']
HI_LABELS = {
    'Invoice': 'बिल', 'Date': 'दिनांक', 'Total': 'कुल राशि', 'Paid': 'भुगतान', 'Balance': 'शेष राशि', 'Customer': 'ग्राहक',
    'Item': 'सामान', 'Qty': 'मात्रा', 'Rate': 'दर', 'Amount': 'राशि', 'Account': 'खाता', 'Statement': 'विवरण',
}


def label(word, hindi):
    return HI_LABELS[word] if hindi else word


def nested(rng, used):
    """An amount, often one that stands inside an amount already on the page, or the other way round."""
    if used and rng.random() < 0.55:
        base = rng.choice(used)
        return rng.choice([base[1:], base[:-1], str(rng.randint(1, 9)) + base, base + '0']) or base
    return str(rng.choice([rng.randint(5, 95), rng.randint(100, 950), rng.randint(1000, 9900)]))


def digits(rng, n):
    return ''.join(rng.choice('0123456789') for _ in range(n))


def a_date(rng):
    return f'{rng.randint(1, 28):02d}/{rng.randint(1, 12):02d}/20{rng.randint(23, 26)}'


def table(head, rows):
    cells = ''.join(f'<th>{c}</th>' for c in head)
    body = ''.join('<tr>' + ''.join(f'<td>{c}</td>' for c in row) + '</tr>' for row in rows)
    return f'<table><tr>{cells}</tr>{body}</table>'


def bill(rng, hindi):
    used, rows, truth = [], [], []
    shop, person = rng.choice(SHOPS), rng.choice(PEOPLE)
    number, date = digits(rng, 5), a_date(rng)
    head = [label(w, hindi) for w in ('Item', 'Qty', 'Rate', 'Amount')]
    for item in rng.sample(ITEMS, rng.randint(5, 9)):
        qty, rate, amount = str(rng.randint(1, 12)), nested(rng, used), nested(rng, used)
        used += [rate, amount]
        rows.append([item, qty, rate, amount])
    total, paid, left = nested(rng, used), nested(rng, used), nested(rng, used)
    html = (f'<h1>{shop}</h1><p>GSTIN 29ABCDE{digits(rng, 4)}F1Z5 Phone {digits(rng, 10)}</p>'
            f'<p>{label("Invoice", hindi)} No {number}</p><p>{label("Date", hindi)} {date}</p>'
            f'<p>{label("Customer", hindi)} {person}</p>' + table(head, rows) +
            f'<p>{label("Total", hindi)} Rs {total}</p><p>{label("Paid", hindi)} Rs {paid}</p>'
            f'<p>{label("Balance", hindi)} Rs {left}</p><p>Thank you</p>')
    truth = [shop] + re.findall(r'GSTIN [^<]+', html) + [
        f'{label("Invoice", hindi)} No {number}', f'{label("Date", hindi)} {date}', f'{label("Customer", hindi)} {person}',
        ' '.join(head)] + [' '.join(r) for r in rows] + [
        f'{label("Total", hindi)} Rs {total}', f'{label("Paid", hindi)} Rs {paid}', f'{label("Balance", hindi)} Rs {left}', 'Thank you']
    return html, truth


def statement(rng, hindi):
    used, rows = [], []
    account = digits(rng, 11)
    head = [label('Date', hindi), 'Details', 'Debit', 'Credit', label('Balance', hindi)]
    for _ in range(rng.randint(10, 16)):
        debit, credit, bal = nested(rng, used), nested(rng, used), nested(rng, used)
        used += [debit, credit, bal]
        rows.append([a_date(rng), f'UPI {digits(rng, 12)}', debit, credit, bal])
    who = rng.choice(PEOPLE)
    html = (f'<h1>{label("Statement", hindi)}</h1><p>{label("Account", hindi)} {account}</p>'
            f'<p>IFSC ABCD0{digits(rng, 6)}</p><p>{who}</p>' + table(head, rows))
    truth = [label('Statement', hindi), f'{label("Account", hindi)} {account}'] + re.findall(r'IFSC [^<]+', html) + [
        who, ' '.join(head)] + [' '.join(r) for r in rows]
    return html, truth


def slip(rng, hindi):
    who, shop = rng.choice(PEOPLE), rng.choice(SHOPS)
    amount, ref, date, phone = str(rng.randint(10, 95000)), digits(rng, 12), a_date(rng), digits(rng, 10)
    lines = [f'{label("Paid", hindi)} Rs {amount}', f'To {shop}', f'From {who}', f'UPI ref {ref}', f'{label("Date", hindi)} {date}',
             f'Phone {phone}', f'Fee Rs {amount[:-1] or amount}', f'{label("Total", hindi)} Rs {amount}']
    return ''.join(f'<p>{l}</p>' for l in lines), lines


CSS = '''
@font-face { font-family: page; src: url(Nirmala.ttc); }
* { font-family: page, sans-serif; font-size: 11pt; color: #111; }
h1 { font-size: 17pt; margin: 0 0 8pt 0; }
p { margin: 3pt 0; }
table { border-collapse: collapse; margin: 9pt 0; }
td, th { padding: 3pt 9pt 3pt 0; text-align: left; }
'''


def bills(out, rng, count):
    archive = fitz.Archive(os.path.dirname(FONT_FILE))
    makers = [bill, statement, slip]
    for n in range(count):
        hindi = n % 2 == 1
        html, truth = makers[n % 3](rng, hindi)
        doc = fitz.open()
        page = doc.new_page(width=A4.width, height=A4.height)
        spare, _ = page.insert_htmlbox(A4 + (45, 50, -45, -50), html, css=CSS, archive=archive)
        if spare < 0:
            raise SystemExit(f'bill {n} did not fit')
        base = f'bill_{n + 1:03d}'
        with open(os.path.join(out, base + '.jpg'), 'wb') as f:
            f.write(scanned(render(page), rng, heavy=n % 4 == 3))
        with io.open(os.path.join(out, base + '.txt'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(unicodedata.normalize('NFC', l) for l in truth) + '\n')
    return count


def hindi_pages(corpus, out, rng, count):
    archive = fitz.Archive(os.path.dirname(FONT_FILE))
    sources = {}
    for name in ('Hindi.txt', 'english_hindi.txt'):
        with io.open(os.path.join(corpus, name), encoding='utf-8') as f:
            sources[name] = [re.sub(r'\s+', ' ', l).strip() for l in f if len(l.strip()) >= 25 and not re.search('[<>&]', l)]
    made = 0
    for n in range(count):
        lines = sources['Hindi.txt' if n % 3 else 'english_hindi.txt']
        at = rng.randrange(max(1, len(lines) - 30))
        chosen, words = [], 0
        for line in lines[at:]:
            if words + len(line.split()) > 230:
                break
            chosen.append(line)
            words += len(line.split())
        html = ''.join(f'<p>{l}</p>' for l in chosen)
        doc = fitz.open()
        page = doc.new_page(width=A4.width, height=A4.height)
        spare, _ = page.insert_htmlbox(A4 + (50, 50, -50, -50), html, css=CSS, archive=archive)
        if spare < 0 or not chosen:
            continue
        base = f'hindi_{n + 1:03d}'
        with open(os.path.join(out, base + '.jpg'), 'wb') as f:
            f.write(scanned(render(page), rng, heavy=n % 4 == 3))
        with io.open(os.path.join(out, base + '.txt'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(unicodedata.normalize('NFC', l) for l in chosen) + '\n')
        made += 1
    return made


def cards(out, rng, count):
    """Pages with few words, the way an ID card or a ticket is: too few for the first two reads to be trusted."""
    archive = fitz.Archive(os.path.dirname(FONT_FILE))
    for n in range(count):
        hindi = n % 2 == 1
        who = rng.choice(PEOPLE)
        number = ' '.join(digits(rng, 4) for _ in range(3))
        lines = [who, f'{label("Date", hindi)} {a_date(rng)}', f'ID {number}', f'Phone {digits(rng, 10)}', f'Rs {rng.randint(10, 9900)}']
        doc = fitz.open()
        page = doc.new_page(width=A4.width, height=A4.height)
        spare, _ = page.insert_htmlbox(fitz.Rect(90, 120, 420, 320), ''.join(f'<p>{l}</p>' for l in lines), css=CSS, archive=archive)
        if spare < 0:
            continue
        base = f'card_{n + 1:03d}'
        with open(os.path.join(out, base + '.jpg'), 'wb') as f:
            f.write(scanned(render(page), rng, heavy=n % 3 == 2))
        with io.open(os.path.join(out, base + '.txt'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(unicodedata.normalize('NFC', l) for l in lines) + '\n')
    return count


def columns(corpus, out, rng, count):
    """Two columns of short lines, each line as the page has it, so that their order can be scored."""
    archive = fitz.Archive(os.path.dirname(FONT_FILE))
    with io.open(os.path.join(corpus, 'English.txt'), encoding='utf-8') as f:
        text = re.sub(r'\s+', ' ', re.sub('[<>&]', ' ', f.read()))
    all_words = text.split(' ')
    made = 0
    for n in range(count):
        at = rng.randrange(max(1, len(all_words) - 400))
        words = all_words[at:at + 320]
        lines = [' '.join(words[i:i + 6]) for i in range(0, len(words), 6)]
        half = len(lines) // 2
        cell = lambda part: ''.join(f'<p>{l}</p>' for l in part)
        doc = fitz.open()
        page = doc.new_page(width=A4.width, height=A4.height)
        css = CSS + 'p { font-size: 9pt; margin: 1pt 0; }'
        left, _ = page.insert_htmlbox(fitz.Rect(36, 40, 290, 800), cell(lines[:half]), css=css, archive=archive)
        right, _ = page.insert_htmlbox(fitz.Rect(305, 40, 559, 800), cell(lines[half:]), css=css, archive=archive)
        if left < 0 or right < 0:
            continue
        base = f'col_{n + 1:03d}'
        with open(os.path.join(out, base + '.jpg'), 'wb') as f:
            f.write(scanned(render(page), rng, heavy=n % 3 == 2))
        with io.open(os.path.join(out, base + '.txt'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(unicodedata.normalize('NFC', l) for l in lines) + '\n')
        made += 1
    return made


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('pdfs')
    parser.add_argument('out')
    parser.add_argument('--seed', type=int, default=20261102)
    parser.add_argument('--bills', type=int, default=36)
    parser.add_argument('--hindi', type=int, default=24)
    parser.add_argument('--corpus', help='folder with Hindi.txt and english_hindi.txt')
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    rng = random.Random(args.seed)
    print('pages of PDFs:', pages_of_pdfs(args.pdfs, args.out, rng))
    print('bills:', bills(args.out, rng, args.bills))
    print('cards:', cards(args.out, rng, 18))
    if args.corpus:
        print('hindi pages:', hindi_pages(args.corpus, args.out, rng, args.hindi))
        print('two-column pages:', columns(args.corpus, args.out, rng, 10))


if __name__ == '__main__':
    main()
