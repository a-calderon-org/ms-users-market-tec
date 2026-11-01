package com.markettec.users.adapters.out.sql;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MSSQLServerContainer;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.DockerClientFactory;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Fresh, disposable SQL Server, with a trusted ephemeral TLS certificate; no Azure access. */
final class IsolatedSqlServer implements AutoCloseable {
    static final String DATABASE = "mtec35_isolated";
    // Verified in MCR as linux/amd64. Pin CU18 by digest instead of a mutable latest tag.
    // Testcontainers 1.21 expects digest-only references (tag+digest changes its parsed repository).
    static final String IMAGE = "mcr.microsoft.com/mssql/server"
            + "@sha256:ea73825f3d88a23c355ac2f9fdc6bd960fec90171c12c572109b36a558f77bb8";
    private TlsSqlServerContainer container;
    private Path temporary;
    private int mappedPort;
    private final java.util.List<SQLServerDataSource> sources = new java.util.ArrayList<>();
    private final String password = "Sql!" + UUID.randomUUID();
    private final String trustPassword = UUID.randomUUID().toString();

    SQLServerDataSource start() throws Exception {
        if (!"Y".equals(System.getenv("MTEC35_SQLSERVER_ACCEPT_EULA"))) {
            throw new IllegalStateException("SQL Server EULA authorization required: MTEC35_SQLSERVER_ACCEPT_EULA=Y");
        }
        // Native Linux x86-64 only; no platform override or ARM emulation.
        var engine = DockerClientFactory.instance().client().infoCmd().exec();
        String architecture = engine.getArchitecture();
        if (!"linux".equalsIgnoreCase(engine.getOsType())
                || (!"amd64".equals(architecture) && !"x86_64".equals(architecture))) {
            throw new IllegalStateException("SQL Server integration requires native x86-64 Linux Docker. Detected "
                    + engine.getOsType() + "/" + architecture + "; ARM emulation is not an accepted compatibility result.");
        }
        temporary = Files.createTempDirectory("mtec35-sql-tls-");
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var name = new X500Name("CN=localhost");
        var certificateBuilder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(86400)), name, pair.getPublic());
        certificateBuilder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName[]{new GeneralName(GeneralName.dNSName, "localhost"),
                        new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
        certificateBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        certificateBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        var certificate = new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        Files.writeString(temporary.resolve("server.pem"), pem(certificate));
        Files.writeString(temporary.resolve("server.key"), pem(pair.getPrivate()));
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("isolated-sql", certificate);
        try (var output = Files.newOutputStream(temporary.resolve("trust.p12"))) {
            trust.store(output, trustPassword.toCharArray());
        }
        Files.writeString(temporary.resolve("mssql.conf"), """
                [network]
                tlscert = /var/opt/mssql/tls/server.pem
                tlskey = /var/opt/mssql/tls/server.key
                tlsprotocols = 1.2
                forceencryption = 1
                """);
        container = new TlsSqlServerContainer()
                .acceptLicense().withEnv("MSSQL_PID", "Developer").withPassword(password)
                .withLabel("markettec.qa", "mtec35")
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(1433))))
                .withCopyFileToContainer(MountableFile.forHostPath(temporary.resolve("server.pem"), 0444), "/var/opt/mssql/tls/server.pem")
                .withCopyFileToContainer(MountableFile.forHostPath(temporary.resolve("server.key"), 0444), "/var/opt/mssql/tls/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(temporary.resolve("mssql.conf"), 0444), "/var/opt/mssql/mssql.conf")
                .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client connections.*", 1))
                .withStartupTimeout(Duration.ofMinutes(3));
        container.start();
        mappedPort = inspectLoopbackPort();
        var admin = source("master");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60)).ignoreExceptions()
                .until(() -> Integer.valueOf(1).equals(new JdbcTemplate(admin).queryForObject("SELECT 1", Integer.class)));
        var adminJdbc = new JdbcTemplate(admin);
        String major = adminJdbc.queryForObject("SELECT CONVERT(varchar(8), SERVERPROPERTY('ProductMajorVersion'))", String.class);
        if (!"16".equals(major)) {
            throw new IllegalStateException("Strict QA requires SQL Server 2022 (major 16), detected " + major);
        }
        String productVersion = adminJdbc.queryForObject("SELECT CONVERT(varchar(128), SERVERPROPERTY('ProductVersion'))", String.class);
        new JdbcTemplate(admin).execute("CREATE DATABASE " + DATABASE + " COLLATE SQL_Latin1_General_CP1_CI_AS");
        System.out.println("MTEC35 SQL fixture: image=" + container.getDockerImageName() + "; productVersion=" + productVersion
                + "; engine=linux/" + architecture
                + "; database=" + DATABASE + "; portBinding=127.0.0.1; TLS certificate verified");
        return source(DATABASE);
    }

    private int inspectLoopbackPort() {
        // Docker may assign a different ephemeral host port after restarting a binding with HostPort=0.
        // Inspect the engine instead of relying on Testcontainers' cached startup inspection.
        var current = DockerClientFactory.instance().client().inspectContainerCmd(container.getContainerId()).exec();
        var bindings = current.getNetworkSettings().getPorts().getBindings().get(new ExposedPort(1433));
        if (bindings == null || bindings.length != 1 || !"127.0.0.1".equals(bindings[0].getHostIp())) {
            throw new IllegalStateException("Isolated SQL port must bind exclusively to loopback");
        }
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    SQLServerDataSource source(String database) throws Exception {
        SQLServerDataSource source = new SQLServerDataSource();
        source.setServerName(container.getHost());
        source.setPortNumber(mappedPort);
        source.setDatabaseName(database);
        // Only the disposable container uses its randomly generated SQL password.
        source.setUser("sa");
        source.setPassword(password);
        source.setEncrypt("true");
        source.setTrustServerCertificate(false);
        source.setHostNameInCertificate("localhost");
        source.setTrustStore(temporary.resolve("trust.p12").toString());
        source.setTrustStoreType("PKCS12");
        source.setTrustStorePassword(trustPassword);
        source.setLoginTimeout(20);
        source.setSocketTimeout(30000);
        sources.add(source);
        return source;
    }

    SQLServerDataSource restrictedRuntimeSource() throws Exception {
        String runtimePassword = "Runtime!" + UUID.randomUUID();
        new JdbcTemplate(source("master")).update("""
                DECLARE @password nvarchar(128)=?;
                DECLARE @statement nvarchar(max)=N'CREATE LOGIN mtec35_runtime WITH PASSWORD=' + QUOTENAME(@password, '''');
                EXEC(@statement);
                """, runtimePassword);
        var admin = new JdbcTemplate(source(DATABASE));
        admin.execute("CREATE USER mtec35_runtime FOR LOGIN mtec35_runtime");
        for (String table : new String[]{"users", "profiles", "external_identities"}) {
            admin.execute("GRANT SELECT, INSERT, UPDATE ON OBJECT::dbo." + table + " TO mtec35_runtime");
        }
        admin.execute("GRANT SELECT ON OBJECT::dbo.flyway_schema_history TO mtec35_runtime");
        admin.execute("GRANT VIEW DEFINITION TO mtec35_runtime");
        var runtime = source(DATABASE);
        runtime.setUser("mtec35_runtime");
        runtime.setPassword(runtimePassword);
        return runtime;
    }

    void restart() {
        int previousPort = mappedPort;
        DockerClientFactory.instance().client().restartContainerCmd(container.getContainerId()).exec();
        mappedPort = inspectLoopbackPort();
        sources.forEach(source -> source.setPortNumber(mappedPort));
        System.out.println("MTEC35 SQL engine restarted: loopback port " + previousPort + " -> " + mappedPort);
    }

    private static String pem(Object object) throws Exception {
        var writer = new StringWriter();
        try (var pem = new JcaPEMWriter(writer)) {
            pem.writeObject(object);
        }
        return writer.toString();
    }

    private static final class TlsSqlServerContainer extends MSSQLServerContainer<TlsSqlServerContainer> {
        // Qualify the outer constant: MSSQLServerContainer also declares an inherited IMAGE field.
        TlsSqlServerContainer() { super(IsolatedSqlServer.IMAGE); }

        @Override
        protected void waitUntilContainerStarted() {
            // The fixture checks readiness through its certificate-verifying datasource.
            // Avoid the module's default JDBC URL, which defaults to encrypt=false.
            getWaitStrategy().waitUntilReady(this);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            if (container != null) {
                container.stop();
            }
        } finally {
            if (temporary != null) {
                // Delete only files generated by this fixture, never application data.
                for (String file : new String[]{"server.pem", "server.key", "trust.p12", "mssql.conf"}) {
                    Files.deleteIfExists(temporary.resolve(file));
                }
                Files.deleteIfExists(temporary);
            }
        }
    }
}
