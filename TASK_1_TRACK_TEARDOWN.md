# Task 1: Simplify Money Track Flow Teardown

**Author:** Anil Kumar Banoth  
**Component Analyzed:** Simplify Money "Track" Engine & UI  
**Scope:** Permission Flow $\to$ Ingest/Sync Pipeline $\to$ Transaction Ledger $\to$ Reconciliation  

---

## 1. Stage-by-Stage Flow Analysis

### Stage 1: The Permission Request
- **Screen & UX:**
  - Upon tapping "Connect Bank" or "Enable Auto-Track", the app displays a preliminary screen highlighting bank-grade security (256-bit encryption, read-only access).
  - Immediately followed by the Android system runtime permission: `Allow Simplify Money to view and manage SMS messages?`
- **UX Critique:**
  - The transition from the branded trust screen to the OS permission is abrupt.
  - The OS prompt says "view and manage" which sounds intrusive to non-technical users (users worry that an app can send or delete SMS).
  - *Recommendation:* Show a visual interactive card clarifying that OTPs, personal chats, and passwords are never read or uploaded, and that only sender prefixes starting with bank shortcodes (e.g., `AD-`, `VM-`, `VK-`) are inspected.

### Stage 2: Ingestion & Message Synchronization
- **System Behavior:**
  - The app begins scanning the local SMS inbox.
  - A loading spinner / progress bar displays: *"Scanning financial transactions... 120 found"*.
  - Background worker batches parsed records and initiates sync with the backend.
- **Latency & Reliability:**
  - Processing ~500 local messages takes approximately 1.5–2.5 seconds on a modern Android device.
  - If network connectivity drops mid-sync, the client occasionally retries the entire batch rather than maintaining an incremental sync checkpoint (`last_synced_message_id`), leading to duplicate payload uploads to the backend.

### Stage 3: The Transaction Feed & Ledger
- **Feed Presentation:**
  - Clean, reverse-chronological list grouped by date (Today, Yesterday, Last Week).
  - Accounts toggle at the top (All Accounts, HDFC **4821, ICICI **9075, Credit Card **3310).
  - Daily micro-spends are rolled up into an expandable tile: *"12 micro spends totaling ₹342.50"*.
  - Transfer indicators show a bi-directional arrow for internal movements.

---

## 2. Real-World Discrepancies & Edge Cases Identified

### Discrepancy 1: Integer Amount Truncation & Available Balance Capture (The INC-2026-09-11 Bug)
- **Raw Message:**
  ```text
  Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161
  ```
- **Track Screen Display:**
  - **Displayed Spend:** ₹92,213.10 (Categorized as SPEND)
  - **Actual Transaction:** ₹5.00
- **What Went Wrong:**
  - The regex parser expected `\.[0-9]{2}`. Because `Rs.5` lacked a decimal paisa fraction, the parser skipped past `Rs.5` and captured the next numeric figure preceded by `Rs.`, which was the available balance `Rs.92,213.10`.
  - The user's monthly spend graph instantly skyrocketed by ~₹92k, completely destroying trust in the ledger.

### Discrepancy 2: Phishing & Threat SMS Ingestion (Hostile SMS)
- **Raw Message:**
  ```text
  Dear Customer your ICICI netbanking will be suspended today. Verify PAN immediately at icicibank-secure.co/152459 to avoid debit of Rs.5126.00
  ```
- **Track Screen Display:**
  - Captured as a debit/spend of ₹5,126.00 against merchant `icicibank-secure.co`.
- **What Went Wrong:**
  - The sender shortcode `VK-ICICIB` was not validated against trusted bank routing alphanumeric headers.
  - The parser searched for keywords like `debit of Rs.` without ensuring that an actual account settlement occurred (absence of `debited from a/c`, `Avl Bal`, or valid transaction reference).
  - Phishing scare alerts mentioning hypothetical penalty debits must be blacklisted and discarded.

### Discrepancy 3: Ghost Balance Drop / Missing SMS Alert
- **Observation on Account 4821:**
  - At `2026-07-29T17:06:00+05:30`, the user paid ₹75 for stationery. The previous balance was ₹36,054.05.
  - The bank SMS arrived quoting: `Avl Bal: Rs.28,479.05`.
  - Math: $36,054.05 - 75.00 = 35,979.05$. But bank stated balance is $28,479.05$.
  - A difference of **₹7,500.00** vanished with zero corresponding alert messages!
- **System Failure:**
  - The app silently accepted the new balance without flagging that ₹7,500 disappeared unaccounted for between two sequential transactions.
  - *Resolution:* Track must generate a reconciliation alert: *"₹7,500 unexplained discrepancy detected between stated bank balance and recorded transactions"*.

---

## 3. Trust Evaluation: Where Did I Trust It? Where Did I Not?

### Where I Trusted It:
1. **Recurring Subscriptions & Standard Spends:**
   - Transactions with clear merchants and two decimals (e.g. Swiggy `₹449.50`, Netflix `₹649.00`, Amazon Pay `₹2,499.50`) were extracted with 100% precision.
2. **Micro-Spends Grouping:**
   - Grouping ₹10 chai, ₹20 vegetable vendor, and ₹15 xerox into a single daily rollup kept the timeline clean and prevented notification fatigue.
3. **Multi-Source Deduplication:**
   - When both an SMS and an email alert arrived for the same credit card spend (e.g. `m-00087-1a2b3c` and `m-00089-77de01`), the engine merged them into a single row rather than double-counting.

### Where I Did NOT Trust It:
1. **Financial Balance Accuracy When Integer Figures Appear:**
   - If a ₹5 water can or ₹18,000 salary is mistaken for an available balance, user net-worth calculations become useless.
2. **Internal Self-Transfers:**
   - Without a bilateral reconciliation engine, moving ₹25,000 between personal accounts momentarily appears as ₹25,000 lost and ₹25,000 earned, distorting savings rates.
3. **Lack of Stated vs Calculated Balance Audit:**
   - The app failed to alert the user when bank stated balances drifted from the mathematical ledger sum.

---

## 4. Three High-Impact Product Changes I Would Make

### 1. Two-Phase Balance Verification & Reconciliation Banner
- **Problem:** Currently, when an SMS is dropped by the telecom provider (like the ₹7,500 gap on account 4821), the ledger has an invisible hole.
- **Change:** Maintain a continuous delta check: `previous_stated_bal - current_txn_amount == current_stated_bal`.
- **Implementation:** If a divergence $> 0$ occurs, highlight a warning banner in Track: *"We noticed a ₹7,500 difference on your HDFC account that wasn't received via SMS. Did you make an ATM withdrawal or cheque payment?"* with a 1-tap button to add the missing entry.

### 2. Client-Side Regex Pre-Filter & Bank Sender Whitelist
- **Problem:** Malicious phishing SMS (`VK-ICICIB`) and promotional loan advertisements (`BP-DELHVY`, `AX-SWGGYX`, personal loan offers) pollute the ingest pipeline.
- **Change:** Implement strict sender domain allowlisting (e.g., Telecom Regulatory Authority of India / DLT headers registered to licensed banking entities: `AD-HDFCBK`, `VM-ICICIB`) and verify transaction intent (must contain settlement confirmation verbs, not conditional threats like *"to avoid debit of"* or *"will be deducted"*).

### 3. Smart Merchant Entity Resolution & Categorization Engine
- **Problem:** Raw SMS strings like `UPI/P2A/PARAG KAPOOR` or `UPI/WATER CAN` look unpolished and confuse users.
- **Change:** Normalize merchant names using a dictionary and pattern matcher:
  - Detect user's own name across linked accounts to automatically classify as `TRANSFER`.
  - Strip gateway prefixes (`UPI/`, `IMPS/P2A/`, `NEFT INWARD`) and map to human-friendly categories and merchant logos.
