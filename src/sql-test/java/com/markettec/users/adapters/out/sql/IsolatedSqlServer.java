package com.markettec.users.adapters.out.sql;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
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
    private GenericContainer<?> container;
    private Path temporary;
    private final String password = "Sql!" + UUID.randomUUID();
    private final String trustPassword = UUID.randomUUID().toString();

    SQLServerDataSource start() throws Exception {
        // Inspect the engine, allowing an ARM client to use a native remote x86-64 Docker host.
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
        container = new GenericContainer<>("mcr.microsoft.com/mssql/server:2022-CU18-ubuntu-22.04")
                .withEnv("ACCEPT_EULA", "Y").withEnv("MSSQL_PID", "Developer")
                .withEnv("MSSQL_SA_PASSWORD", password).withExposedPorts(1433)
                .withCopyFileToContainer(MountableFile.forHostPath(temporary.resolve("server.pem"), 0444), "/var/opt/mssql/tls/server.pem")
                .withCopyFileToContainer(MountableFile.forHostPath(temporary.resolve("server.key"), 0444), "/var/opt/mssql/tls/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(temporary.resolve("mssql.conf"), 0444), "/var/opt/mssql/mssql.conf")
                .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client connections.*", 1))
                .withStartupTimeout(Duration.ofMinutes(3));
        container.start();
        var admin = source("master");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + DATABASE + " COLLATE SQL_Latin1_General_CP1_CI_AS");
        return source(DATABASE);
    }

    SQLServerDataSource source(String database) throws Exception {
        SQLServerDataSource source = new SQLServerDataSource();
        source.setServerName(container.getHost());
        source.setPortNumber(container.getMappedPort(1433));
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
        return source;
    }

    void restart() {
        DockerClientFactory.instance().client().restartContainerCmd(container.getContainerId()).exec();
    }

    private static String pem(Object object) throws Exception {
        var writer = new StringWriter();
        try (var pem = new JcaPEMWriter(writer)) {
            pem.writeObject(object);
        }
        return writer.toString();
    }

    @Override
    public void close() throws Exception {
        if (container != null) {
            container.stop();
        }
        if (temporary != null) {
            // Delete only files generated by this fixture, never application data.
            for (String file : new String[]{"server.pem", "server.key", "trust.p12", "mssql.conf"}) {
                Files.deleteIfExists(temporary.resolve(file));
            }
            Files.deleteIfExists(temporary);
        }
    }
}
