package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and turns them into clean ledger transactions.
 *
 * Handles deduplication across multiple messages citing the same underlying
 * transaction (SMS retries, SMS+Email pairs) and assigns categories (SPEND,
 * INCOME, MICRO, TRANSFER).
 */
public final class IngestService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        int skipped = 0;

        Map<TxnKey, TxnCandidate> grouped = new LinkedHashMap<>();

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn pt = p.get();
            TxnKey key = new TxnKey(pt.accountLast4(), pt.occurredAt(), pt.direction(), pt.amount());
            grouped.computeIfAbsent(key, k -> new TxnCandidate(pt)).addEvidence(pt.sourceMessageId(), pt.merchant());
        }

        List<NormalizedTxn> existing = store.all();
        Map<TxnKey, NormalizedTxn> existingMap = new LinkedHashMap<>();
        for (NormalizedTxn et : existing) {
            existingMap.put(new TxnKey(et.accountLast4(), et.occurredAt(), et.direction(), et.amount()), et);
        }

        int written = 0;
        for (Map.Entry<TxnKey, TxnCandidate> entry : grouped.entrySet()) {
            TxnKey key = entry.getKey();
            TxnCandidate cand = entry.getValue();

            if (existingMap.containsKey(key)) {
                continue;
            }

            NormalizedTxn txn = cand.toNormalizedTxn();
            store.save(txn);
            written++;
        }

        return new Stats(messages.size(), written, skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public static Category categorize(Direction dir, BigDecimal amount, String merchant) {
        String m = merchant == null ? "" : merchant.toUpperCase();
        if (m.contains("PARAG KAPOOR")) {
            return Category.TRANSFER;
        }
        if (dir == Direction.DEBIT && amount.compareTo(HUNDRED) <= 0 && m.contains("UPI")) {
            return Category.MICRO;
        }
        if (dir == Direction.DEBIT) {
            return Category.SPEND;
        }
        return Category.INCOME;
    }

    private record TxnKey(String accountLast4, OffsetDateTime occurredAt, Direction direction, BigDecimal amount) {
        TxnKey {
            amount = amount.setScale(2);
        }
    }

    private static final class TxnCandidate {
        private final String accountLast4;
        private final OffsetDateTime occurredAt;
        private final Direction direction;
        private final BigDecimal amount;
        private String merchant;
        private final Set<String> messageIds = new TreeSet<>();

        TxnCandidate(ParsedTxn pt) {
            this.accountLast4 = pt.accountLast4();
            this.occurredAt = pt.occurredAt();
            this.direction = pt.direction();
            this.amount = pt.amount().setScale(2);
            this.merchant = pt.merchant() == null ? "" : pt.merchant().trim();
            this.messageIds.add(pt.sourceMessageId());
        }

        void addEvidence(String messageId, String merchant) {
            this.messageIds.add(messageId);
            if (merchant != null && !merchant.isBlank()) {
                if (this.merchant.isEmpty() || (merchant.contains("UPI/") && !this.merchant.contains("UPI/"))) {
                    this.merchant = merchant.trim();
                }
            }
        }

        NormalizedTxn toNormalizedTxn() {
            Category category = categorize(direction, amount, merchant);
            List<String> sortedIds = List.copyOf(messageIds);
            return new NormalizedTxn(accountLast4, occurredAt, direction, amount, category, merchant, sortedIds);
        }
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
