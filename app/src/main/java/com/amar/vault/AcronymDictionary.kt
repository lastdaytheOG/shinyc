package com.amar.vault

/**
 * Sprint 4B — deterministic acronym expansion layer.
 *
 * Pure Kotlin. Immutable. Thread-safe (a read-only [Map] published once at class load).
 * No Android dependencies, no I/O, no LLM, no network — this is a static lookup table and
 * two pure string functions, nothing more.
 *
 * Purpose: two retrieval issues surfaced in architecture review —
 *   1. queries shorter than 4 chars never reach the semantic lane;
 *   2. semantic-only hits are dropped by the lexical text-presence gate.
 * Both hurt short technical acronyms ("AI", "OCR", "PDF") whose meaning lives in an
 * expanded form the document actually contains ("Artificial Intelligence"). This layer
 * lets a recognized acronym carry its expansion into every retrieval lane WITHOUT
 * replacing the original token and WITHOUT changing any ranking math.
 *
 * Design rules:
 *  - EXPAND, never replace. The original token always survives (see [expand]).
 *  - No ambiguous abbreviations. Everyday English words that merely look like acronyms
 *    (IT, US, OR, IN, IP, PIN, MAC, POS, PR…) are deliberately excluded — expanding them
 *    would corrupt normal queries. Only unambiguous technical terms are included.
 *  - No-op for anything not in the table: [expand] returns the input string unchanged
 *    when no token matches, so non-acronym queries are byte-identical downstream.
 */
object AcronymDictionary {

    /** Whitespace splitter, compiled once. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Curated, unambiguous technical abbreviations → full form.
     *
     * Keys are stored UPPERCASE and matched case-insensitively. Values are the canonical
     * expanded form as it typically appears in documents. Intentionally excludes ambiguous
     * short words (IT, US, OR, IN, IP, MAC, PIN, POS, PR, AD, CD-the-audio, …).
     */
    private val MAP: Map<String, String> = buildMap {
        // ── AI / ML / NLP core (the Sprint 4B acceptance set) ────────────────
        put("AI", "Artificial Intelligence")
        put("ML", "Machine Learning")
        put("LLM", "Large Language Model")
        put("NLP", "Natural Language Processing")
        put("OCR", "Optical Character Recognition")
        put("PDF", "Portable Document Format")
        put("RAG", "Retrieval Augmented Generation")
        put("ONNX", "Open Neural Network Exchange")
        put("NLU", "Natural Language Understanding")
        put("NLG", "Natural Language Generation")
        put("ASR", "Automatic Speech Recognition")
        put("TTS", "Text To Speech")
        put("STT", "Speech To Text")
        put("NER", "Named Entity Recognition")
        put("CNN", "Convolutional Neural Network")
        put("RNN", "Recurrent Neural Network")
        put("LSTM", "Long Short Term Memory")
        put("GRU", "Gated Recurrent Unit")
        put("GAN", "Generative Adversarial Network")
        put("MLP", "Multi Layer Perceptron")
        put("SVM", "Support Vector Machine")
        put("KNN", "K Nearest Neighbors")
        put("PCA", "Principal Component Analysis")
        put("SGD", "Stochastic Gradient Descent")
        put("RL", "Reinforcement Learning")
        put("RLHF", "Reinforcement Learning from Human Feedback")
        put("GPT", "Generative Pre-trained Transformer")
        put("BERT", "Bidirectional Encoder Representations from Transformers")
        put("HNSW", "Hierarchical Navigable Small World")
        put("ANN", "Approximate Nearest Neighbor")
        put("EMB", "Embedding")

        // ── Hardware / compute ───────────────────────────────────────────────
        put("CPU", "Central Processing Unit")
        put("GPU", "Graphics Processing Unit")
        put("TPU", "Tensor Processing Unit")
        put("NPU", "Neural Processing Unit")
        put("RAM", "Random Access Memory")
        put("ROM", "Read Only Memory")
        put("SSD", "Solid State Drive")
        put("HDD", "Hard Disk Drive")
        put("USB", "Universal Serial Bus")
        put("GPS", "Global Positioning System")
        put("NFC", "Near Field Communication")
        put("BLE", "Bluetooth Low Energy")
        put("RFID", "Radio Frequency Identification")
        put("IOT", "Internet of Things")

        // ── Data formats / serialization ─────────────────────────────────────
        put("JSON", "JavaScript Object Notation")
        put("XML", "Extensible Markup Language")
        put("YAML", "YAML Ain't Markup Language")
        put("TOML", "Tom's Obvious Minimal Language")
        put("CSV", "Comma Separated Values")
        put("HTML", "HyperText Markup Language")
        put("CSS", "Cascading Style Sheets")
        put("SVG", "Scalable Vector Graphics")
        put("PNG", "Portable Network Graphics")
        put("JPEG", "Joint Photographic Experts Group")
        put("GIF", "Graphics Interchange Format")
        put("MIME", "Multipurpose Internet Mail Extensions")
        put("ASCII", "American Standard Code for Information Interchange")
        put("UTF", "Unicode Transformation Format")
        put("BLOB", "Binary Large Object")

        // ── Networking / web / protocols ─────────────────────────────────────
        put("HTTP", "HyperText Transfer Protocol")
        put("HTTPS", "HyperText Transfer Protocol Secure")
        put("URL", "Uniform Resource Locator")
        put("URI", "Uniform Resource Identifier")
        put("DNS", "Domain Name System")
        put("TCP", "Transmission Control Protocol")
        put("UDP", "User Datagram Protocol")
        put("SSH", "Secure Shell")
        put("SSL", "Secure Sockets Layer")
        put("TLS", "Transport Layer Security")
        put("FTP", "File Transfer Protocol")
        put("SMTP", "Simple Mail Transfer Protocol")
        put("IMAP", "Internet Message Access Protocol")
        put("REST", "Representational State Transfer")
        put("SOAP", "Simple Object Access Protocol")
        put("RPC", "Remote Procedure Call")
        put("CDN", "Content Delivery Network")
        put("VPN", "Virtual Private Network")
        put("LAN", "Local Area Network")
        put("WAN", "Wide Area Network")
        put("CORS", "Cross Origin Resource Sharing")
        put("CSRF", "Cross Site Request Forgery")
        put("XSS", "Cross Site Scripting")
        put("DDOS", "Distributed Denial of Service")
        put("WEBRTC", "Web Real-Time Communication")
        put("WEBGL", "Web Graphics Library")

        // ── Software engineering / APIs / tooling ────────────────────────────
        put("API", "Application Programming Interface")
        put("SDK", "Software Development Kit")
        put("IDE", "Integrated Development Environment")
        put("CLI", "Command Line Interface")
        put("GUI", "Graphical User Interface")
        put("UI", "User Interface")
        put("UX", "User Experience")
        put("ORM", "Object Relational Mapping")
        put("CRUD", "Create Read Update Delete")
        put("MVC", "Model View Controller")
        put("MVVM", "Model View ViewModel")
        put("DTO", "Data Transfer Object")
        put("DI", "Dependency Injection")
        put("VCS", "Version Control System")
        put("CICD", "Continuous Integration Continuous Delivery")
        put("IAC", "Infrastructure as Code")
        put("VM", "Virtual Machine")
        put("SAAS", "Software as a Service")
        put("PAAS", "Platform as a Service")
        put("IAAS", "Infrastructure as a Service")

        // ── Databases ────────────────────────────────────────────────────────
        put("SQL", "Structured Query Language")
        put("NOSQL", "Not Only SQL")
        put("DBMS", "Database Management System")
        put("RDBMS", "Relational Database Management System")
        put("OLAP", "Online Analytical Processing")
        put("OLTP", "Online Transaction Processing")
        put("ETL", "Extract Transform Load")
        put("ACID", "Atomicity Consistency Isolation Durability")
        put("UUID", "Universally Unique Identifier")
        put("GUID", "Globally Unique Identifier")

        // ── Security / auth / crypto ─────────────────────────────────────────
        put("JWT", "JSON Web Token")
        put("SSO", "Single Sign On")
        put("MFA", "Multi Factor Authentication")
        put("OTP", "One Time Password")
        put("RBAC", "Role Based Access Control")
        put("ACL", "Access Control List")
        put("AES", "Advanced Encryption Standard")
        put("RSA", "Rivest Shamir Adleman")
        put("SHA", "Secure Hash Algorithm")
        put("HMAC", "Hash-based Message Authentication Code")
        put("PKI", "Public Key Infrastructure")
        put("CVE", "Common Vulnerabilities and Exposures")

        // ── Ops / reliability / metrics ──────────────────────────────────────
        put("SLA", "Service Level Agreement")
        put("SLO", "Service Level Objective")
        put("QOS", "Quality of Service")
        put("TTL", "Time To Live")
        put("KPI", "Key Performance Indicator")
        put("FPS", "Frames Per Second")
        put("DPI", "Dots Per Inch")

        // ── Algorithms / CS fundamentals ─────────────────────────────────────
        put("DAG", "Directed Acyclic Graph")
        put("BFS", "Breadth First Search")
        put("DFS", "Depth First Search")
        put("FIFO", "First In First Out")
        put("LIFO", "Last In First Out")
        put("REGEX", "Regular Expression")
        put("CRC", "Cyclic Redundancy Check")
        put("EOF", "End Of File")

        // ── XR / media ───────────────────────────────────────────────────────
        put("AR", "Augmented Reality")
        put("VR", "Virtual Reality")
        put("XR", "Extended Reality")
        put("QR", "Quick Response")
        put("RGB", "Red Green Blue")

        // ── Documents / office ───────────────────────────────────────────────
        put("DOCX", "Microsoft Word Document")
        put("XLSX", "Microsoft Excel Spreadsheet")
        put("PPTX", "Microsoft PowerPoint Presentation")
        put("EPUB", "Electronic Publication")
        put("ISBN", "International Standard Book Number")
    }

    /** All recognized acronym keys (uppercase). Exposed for benchmarks/diagnostics. */
    val keys: Set<String> get() = MAP.keys

    /** The canonical expansion for [acronym], or null if unknown. Case-insensitive. */
    fun expansionOf(acronym: String): String? = MAP[acronym.trim().uppercase()]

    /** Result of the single token scan: the trimmed query and its per-token expansions. */
    private class Scan(val trimmed: String, val expansions: List<String>)

    /**
     * The one and only place acronym expansion is computed. Both [expand] and [analyze]
     * delegate here, so there is exactly one scan of the query against the dictionary.
     */
    private fun scan(query: String): Scan {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Scan(trimmed, emptyList())
        val expansions = ArrayList<String>()
        for (token in trimmed.split(WHITESPACE)) {
            MAP[token.uppercase()]?.let { expansions.add(it) }
        }
        return Scan(trimmed, expansions)
    }

    /**
     * Structured expansion — the Sprint 4B.1 entry point retrieval uses. Produces an
     * immutable [ExpandedQuery] whose [ExpandedQuery.joined] is value-identical to the
     * Sprint 4B `expand()` string, and whose [ExpandedQuery.gateTerms] reproduce the old
     * gate word set. Expansion happens once (a single [scan]).
     */
    fun analyze(query: String): ExpandedQuery {
        val s = scan(query)
        val hasAcronym = s.expansions.isNotEmpty()
        // First term reproduces expand()'s leading phrase exactly: the trimmed query when
        // an acronym is present, else the original string verbatim (the 4B no-op path).
        val front = if (hasAcronym) s.trimmed else query
        val terms = if (hasAcronym) buildList { add(front); addAll(s.expansions) } else listOf(front)
        val gate = terms.joinToString(" ").lowercase().trim()
            .split(WHITESPACE).filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
        return ExpandedQuery(
            originalQuery = query,
            normalizedQuery = query.lowercase().trim(),
            expandedTerms = terms,
            gateTerms = gate,
            containsKnownAcronym = hasAcronym,
        )
    }

    /**
     * True when the whole trimmed [query] is a single recognized acronym.
     *
     * Used only by the semantic-length gate: it lets a short-but-recognized acronym
     * ("AI", "ML", "PDF") bypass the `length < 4` skip, while genuine short junk queries
     * keep today's behavior. Multi-token queries are already long enough to pass the gate,
     * so a single-token check is sufficient and keeps the predicate cheap.
     */
    fun isKnownAcronym(query: String): Boolean {
        val t = query.trim()
        if (t.isEmpty()) return false
        return MAP.containsKey(t.uppercase())
    }

    /**
     * Expand any recognized acronym tokens in [query] by APPENDING their full forms,
     * preserving the original query verbatim at the front.
     *
     *   "AI"        → "AI Artificial Intelligence"
     *   "ocr pdf"   → "ocr pdf Optical Character Recognition Portable Document Format"
     *   "amazon receipt" → "amazon receipt"   (unchanged — no acronym token)
     *
     * Guarantees:
     *  - the original token(s) always remain (recall-only, never replacement);
     *  - if NO token matches, the input string is returned unchanged (referential no-op),
     *    so non-acronym queries flow through retrieval exactly as before;
     *  - the original leading phrase stays contiguous so downstream exact-phrase logic on
     *    the original query is unaffected.
     */
    fun expand(query: String): String {
        val s = scan(query)
        if (s.expansions.isEmpty()) return query // no acronym → identical string, zero drift
        return buildString {
            append(s.trimmed)
            for (e in s.expansions) { append(' '); append(e) }
        }
    }
}
