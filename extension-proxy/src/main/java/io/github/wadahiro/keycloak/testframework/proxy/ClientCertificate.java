package io.github.wadahiro.keycloak.testframework.proxy;

import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * A self-signed client certificate and its private key, generated for a test to present to a {@link FrontProxy} that
 * requests client certificates.
 */
public final class ClientCertificate {
    private final KeyStore keyStore;

    private ClientCertificate(KeyStore keyStore) {
        this.keyStore = keyStore;
    }

    /** Generates an RSA key and a self-signed certificate for {@code subject}, for example {@code CN=alice,O=Example}. */
    public static ClientCertificate generate(String subject) {
        return new ClientCertificate(Certificates.selfSigned(subject, false));
    }

    public X509Certificate getCertificate() { return Certificates.certificate(keyStore); }

    /** A PKCS #12 key store with the key entry; its store and key password is {@link #getPassword()}. */
    public KeyStore getKeyStore() { return keyStore; }

    public char[] getPassword() { return Certificates.PASSWORD.clone(); }
}
