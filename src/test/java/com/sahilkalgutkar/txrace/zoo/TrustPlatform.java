package com.sahilkalgutkar.txrace.zoo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The tables and statements of my trust-platform identity service, an OAuth provider whose token
 * endpoint rotates refresh tokens and burns authorization codes on first use. The tables are its
 * Flyway migration without the foreign keys. Every statement is one Hibernate logged for a real
 * grant, with the same text and in the same order, including the select by id that {@code save()}
 * runs for an entity with an assigned id and the update that waits for the flush at commit. The
 * statements a grant runs against other tables (users, signing keys, the audit outbox) are left
 * out, because they never touch these rows.
 */
final class TrustPlatform {

    static final UUID TENANT = UUID.fromString("7e1a0000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("05e40000-0000-0000-0000-000000000001");
    static final String CLIENT = "web-app";
    static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    static final Instant LATER = NOW.plusSeconds(30 * 24 * 3600);

    /** Thrown where the grant rejects a credential that was already used, and revokes its family. */
    static final class Replay extends RuntimeException {
        Replay() {
            super("replay detected", null, false, false);
        }
    }

    private TrustPlatform() {
    }

    static void create(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS refresh_tokens (
                    id           UUID PRIMARY KEY,
                    tenant_id    UUID         NOT NULL,
                    token_hash   VARCHAR(64)  NOT NULL,
                    family_id    UUID         NOT NULL,
                    client_id    VARCHAR(128) NOT NULL,
                    user_id      UUID         NOT NULL,
                    scope        TEXT         NOT NULL,
                    issued_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    expires_at   TIMESTAMPTZ  NOT NULL,
                    consumed_at  TIMESTAMPTZ,
                    revoked_at   TIMESTAMPTZ,
                    CONSTRAINT uq_refresh_tenant_hash UNIQUE (tenant_id, token_hash))""");
        statement.execute("""
                CREATE TABLE IF NOT EXISTS authorization_codes (
                    id                    UUID PRIMARY KEY,
                    tenant_id             UUID         NOT NULL,
                    code_hash             VARCHAR(64)  NOT NULL,
                    client_id             VARCHAR(128) NOT NULL,
                    user_id               UUID         NOT NULL,
                    redirect_uri          TEXT         NOT NULL,
                    scope                 TEXT         NOT NULL,
                    nonce                 VARCHAR(255),
                    code_challenge        VARCHAR(128),
                    code_challenge_method VARCHAR(8),
                    issued_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    expires_at            TIMESTAMPTZ  NOT NULL,
                    consumed_at           TIMESTAMPTZ,
                    CONSTRAINT uq_auth_codes_tenant_hash UNIQUE (tenant_id, code_hash))""");
    }

    static void clear(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM refresh_tokens");
            statement.execute("DELETE FROM authorization_codes");
        }
    }

    /** The id of the token or code transaction n issues, made up from n so every run issues the same. */
    static UUID issuedBy(int transaction) {
        return UUID.fromString("%08d-0000-0000-0000-000000000000".formatted(transaction));
    }

    /** Hibernate's {@code findByTokenHash}, with the tenant predicate it adds twice. */
    static Map<String, Object> findTokenByHash(Connection connection, String hash) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select rte1_0.id,rte1_0.client_id,"
                + "rte1_0.consumed_at,rte1_0.expires_at,rte1_0.family_id,rte1_0.issued_at,rte1_0.revoked_at,"
                + "rte1_0.scope,rte1_0.tenant_id,rte1_0.token_hash,rte1_0.user_id from refresh_tokens rte1_0 "
                + "where rte1_0.tenant_id = ? and rte1_0.tenant_id = ? and rte1_0.token_hash=?")) {
            select.setObject(1, TENANT);
            select.setObject(2, TENANT);
            select.setString(3, hash);
            return onlyRow(select);
        }
    }

    /** The select by id that {@code save()} runs to see whether a new token already exists. */
    static void findTokenById(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select rte1_0.id,rte1_0.client_id,"
                + "rte1_0.consumed_at,rte1_0.expires_at,rte1_0.family_id,rte1_0.issued_at,rte1_0.revoked_at,"
                + "rte1_0.scope,rte1_0.tenant_id,rte1_0.token_hash,rte1_0.user_id from refresh_tokens rte1_0 "
                + "where rte1_0.id=?")) {
            select.setObject(1, id);
            select.executeQuery().close();
        }
    }

    static void insertToken(Connection connection, UUID id, String hash, UUID family) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("insert into refresh_tokens (client_id,"
                + "consumed_at,expires_at,family_id,issued_at,revoked_at,scope,tenant_id,token_hash,user_id,id) "
                + "values (?,?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, CLIENT);
            insert.setTimestamp(2, null);
            insert.setTimestamp(3, Timestamp.from(LATER));
            insert.setObject(4, family);
            insert.setTimestamp(5, Timestamp.from(NOW));
            insert.setTimestamp(6, null);
            insert.setString(7, "openid profile");
            insert.setObject(8, TENANT);
            insert.setString(9, hash);
            insert.setObject(10, USER);
            insert.setObject(11, id);
            insert.executeUpdate();
        }
    }

    /** The update Hibernate flushes for a dirty token: every column, from the values it read. */
    static void updateToken(Connection connection, Map<String, Object> token) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("update refresh_tokens set client_id=?,"
                + "consumed_at=?,expires_at=?,family_id=?,issued_at=?,revoked_at=?,scope=?,token_hash=?,user_id=? "
                + "where id=?")) {
            update.setObject(1, token.get("client_id"));
            update.setObject(2, token.get("consumed_at"));
            update.setObject(3, token.get("expires_at"));
            update.setObject(4, token.get("family_id"));
            update.setObject(5, token.get("issued_at"));
            update.setObject(6, token.get("revoked_at"));
            update.setObject(7, token.get("scope"));
            update.setObject(8, token.get("token_hash"));
            update.setObject(9, token.get("user_id"));
            update.setObject(10, token.get("id"));
            update.executeUpdate();
        }
    }

    /** The conditional consumption Hibernate generates for {@code RefreshTokenRepository.consumeIfUnused}. */
    static int consumeTokenIfUnused(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("update refresh_tokens rte1_0 set consumed_at=? "
                + "where rte1_0.id=? and rte1_0.consumed_at is null and rte1_0.revoked_at is null "
                + "and rte1_0.tenant_id = ? and rte1_0.tenant_id = ?")) {
            update.setTimestamp(1, Timestamp.from(NOW));
            update.setObject(2, id);
            update.setObject(3, TENANT);
            update.setObject(4, TENANT);
            return update.executeUpdate();
        }
    }

    static void insertCode(Connection connection, UUID id, String hash) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("insert into authorization_codes (client_id,"
                + "code_challenge,code_challenge_method,code_hash,consumed_at,expires_at,issued_at,nonce,"
                + "redirect_uri,scope,tenant_id,user_id,id) values (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, CLIENT);
            insert.setString(2, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
            insert.setString(3, "S256");
            insert.setString(4, hash);
            insert.setTimestamp(5, null);
            insert.setTimestamp(6, Timestamp.from(NOW.plusSeconds(60)));
            insert.setTimestamp(7, Timestamp.from(NOW));
            insert.setString(8, null);
            insert.setString(9, "https://app.example.com/callback");
            insert.setString(10, "openid profile");
            insert.setObject(11, TENANT);
            insert.setObject(12, USER);
            insert.setObject(13, id);
            insert.executeUpdate();
        }
    }

    /** Hibernate's {@code findByCodeHash}. */
    static Map<String, Object> findCodeByHash(Connection connection, String hash) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select ace1_0.id,ace1_0.client_id,"
                + "ace1_0.code_challenge,ace1_0.code_challenge_method,ace1_0.code_hash,ace1_0.consumed_at,"
                + "ace1_0.expires_at,ace1_0.issued_at,ace1_0.nonce,ace1_0.redirect_uri,ace1_0.scope,ace1_0.tenant_id,"
                + "ace1_0.user_id from authorization_codes ace1_0 "
                + "where ace1_0.tenant_id = ? and ace1_0.tenant_id = ? and ace1_0.code_hash=?")) {
            select.setObject(1, TENANT);
            select.setObject(2, TENANT);
            select.setString(3, hash);
            return onlyRow(select);
        }
    }

    /** The update Hibernate flushes for a dirty code. */
    static void updateCode(Connection connection, Map<String, Object> code) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("update authorization_codes set client_id=?,"
                + "code_challenge=?,code_challenge_method=?,code_hash=?,consumed_at=?,expires_at=?,issued_at=?,"
                + "nonce=?,redirect_uri=?,scope=?,user_id=? where id=?")) {
            int parameter = 1;
            for (String column : List.of("client_id", "code_challenge", "code_challenge_method", "code_hash",
                    "consumed_at", "expires_at", "issued_at", "nonce", "redirect_uri", "scope", "user_id", "id")) {
                update.setObject(parameter++, code.get(column));
            }
            update.executeUpdate();
        }
    }

    /** The conditional consumption for {@code AuthorizationCodeRepository.consumeIfUnused}. */
    static int consumeCodeIfUnused(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("update authorization_codes ace1_0 "
                + "set consumed_at=? where ace1_0.id=? and ace1_0.consumed_at is null "
                + "and ace1_0.tenant_id = ? and ace1_0.tenant_id = ?")) {
            update.setTimestamp(1, Timestamp.from(NOW));
            update.setObject(2, id);
            update.setObject(3, TENANT);
            update.setObject(4, TENANT);
            return update.executeUpdate();
        }
    }

    /** Hibernate's {@code findAllByFamilyId}, which the revocation used to start with. */
    static List<Map<String, Object>> findTokensByFamily(Connection connection, UUID family) throws SQLException {
        List<Map<String, Object>> tokens = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("select rte1_0.id,rte1_0.client_id,"
                + "rte1_0.consumed_at,rte1_0.expires_at,rte1_0.family_id,rte1_0.issued_at,rte1_0.revoked_at,"
                + "rte1_0.scope,rte1_0.tenant_id,rte1_0.token_hash,rte1_0.user_id from refresh_tokens rte1_0 "
                + "where rte1_0.tenant_id = ? and rte1_0.tenant_id = ? and rte1_0.family_id=?")) {
            select.setObject(1, TENANT);
            select.setObject(2, TENANT);
            select.setObject(3, family);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    tokens.add(row(rows));
                }
            }
        }
        return tokens;
    }

    /** The bulk revocation Hibernate generates for {@code RefreshTokenRepository.revokeUnrevoked}. */
    static int revokeUnrevoked(Connection connection, UUID family) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("update refresh_tokens rte1_0 set revoked_at=? "
                + "where rte1_0.family_id=? and rte1_0.revoked_at is null "
                + "and rte1_0.tenant_id = ? and rte1_0.tenant_id = ?")) {
            update.setTimestamp(1, Timestamp.from(NOW));
            update.setObject(2, family);
            update.setObject(3, TENANT);
            update.setObject(4, TENANT);
            return update.executeUpdate();
        }
    }

    /** Every refresh token by its hash, followed by "consumed" or "revoked" where either is set. */
    static List<String> tokens(DataSource database) throws SQLException {
        List<String> tokens = new ArrayList<>();
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT token_hash, "
                     + "consumed_at IS NOT NULL, revoked_at IS NOT NULL FROM refresh_tokens ORDER BY token_hash")) {
            while (rows.next()) {
                tokens.add(rows.getString(1) + (rows.getBoolean(2) ? " consumed" : "")
                        + (rows.getBoolean(3) ? " revoked" : ""));
            }
        }
        return tokens;
    }

    static Map<String, Object> onlyRow(PreparedStatement select) throws SQLException {
        try (ResultSet rows = select.executeQuery()) {
            return rows.next() ? row(rows) : null;
        }
    }

    private static Map<String, Object> row(ResultSet rows) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
            row.put(rows.getMetaData().getColumnName(column), rows.getObject(column));
        }
        return row;
    }
}
