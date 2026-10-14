package com.sahilkalgutkar.txrace.zoo;

import static com.sahilkalgutkar.txrace.zoo.TrustPlatform.NOW;

import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Two refreshes of one token at once, through trust-platform's refresh grant. As it was, the grant
 * read the token, rejected it if it was consumed or revoked, issued a new one, and left marking it
 * consumed to Hibernate's flush at commit. Two refreshes that both read the token before either
 * commits both pass the check, and the family ends up with two live tokens, the state reuse
 * detection exists to prevent. The fix consumes the token with a conditional update straight
 * after the check, and treats an update that changed nothing as a replay.
 */
final class RefreshTokenReuse implements Specimen {

    static final UUID PRESENTED = UUID.fromString("70ce0000-0000-0000-0000-000000000001");
    static final UUID FAMILY = UUID.fromString("fa000000-0000-0000-0000-000000000001");
    static final String HASH = "presented";

    @Override
    public void create(Statement statement) throws SQLException {
        TrustPlatform.create(statement);
    }

    static void issued(DataSource database) throws SQLException {
        TrustPlatform.clear(database);
        try (Connection connection = database.getConnection()) {
            TrustPlatform.insertToken(connection, PRESENTED, HASH, FAMILY);
        }
    }

    @Override
    public Object observe(DataSource database) throws SQLException {
        return TrustPlatform.tokens(database);
    }

    private static Map<String, Object> presented(Connection connection) throws SQLException {
        Map<String, Object> token = TrustPlatform.findTokenByHash(connection, HASH);
        if (token.get("consumed_at") != null || token.get("revoked_at") != null) {
            throw new TrustPlatform.Replay();
        }
        return token;
    }

    private static String rotate(Connection connection, int transaction) throws SQLException {
        UUID rotated = TrustPlatform.issuedBy(transaction);
        String hash = "rotated by " + transaction;
        TrustPlatform.findTokenById(connection, rotated);
        TrustPlatform.insertToken(connection, rotated, hash, FAMILY);
        return hash;
    }

    /** The grant as it was: the consumption is flushed at commit, after the new token's insert. */
    static Transaction consumeAtFlush(int transaction) {
        return connection -> {
            Map<String, Object> token = presented(connection);
            token.put("consumed_at", Timestamp.from(NOW));
            String rotated = rotate(connection, transaction);
            TrustPlatform.updateToken(connection, token);
            return rotated;
        };
    }

    static Transaction consumeIfUnused(int transaction) {
        return connection -> {
            presented(connection);
            if (TrustPlatform.consumeTokenIfUnused(connection, PRESENTED) == 0) {
                throw new TrustPlatform.Replay();
            }
            return rotate(connection, transaction);
        };
    }

    @Override
    public Twin buggy() {
        return new Twin(RefreshTokenReuse::issued,
                List.of(consumeAtFlush(1), consumeAtFlush(2)));
    }

    @Override
    public Twin fixed() {
        return new Twin(RefreshTokenReuse::issued,
                List.of(consumeIfUnused(1), consumeIfUnused(2)));
    }
}
