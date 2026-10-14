package com.sahilkalgutkar.txrace.zoo;

import static com.sahilkalgutkar.txrace.zoo.TrustPlatform.NOW;

import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Two exchanges of one authorization code at once, through trust-platform's code grant. The same
 * race as {@link RefreshTokenReuse}, one step earlier in the flow: RFC 6749 says a code works
 * once, and two exchanges that both read it unconsumed both get tokens.
 */
final class CodeReuse implements Specimen {

    static final UUID CODE = UUID.fromString("c0de0000-0000-0000-0000-000000000001");
    static final String HASH = "presented code";

    @Override
    public void create(Statement statement) throws SQLException {
        TrustPlatform.create(statement);
    }

    static void issued(DataSource database) throws SQLException {
        TrustPlatform.clear(database);
        try (Connection connection = database.getConnection()) {
            TrustPlatform.insertCode(connection, CODE, HASH);
        }
    }

    /** Whether the code is consumed, then every refresh token it led to. */
    @Override
    public Object observe(DataSource database) throws SQLException {
        List<String> state = new ArrayList<>();
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement()
                     .executeQuery("SELECT consumed_at IS NOT NULL FROM authorization_codes")) {
            rows.next();
            state.add(rows.getBoolean(1) ? "code consumed" : "code unused");
        }
        state.addAll(TrustPlatform.tokens(database));
        return state;
    }

    private static Map<String, Object> presented(Connection connection) throws SQLException {
        Map<String, Object> code = TrustPlatform.findCodeByHash(connection, HASH);
        if (code.get("consumed_at") != null) {
            throw new TrustPlatform.Replay();
        }
        return code;
    }

    /** The refresh token the exchange issues, in the family named after the code. */
    private static String issue(Connection connection, int transaction) throws SQLException {
        UUID issued = TrustPlatform.issuedBy(transaction);
        String hash = "issued to " + transaction;
        TrustPlatform.findTokenById(connection, issued);
        TrustPlatform.insertToken(connection, issued, hash, CODE);
        return hash;
    }

    static Transaction consumeAtFlush(int transaction) {
        return connection -> {
            Map<String, Object> code = presented(connection);
            code.put("consumed_at", Timestamp.from(NOW));
            String issued = issue(connection, transaction);
            TrustPlatform.updateCode(connection, code);
            return issued;
        };
    }

    static Transaction consumeIfUnused(int transaction) {
        return connection -> {
            presented(connection);
            if (TrustPlatform.consumeCodeIfUnused(connection, CODE) == 0) {
                throw new TrustPlatform.Replay();
            }
            return issue(connection, transaction);
        };
    }

    @Override
    public Twin buggy() {
        return new Twin(CodeReuse::issued, List.of(consumeAtFlush(1), consumeAtFlush(2)));
    }

    @Override
    public Twin fixed() {
        return new Twin(CodeReuse::issued, List.of(consumeIfUnused(1), consumeIfUnused(2)));
    }
}
