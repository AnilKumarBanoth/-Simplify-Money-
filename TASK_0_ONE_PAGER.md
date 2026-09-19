# Task 0: Simplify Money App Feedback & User Teardown

**Author:** Anil Kumar Banoth  
**Role:** Backend Software Engineer / Intern Take-Home Submission  
**Date:** September 2026  

---

## 1. Onboarding & Profile Completion
- Downloaded the Simplify Money app, authenticated via mobile number OTP, and completed full user profiling.
- Connected SMS read permissions for automated financial transaction parsing across bank accounts (HDFC Bank Savings, ICICI Bank, and Credit Card).
- Followed official social channels:
  - **LinkedIn:** [Simplify Money](https://www.linkedin.com/company/simplify-money/)
  - **Instagram:** [@simplifymoney.ai](https://www.instagram.com/simplifymoney.ai)
  - **YouTube:** [@simplify_money](https://www.youtube.com/@simplify_money)

---

## 2. Peer Referral & Brutally Honest Feedback Summary
I referred the Simplify Money app to 3 friends (software engineers and daily UPI users) with the instruction: *"Break this, tell me what sucks, and don't be polite."*

### Friend 1: Parikshit (Senior Frontend Engineer, UPI Power User — ~15 txns/day)
- **What worked:**
  - Fast onboarding and modern aesthetic.
  - Loved the automatic classification of daily small UPI spends ("Micro spends" rollup cleans up the feed).
- **What they disliked (Brutally Honest):**
  - **Delayed Sync Latency:** *"When I pay a chaiwala ₹20 via Google Pay, the app doesn't immediately reflect it unless I manually swipe to refresh or wait a few minutes for background workers to wake up."*
  - **Opaque SMS Permissions:** *"The Android permission prompt asks for full SMS access without explaining that OTPs/personal messages are discarded client-side. It triggers immediate privacy skepticism."*
  - **Merchant Name Clutter:** UPI transaction descriptions like `UPI/9849012345@paytm/CHAI` look raw and ugly instead of showing clean vendor branding like "Ramesh Tea Stall".

### Friend 2: Sneha (Product Analyst, Multiple Bank Accounts + 2 Credit Cards)
- **What worked:**
  - Consolidated view of net worth and bank balances across disparate accounts without logging into individual netbanking portals.
- **What they disliked (Brutally Honest):**
  - **Account Transfer Confusion:** *"I transferred ₹15,000 from my HDFC savings to my ICICI savings to pay a credit card bill. The app initially showed it as a ₹15,000 spend on HDFC and ₹15,000 income on ICICI before reconciling it as an internal transfer. For a moment, my monthly spending chart was completely inflated."*
  - **Credit Card Billing vs Real Balance:** *"Credit card available limit fluctuations don't equate to bank account balances. When an e-commerce refund is pending or a card payment is in clearance, the available limit drifts."*
  - **No Manual Edit/Split:** *"If I pay ₹1,800 for dinner on behalf of 3 friends, there is no easy split-bill toggle to mark ₹1,200 as an expected reimbursement rather than personal spend."*

### Friend 3: Rohit (DevOps Engineer, Security-Conscious)
- **What worked:**
  - Clean UI with dark mode; zero banner advertisements or predatory loan spam.
- **What they disliked (Brutally Honest):**
  - **False Positive Alert Ingestion:** *"I received a fake SMS phishing alert saying my ICICI card would be blocked with a fee of ₹4,999. The app caught the amount figure and momentarily showed an unverified alert in my feed."*
  - **Offline Resilience:** *"If I open the app in an elevator or basement parking with spotty cellular connection, screens show infinite skeleton loaders instead of cached local state."*
  - **Notification Overload:** *"I don't need a push notification congratulating me every time I buy a ₹10 packet of biscuits. Daily/weekly digests would be much better."*

---

## 3. Key Takeaways & Product Engineering Action Items
1. **Explain Privacy First:** Introduce a pre-permission modal demonstrating local regex redaction before prompting for Android `READ_SMS`.
2. **Atomic Internal Transfer Matching:** Correlate dual-leg transfers within a 5-minute sliding window before presenting them on the UI feed, preventing momentary spend/income spikes.
3. **Local-First SQLite/Room Caching:** Serve the Track screen from a local database cache immediately on launch, streaming fresh sync deltas in the background.
