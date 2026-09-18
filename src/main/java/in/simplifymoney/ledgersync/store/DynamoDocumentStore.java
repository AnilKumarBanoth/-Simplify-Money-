package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Production implementation of DocumentStore modeled after DynamoDB Single-Table Design.
 *
 * Single-Table Schema:
 *   Table: LedgerSync
 *   Partition Key (PK): String
 *   Sort Key (SK): String
 *   Global Secondary Index: GSI1 (PK: GSI1PK, SK: GSI1SK)
 *
 * Entities:
 *   1. Transaction Item:
 *      PK: ACCOUNT#<accountLast4>
 *      SK: TXN#<occurredAt_ISO>#<firstMsgId>
 *      GSI1PK: MSG#<messageId> (for each message id in sourceMessageIds)
 *      GSI1SK: TXN#<occurredAt_ISO>
 *
 *   2. Account Category Running Totals Metadata Item:
 *      PK: ACCOUNT#<accountLast4>
 *      SK: METADATA
 *      Attribute 'totals': Map<Category, BigDecimal> maintained atomically
 *
 * Efficiency metrics at 100,000 transactions:
 *   Q1 forAccountMonth: ScannedCount = Count = N (exact range match on PK + SK between start and end)
 *   Q2 categoryTotals:  ScannedCount = Count = 1 (direct point lookup of METADATA item)
 *   Q3 byMessageId:     ScannedCount = Count = 1 (GSI1 index lookup by message id)
 */
public class DynamoDocumentStore implements DocumentStore {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    // Primary table storage: PK -> (SK -> DocumentItem)
    private final Map<String, ConcurrentSkipListMap<String, DocumentItem>> table = new ConcurrentHashMap<>();

    // GSI1 index storage: GSI1PK -> DocumentItem
    private final Map<String, DocumentItem> gsi1Index = new ConcurrentHashMap<>();

    // Query examination counters for verification and reporting
    private long lastScannedCount = 0;
    private long lastCount = 0;

    @Override
    public synchronized void save(NormalizedTxn txn) {
        Objects.requireNonNull(txn, "txn cannot be null");

        String pk = "ACCOUNT#" + txn.accountLast4();
        String primaryMsgId = txn.sourceMessageIds().isEmpty() ? "none" : txn.sourceMessageIds().get(0);
        String sk = "TXN#" + txn.occurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "#" + primaryMsgId;

        DocumentItem item = new DocumentItem(pk, sk, txn);
        table.computeIfAbsent(pk, k -> new ConcurrentSkipListMap<>()).put(sk, item);

        // Index each message ID in GSI1
        for (String msgId : txn.sourceMessageIds()) {
            gsi1Index.put("MSG#" + msgId, item);
        }

        // Update running totals metadata item atomically
        String metadataSk = "METADATA";
        ConcurrentSkipListMap<String, DocumentItem> accountPartition = table.get(pk);
        DocumentItem metadataItem = accountPartition.get(metadataSk);
        Map<Category, BigDecimal> totals;
        if (metadataItem == null || metadataItem.categoryTotals == null) {
            totals = new EnumMap<>(Category.class);
            for (Category c : Category.values()) {
                totals.put(c, ZERO);
            }
        } else {
            totals = new EnumMap<>(metadataItem.categoryTotals);
        }
        totals.put(txn.category(), totals.get(txn.category()).add(txn.amount()));
        accountPartition.put(metadataSk, new DocumentItem(pk, metadataSk, totals));
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        String pk = "ACCOUNT#" + accountLast4;
        ConcurrentSkipListMap<String, DocumentItem> partition = table.get(pk);
        if (partition == null) {
            lastScannedCount = 0;
            lastCount = 0;
            return List.of();
        }

        // Exact month prefix range in sort key: TXN#YYYY-MM-01 ... TXN#YYYY-MM-31T23:59:59
        String startSk = "TXN#" + month.atDay(1).toString() + "T00:00:00";
        String endSk = "TXN#" + month.atEndOfMonth().toString() + "T23:59:59.999999999Z";

        // Query partition with range condition: SK BETWEEN startSk AND endSk
        // DynamoDB scans only items matching the range condition
        var subMap = partition.subMap(startSk, true, endSk, true);
        List<NormalizedTxn> results = new ArrayList<>();

        long scanned = 0;
        for (DocumentItem item : subMap.values()) {
            scanned++;
            if (item.txn != null) {
                results.add(item.txn);
            }
        }

        // Sort newest first as required by Q1 specification
        results.sort(Comparator.comparing(NormalizedTxn::occurredAt).reversed());

        this.lastScannedCount = scanned;
        this.lastCount = results.size();
        return Collections.unmodifiableList(results);
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        String pk = "ACCOUNT#" + accountLast4;
        ConcurrentSkipListMap<String, DocumentItem> partition = table.get(pk);
        Map<Category, BigDecimal> out = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            out.put(c, ZERO);
        }

        if (partition == null) {
            lastScannedCount = 0;
            lastCount = 0;
            return out;
        }

        // O(1) direct point lookup of METADATA item: PK=ACCOUNT#..., SK=METADATA
        DocumentItem metadataItem = partition.get("METADATA");
        if (metadataItem != null && metadataItem.categoryTotals != null) {
            lastScannedCount = 1;
            lastCount = 1;
            out.putAll(metadataItem.categoryTotals);
            return Collections.unmodifiableMap(out);
        }

        lastScannedCount = 0;
        lastCount = 0;
        return Collections.unmodifiableMap(out);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        String gsi1pk = "MSG#" + messageId;
        // GSI1 lookup
        DocumentItem item = gsi1Index.get(gsi1pk);
        if (item != null && item.txn != null) {
            lastScannedCount = 1;
            lastCount = 1;
            return Optional.of(item.txn);
        }
        lastScannedCount = 0;
        lastCount = 0;
        return Optional.empty();
    }

    public long getLastScannedCount() {
        return lastScannedCount;
    }

    public long getLastCount() {
        return lastCount;
    }

    public long size() {
        return table.values().stream()
                .mapToLong(p -> p.values().stream().filter(i -> i.txn != null).count())
                .sum();
    }

    /** Single-table document container representing either a transaction or metadata record */
    private static final class DocumentItem {
        final String pk;
        final String sk;
        final NormalizedTxn txn;
        final Map<Category, BigDecimal> categoryTotals;

        DocumentItem(String pk, String sk, NormalizedTxn txn) {
            this.pk = pk;
            this.sk = sk;
            this.txn = txn;
            this.categoryTotals = null;
        }

        DocumentItem(String pk, String sk, Map<Category, BigDecimal> totals) {
            this.pk = pk;
            this.sk = sk;
            this.txn = null;
            this.categoryTotals = totals;
        }
    }
}
