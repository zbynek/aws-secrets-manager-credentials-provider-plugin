package io.jenkins.plugins.credentials.secretsmanager;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsUnavailableException;
import com.cloudbees.plugins.credentials.NameWith;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;

import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;

/**
 * A multi-type credential class backed by AWS Secrets Manager, which detects its type at lookup
 * time.
 *
 * <p>NOTE: The underlying AWS secret must have the necessary format and metadata to be used as a
 * particular credential type. If these things are not present, the relevant accessor method(s) will
 * fail at lookup time. (For example, to use the AWS secret as a Jenkins {@link SSHUserPrivateKey},
 * the secretString must be in private key format, and username metadata must be present in the
 * secret's tags.)
 */
@NameWith(value = AwsCredentials.NameProvider.class)
abstract class AwsCredentials extends BaseStandardCredentials implements StringCredentials, StandardUsernamePasswordCredentials, SSHUserPrivateKey, StandardCertificateCredentials {

    private static final char[] NO_PASSWORD = {};
    private static final Secret NO_SECRET = Secret.fromString("");

    static final String USERNAME_TAG = "jenkins:credentials:username";

    private final Map<String, String> tags;

    AwsCredentials(String id, String description, Map<String, String> tags) {
        super(id, description);
        this.tags = tags;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    @Nonnull
    @Override
    public Secret getSecret() {
        return Secret.fromString(getSecretString());
    }

    @NonNull
    @Override
    public Secret getPassword() {
        if (tags.containsKey(USERNAME_TAG)) {
            // username/password
            return Secret.fromString(getSecretString());
        } else {
            // certificate
            return NO_SECRET;
        }
    }

    @NonNull
    @Override
    public String getUsername() {
        if (tags.containsKey(USERNAME_TAG)) {
            return tags.get(USERNAME_TAG);
        } else {
            throw new CredentialsUnavailableException("username", Messages.noUsernameError());
        }
    }

    @Override
    public Secret getPassphrase() {
        return NO_SECRET;
    }

    @NonNull
    @Override
    public List<String> getPrivateKeys() {
        return Collections.singletonList(getPrivateKey());
    }

    @NonNull
    @Deprecated
    @Override
    public String getPrivateKey() {
        final String secretValue = getSecretString();

        if (SshKeyValidator.isValid(secretValue)) {
            return secretValue;
        } else {
            throw new CredentialsUnavailableException("privateKey", Messages.noPrivateKeyError());
        }
    }

    @NonNull
    @Override
    public KeyStore getKeyStore() {
        final byte[] secretValue = getSecretBinary();

        try (InputStream stream = new ByteArrayInputStream(secretValue)) {
            final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            // JDK9 workaround: PKCS#12 keystores must have at least an empty password (not null)
            keyStore.load(stream, NO_PASSWORD);
            return keyStore;
        } catch (IOException | CertificateException | KeyStoreException | NoSuchAlgorithmException ex) {
            throw new CredentialsUnavailableException("keyStore", Messages.noCertificateError());
        }
    }

    private String getSecretString() {
        return getSecretValue().match(new SecretValue.Matcher<String>() {
            @Override
            public String string(String str) {
                return str;
            }

            @Override
            public String binary(byte[] bytes) {
                return null;
            }
        });
    }

    private byte[] getSecretBinary() {
        return getSecretValue().match(new SecretValue.Matcher<byte[]>() {
            @Override
            public byte[] string(String str) {
                return null;
            }

            @Override
            public byte[] binary(byte[] bytes) {
                return bytes;
            }
        });
    }

    @NonNull
    abstract SecretValue getSecretValue();

    public static class NameProvider extends CredentialsNameProvider<AwsCredentials> {
        @NonNull
        @Override
        public String getName(@NonNull AwsCredentials credential) {
            return credential.getId();
        }
    }

    @Extension
    @SuppressWarnings("unused")
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @Override
        @Nonnull
        public String getDisplayName() {
            return Messages.awsSecretsManagerSecret();
        }
    }
}
