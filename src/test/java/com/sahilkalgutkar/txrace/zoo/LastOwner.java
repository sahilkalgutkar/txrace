package com.sahilkalgutkar.txrace.zoo;

import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * A team with two owners, both leaving at once. Each counts the owners, sees two, and leaves,
 * since someone would still be in charge. They write different rows, so no lock stops either,
 * and the team ends up with nobody who can manage it: write skew. The fix makes both lock the
 * team's row before counting, so the second counts after the first has gone.
 */
final class LastOwner implements Specimen {

    @Override
    public void create(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE team (id INT PRIMARY KEY)");
        statement.execute("CREATE TABLE membership (team_id INT NOT NULL, member TEXT NOT NULL, role TEXT NOT NULL)");
    }

    static void twoOwners(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM membership");
            statement.execute("DELETE FROM team");
            statement.execute("INSERT INTO team VALUES (1)");
            statement.execute("INSERT INTO membership VALUES (1, 'alice', 'owner'), (1, 'bob', 'owner')");
        }
    }

    @Override
    public Object observe(DataSource database) throws SQLException {
        List<String> owners = new ArrayList<>();
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT member FROM membership WHERE team_id = 1 AND role = 'owner' ORDER BY member")) {
            while (rows.next()) {
                owners.add(rows.getString(1));
            }
        }
        return owners;
    }

    static Transaction leave(String member) {
        return connection -> leaveIfAnotherOwnerStays(connection, member);
    }

    static Transaction lockTheTeamThenLeave(String member) {
        return connection -> {
            connection.createStatement().executeQuery("SELECT id FROM team WHERE id = 1 FOR UPDATE").close();
            return leaveIfAnotherOwnerStays(connection, member);
        };
    }

    private static String leaveIfAnotherOwnerStays(Connection connection, String member) throws SQLException {
        try (ResultSet rows = connection.createStatement().executeQuery(
                "SELECT COUNT(*) FROM membership WHERE team_id = 1 AND role = 'owner'")) {
            rows.next();
            if (rows.getInt(1) < 2) {
                return "last owner";
            }
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM membership WHERE team_id = 1 AND member = ?")) {
            delete.setString(1, member);
            delete.executeUpdate();
        }
        return "left";
    }

    @Override
    public Twin buggy() {
        return new Twin(LastOwner::twoOwners, List.of(leave("alice"), leave("bob")));
    }

    @Override
    public Twin fixed() {
        return new Twin(LastOwner::twoOwners, List.of(lockTheTeamThenLeave("alice"), lockTheTeamThenLeave("bob")));
    }
}
