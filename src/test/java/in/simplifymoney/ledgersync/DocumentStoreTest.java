package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.DynamoDocumentStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentStoreTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    @Test
    @DisplayName("Q1: returns one account's transactions for one month, newest first")
    void q1ForAccountMonthNewestFirst() {
        DynamoDocumentStore store = new DynamoDocumentStore();

        NormalizedTxn t1 = new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 5, 10, 0, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "M1", List.of("m-1"));
        NormalizedTxn t2 = new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 20, 15, 30, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("250.00"), Category.SPEND, "M2", List.of("m-2"));
        NormalizedTxn t3OtherMonth = new NormalizedTxn("4821", OffsetDateTime.of(2026, 8, 1, 9, 0, 0, 0, IST),
                Direction.CREDIT, new BigDecimal("5000.00"), Category.INCOME, "M3", List.of("m-3"));
        NormalizedTxn t4OtherAccount = new NormalizedTxn("9075", OffsetDateTime.of(2026, 7, 10, 12, 0, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("50.00"), Category.MICRO, "M4", List.of("m-4"));

        store.save(t1);
        store.save(t2);
        store.save(t3OtherMonth);
        store.save(t4OtherAccount);

        List<NormalizedTxn> july4821 = store.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(2, july4821.size());
        // Newest first: t2 (July 20) before t1 (July 5)
        assertEquals("m-2", july4821.get(0).sourceMessageIds().get(0));
        assertEquals("m-1", july4821.get(1).sourceMessageIds().get(0));

        // ScannedCount matches Count
        assertEquals(2, store.getLastScannedCount());
        assertEquals(2, store.getLastCount());
    }

    @Test
    @DisplayName("Q2: running totals per category for an account with O(1) examination")
    void q2RunningCategoryTotals() {
        DynamoDocumentStore store = new DynamoDocumentStore();

        store.save(new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 1, 10, 0, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("500.00"), Category.SPEND, "M1", List.of("m-1")));
        store.save(new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 2, 11, 0, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("75.00"), Category.MICRO, "M2", List.of("m-2")));
        store.save(new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 3, 12, 0, 0, 0, IST),
                Direction.CREDIT, new BigDecimal("2000.00"), Category.INCOME, "M3", List.of("m-3")));
        store.save(new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 4, 13, 0, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("1000.00"), Category.TRANSFER, "M4", List.of("m-4")));

        Map<Category, BigDecimal> totals = store.categoryTotals("4821");
        assertEquals(new BigDecimal("500.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("75.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("2000.00"), totals.get(Category.INCOME));
        assertEquals(new BigDecimal("1000.00"), totals.get(Category.TRANSFER));

        // Directly retrieves pre-calculated METADATA item: ScannedCount = 1, Count = 1
        assertEquals(1, store.getLastScannedCount());
        assertEquals(1, store.getLastCount());
    }

    @Test
    @DisplayName("Q3: retrieves transaction by message ID via GSI")
    void q3ByMessageId() {
        DynamoDocumentStore store = new DynamoDocumentStore();

        NormalizedTxn txn = new NormalizedTxn("4821", OffsetDateTime.of(2026, 7, 4, 20, 24, 0, 0, IST),
                Direction.DEBIT, new BigDecimal("2499.50"), Category.SPEND, "AMAZON PAY",
                List.of("m-00087-1a2b3c", "m-00089-77de01"));
        store.save(txn);

        Optional<NormalizedTxn> byMsg1 = store.byMessageId("m-00087-1a2b3c");
        assertTrue(byMsg1.isPresent());
        assertEquals(txn, byMsg1.get());
        assertEquals(1, store.getLastScannedCount());
        assertEquals(1, store.getLastCount());

        Optional<NormalizedTxn> byMsg2 = store.byMessageId("m-00089-77de01");
        assertTrue(byMsg2.isPresent());
        assertEquals(txn, byMsg2.get());

        Optional<NormalizedTxn> notFound = store.byMessageId("m-nonexistent");
        assertFalse(notFound.isPresent());
    }

    @Test
    @DisplayName("Efficiency benchmark: 100,000 transactions performance")
    void efficiencyBenchmarkAt100kTransactions() {
        DynamoDocumentStore store = new DynamoDocumentStore();

        // Populate 100,000 transactions across 100 accounts (1,000 per account)
        for (int a = 1; a <= 100; a++) {
            String acct = String.format("%04d", a);
            for (int day = 1; day <= 10; day++) {
                // 100 txns per day
                for (int i = 0; i < 100; i++) {
                    NormalizedTxn t = new NormalizedTxn(acct,
                            OffsetDateTime.of(2026, 7, day, 10, i % 60, 0, 0, IST),
                            Direction.DEBIT,
                            new BigDecimal("10.00"),
                            Category.SPEND,
                            "Merchant",
                            List.of("m-" + acct + "-" + day + "-" + i));
                    store.save(t);
                }
            }
        }

        assertEquals(100_000, store.size());

        // Test Q1: query one account's transactions for July 2026
        List<NormalizedTxn> q1Result = store.forAccountMonth("0042", YearMonth.of(2026, 7));
        assertEquals(1000, q1Result.size());
        assertEquals(1000, store.getLastScannedCount(), "ScannedCount must equal Count");
        assertEquals(1000, store.getLastCount());

        // Test Q2: query running category totals for account 0042
        Map<Category, BigDecimal> q2Result = store.categoryTotals("0042");
        assertEquals(new BigDecimal("10000.00"), q2Result.get(Category.SPEND));
        assertEquals(1, store.getLastScannedCount(), "ScannedCount must be 1");
        assertEquals(1, store.getLastCount(), "Count must be 1");

        // Test Q3: query by message ID
        Optional<NormalizedTxn> q3Result = store.byMessageId("m-0042-5-42");
        assertTrue(q3Result.isPresent());
        assertEquals(1, store.getLastScannedCount(), "ScannedCount must be 1");
        assertEquals(1, store.getLastCount(), "Count must be 1");
    }
}
