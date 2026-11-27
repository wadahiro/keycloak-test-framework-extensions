package io.github.wadahiro.keycloak.testframework.proxy;

import java.math.BigInteger;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Self-signed certificates and TLS contexts for the front proxy and its test clients. */
final class Certificates {
    static final char[] PASSWORD = "front-proxy".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Certificates() { }

    /** A key store holding one self-signed key entry, valid for a day before and a year after now. */
    static KeyStore selfSigned(String subject, boolean server) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            X500Principal name = new X500Principal(subject);
            Instant now = Instant.now();
            var builder = new JcaX509v3CertificateBuilder(name, new BigInteger(64, RANDOM),
                    Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(365))), name, keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            if (server) {
                builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
                        new GeneralName(GeneralName.iPAddress, "127.0.0.1"), new GeneralName(GeneralName.dNSName, "localhost")}));
                builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            } else {
                builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            }
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("key", keys.getPrivate(), PASSWORD, new X509Certificate[] {certificate});
            return store;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to generate a certificate for " + subject, failure);
        }
    }

    static X509Certificate certificate(KeyStore keyStore) {
        try {
            return (X509Certificate) keyStore.getCertificate("key");
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static KeyStore trustStore(X509Certificate certificate) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry("front-proxy", certificate);
            return store;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** A client context that trusts {@code trusted} and, when {@code keys} is given, presents its certificate. */
    static SSLContext clientContext(KeyStore trusted, KeyStore keys) {
        try {
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trusted);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys == null ? null : keyManagers(keys).getKeyManagers(), trust.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The proxy's server context; it accepts any client certificate, leaving validation to the test's assertions. */
    static SSLContext serverContext(KeyStore keys) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers(keys).getKeyManagers(), new TrustManager[] {new AnyClientCertificate()}, null);
            return context;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static KeyManagerFactory keyManagers(KeyStore keys) throws GeneralSecurityException {
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, PASSWORD);
        return factory;
    }

    private static final class AnyClientCertificate extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { throw new UnsupportedOperationException(); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { throw new UnsupportedOperationException(); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { throw new UnsupportedOperationException(); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
