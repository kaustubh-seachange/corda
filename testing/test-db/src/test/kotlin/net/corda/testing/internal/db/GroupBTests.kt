package net.corda.testing.internal.db

import org.junit.jupiter.api.Test

@GroupB
class GroupBTests {

    @Test(timeout=300_000)
fun setExpectations() {
        AssertingTestDatabaseContext.addExpectations("groupB",
                "forClassGroupBTests-setup", "specialSql1-setup", "specialSql1-teardown", "forClassGroupBTests-teardown")
    }

    @Test(timeout=300_000)
fun noSpecialSqlRequired() {
    }

    @Test(timeout=300_000)
@SpecialSql1
    fun someSpecialSqlRequired() {
    }
}