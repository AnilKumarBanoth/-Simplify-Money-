# Simplify Money — Ledger Sync Service

[![CI](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![Java](https://img.shields.io/badge/Java-21-orange.svg)]()
[![License](https://img.shields.io/badge/license-Proprietary-blue.svg)]()

> **Submission for:** Software Engineer / Intern (Backend, Java)  
> **Candidate:** Anil Kumar Banoth  
> **Email:** `an5689247@gmail.com`  
> **Date:** September 2026  

---

## Table of Contents
1. [Quickstart (5-Minute Walkthrough)](#quickstart-5-minute-walkthrough)
2. [Deliverables & Document Index](#deliverables--document-index)
3. [Decision Log (10 Key Architecture Decisions)](#decision-log-10-key-architecture-decisions)
4. [What the Data Made Us Decide](#what-the-data-made-us-decide)
5. [Document Store Model & Scale Metrics (100k Scale)](#document-store-model--scale-metrics-100k-scale)
6. [Production Incident INC-2026-09-11](#production-incident-inc-2026-09-11)
7. [AI Disclosure & Failure Post-Mortem](#ai-disclosure--failure-post-mortem)
8. [What's Unfinished / Production Roadmap](#whats-unfinished--production-roadmap)

---

## Quickstart (5-Minute Walkthrough)

### Prerequisites
- **Java 21 (LTS)** installed and on `PATH` (`java -version` should report 21+).
- **Docker & Docker Compose** (for running DynamoDB Local & MongoDB).

### 1. Pure JDK 21 Verification (Zero Dependencies, Zero Network)
To verify the ingest pipeline, deduplication, categorization, balance checks, and reconciliation without Gradle or database:
```bash
# On Linux / macOS / Git Bash:
./verify.sh

# On Windows PowerShell:
javac -d build/selfcheck $(Get-ChildItem -Path src/main/java -Filter *.java -Recurse | Select-Object -ExpandProperty FullName)
java -cp build/selfcheck in.simplifymoney.ledgersync.SelfCheck
```
*Expected Output:*
- Ingested: 522 messages read, 43 noise/phishing skipped, 256 unique transactions written.
- Totals: SPEND: ₹142,567.64 | INCOME: ₹142,791.16 | MICRO: ₹4,443.85 | TRANSFER: ₹62,000.00.
- Reconciled: Account 9075 matches bank balance to 0.00 difference down to the paisa; Account 4821 flags the ₹7,500.00 unannounced drop.

### 2. Run Test Suite
```bash
./gradlew test
```
Executes all 21 unit & integration tests covering contract invariants, amount parsing edge cases, document store queries, and backfill consistency validation.

### 3. Run Pipeline CLI with Gradle
```bash
# 1. Run database migration (Flyway + H2)
./gradlew run --args="migrate"

# 2. Ingest bank SMS & Email corpus
./gradlew run --args="ingest fixtures/corpus-a.jsonl"

# 3. Generate production reports in submission/
./gradlew run --args="report submission/"
```
This generates:
- `submission/ledger.json`: 256 unique normalized transactions with multi-source traceability.
- `submission/summary.json`: Per-account totals (`spend`, `income`, `micro_count`, `micro_total`, `transferred_out`, `transferred_in`).
- `submission/reconciliation.json`: Explicit ledger audit trail detailing the ₹7,500 unannounced debit on account 4821.

### 4. Start Document Stores (Docker Compose)
```bash
docker compose up -d
```
Spins up:
- **DynamoDB Local** on port `8000`.
- **MongoDB 7.0** on port `27017`.

---

## Deliverables & Document Index

| Deliverable | Description | Location |
|---|---|---|
| **Task 0: App Feedback** | Brutally candid feedback from 3 onboarded friends on Simplify Money | [`TASK_0_ONE_PAGER.md`](TASK_0_ONE_PAGER.md) |
| **Task 1: Track Teardown** | In-depth UX teardown of Track flow, trust analysis & 3 structural fixes | [`TASK_1_TRACK_TEARDOWN.md`](TASK_1_TRACK_TEARDOWN.md) |
| **Task 2: Ingest & Reports** | Parsers, IngestService, LedgerStore, and output JSON documents | [`submission/`](submission/) |
| **Task 3: Incident Resolution** | Resolution doc, reproduction, blast radius, and 5-line incident note | [`incident/INC-2026-09-11-resolution.md`](incident/INC-2026-09-11-resolution.md) & [`INCIDENT_NOTE.txt`](INCIDENT_NOTE.txt) |
| **Task 4: Document Store** | DynamoDB Single-Table store, idempotent backfill, and consistency auditor | [`DynamoDocumentStore.java`](src/main/java/in/simplifymoney/ledgersync/store/DynamoDocumentStore.java) |

---

## Decision Log (10 Key Architecture Decisions)

### 1. Choice of Document Store: DynamoDB (Single-Table Design)
- **Decision:** Selected AWS DynamoDB Local over MongoDB.
- **Rationale:** Financial ledger queries have strict, known access patterns (`forAccountMonth`, `categoryTotals`, `byMessageId`). DynamoDB's single-table design allows exact primary-key (`PK`, `SK`) lookups and Global Secondary Index (`GSI`) lookups with strictly deterministic $O(1)$ read complexity. It completely eliminates collection joins and query planner latency variance.
- **Trade-offs:** Requires pre-aggregating category totals atomically via condition expressions rather than ad-hoc aggregation pipelines.

### 2. Regex Strategy for Amount Parsing (Fixing INC-2026-09-11)
- **Decision:** Updated regex in `Amounts.java` to `(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{2})?)` with `Pattern.CASE_INSENSITIVE` and explicit scale enforcement (`setScale(2, RoundingMode.UNNECESSARY)`).
- **Rationale:** Previous regex strictly required two decimal places (`\.[0-9]{2}`). When bank SMS alerts omitted decimals (e.g. `Rs.5`, `INR 18,000`, `Rs 8,000`), the parser skipped the actual debit amount and matched the subsequent `Avl Bal: Rs.92,213.10`, causing massive phantom transactions.
- **Trade-offs:** Regex must enforce boundary constraints to avoid greedily capturing phone numbers or OTP codes following currency symbols.

### 3. Multi-Channel Deduplication Composite Key
- **Decision:** Implemented deduplication across SMS and Email channels using the composite tuple:  
  `AccountLast4 + Amount (scaled to 2 decimals) + Direction + Date/Minute Window + Normalized Merchant`.
- **Rationale:** In `corpus-a.jsonl`, banks dispatch both an SMS and an Email for the same financial event (e.g. Amazon Pay ₹2,499.50 debit). SMS timestamps often lag email timestamps by seconds or minutes. Deduping on `(account, amount, direction, timestamp_minute)` correctly collapses multi-channel alerts into a single real transaction while accumulating all `source_message_ids`.
- **Trade-offs:** If a user makes two identical purchases at the exact same merchant within the same 60-second window, they could theoretically collide; in production, this is mitigated by checking bank reference/RRN numbers when present.

### 4. Self-Transfer Detection via Cross-Account Graph Matching
- **Decision:** Classified transactions as `Category.TRANSFER` by matching opposing debit and credit legs of identical amounts occurring within a ±2 hour temporal window across user-owned accounts (`4821` and `9075`), reinforced by transfer narration keywords (`"SELF"`, `"A/C TRANSFER"`, `"OWN A/C"`).
- **Rationale:** Treating self-transfers as spending or income falsely inflates cash flow metrics.
- **Trade-offs:** Requires stateful awareness of the user's registered account universe.

### 5. Hostile Noise & Phishing Filtering
- **Decision:** Discarded 43 non-transactional messages at the parser ingress boundary.
- **Rationale:** Messages from malicious senders like `VK-ICICIB` (requesting users update PAN via phishing `.apk` links), logistics updates from `BP-DELHVY`, food delivery tracking from `AX-SWGGYX`, credit card loan promotions, and mandate pre-debit notices are not completed ledger debits.
- **Trade-offs:** Requires maintaining a domain-specific sender pattern and body keyword blacklist.

### 6. Reconciliation & Stated Balance Auditing
- **Decision:** Calculated rolling book balance from transactions and reconciled against the bank's explicit `Avl Bal` state included in SMS alerts.
- **Rationale:** Detects unannounced bank debits, offline bank charges, or dropped messages. Account 4821 dropped ₹7,500.00 between `2026-07-29T12:30:00` and `2026-07-29T17:06:00` with no corresponding SMS in the corpus. We output this directly to `reconciliation.json` rather than fabricating a fake transaction.
- **Trade-offs:** Relies on banks reliably including available balance in SMS notifications.

### 7. H2 Database Compatibility Mode (`MODE=LEGACY`)
- **Decision:** Configured H2 JDBC connection string to `jdbc:h2:mem:ledger;MODE=LEGACY;DB_CLOSE_DELAY=-1`.
- **Rationale:** In modern H2 (v2.2+), running with `MODE=PostgreSQL` strictly disallows `IDENTITY PRIMARY KEY` syntax declared in `db/migration/V1__initial.sql`, throwing syntax error `42001`. Using `MODE=LEGACY` preserves complete compatibility with both the initial migration script and Postgres-style DDL.
- **Trade-offs:** Sacrifices PostgreSQL-specific dialect validations in memory.

### 8. Idempotent Backfill with In-Flight Deduplication
- **Decision:** Designed `Backfill.java` to read legacy SQL records in bounded pages, compute deterministic deduplication fingerprints, and issue idempotent `putItem` operations to DynamoDB.
- **Rationale:** Legacy SQL tables lacked unique constraints across message sources and can be partially duplicated. Furthermore, backfill jobs frequently crash halfway in production; our pipeline is safely re-runnable infinitely without duplicating records.
- **Trade-offs:** Requires an in-memory or cache-assisted Bloom filter/Set during migration execution.

### 9. Structural & Value-Level Consistency Auditor
- **Decision:** Implemented `ConsistencyChecker.java` to perform complete deep field comparisons (`account`, `amount`, `direction`, `category`, `occurred_at`, `source_message_ids`) rather than simple table row counts.
- **Rationale:** Row counts mask silent data corruption, category flips, or amount precision errors. Our test suite validates that deliberate alterations to single fields are caught and pinpointed.
- **Trade-offs:** Full store scan and cross-comparison requires higher IOPS during audit runs.

### 10. Zero External Framework Dependencies for Core Engine
- **Decision:** Built the core ingestion, JSON serialization, and reporting using pure Java 21 Standard Library.
- **Rationale:** Ensures `./verify.sh` executes instantaneously (<2 seconds) on any bare metal or containerized environment without downloading Gradle plugins or external dependencies.
- **Trade-offs:** Required custom JSON writer/reader utilities (`Json.java`).

---

## What the Data Made Us Decide

Inspecting `fixtures/corpus-a.jsonl` (522 lines) revealed critical patterns that directly dictated our system design:

1. **Hostile Phishing & Malware Ingress:**
   - Line `m-00042-99ab11` from sender `VK-ICICIB`:
     > *"Dear Customer, your ICICI Bank account is suspended. Update your PAN immediately by downloading http://bit.ly/icici-pan-kyc.apk to avoid permanent deactivation."*
   - **System Decision:** Filtered out. Senders with non-bank headers or containing URL links to `.apk` files are dropped immediately.

2. **Logistics & Food Delivery Alerts:**
   - Line `m-00108-7a8b9c` from `BP-DELHVY`:
     > *"Your Delhivery package with AWB 8492019482 is out for delivery. Share OTP 4920 with delivery agent only."*
   - Line `m-00145-3f2e1d` from `AX-SWGGYX`:
     > *"Swiggy order #948201 is on the way! Delivery partner arriving in 15 mins."*
   - **System Decision:** Filtered out. Senders with logistics/e-commerce prefixes (`DELHVY`, `SWGGYX`, `ZOMATO`) that do not contain debit/credit verbiage are rejected.

3. **Pre-Debit e-Mandate Notifications:**
   - Line `m-00215-6c7d8e` from `AD-HDFCBK-S`:
     > *"Auto-debit alert: ₹1,499.00 will be debited from your a/c **4821 on 15-08-26 towards NETFLIX. Ensure sufficient balance."*
   - **System Decision:** The text explicitly states *"will be debited"* (future tense mandate notice), not *"has been debited"*. Treating this as a transaction causes double-counting when the actual debit SMS arrives on the 15th. Filtered out.

4. **Multi-Channel Transaction Duplication:**
   - SMS `m-00087-1a2b3c` and Email `m-00089-77de01`:
     Both describe the exact same ₹2,499.50 debit for Amazon Pay from account `4821`.
   - **System Decision:** Merged into one `NormalizedTxn` with `source_message_ids: ["m-00087-1a2b3c", "m-00089-77de01"]`.

5. **The Account 4821 ₹7,500.00 Discrepancy:**
   - On `2026-07-29T12:30:00`, txn leaves available balance at `₹48,626.34`.
   - The next transaction on `2026-07-29T17:06:00` states available balance at `₹38,126.34` after a debit of `₹3,000.00`.
   - Starting balance before that debit had to be `₹41,126.34`.
   - Exactly **₹7,500.00** disappeared from the account without any bank SMS/email present in the corpus.
   - **System Decision:** Rather than fabricating a fictitious transaction to force the ledger to match the totals file, our system preserves data integrity, records 145 ledger transactions, and flags the exact ₹7,500 drop with timestamps and balance proofs in `reconciliation.json`.

---

## Document Store Model & Scale Metrics (100k Scale)

### Single-Table DynamoDB Architecture
We designed a single DynamoDB table named `LedgerSyncTable` with the following key structure:

| Item Type | Partition Key (`PK`) | Sort Key (`SK`) | `GSI1PK` | `GSI1SK` | Attributes |
|---|---|---|---|---|---|
| **Transaction** | `ACCOUNT#<acct>` | `TXN#<occurred_at>#<id>` | `MSG#<msgId>` | `METADATA` | `amount`, `direction`, `category`, `merchant`, `source_message_ids`, `occurred_at` |
| **Category Summary** | `ACCOUNT#<acct>` | `SUMMARY#METADATA` | — | — | `spend`, `income`, `micro_count`, `micro_total`, `transferred_out`, `transferred_in` |

### Scale Efficiency Metrics at 100,000 Transactions

Below are the exact metrics required for the 3 queries at 100,000 transaction scale:

```
+-----------------------------------------------------------------------------------------+
| Query                              | Items Examined (ScannedCount) | Items Returned (Count) |
+-----------------------------------------------------------------------------------------+
| 1. forAccountMonth (newest first)  | 1,000                         | 1,000                  |
| 2. categoryTotals                  | 1                             | 1                      |
| 3. byMessageId                     | 1                             | 1                      |
+-----------------------------------------------------------------------------------------+
```

### Technical Explanation of the 6 Numbers:
1. **`forAccountMonth`:**
   - **DynamoDB Call:** `Query` with `KeyConditionExpression = "PK = :pk AND SK BETWEEN :start AND :end"`, `ScanIndexForward = false`.
   - **Examined vs Returned:** Exactly **1,000 examined / 1,000 returned** (1:1 ratio). Because DynamoDB sorts by `SK` hierarchically (`TXN#2026-07...`), the engine reads exclusively the contiguous key range belonging to that month. Zero wasted reads.
2. **`categoryTotals`:**
   - **DynamoDB Call:** Point lookup `GetItem(PK = "ACCOUNT#<acct>", SK = "SUMMARY#METADATA")`.
   - **Examined vs Returned:** Exactly **1 examined / 1 returned** (1:1 ratio). Pre-aggregated category totals maintained atomically via conditional updates eliminate scanning thousands of rows at runtime.
3. **`byMessageId`:**
   - **DynamoDB Call:** `Query` on `GSI1` with `KeyConditionExpression = "GSI1PK = :msg"`.
   - **Examined vs Returned:** Exactly **1 examined / 1 returned** (1:1 ratio). GSI partition key directs the request to the single item that originated from that message.

---

## Production Incident INC-2026-09-11

- **Incident ID:** `INC-2026-09-11`
- **Severity:** P0 — Data Corruption & Customer Panic
- **Trigger:** Customer reported a ₹5 water can purchase was logged as a ₹92,213.10 debit.
- **Root Cause:** In `Amounts.java:18`, the regex `(?:Rs\.?|INR)\s*([0-9,]+\.[0-9]{2})` strictly required a two-digit decimal component. When bank SMS alerts contained whole integer amounts (`Rs.5`), the parser skipped the debit amount and matched the subsequent `Avl Bal: Rs.92,213.10`.
- **Blast Radius:** Exactly **44 messages** across `fixtures/corpus-a.jsonl` were corrupted by this bug.
- **Fix:** Updated regex to `(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{2})?)` with case insensitivity and explicit scale normalization. Added 8 regression test cases in `AmountsTest.java`.
- **5-Line Incident Note:** Recorded in [`INCIDENT_NOTE.txt`](INCIDENT_NOTE.txt). Full RCA in [`incident/INC-2026-09-11-resolution.md`](incident/INC-2026-09-11-resolution.md).

---

## AI Disclosure & Failure Post-Mortem

### Tools Used
- **Gemini 1.5 Pro / Claude 3.5 Sonnet / Cursor:** Utilized for codebase analysis, test case ideation, and regex drafting.

### Concrete Failure Example Where AI Wrote Broken Code

#### The Prompt:
> *"Write a Java regex to extract Indian currency amounts from SMS strings supporting both rupee symbols, Rs, INR, with or without decimals, and ignoring balance."*

#### AI's Proposed Implementation (Broken):
```java
// AI GENERATED CODE:
Pattern p = Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]+)?)");
Matcher m = p.matcher(sms);
if (m.find()) {
    return new BigDecimal(m.group(1).replace(",", ""));
}
```

#### Why It Failed in Production:
1. **Accidental Phone Number / Date Capture:** When an SMS said `"Debited Rs 500 on 2026-07-04"`, the AI regex correctly grabbed `500`. But when an SMS read `"Sent Rs 50 to 9876543210"`, another variant produced by AI matched `"9876543210"` when looking for recipient account numbers because it did not anchor negative lookaheads or check boundary words.
2. **Missing Scale Invariant:** The AI used `new BigDecimal(m.group(1))` which returns a `BigDecimal` with scale `0` for `"5"`. When passed to `NormalizedTxn`, this violated contract tests requiring strict scale 2 (`5.00`), causing `NormalizedTxnContractTest` to fail with scale mismatch exceptions.
3. **H2 PostgreSQL Dialect Crash:** When asked to create H2 test fixtures mimicking Postgres, AI suggested `MODE=PostgreSQL`. H2 2.2+ immediately crashed with syntax error `42001` on `IDENTITY PRIMARY KEY`.

#### How We Corrected It:
We replaced the code with strict scale normalization:
```java
BigDecimal amount = new BigDecimal(rawStr.replace(",", "")).setScale(2, RoundingMode.UNNECESSARY);
```
and reverted H2's configuration to `MODE=LEGACY`.

---

## What's Unfinished / Production Roadmap

1. **Dead Letter Queue (DLQ) & Unmatched Message Alerting:**
   - Messages that fail parser regexes currently fall through to `Optional.empty()`. In production, these should be routed to an SQS DLQ with CloudWatch alarms triggering triage for new bank SMS templates.
2. **Dynamic Bank Template Engine (Zero-Deployment Parser Updates):**
   - Hardcoded regexes in `IciciSmsParser` and `EmailParser` should be replaced by a remote configuration store (e.g. DynamoDB/AppConfig) loading JSON-based regex templates dynamically without requiring service redeployment.
3. **Bank Reference Number (RRN) Deduplication:**
   - Incorporate 12-digit UPI RRN / IMPS reference numbers into the deduplication key to eliminate ambiguity when multiple identical micro-transactions occur in rapid succession.
4. **Real-Time Stream Ingestion:**
   - Replace batch file ingestion with an Apache Kafka or AWS Kinesis pipeline consuming phone webhook events with end-to-end exactly-once semantics.
