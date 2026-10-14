package com.sahilkalgutkar.txrace.zoo;

import static com.sahilkalgutkar.txrace.zoo.TrustPlatform.NOW;

import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * trust-platform revoking a token family, after a replay or a logout, while the family's live
 * token is being refreshed. The revocation used to read the family's tokens and save each one
 * revoked. A refresh in flight has consumed the live token and inserted its replacement without
 * committing, so the read misses the replacement, which stays live in a revoked family, and the
 * save writes the live token back as it was read, undoing its consumption. The fix revokes with
 * an update of {@code revoked_at} alone, repeated until it revokes nothing.
 */
final class FamilyRevocation implements Specimen {

    static final UUID ROTATED_AWAY = UUID.fromString("70ce0000-0000-0000-0000-000000000000");

    @Override
    public void create(Statement statement) throws SQLException {
        TrustPlatform.create(statement);
    }

    /** A family with one token consumed by an earlier refresh, and the live one it was rotated into. */
    static void rotatedOnce(DataSource database) throws SQLException {
        RefreshTokenReuse.issued(database);
        try (Connection connection = database.getConnection()) {
            TrustPlatform.insertToken(connection, ROTATED_AWAY, "rotated away", RefreshTokenReuse.FAMILY);
            try (PreparedStatement consume = connection.prepareStatement(
                    "UPDATE refresh_tokens SET consumed_at = ? WHERE id = ?")) {
                consume.setTimestamp(1, Timestamp.from(NOW));
                consume.setObject(2, ROTATED_AWAY);
                consume.executeUpdate();
            }
        }
    }

    @Override
    public Object observe(DataSource database) throws SQLException {
        return TrustPlatform.tokens(database);
    }

    /** The revocation as it was: every token read, then each saved with its revocation set. */
    static Transaction readThenSave() {
        return connection -> {
            List<Map<String, Object>> family = TrustPlatform.findTokensByFamily(connection, RefreshTokenReuse.FAMILY);
            // The select has no ORDER BY, so PostgreSQL returns the rows as they lie on disk, and that
            // changes once vacuum has freed space. Then the same order of steps saves the tokens in a
            // different order, and the explorer's count of failing orders changed between runs.
            // Hibernate's order isn't defined either; sorting picks one.
            family.sort(Comparator.comparing(token -> (String) token.get("token_hash")));
            for (Map<String, Object> token : family) {
                if (token.get("revoked_at") == null) {
                    token.put("revoked_at", Timestamp.from(NOW));
                    TrustPlatform.updateToken(connection, token);
                }
            }
            return "revoked";
        };
    }

    /**
     * One pass of the fix's update. It waits for the refresh's lock on the live token, but its
     * snapshot was taken before the refresh inserted the new token, so the new token stays live.
     */
    static Transaction revokeOnce() {
        return connection -> {
            TrustPlatform.revokeUnrevoked(connection, RefreshTokenReuse.FAMILY);
            return "revoked";
        };
    }

    static Transaction revokeUntilNoneAreLeft() {
        return connection -> {
            int revoked;
            do {
                revoked = TrustPlatform.revokeUnrevoked(connection, RefreshTokenReuse.FAMILY);
            } while (revoked > 0);
            return "revoked";
        };
    }

    @Override
    public Twin buggy() {
        return new Twin(FamilyRevocation::rotatedOnce, List.of(RefreshTokenReuse.consumeIfUnused(1), readThenSave()));
    }

    @Override
    public Twin fixed() {
        return new Twin(FamilyRevocation::rotatedOnce,
                List.of(RefreshTokenReuse.consumeIfUnused(1), revokeUntilNoneAreLeft()));
    }
}
