package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Parses transaction alert emails from supported banks (HDFC, ICICI).
 */
public final class EmailParser implements MessageParser {

    private static final Pattern DATE_HEADER = Pattern.compile("(?m)^Date:\\s*(?<date>.+)$");
    private static final Pattern ALERT = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with .*?\\n"
                    + "Merchant / Remarks:\\s*(?<merchant>.+?)(?:\\r?\\n)",
            Pattern.DOTALL);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher d = DATE_HEADER.matcher(m.body());
        Matcher a = ALERT.matcher(m.body());
        if (!d.find() || !a.find()) return Optional.empty();

        OffsetDateTime when = Dates.ist(d.group("date"));
        BigDecimal amount = Amounts.first(m.body());
        if (when == null || amount == null) return Optional.empty();

        Direction dir = "debited".equalsIgnoreCase(a.group("dir"))
                ? Direction.DEBIT : Direction.CREDIT;
        String merchant = a.group("merchant").trim();
        BigDecimal balance = Amounts.statedBalance(m.body());

        return Optional.of(new ParsedTxn(
                a.group("acct"),
                when,
                dir,
                amount,
                merchant,
                balance,
                m.messageId()));
    }
}
