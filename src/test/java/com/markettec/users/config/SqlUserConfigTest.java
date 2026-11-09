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

    @Test
    void appServiceDatasourceUsesManagedIdentityWithoutDeveloperFallback() throws Exception {
        var source = AzureSqlConnection.managedIdentityDataSource("example.database.windows.net", "isolated", 1433);
        assertEquals("ActiveDirectoryManagedIdentity", source.getAuthentication());
        assertEquals("true", source.getEncrypt());
        assertFalse(source.getTrustServerCertificate());
        assertEquals("*.database.windows.net", source.getHostNameInCertificate());
        assertTrue(source.getUser() == null || source.getUser().isEmpty());
        assertEquals(30, source.getLoginTimeout());
        assertEquals(30000, source.getSocketTimeout());
    }

    @Test void appServiceRejectsDeveloperProfileBeforeConnecting() { rejectAppServiceCombination("sql-appservice,sql-dev"); }
    @Test void appServiceRejectsMemoryProfileBeforeConnecting() { rejectAppServiceCombination("sql-appservice,local"); }
    @Test void appServiceRejectsIntegrationProfileBeforeConnecting() { rejectAppServiceCombination("sql-appservice,sql-integration"); }

    private void rejectAppServiceCombination(String profiles) {
        contexts.withPropertyValues("spring.profiles.active=" + profiles, "spring.application.name=ms-users",
                "users.sql.server=example.invalid", "users.sql.database=isolated", "users.sql.port=1433",
                "users.sql.maximum-pool-size=1").run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure())
                            .getMessage().contains("exactly one persistence profile"));
                });
    }

    @Test
    void appServiceRequiresExplicitDatabaseConfiguration() {
        contexts.withBean(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class)
                .withPropertyValues("spring.profiles.active=sql-appservice", "spring.application.name=ms-users",
                        "users.sql.port=1433", "users.sql.maximum-pool-size=1")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure())
                            .getMessage().contains("users.sql.server"));
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " "})
    void appServiceRejectsBlankDestinationsBeforeAnyConnection(String blank) {
        var environment = new org.springframework.mock.env.MockEnvironment();
        environment.setActiveProfiles("sql-appservice");
        var config = new SqlUserConfig();
        assertThrows(IllegalArgumentException.class, () -> config.sqlDataSource(environment, blank, "isolated", 1433, 1));
        assertThrows(IllegalArgumentException.class, () -> config.sqlDataSource(environment, "example.invalid", blank, 1433, 1));
    }

    @Test
    void unavailableManagedIdentityFailsStartupWithoutSelectingDeveloperCredentials() throws Exception {
        var failedIdentity = org.mockito.Mockito.mock(com.microsoft.sqlserver.jdbc.SQLServerDataSource.class);
        org.mockito.Mockito.when(failedIdentity.getConnection()).thenThrow(new java.sql.SQLException("Managed identity unavailable"));
        try (var connections = org.mockito.Mockito.mockStatic(AzureSqlConnection.class)) {
            connections.when(() -> AzureSqlConnection.managedIdentityDataSource("example.invalid", "isolated", 1433))
                    .thenReturn(failedIdentity);
            contexts.withPropertyValues("spring.profiles.active=sql-appservice", "spring.application.name=ms-users",
                    "users.sql.server=example.invalid", "users.sql.database=isolated", "users.sql.port=1433",
                    "users.sql.maximum-pool-size=1").run(context -> {
                        assertNotNull(context.getStartupFailure());
                        assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure())
                                .getMessage().contains("Managed identity unavailable"));
                    });
            // MTEC-37: a non-transient failure is not retried by the startup SQL wait.
            org.mockito.Mockito.verify(failedIdentity, org.mockito.Mockito.times(1)).getConnection();
            connections.verify(() -> AzureSqlConnection.dataSource("example.invalid", "isolated", 1433), org.mockito.Mockito.never());
        }
    }
}
