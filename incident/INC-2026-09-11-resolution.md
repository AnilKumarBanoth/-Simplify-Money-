# Incident INC-2026-09-11 Resolution & Analysis

## Incident Summary

- **Incident:** INC-2026-09-11
- **Component:** `in.simplifymoney.ledgersync.parse.Amounts`
- **Root Cause File & Line:** `src/main/java/in/simplifymoney/ledgersync/parse/Amounts.java:18`
- **Severity:** S2 — Financial Data Corruption
- **Status:** RESOLVED

---

## Five-Line Incident Note (For Incident Channel)

1. **What broke:** `Amounts.AMOUNT` regex strictly mandated two decimal places (`\.[0-9]{2}`), causing integer amounts like `Rs.5` to be ignored and extracting the subsequent available balance (`Rs.92,213.10`) as the spend.
2. **How we found it:** Traced message `m-00004-9c11ae` in `incident/app.log` where amount extraction jumped from `Rs.5` to `92213.10`, causing a ₹1,254,130.19 balance divergence alert on account `**4821`.
3. **Who was affected:** 44 messages across `fixtures/corpus-a.jsonl` where transactions lacked explicit decimal paisa (e.g., `Rs.5`, `INR 18,000`, `Rs 8,000`, `Rs.25`), corrupting user balances and category totals.
4. **Fix applied:** Updated `Amounts.AMOUNT` and `BALANCE` regex to `(?:Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{2})?)` (case-insensitive) and enforced scale 2 via `BigDecimal.setScale(2)`.
5. **Why it cannot recur:** Added unit regression tests in `AmountsTest` guarding against `Rs.5` water can, `INR 18,000` comma-separated values, and space prefixes; build pipeline blocks any decimal omission regressions.

---

## Technical Details

### 1. Root Cause
In `Amounts.java`, line 18:
```java
private static final Pattern AMOUNT =
        Pattern.compile("(?:Rs\\.?|INR)\\s*([0-9,]+\\.[0-9]{2})");
```
This regex required `.[0-9]{2}`. When an SMS or email contained an integer rupee amount such as:
`"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10."`
The `AMOUNT` pattern failed to match `"Rs.5"`. It continued scanning the string until it encountered the next currency prefix: `"Rs.92,213.10"` in `"Avl Bal: Rs.92,213.10"`.
Consequently, the service recorded a debit of ₹92,213.10 instead of ₹5.00!

### 2. Blast Radius Rule
- **Rule:** Any transaction message where the transaction amount is formatted without two decimal places (`.[0-9]{2}`) is affected.
- **Count in `fixtures/corpus-a.jsonl`:** Exactly **44 messages**.

### 3. Why Existing Tests Were Green
The preexisting test suite in `AmountsTest.java` only tested inputs with two explicit decimal places:
- `"Rs.2,499.50"`
- `"INR 333.33"`
- `"Rs.45,000.00"`
No test in the suite ever tested an amount without `.xx` (e.g. `Rs.5` or `INR 18,000`), leaving the defect undetected in CI.
