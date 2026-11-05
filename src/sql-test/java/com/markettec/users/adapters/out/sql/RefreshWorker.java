package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.*;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.markettec.users.UsersApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Properties;
import java.util.Map;

/** One-shot process used only by SQL integration QA. Protocol never writes credentials to logs. */
public final class RefreshWorker {
    private RefreshWorker() { }

    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var properties = new Properties();
        properties.load(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(input.readLine())));
        var source = new SQLServerDataSource();
        source.setServerName("127.0.0.1");
        source.setPortNumber(Integer.parseInt(properties.getProperty("port")));
        source.setDatabaseName(IsolatedSqlServer.DATABASE);
        source.setUser("sa");
        source.setPassword(properties.getProperty("password"));
        source.setEncrypt("true");
        source.setTrustServerCertificate(false);
        source.setHostNameInCertificate("localhost");
        source.setTrustStore(properties.getProperty("trustStore"));
        source.setTrustStoreType("PKCS12");
        source.setTrustStorePassword(properties.getProperty("trustPassword"));
        source.setLoginTimeout(20);
        source.setSocketTimeout(30000);
        var jdbc = new JdbcTemplate(source);
        if (jdbc.queryForObject("SELECT 1", Integer.class) != 1) throw new IllegalStateException("SQL unavailable");
        if ("http".equals(properties.getProperty("operation"))) {
            serveHttp(input, properties, source);
            return;
        }
        var store = new SqlUserStore(jdbc, new DataSourceTransactionManager(source));
        var permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        var keys = CsrfKeys.fromJson("{\"sql-test\":\"" + properties.getProperty("csrfKey") + "\"}", "sql-test");
        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC),
                new SecureRandom(), permit, keys);
        System.out.println("READY " + ProcessHandle.current().pid());
        System.out.flush();
        if (!"GO".equals(input.readLine())) throw new IllegalStateException("Missing release signal");
        String operation = properties.getProperty("operation");
        String refresh = properties.getProperty("refresh");
        String csrf = properties.getProperty("csrf");
        try {
            if ("revoke".equals(operation)) {
                authority.revoke(properties.getProperty("sessionId"));
                System.out.println("RESULT REVOKED");
            } else {
                var result = authority.refreshWithSnapshot(refresh, csrf, a -> {
                    if ("fail-sign".equals(operation)) throw new IllegalStateException("synthetic signer failure");
                    return "synthetic-jwt";
                });
                // This pipe is consumed by the parent test; the tokens are never logged.
                System.out.println("RESULT ROTATED " + result.tokens().refreshToken() + " "
                        + result.tokens().csrfToken());
            }
        } catch (SessionRejectedException rejected) {
            System.out.println("RESULT REJECTED " + rejected.reason());
        } catch (IllegalStateException failure) {
            if (!"fail-sign".equals(operation) || !"synthetic signer failure".equals(failure.getMessage())) throw failure;
            System.out.println("RESULT SIGNER_FAILED");
        }
        System.out.flush();
    }

    private static void serveHttp(BufferedReader input, Properties config, SQLServerDataSource source) throws Exception {
        var settings = Map.<String, Object>ofEntries(
                Map.entry("spring.profiles.active", "sql-integration"),
                Map.entry("server.address", "127.0.0.1"),
                Map.entry("server.port", "0"),
                Map.entry("server.ssl.enabled", "true"),
                Map.entry("server.ssl.key-store", config.getProperty("httpsStore")),
                Map.entry("server.ssl.key-store-password", config.getProperty("httpsPassword")),
                Map.entry("server.ssl.key-store-type", "PKCS12"),
                Map.entry("server.ssl.key-alias", "localhost"),
                Map.entry("management.server.port", "-1"),
                Map.entry("users.sql.database", IsolatedSqlServer.DATABASE),
                Map.entry("users.csrf.active-kid", "sql-test"),
                Map.entry("users.csrf.keys-json", "{\"sql-test\":\"" + config.getProperty("csrfKey") + "\"}"),
                Map.entry("users.internal-jwt.enabled", "true"),
                Map.entry("users.internal-jwt.issuer", "https://api.markettec.example"),
                Map.entry("users.internal-jwt.audience", "mtec40-qa"),
                Map.entry("users.internal-jwt.public-jwks-uri", "https://api.markettec.example/.well-known/jwks.json"),
                Map.entry("users.internal-jwt.active-kid", "sql-test"),
                Map.entry("users.internal-jwt.keys-json", config.getProperty("jwtKeys")),
                Map.entry("users.session.http.enabled", "true"),
                Map.entry("users.session.refresh.enabled", "true"),
                Map.entry("users.session.http.allowed-origins", "https://app.markettec.example"),
                Map.entry("users.session.http.public-api-origin", "https://api.markettec.example"),
                Map.entry("users.session.http.https-topology-verified", "true"));
        try (var context = new SpringApplicationBuilder(UsersApplication.class)
                .profiles("sql-integration")
                .initializers(application -> {
                    application.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("mtec40-https-qa", settings));
                    application.getBeanFactory().registerSingleton("dataSource", source);
                })
                .run()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            System.out.println("READY HTTP " + ProcessHandle.current().pid() + " " + port);
            System.out.flush();
            if (!"STOP".equals(input.readLine())) throw new IllegalStateException("Missing stop signal");
        }
    }
}
