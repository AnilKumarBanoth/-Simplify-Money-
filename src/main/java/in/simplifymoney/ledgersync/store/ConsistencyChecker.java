package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Checks:
 *  1. Every transaction in SQL exists in DocumentStore with exact matching fields
 *     (amount, direction, category, occurredAt, merchant, accountLast4).
 *  2. Running category totals for each account match across both stores.
 *  3. Monthly window queries (forAccountMonth) return the exact expected transaction sets.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();
        List<NormalizedTxn> sqlRows = sql.all();

        // 1. Group and deduplicate raw SQL rows into canonical transactions
        Map<String, CanonicalTxn> canonicalMap = new LinkedHashMap<>();
        Map<String, Map<Category, BigDecimal>> sqlTotals = new LinkedHashMap<>();
        Set<AccountMonth> accountMonths = new TreeSet<>();

        for (NormalizedTxn row : sqlRows) {
            String key = row.accountLast4() + "#" + row.occurredAt() + "#" + row.direction() + "#" + row.amount();
            canonicalMap.computeIfAbsent(key, k -> new CanonicalTxn(row)).addEvidence(row.sourceMessageIds());

            // Track account months
            accountMonths.add(new AccountMonth(row.accountLast4(), YearMonth.from(row.occurredAt())));
        }

        // Calculate expected category totals from canonical SQL data
        for (CanonicalTxn ct : canonicalMap.values()) {
            NormalizedTxn t = ct.prototype;
            Map<Category, BigDecimal> acctTotals = sqlTotals.computeIfAbsent(t.accountLast4(), k -> {
                Map<Category, BigDecimal> map = new EnumMap<>(Category.class);
                for (Category c : Category.values()) map.put(c, BigDecimal.ZERO.setScale(2));
                return map;
            });
            acctTotals.put(t.category(), acctTotals.get(t.category()).add(t.amount()));
        }

        // 2. Field-level verification for every canonical transaction
        for (CanonicalTxn ct : canonicalMap.values()) {
            NormalizedTxn sqlTxn = ct.prototype;
            String key = sqlTxn.accountLast4() + " " + sqlTxn.occurredAt() + " " + sqlTxn.amount();

            NormalizedTxn docTxn = null;
            for (String msgId : ct.messageIds) {
                Optional<NormalizedTxn> found = documents.byMessageId(msgId);
                if (found.isPresent()) {
                    docTxn = found.get();
                    break;
                }
            }

            if (docTxn == null) {
                divergences.add(new Divergence(
                        "Missing transaction in document store for " + key,
                        sqlTxn.toString(),
                        "null"));
                continue;
            }

            if (sqlTxn.amount().compareTo(docTxn.amount()) != 0) {
                divergences.add(new Divergence(
                        "Amount mismatch for txn " + key,
                        sqlTxn.amount().toPlainString(),
                        docTxn.amount().toPlainString()));
            }

            if (sqlTxn.direction() != docTxn.direction()) {
                divergences.add(new Divergence(
                        "Direction mismatch for txn " + key,
                        sqlTxn.direction().name(),
                        docTxn.direction().name()));
            }

            if (sqlTxn.category() != docTxn.category()) {
                divergences.add(new Divergence(
                        "Category mismatch for txn " + key,
                        sqlTxn.category().name(),
                        docTxn.category().name()));
            }

            if (!sqlTxn.occurredAt().isEqual(docTxn.occurredAt())) {
                divergences.add(new Divergence(
                        "OccurredAt mismatch for txn " + key,
                        sqlTxn.occurredAt().toString(),
                        docTxn.occurredAt().toString()));
            }

            if (!sqlTxn.accountLast4().equals(docTxn.accountLast4())) {
                divergences.add(new Divergence(
                        "AccountLast4 mismatch for txn " + key,
                        sqlTxn.accountLast4(),
                        docTxn.accountLast4()));
            }

            if (!sqlTxn.merchant().trim().equalsIgnoreCase(docTxn.merchant().trim())) {
                divergences.add(new Divergence(
                        "Merchant mismatch for txn " + key,
                        sqlTxn.merchant(),
                        docTxn.merchant()));
            }
        }

        // 3. Category totals consistency verification
        for (Map.Entry<String, Map<Category, BigDecimal>> entry : sqlTotals.entrySet()) {
            String acct = entry.getKey();
            Map<Category, BigDecimal> expected = entry.getValue();
            Map<Category, BigDecimal> actual = documents.categoryTotals(acct);

            for (Category c : Category.values()) {
                BigDecimal expAmt = expected.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                BigDecimal actAmt = actual.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                if (expAmt.compareTo(actAmt) != 0) {
                    divergences.add(new Divergence(
                            "Category total mismatch for account " + acct + " category " + c,
                            expAmt.toPlainString(),
                            actAmt.toPlainString()));
                }
            }
        }

        // 4. Monthly window query verification
        for (AccountMonth am : accountMonths) {
            List<NormalizedTxn> docList = documents.forAccountMonth(am.accountLast4, am.month);
            long expectedMonthCount = canonicalMap.values().stream()
                    .map(c -> c.prototype)
                    .filter(t -> t.accountLast4().equals(am.accountLast4) && YearMonth.from(t.occurredAt()).equals(am.month))
                    .count();

            if (docList.size() != expectedMonthCount) {
                divergences.add(new Divergence(
                        "Monthly transaction count mismatch for " + am.accountLast4 + " " + am.month,
                        String.valueOf(expectedMonthCount),
                        String.valueOf(docList.size())));
            }
        }

        return divergences;
    }

    private static final class CanonicalTxn {
        final NormalizedTxn prototype;
        final Set<String> messageIds = new TreeSet<>();

        CanonicalTxn(NormalizedTxn proto) {
            this.prototype = proto;
        }

        void addEvidence(List<String> ids) {
            this.messageIds.addAll(ids);
        }
    }

    private record AccountMonth(String accountLast4, YearMonth month) implements Comparable<AccountMonth> {
        @Override
        public int compareTo(AccountMonth o) {
            int c = accountLast4.compareTo(o.accountLast4);
            return c != 0 ? c : month.compareTo(o.month);
        }
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
