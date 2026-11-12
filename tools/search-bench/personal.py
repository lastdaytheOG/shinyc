import random

R = random.Random(20261112)

FIRST = ["Aarav", "Vivaan", "Ishaan", "Kabir", "Rohan", "Arjun", "Meera", "Ananya", "Diya", "Kavya", "Nisha",
         "Pooja", "Sanjay", "Vikram", "Farhan", "Imran", "Gurpreet", "Harleen", "Joseph", "Maria", "Lakshmi",
         "Suresh", "Deepak", "Neha", "Ritu", "Tanvi", "Yash", "Zoya", "Alok", "Bhavna"]
LAST = ["Sharma", "Verma", "Iyer", "Nair", "Reddy", "Khan", "Singh", "Gill", "Das", "Mehta", "Patel", "Joshi",
        "Kulkarni", "Fernandes", "Bose", "Chauhan", "Rao", "Pillai", "Saxena", "Tiwari"]
CITY = ["Pune", "Indore", "Lucknow", "Kochi", "Jaipur", "Bhopal", "Nagpur", "Surat", "Mysuru", "Dehradun",
        "Patna", "Guwahati", "Coimbatore", "Vadodara", "Ranchi"]
STATIONS = ["Pune Jn", "Mumbai CSMT", "New Delhi", "Lucknow NR", "Bhopal Jn", "Nagpur", "Jaipur Jn", "Howrah",
            "Chennai Central", "Bengaluru City", "Indore Jn", "Patna Jn", "Kota Jn", "Varanasi Jn"]


def name():
    return f"{R.choice(FIRST)} {R.choice(LAST)}"


def digits(n, first="123456789"):
    return R.choice(first) + "".join(R.choice("0123456789") for _ in range(n - 1))


def inr(x):
    whole, frac = f"{x:.2f}".split(".")
    if len(whole) > 3:
        head, tail = whole[:-3], whole[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        whole = ",".join(parts + [tail])
    return f"{whole}.{frac}"


def money(lo, hi, step=1):
    return round(R.randrange(int(lo), int(hi), step) + (R.choice([0, 0, 0.5, 0.25, 0.9]) if step == 1 else 0), 2)


def D(months=None, day=None, days=None, fmt="d MMM yyyy"):
    if days is not None:
        return f"⟦D:days={days};f={fmt}⟧"
    return f"⟦D:m={months};d={day};f={fmt}⟧"


def when(months=None, day=None, days=None):
    return {"daysAgo": days} if days is not None else {"monthsAgo": months, "day": day}


ITEMS = []


def add(kind, itype, file, lang, date, pages, facts=None, noisy=False, tags=None, group=None):
    iid = f"p-{kind}-{len([i for i in ITEMS if i['kind'] == kind]) + 1:02d}"
    ITEMS.append({"id": iid, "kind": kind, "type": itype, "name": file, "lang": lang, "date": date,
                  "pages": pages, "facts": facts or {}, "noisy": noisy, "group": group or kind})
    return iid


POWER = [("Shivalik Power Distribution Ltd", "SPDL"), ("Narmada Vidyut Vitaran Nigam", "NVVN")]
POWER_HI = "नर्मदा विद्युत वितरण निगम"


def electricity():
    out = {}
    for p, (company, short) in enumerate(POWER):
        consumer = digits(10)
        holder = name()
        for m in range(1, 9):
            units = R.randrange(120, 480)
            amount = round(units * 7.35 + 145, 2)
            day = 4 + p * 3
            if p == 1 and m % 2 == 0:
                text = (f"{POWER_HI}\nबिजली बिल\nउपभोक्ता क्रमांक {consumer}\nनाम {holder}\nबिल दिनांक {D(m, day, fmt='dd/MM/yyyy')}\n"
                        f"खपत {units} यूनिट\nकुल देय राशि ₹{inr(amount)}\nअंतिम तिथि {D(m - 1 if m > 1 else 0, day, fmt='dd/MM/yyyy') if m > 1 else D(days=1, fmt='dd/MM/yyyy')}\n"
                        f"समय पर भुगतान करें, विलंब शुल्क ₹50 लगेगा")
                lang = "hi"
            else:
                text = (f"{company}\nElectricity Bill\nConsumer No. {consumer}\nName: {holder}\nBill date: {D(m, day)}\n"
                        f"Units consumed: {units} kWh\nEnergy charges Rs {inr(units * 7.35)}\nFixed charges Rs 145.00\n"
                        f"Net amount payable: Rs {inr(amount)}\nPay before the due date to avoid a late payment surcharge.")
                lang = "en"
            iid = add("electricity_bill", "PDF", R.choice([f"{short}_bill_{digits(6)}.pdf", f"bill ({m}).pdf", f"download{m}.pdf"]),
                      lang, when(m, day), [text], {"AMOUNT": amount, "ID_NUMBER": consumer, "PARTY": company, "DOC_KIND": "electricity bill"})
            out[(p, m)] = iid
    return out


def phone_bills():
    for m in range(1, 5):
        amount = R.choice([399, 499, 599, 749]) + 0.0
        acct = digits(9)
        add("mobile_bill", "PDF", f"Nimbus_postpaid_{acct}.pdf", "en", when(m, 9),
            [f"Nimbus Telecom\nPostpaid bill for mobile 98{digits(8)}\nAccount {acct}\nBill period ending {D(m, 9)}\n"
             f"Plan: Unlimited 75 GB data, calls and 100 SMS a day\nTotal due Rs {inr(amount * 1.18)} including GST\n"
             f"Data used 61.4 GB, international roaming not active"],
            {"AMOUNT": round(amount * 1.18, 2), "ID_NUMBER": acct, "PARTY": "Nimbus Telecom", "DOC_KIND": "mobile bill"})
    for m in (1, 3):
        add("broadband_bill", "PDF", f"invoice_{digits(7)}.pdf", "en", when(m, 15),
            [f"SkyLink Fibre Broadband\nTax invoice {D(m, 15)}\n200 Mbps fibre plan, router rental included\n"
             f"Customer ID SLF{digits(7)}\nAmount Rs {inr(1179.82)}\nWi-Fi support: call 1800 200 3344"],
            {"AMOUNT": 1179.82, "PARTY": "SkyLink Fibre", "DOC_KIND": "broadband bill"})
    for m in (2, 5):
        add("water_bill", "PDF", f"jal_{digits(5)}.pdf", "hi", when(m, 20),
            [f"नगर निगम जल कार्य विभाग\nपानी का बिल\nकनेक्शन संख्या {digits(8)}\nबिल दिनांक {D(m, 20, fmt='dd/MM/yyyy')}\n"
             f"देय राशि ₹{inr(612.0)}\nपानी की बर्बादी रोकें"],
            {"AMOUNT": 612.0, "PARTY": "नगर निगम", "DOC_KIND": "water bill"})


SHOPS = [("Ramesh Kirana Store", "grocery"), ("FreshBasket Supermart", "grocery"), ("Apna Medical Hall", "pharmacy"),
         ("CarePlus Pharmacy", "pharmacy"), ("Saffron Tandoor Restaurant", "restaurant"), ("Udupi Sagar", "restaurant"),
         ("Bharat Petroleum Fuel Station, Ring Road", "fuel"), ("Cafe Mocha Hut", "restaurant")]


def receipts():
    lines = {
        "grocery": [("Toor dal 1kg", 165), ("Basmati rice 5kg", 640), ("Sunflower oil 1L", 155), ("Atta 10kg", 455),
                    ("Milk 1L", 66), ("Sugar 1kg", 48), ("Tea leaves 500g", 260), ("Detergent 2kg", 310)],
        "pharmacy": [("Paracetamol 650 strip", 32), ("Cough syrup 100ml", 118), ("Vitamin D3 sachet", 45),
                     ("Azithromycin 500 strip", 115), ("ORS packets x5", 105), ("Digital thermometer", 240)],
        "restaurant": [("Paneer butter masala", 320), ("Butter naan x4", 160), ("Dal makhani", 260),
                       ("Masala dosa x2", 180), ("Filter coffee x2", 80), ("Gulab jamun", 90), ("Veg biryani", 280)],
        "fuel": [("Petrol 22.6 L @ 104.21", 2355.15)],
    }
    out = []
    for shop, kind in SHOPS:
        for k in range(R.choice([6, 7])):
            m = R.randrange(0, 7)
            day = R.randrange(2, 27)
            picked = R.sample(lines[kind], min(len(lines[kind]), R.randrange(1, 5))) if kind != "fuel" else lines["fuel"]
            subtotal = sum(p for _, p in picked)
            gst = round(subtotal * (0.05 if kind == "restaurant" else 0.0), 2)
            total = round(subtotal + gst, 2)
            body = "\n".join(f"{a}  {inr(p)}" for a, p in picked)
            bill_no = digits(5)
            text = (f"{shop}\n{CITY[SHOPS.index((shop, kind)) % len(CITY)]}\nBill No {bill_no}  Date {D(m, day, fmt='dd-MM-yyyy')}\n{body}\n"
                    + (f"CGST 2.5% {inr(gst / 2)}  SGST 2.5% {inr(gst / 2)}\n" if gst else "")
                    + f"TOTAL Rs {inr(total)}\nThank you, visit again")
            kname = {"grocery": "grocery_receipt", "pharmacy": "pharmacy_bill", "restaurant": "restaurant_bill", "fuel": "fuel_receipt"}[kind]
            out.append(add(kname, "PHOTO_OF_PAPER", f"IMG_{digits(8)}_{digits(6)}.jpg", "en", when(m, day), [text],
                           {"AMOUNT": total, "PARTY": shop, "DOC_KIND": kname.replace("_", " ")}, noisy=True))
    return out


def tickets():
    for k in range(10):
        a, b = R.sample(STATIONS, 2)
        pnr = digits(10, "2468")
        fare = R.choice([455, 1235, 2210, 785, 1560]) + 0.0
        m, day = R.randrange(0, 6), R.randrange(1, 28)
        cls = R.choice(["SLEEPER (SL)", "AC 3 TIER (3A)", "AC 2 TIER (2A)", "AC CHAIR CAR (CC)"])
        add("train_ticket", "PDF", R.choice([f"ticket_{pnr}.pdf", f"ERS_{digits(6)}.pdf", "e-ticket.pdf"]), "en", when(m, day),
            [f"Electronic Reservation Slip\nPNR {pnr}  Train No. {digits(5, '12')}\nFrom {a} To {b}\nDate of journey {D(m, day)}\n"
             f"Class {cls}  Quota GENERAL\nPassenger {name()}  Age {R.randrange(19, 70)}  CNF/B{R.randrange(1, 9)}/{R.randrange(1, 72)}\n"
             f"Total fare Rs {inr(fare)}\nCarry an original photo identity proof during the journey."],
            {"AMOUNT": fare, "ID_NUMBER": pnr, "DOC_KIND": "train ticket", "PARTY": "Indian Railways"})
    for k in range(5):
        pnr = "".join(R.choice("ABCDEFGHJKLMNPQRSTUVWXYZ") for _ in range(6))
        a, b = R.sample(["DEL", "BOM", "BLR", "MAA", "CCU", "HYD", "PNQ", "GOI", "JAI", "LKO"], 2)
        m, day = R.randrange(0, 8), R.randrange(1, 28)
        fare = R.choice([4890, 6230, 3415, 7999]) + 0.0
        add("flight_ticket", "PDF", f"Itinerary_{pnr}.pdf", "en", when(m, day),
            [f"SkyHop Airlines e-ticket itinerary\nBooking reference (PNR) {pnr}\nFlight SH {digits(3)}  {a} to {b}\n"
             f"Departure {D(m, day)} 06:40, terminal 1\nBaggage: 15 kg check-in, 7 kg cabin\nPassenger {name()}\n"
             f"Fare paid INR {inr(fare)}\nWeb check-in opens 48 hours before departure."],
            {"AMOUNT": fare, "ID_NUMBER": pnr, "DOC_KIND": "flight ticket", "PARTY": "SkyHop Airlines"})
    for k in range(3):
        m, day = R.randrange(0, 5), R.randrange(1, 28)
        add("movie_ticket", "SCREENSHOT", f"Screenshot_{digits(8)}.png", "en", when(m, day),
            [f"Booking confirmed\nGalaxy Cinemas, {R.choice(CITY)}\nScreen 3  Seats F11, F12\nShow {D(m, day)} 7:15 PM\n"
             f"Booking ID GC{digits(7)}\nAmount paid Rs {inr(560.0)}"],
            {"AMOUNT": 560.0, "DOC_KIND": "movie ticket", "PARTY": "Galaxy Cinemas"}, noisy=True)
    for k in range(3):
        m, day = R.randrange(0, 5), R.randrange(1, 28)
        a, b = R.sample(CITY, 2)
        add("bus_ticket", "SCREENSHOT", f"Screenshot_{digits(8)}.png", "en", when(m, day),
            [f"RoadStar Travels\nSeat 2B, AC sleeper\n{a} to {b}\nDeparture {D(m, day)} 10:30 PM\nBoarding point: Bus stand gate 2\n"
             f"Ticket no RS{digits(8)}\nFare Rs {inr(1150.0)}"],
            {"AMOUNT": 1150.0, "DOC_KIND": "bus ticket", "PARTY": "RoadStar Travels"}, noisy=True)


def institutions():
    student = name()
    rec = digits(6)
    add("admission_receipt", "PHOTO_OF_PAPER", f"IMG_{digits(8)}.jpg", "en", when(4, 18),
        [f"Govind Ballabh College of Engineering\nFee Receipt\nReceipt No. {rec}  Date {D(4, 18, fmt='dd/MM/yyyy')}\n"
         f"Student: {student}\nProgramme: B.Tech Computer Science, First Year\nAdmission fee 15,000.00\nTuition fee 48,500.00\n"
         f"Caution deposit 5,000.00\nTotal received Rs 68,500.00\nThis receipt is computer generated."],
        {"AMOUNT": 68500.0, "ID_NUMBER": rec, "PARTY": "Govind Ballabh College of Engineering", "DOC_KIND": "fee receipt"}, noisy=True)
    for k in range(3):
        m = R.choice([1, 2, 3])
        add("school_fee_receipt", "PDF", f"fee_{digits(4)}.pdf", "en", when(m, 7),
            [f"St. Xavier's Senior Secondary School\nFee receipt, quarter {k + 1}\nStudent {name()}, Class VII B\n"
             f"Tuition Rs 9,600.00  Transport Rs 2,400.00\nTotal Rs 12,000.00 paid online on {D(m, 7)}"],
            {"AMOUNT": 12000.0, "DOC_KIND": "school fee receipt", "PARTY": "St. Xavier's Senior Secondary School"})
    add("marksheet", "PDF", f"result_{digits(6)}.pdf", "en", when(7, 2),
        [f"Central Board of Secondary Education style statement of marks (sample)\nCandidate {student}\n"
         f"English 88  Mathematics 94  Physics 91  Chemistry 86  Computer Science 97\nResult PASS, Division FIRST"],
        {"DOC_KIND": "marksheet"})
    add("marksheet", "PDF", f"marksheet_hindi.pdf", "hi", when(9, 2),
        [f"माध्यमिक शिक्षा मंडल (नमूना)\nअंकसूची\nपरीक्षार्थी {name()}\nहिंदी 81  अंग्रेज़ी 76  गणित 92  विज्ञान 88  सामाजिक विज्ञान 79\nपरिणाम उत्तीर्ण, प्रथम श्रेणी"],
        {"DOC_KIND": "marksheet"})


def money_docs():
    for k, (ins, what) in enumerate([("Kavach General Insurance", "Private car package policy"),
                                    ("Suraksha Health Insurance", "Family floater health policy"),
                                    ("Jeevan Rekha Life Insurance", "Term life insurance plan")]):
        pol = digits(12)
        prem = [14230.0, 22650.0, 11999.0][k]
        sa = ["IDV Rs 5,42,000", "Sum insured Rs 10,00,000", "Sum assured Rs 1,00,00,000"][k]
        add("insurance_policy", "PDF", R.choice([f"policy_{pol}.pdf", "Policy Schedule.pdf"]), "en", when(R.randrange(2, 10), 3),
            [f"{ins}\n{what}\nPolicy number {pol}\nPolicy holder {name()}\nPeriod of insurance: 12 months\n{sa}\n"
             f"Premium paid Rs {inr(prem)} including GST\nClaims: call the toll free number within 48 hours of the incident."],
            {"AMOUNT": prem, "ID_NUMBER": pol, "PARTY": ins, "DOC_KIND": "insurance policy"})
    emp = "Corvanta Software Services Pvt Ltd"
    for m in (1, 2, 3):
        net = 84213.0
        add("salary_slip", "PDF", f"payslip_{m}.pdf", "en", when(m, 30 if m != 2 else 28),
            [f"{emp}\nPayslip for the month ending {D(m, 28, fmt='MMMM yyyy')}\nEmployee {name()}  Code CSS{digits(5)}\n"
             f"Basic 52,000.00  HRA 20,800.00  Special allowance 18,413.00\nProvident fund 6,240.00  Professional tax 200.00\n"
             f"Income tax (TDS) 560.00\nNet pay Rs {inr(net)}"],
            {"AMOUNT": net, "PARTY": emp, "DOC_KIND": "salary slip"})
    acct = digits(4)
    add("bank_statement", "PDF", f"Statement_{digits(6)}.pdf", "en", when(1, 2),
        [f"Bharat Sahakari Bank\nAccount statement, savings account ending {acct}\n"
         f"01 UPI/ramesh kirana/450.00 Dr\n03 NEFT salary Corvanta 84,213.00 Cr\n07 ATM withdrawal 5,000.00 Dr\n"
         f"12 EMI home loan 23,480.00 Dr\n18 Interest credit 412.00 Cr\nClosing balance 1,18,702.10"],
        {"AMOUNT": 118702.10, "ID_NUMBER": acct, "PARTY": "Bharat Sahakari Bank", "DOC_KIND": "bank statement"})
    add("rent_receipt", "PDF", "rent receipt.pdf", "en", when(2, 5),
        [f"Rent receipt\nReceived from {name()} a sum of Rs 18,000 (eighteen thousand only) towards rent of flat 304, "
         f"Lotus Residency, {R.choice(CITY)} for the month of {D(2, 5, fmt='MMMM yyyy')}.\nLandlord: {name()}  PAN AQXPK{digits(4)}L"],
        {"AMOUNT": 18000.0, "DOC_KIND": "rent receipt"})
    add("laptop_invoice", "PDF", f"INV{digits(8)}.pdf", "en", when(6, 11),
        [f"TechWorld Retail Pvt Ltd\nTax invoice INV{digits(8)}\nApple MacBook Air 13 inch, M3, 16 GB, 512 GB SSD\n"
         f"Serial number C02{digits(7)}\nPrice Rs 1,14,900.00 including 18% GST\nWarranty one year from the date of purchase"],
        {"AMOUNT": 114900.0, "PARTY": "TechWorld Retail", "DOC_KIND": "invoice"})
    add("warranty_card", "PHOTO_OF_PAPER", f"IMG_{digits(8)}.jpg", "en", when(5, 23),
        [f"Coolbreeze 1.5 ton inverter split air conditioner\nWarranty card\nModel CB-IV18  Serial {digits(9)}\n"
         f"Compressor warranty 10 years, other parts 1 year\nInstalled on {D(5, 23)} by authorised dealer"],
        {"DOC_KIND": "warranty card", "PARTY": "Coolbreeze"}, noisy=True)
    add("gas_receipt", "SCREENSHOT", f"Screenshot_{digits(8)}.png", "en", when(1, 16),
        [f"Indane style LPG refill booking\nBooking confirmed for 14.2 kg cylinder\nConsumer no {digits(7)}\n"
         f"Amount Rs 903.00 paid by UPI\nDelivery expected within 2 days"],
        {"AMOUNT": 903.0, "DOC_KIND": "gas booking"}, noisy=True)


def ids():
    who = name()
    add("national_id", "PHOTO_OF_PAPER", f"IMG_{digits(8)}.jpg", "en", when(11, 3),
        [f"Government of India\nआधार - आम आदमी का अधिकार\n{who}\nDOB: 1{digits(1)}/0{R.randrange(1, 9)}/199{R.randrange(0, 9)}\nMALE\n"
         f"0{digits(3)} {digits(4)} {digits(4)}\nSPECIMEN - NOT A VALID DOCUMENT"],
        {"DOC_KIND": "aadhaar card"}, noisy=True)
    add("national_id", "PHOTO_OF_PAPER", f"IMG_{digits(8)}.jpg", "en", when(11, 3),
        [f"Address: House 12, Gali 4, Shanti Nagar, {R.choice(CITY)} 4520{digits(2)}\nUnique Identification Authority of India\n"
         f"1947  help@uidai.example\nSPECIMEN - NOT A VALID DOCUMENT"],
        {"DOC_KIND": "aadhaar card"}, noisy=True, group="national_id_back")
    add("pan_card", "PHOTO_OF_PAPER", f"IMG_{digits(8)}.jpg", "en", when(11, 4),
        [f"INCOME TAX DEPARTMENT  GOVT. OF INDIA\nPermanent Account Number Card\nZZZPZ{digits(4)}Z\n{who.upper()}\n"
         f"Date of Birth 1{digits(1)}/0{R.randrange(1, 9)}/199{R.randrange(0, 9)}\nSPECIMEN"],
        {"DOC_KIND": "pan card"}, noisy=True)
    add("driving_licence", "PHOTO_OF_PAPER", f"IMG_{digits(8)}.jpg", "en", when(10, 9),
        [f"Union of India  Driving Licence (specimen)\nDL No MH12 2019{digits(7)}\nName {who}\nValid till 2039\n"
         f"Class of vehicle LMV, MCWG\nBlood group B+"],
        {"DOC_KIND": "driving licence"}, noisy=True)
    add("voter_id", "PHOTO_OF_PAPER", f"IMG_{digits(8)}.jpg", "hi", when(10, 9),
        [f"भारत निर्वाचन आयोग\nमतदाता फोटो पहचान पत्र (नमूना)\nनाम {who}\nपिता का नाम {name()}\nपहचान संख्या XQZ{digits(7)}"],
        {"DOC_KIND": "voter id"}, noisy=True)


CHATS = [
    ("trip", "en", ["Goa plan final? Leaving friday night", "yes booked the homestay near Anjuna, 3 nights",
                    "Bring sunscreen and the speaker", "Train at 9:40 from Pune, coach S4"]),
    ("birthday", "en", ["Surprise party for Nisha on saturday", "cake ordered, chocolate truffle 2 kg",
                        "decorations at 6, she arrives 8", "dont tell her!!"]),
    ("rent", "hi", ["भैया इस महीने का किराया 5 तारीख तक भेज दूँगा", "ठीक है, बिजली का बिल अलग से देना",
                    "पानी की मोटर भी ठीक करवा दीजिए", "हाँ कल प्लंबर आएगा"]),
    ("doctor", "en", ["Appointment with Dr Rao moved to Tuesday 11:30", "bring the old reports and the x ray",
                      "fasting needed for the blood test", "ok will reach by 11"]),
    ("exam", "hi", ["kal maths ka paper hai, chapter 5 aur 7 aayenge", "formulas ki list bhej do please",
                    "sample paper solve kiya?", "haan, 2 baje library milte hain"]),
    ("interview", "en", ["Your interview with Corvanta is on 14th at 10 am", "second round is a system design discussion",
                         "carry two copies of your resume", "the office is on the 5th floor, tower B"]),
    ("recipe", "hi", ["mummy rajma kaise banate hain?", "raat bhar bhigo do, phir pressure cooker mein 6 seeti",
                      "pyaaz tamatar ka masala alag se bhuno", "end mein kasuri methi daalna"]),
    ("cricket", "en", ["did you see the last over yesterday", "needed 14 off 6 and he hit two sixes",
                       "best finish of the season", "tickets for the next home match open tomorrow"]),
    ("wifi", "en", ["internet down again since morning", "restart the router, the red light is blinking",
                    "called SkyLink, technician comes at 4", "ok working now, 180 mbps"]),
    ("parcel", "hi", ["आपका पार्सल आज शाम तक पहुँच जाएगा", "घर पर कोई नहीं है, गार्ड को दे देना",
                      "ठीक है, कोड बता दीजिए", "कोड मैसेज में भेज दिया"]),
    ("leak", "en", ["bathroom tap is leaking all night", "plumber said he will come tomorrow 10 am",
                    "buy a new washer and teflon tape", "fixed, paid him 350"]),
    ("movie", "en", ["shall we watch the new thriller on saturday", "evening show at Galaxy, 7:15",
                     "I'll book F11 and F12", "popcorn on me"]),
    ("loan", "en", ["the bank approved the home loan!", "emi will be around 28k for 20 years",
                    "need to submit the sale deed copy", "registration next week"]),
    ("wedding", "hi", ["शादी की तैयारी कैसी चल रही है", "कार्ड छप गए हैं, कल बाँटेंगे",
                       "हलवाई को मेन्यू भेज दिया", "मेहंदी की रस्म शुक्रवार को"]),
]


def screenshots():
    for topic, lang, msgs in CHATS:
        for k in range(2):
            m, day = R.randrange(0, 6), R.randrange(1, 28)
            other = R.choice(FIRST)
            body = "\n".join(f"{other if i % 2 == 0 else 'You'}: {t}" for i, t in enumerate(msgs if k == 0 else msgs[1:] + msgs[:1]))
            add(f"chat_{topic}", "SCREENSHOT", f"Screenshot_{digits(8)}_WhatsApp.png", lang, when(m, day),
                [f"{other}\nonline\n{body}\nType a message"], {"DOC_KIND": "chat"}, noisy=True, group=f"chat_{topic}")
    payees = ["Ramesh Kirana Store", "Shree Ganesh Dairy", "Metro Auto Stand", "Dr Rao Clinic", "Sunrise Tiffin Service",
              "Anil Vegetables", "Sharma Electricals", "City Laundry", "Om Sai Hardware", "Fresh Fruits Corner"]
    for k in range(90):
        payee = payees[k % len(payees)]
        amt = R.choice([45, 120, 250, 450, 560, 799, 1200, 1500, 2350, 25000, 24850, 25300])
        m, day = R.randrange(0, 7), R.randrange(1, 28)
        ref = digits(12)
        if k % 5 == 4:
            text = f"₹{inr(amt)} भुगतान सफल\n{payee} को भेजा गया\nUPI लेनदेन आईडी {ref}\n{D(m, day, fmt='d MMM yyyy')}"
            lang = "hi"
        else:
            text = f"Payment successful\n₹{inr(amt)}\nPaid to {payee}\nUPI transaction ID {ref}\n{D(m, day, fmt='d MMM yyyy, h:mm a')}\nDebited from Bharat Sahakari Bank ••{digits(4)}"
            lang = "en"
        add("upi_payment", "SCREENSHOT", f"Screenshot_{digits(8)}_PayApp.png", lang, when(m, day), [text],
            {"AMOUNT": float(amt), "ID_NUMBER": ref, "PARTY": payee, "DOC_KIND": "payment"}, noisy=True)
    for k in range(20):
        m, day = R.randrange(0, 4), R.randrange(1, 28)
        code = digits(6)
        add("otp_sms", "SCREENSHOT", f"Screenshot_{digits(8)}_Messages.png", "en", when(m, day),
            [f"AX-BHSBNK\n{code} is your one time password for a transaction of Rs {inr(R.choice([1200, 4999, 25000]))}. "
             f"Do not share it with anyone. Bank staff never ask for OTP."], {"DOC_KIND": "otp"}, noisy=True)
    stores = [("ShopKart", "Noise-cancelling wireless headphones, black"), ("ShopKart", "Pressure cooker 5 litre, steel"),
              ("Bazaarly", "Running shoes size 9, grey"), ("Bazaarly", "Kids school bag with water bottle"),
              ("ShopKart", "Phone charger 33W USB-C"), ("Bazaarly", "Yoga mat 6 mm, purple")]
    for k, (store, thing) in enumerate(stores * 4):
        m, day = R.randrange(0, 6), R.randrange(1, 28)
        add("order_confirmation", "SCREENSHOT", f"Screenshot_{digits(8)}_{store}.png", "en", when(m, day),
            [f"{store}\nOrder placed\n{thing}\nOrder ID OD{digits(11)}\nArriving by {D(m, max(1, day - 3))}\nTotal Rs {inr(R.choice([599, 1299, 2499, 3999]))}"],
            {"DOC_KIND": "order", "PARTY": store}, noisy=True)
    for k in range(20):
        m, day = R.randrange(0, 6), R.randrange(1, 28)
        a, b = R.sample(["Home", "Office", "Railway Station", "Airport T2", "City Hospital", "Phoenix Mall"], 2)
        add("cab_receipt", "SCREENSHOT", f"Screenshot_{digits(8)}_RideNow.png", "en", when(m, day),
            [f"RideNow\nThanks for riding\n{a} to {b}\n14.2 km, 38 min\nTrip fare ₹{inr(R.choice([212, 348, 465, 690]))}\nPaid by cash"],
            {"DOC_KIND": "cab receipt", "PARTY": "RideNow"}, noisy=True)
    news = [("Heavy rain alert for coastal districts as the monsoon trough strengthens; schools shut for two days", "weather"),
            ("Central bank keeps the repo rate unchanged, signals easing if prices cool", "economy"),
            ("Metro line 3 trial run completed; commercial service expected by March", "city"),
            ("New rules for income tax refund processing come into effect from April", "tax"),
            ("Spinner takes five wickets as home side clinches the series", "sport"),
            ("Researchers develop a cheaper battery that charges in ten minutes", "science")]
    for text, topic in news:
        for k in range(3):
            m, day = R.randrange(0, 7), R.randrange(1, 28)
            add(f"news_{topic}", "SCREENSHOT", f"Screenshot_{digits(8)}_News.png", "en", when(m, day),
                [f"Daily Herald\n{text}\n{R.choice(CITY)}, {D(m, day)}\nRead more"], {"DOC_KIND": "news"}, noisy=True, group=f"news_{topic}")
    misc = [
        ("error_screen", "java.lang.NullPointerException: Attempt to invoke virtual method on a null object reference\nat com.example.app.MainActivity.onCreate(MainActivity.kt:42)\nApp has stopped"),
        ("storage_full", "Storage almost full\n58.3 GB of 64 GB used\nPhotos and videos 31.2 GB\nApps 14.8 GB\nFree up space"),
        ("screen_time", "Digital wellbeing\nScreen time today 5 hr 12 min\nVideo app 2 hr 3 min\nChat app 1 hr 41 min\nUnlocks 87"),
        ("directions", "Directions to City Hospital\n12 min (4.1 km) via Ring Road\nFastest route now due to traffic conditions\nStart"),
        ("weather", "Indore\n31°\nPartly cloudy\nHumidity 64%  Wind 12 km/h\nRain expected after 4 pm"),
        ("calendar", "Parent teacher meeting\nSaturday 10:00 - 11:00 am\nSt. Xavier's school hall\nReminder 1 day before"),
        ("password_reset", "Reset your password\nUse this link within 15 minutes\nIf you didn't ask for this, ignore this email\nSecurity team"),
        ("job_offer", "Congratulations! We are pleased to offer you the role of Data Analyst at Corvanta Software Services.\nCTC Rs 9,60,000 per annum\nJoining date 1 December"),
        ("exam_result", "Semester 5 result\nSGPA 8.62\nAll subjects cleared\nDownload marksheet"),
        ("boarding_pass", "SkyHop Airlines boarding pass\nGate 14  Seat 17C\nBoarding 06:10\nGroup 3\nDEL to BLR"),
        ("bank_alert", "Your a/c XX4471 is debited with Rs 23,480.00 towards EMI on 12-month home loan instalment. Avl bal Rs 61,233.40"),
        ("electricity_app", "Bill paid successfully\nShivalik Power Distribution Ltd\nConsumer No. linked\nAmount Rs 2,814.00\nThank you for paying on time"),
        ("dashboard", "Sales dashboard\nRevenue this quarter Rs 42.6 lakh, up 12%\nActive customers 3,214\nChurn 2.1%\nTop region: West"),
        ("vaccination", "Vaccination certificate (specimen)\nBeneficiary name\nDose 2 of 2 completed\nCertificate ID 10{digits}".replace("{digits}", digits(10))),
        ("fitness", "Today\n8,412 steps\n5.9 km\n312 kcal\nGoal 10,000 steps"),
    ]
    for kind, text in misc:
        for k in range(4):
            m, day = R.randrange(0, 7), R.randrange(1, 28)
            add(kind, "SCREENSHOT", f"Screenshot_{digits(8)}.png", "en", when(m, day), [text], {"DOC_KIND": kind}, noisy=True)


OFFICE = [
    ("rental_agreement", "Rental Agreement.docx", "WORD", "en", [
        "This rental agreement is made between the owner and the tenant for flat 304, Lotus Residency. The monthly rent is Rs 18,000 payable on or before the 5th of every month.",
        "The tenant has paid a security deposit of Rs 54,000, refundable within thirty days of vacating, after deducting unpaid dues and damage. The agreement is for eleven months and may be renewed with a 5% increase.",
        "Either party may end the agreement with one month's written notice. The tenant shall not sublet the premises or keep pets without permission."]),
    ("offer_letter", "Offer_Letter_Final.pdf", "PDF", "en", [
        "Dear candidate, we are pleased to offer you the position of Data Analyst in our Pune office. Your annual cost to company will be Rs 9,60,000.",
        "You will be on probation for six months. Working hours are 9:30 am to 6:30 pm, Monday to Friday. Please confirm your acceptance within seven days."]),
    ("resignation_letter", "resignation.docx", "WORD", "en", [
        "I hereby resign from my position as Senior Accountant, with my last working day being thirty days from today. I am grateful for the opportunities and support during my four years with the team."]),
    ("meeting_notes", "notes 14 oct.docx", "WORD", "en", [
        "Quarterly planning meeting. Attendees: product, sales and support leads. Decision: move the pricing page redesign to next quarter.",
        "Action items: support to publish a help article on refunds; sales to share the list of churned accounts; product to estimate the offline mode."]),
    ("project_proposal", "proposal_v3.pdf", "PDF", "en", [
        "Proposal: a solar powered cold storage unit for small farmers. Vegetables lose up to a third of their value within a day of harvest without cooling.",
        "The unit stores two tonnes, runs on a 5 kW rooftop array with a battery bank, and costs about Rs 6 lakh. Farmers pay per crate per day."]),
    ("resume", "CV_2026.pdf", "PDF", "en", [
        "Experienced software engineer with six years in Android development. Built offline-first apps used by two million people. Skills: Kotlin, Jetpack Compose, Room, coroutines, CI pipelines.",
        "Education: B.Tech in Electronics, 2019. Languages: English, Hindi, Marathi."]),
    ("leave_application", "avedan.docx", "WORD", "hi", [
        "सेवा में, प्रधानाचार्य महोदय, सविनय निवेदन है कि मुझे तेज़ बुखार होने के कारण तीन दिन का अवकाश चाहिए। कृपया मुझे दिनांक से तीन दिन की छुट्टी प्रदान करें। आपका आज्ञाकारी शिष्य।"]),
    ("rti_application", "RTI.pdf", "PDF", "hi", [
        "सूचना का अधिकार अधिनियम 2005 के अंतर्गत आवेदन। कृपया मेरे वार्ड में सड़क मरम्मत पर पिछले दो वर्षों में खर्च की गई राशि की जानकारी दें। आवेदन शुल्क दस रुपये संलग्न है।"]),
    ("complaint_letter", "complaint.docx", "WORD", "en", [
        "Subject: complaint about a defective washing machine. The drum stopped spinning eleven days after delivery and the technician has not visited despite three requests. I request a replacement or a full refund."]),
    ("travel_itinerary", "Kerala trip plan.pdf", "PDF", "en", [
        "Day 1: arrive Kochi, Fort Kochi walk and Chinese fishing nets. Day 2: drive to Munnar, tea gardens and Eravikulam park.",
        "Day 3: Alleppey houseboat overnight on the backwaters. Day 4: Varkala cliff beach, return flight from Thiruvananthapuram."]),
    ("wedding_invitation", "invite.pdf", "PDF", "hi", [
        "शुभ विवाह। हमारे पुत्र और पुत्रवधू के विवाह समारोह में आप सपरिवार सादर आमंत्रित हैं। स्थान: गुलमोहर गार्डन, समय: शाम सात बजे। प्रीतिभोज के पश्चात विदाई।"]),
    ("syllabus", "DBMS syllabus.pdf", "PDF", "en", [
        "Unit 1: introduction to database systems, data models, entity relationship diagrams. Unit 2: relational algebra and SQL queries, joins and subqueries.",
        "Unit 3: normalisation up to BCNF. Unit 4: transactions, concurrency control and recovery. Unit 5: indexing with B+ trees and hashing."]),
    ("manual", "manual_WM.pdf", "PDF", "en", [
        "Front load washing machine user manual. Use only high efficiency detergent. For woollens select the gentle cycle at 30 degrees.",
        "Error E21: water is not draining. Clean the drain pump filter at the bottom right behind the small flap. Error E11: the tap is closed or pressure is low."]),
    ("loan_sanction", "sanction letter.pdf", "PDF", "en", [
        "Home loan sanction letter. We are pleased to sanction a loan of Rs 32,00,000 for the purchase of a residential flat. Rate of interest 8.6% floating, tenure 240 months.",
        "Equated monthly instalment Rs 27,971. Processing fee Rs 5,900 has been received. The loan is subject to a legal and technical check of the property."]),
    ("medical_report", "report_lab.pdf", "PDF", "en", [
        "Complete blood count and lipid profile. Haemoglobin 12.1 g/dL (low). Total cholesterol 228 mg/dL (high), LDL 151 mg/dL, HDL 41 mg/dL.",
        "Fasting blood sugar 98 mg/dL. Vitamin D 14 ng/mL, deficient. Please consult your physician with this report."]),
    ("prescription", "IMG_presc.jpg", "PHOTO_OF_PAPER", "en", [
        "Dr S. Rao, MD Medicine. Rx: Tab Cholecalciferol 60,000 IU once a week for 8 weeks. Tab Atorvastatin 10 mg at night. Review after one month with lipid profile."]),
]


def office():
    for kind, file, itype, lang, pages in OFFICE:
        add(kind, itype, file, lang, when(R.randrange(0, 9), R.randrange(1, 28)), pages, {"DOC_KIND": kind.replace("_", " ")},
            noisy=itype == "PHOTO_OF_PAPER")


def build():
    electricity()
    phone_bills()
    receipts()
    tickets()
    institutions()
    money_docs()
    ids()
    screenshots()
    office()
    return ITEMS
