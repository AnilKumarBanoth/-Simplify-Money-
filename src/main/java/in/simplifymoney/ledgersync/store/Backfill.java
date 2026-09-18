package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Handles:
 *  - SQL store dirtiness: deduplicates unconstrained duplicate rows and merges message citations.
 *  - Idempotency & Partial Failures: checks existing entries in the target document store
 *    so it can be re-run safely without creating duplicate items or inflating category totals.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> sqlRows = source.all();
        long read = sqlRows.size();

        // Group and deduplicate raw SQL rows by transaction key
        Map<TxnKey, CanonicalCandidate> canonicalMap = new LinkedHashMap<>();
        for (NormalizedTxn row : sqlRows) {
            TxnKey key = new TxnKey(row.accountLast4(), row.occurredAt(), row.direction(), row.amount());
            canonicalMap.computeIfAbsent(key, k -> new CanonicalCandidate(row)).addEvidence(row.sourceMessageIds());
        }

        long written = 0;
        long skipped = 0;

        for (CanonicalCandidate candidate : canonicalMap.values()) {
            NormalizedTxn txn = candidate.toTxn();

            // Check if this transaction is already present in document store
            boolean alreadyPresent = false;
            for (String msgId : txn.sourceMessageIds()) {
                if (target.byMessageId(msgId).isPresent()) {
                    alreadyPresent = true;
                    break;
                }
            }

            if (alreadyPresent) {
                skipped += candidate.occurrenceCount;
            } else {
                target.save(txn);
                written += 1;
                skipped += (candidate.occurrenceCount - 1);
            }
        }

        return new Result(read, written, skipped);
    }

    private record TxnKey(String accountLast4, OffsetDateTime occurredAt, Direction direction, BigDecimal amount) {
        TxnKey {
            amount = amount.setScale(2);
        }
    }

    private static final class CanonicalCandidate {
        private final NormalizedTxn prototype;
        private final Set<String> messageIds = new TreeSet<>();
        private long occurrenceCount = 0;

        CanonicalCandidate(NormalizedTxn proto) {
            this.prototype = proto;
        }

        void addEvidence(List<String> ids) {
            this.messageIds.addAll(ids);
            this.occurrenceCount++;
        }

        NormalizedTxn toTxn() {
            return new NormalizedTxn(
                    prototype.accountLast4(),
                    prototype.occurredAt(),
                    prototype.direction(),
                    prototype.amount(),
                    prototype.category(),
                    prototype.merchant(),
                    List.copyOf(messageIds));
        }
    }

    public record Result(long read, long written, long skipped) {}
}
