package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.DynamoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackfillAndConsistencyTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private Path tempDb;
    private SqlLedgerStore sqlStore;

    @BeforeEach
    void setUp() throws IOException {
        tempDb = Files.createTempFile("test_ledger", ".db");
        sqlStore = new SqlLedgerStore(tempDb);
        sqlStore.migrate(Path.of("db", "migration"));
    }

    @AfterEach
    void tearDown() throws IOException {
        sqlStore.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    @DisplayName("Backfill moves legacy rows and deduplicates unconstrained duplicates")
    void backfillDeduplicatesAndIsIdempotent() {
        DynamoDocumentStore docStore = new DynamoDocumentStore();
        Backfill backfill = new Backfill(sqlStore, docStore);

        // First run
        Backfill.Result res1 = backfill.run();
        // V2__seed.sql has 15 rows, but several are duplicate messages for same transactions
        assertTrue(res1.read() >= 15);
        assertTrue(res1.written() > 0);
        assertTrue(res1.skipped() > 0, "Should skip duplicate SQL seed rows");
        assertEquals(res1.read(), res1.written() + res1.skipped());

        // Second run: completely idempotent, 0 new writes
        Backfill.Result res2 = backfill.run();
        assertEquals(res1.read(), res2.read());
        assertEquals(0, res2.written());
        assertEquals(res2.read(), res2.skipped());
    }

    @Test
    @DisplayName("ConsistencyChecker reports no divergences between clean stores")
    void consistencyCheckerAgreesOnCleanStore() {
        DynamoDocumentStore docStore = new DynamoDocumentStore();
        Backfill backfill = new Backfill(sqlStore, docStore);
        backfill.run();

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();
        assertTrue(divergences.isEmpty(), "Expected 0 divergences after backfill, but found: " + divergences);
    }

    @Test
    @DisplayName("ConsistencyChecker catches and names deliberate alterations in DocumentStore")
    void consistencyCheckerCatchesDeliberateAlterations() {
        DynamoDocumentStore docStore = new DynamoDocumentStore();
        Backfill backfill = new Backfill(sqlStore, docStore);
        backfill.run();

        // Deliberate alteration: modify an amount in DocumentStore
        NormalizedTxn altered = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-06-29T09:15+05:30"),
                Direction.CREDIT,
                new BigDecimal("99999.00"), // altered amount
                Category.INCOME,
                "SALARY CREDIT",
                List.of("m-legacy-0011"));
        docStore.save(altered);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertFalse(divergences.isEmpty(), "Checker must detect the deliberate modification");
        boolean caughtAmount = divergences.stream().anyMatch(d -> d.what().contains("Amount mismatch"));
        boolean caughtTotal = divergences.stream().anyMatch(d -> d.what().contains("Category total mismatch"));

        assertTrue(caughtAmount, "Checker must specifically name the amount mismatch");
        assertTrue(caughtTotal, "Checker must specifically name the category total mismatch");
    }
}
