package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(PostgresExtension.class)
class PostgresLockWatchTest extends LockWatchContract {
}
