package com.markettec.users.config;

import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.adapters.out.sql.AzureSqlConnection;
import com.markettec.users.adapters.out.sql.SqlUserStore;
import com.markettec.users.domain.model.UserDomainException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class SqlUserConfigTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(ApplicationConfig.class, LocalUserConfig.class, SqlUserConfig.class);

    @Test
    void inMemoryProfileRequiresNoDatasource() {
        contexts.withPropertyValues("spring.profiles.active=in-memory", "spring.application.name=ms-users")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(InMemoryUserStore.class).size());
                    assertEquals(0, context.getBeansOfType(SqlUserStore.class).size());
                });
    }

    @Test
    void sqlProfileFailsWithoutDatasourceAndCannotFallBackToMemory() {
        contexts.withPropertyValues("spring.profiles.active=sql-integration", "spring.application.name=ms-users")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void conflictingProfilesFailBeforeConnecting() {
        contexts.withPropertyValues("spring.profiles.active=local,sql-dev", "spring.application.name=ms-users",
                "users.sql.server=example.invalid", "users.sql.database=isolated", "users.sql.port=1433",
                "users.sql.maximum-pool-size=1").run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()).getMessage()
                            .contains("exactly one persistence profile"));
                });
    }

    @Test
    void azureDatasourceUsesEntraTlsAndNoSqlCredentials() throws Exception {
        var source = AzureSqlConnection.dataSource("sql-markettec-dev.database.windows.net", "sqldb-ms-users-dev", 1433);
        assertEquals("ActiveDirectoryDefault", source.getAuthentication());
        assertEquals("true", source.getEncrypt());
        assertFalse(source.getTrustServerCertificate());
        assertEquals("*.database.windows.net", source.getHostNameInCertificate());
        assertTrue(source.getUser() == null || source.getUser().isEmpty());
        assertEquals("sqldb-ms-users-dev", source.getDatabaseName());
    }

    @Test
    void sqlLinkAuthorizationIsDeniedUntilTrustedAdapterExists() {
        var denied = new SqlUserConfig().sqlIdentityLinkAuthorization();
        assertEquals(UserDomainException.Reason.UNAUTHORIZED,
                assertThrows(UserDomainException.class, () -> denied.requireAuthorization(null, null, null)).reason());
    }
}
