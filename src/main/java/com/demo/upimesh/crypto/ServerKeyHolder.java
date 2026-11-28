package com.demo.upimesh.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class ServerKeyHolder {
    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final String LEGACY_PRIVATE_KEY_FILE = "server-private.pk8";
    private static final String LEGACY_PUBLIC_KEY_FILE = "server-public.spki";

    private final Path keyDirectory;
    private final String activeKeyId;
    private final Map<String, KeyPair> keys = new ConcurrentHashMap<>();

    @Autowired
    public ServerKeyHolder(
            @Value("${upi.mesh.crypto.key-directory:./data/upimesh-keys}") String keyDirectory,
            @Value("${upi.mesh.crypto.active-key-id:v1}") String activeKeyId) {
        this(Path.of(keyDirectory), activeKeyId);
    }

    public ServerKeyHolder(Path keyDirectory) {
        this(keyDirectory, "v1");
    }

    public ServerKeyHolder(Path keyDirectory, String activeKeyId) {
        if (!KEY_ID.matcher(activeKeyId).matches()) {
            throw new IllegalArgumentException("Key ID must contain only letters, digits, hyphens, or underscores");
        }
        this.keyDirectory = keyDirectory;
        this.activeKeyId = activeKeyId;
    }

    @PostConstruct
    public void init() throws Exception {
        keys.put(activeKeyId, loadOrCreate(activeKeyId));
        log.info("Loaded persistent server RSA key {} from {}", activeKeyId, keyDirectory.toAbsolutePath());
    }

    public PublicKey getPublicKey() {
        return getKeyPair(activeKeyId).getPublic();
    }

    public PublicKey getPublicKey(String keyId) {
        return getKeyPair(keyId).getPublic();
    }

    public PrivateKey getPrivateKey() {
        return getKeyPair(activeKeyId).getPrivate();
    }

    public PrivateKey getPrivateKey(String keyId) {
        return getKeyPair(keyId).getPrivate();
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(getPublicKey().getEncoded());
    }

    private KeyPair getKeyPair(String keyId) {
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Invalid key ID");
        }
        try {
            return keys.computeIfAbsent(keyId, this::loadExisting);
        } catch (KeyLoadException exception) {
            throw new IllegalArgumentException("Unknown server key ID: " + keyId, exception.getCause());
        }
    }

    private KeyPair loadOrCreate(String keyId) throws Exception {
        KeyPaths paths = pathsFor(keyId);
        boolean privateExists = Files.exists(paths.privateKey());
        boolean publicExists = Files.exists(paths.publicKey());
        if (privateExists != publicExists) {
            throw new IllegalStateException("Server key directory contains an incomplete key pair: " + keyDirectory);
        }
        if (privateExists) {
            return loadKeyPair(paths);
        }
        Files.createDirectories(keyDirectory);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        Files.write(paths.privateKey(), keyPair.getPrivate().getEncoded());
        Files.write(paths.publicKey(), keyPair.getPublic().getEncoded());
        log.info("Created persistent server RSA key {} in {}", keyId, keyDirectory.toAbsolutePath());
        return keyPair;
    }

    private KeyPair loadExisting(String keyId) {
        try {
            KeyPaths paths = pathsFor(keyId);
            if (!Files.exists(paths.privateKey()) || !Files.exists(paths.publicKey())) {
                throw new IllegalArgumentException("Key files do not exist");
            }
            return loadKeyPair(paths);
        } catch (Exception exception) {
            throw new KeyLoadException(exception);
        }
    }

    private KeyPaths pathsFor(String keyId) {
        if ("v1".equals(keyId)) {
            return new KeyPaths(keyDirectory.resolve(LEGACY_PRIVATE_KEY_FILE), keyDirectory.resolve(LEGACY_PUBLIC_KEY_FILE));
        }
        return new KeyPaths(
                keyDirectory.resolve("server-" + keyId + "-private.pk8"),
                keyDirectory.resolve("server-" + keyId + "-public.spki"));
    }

    private KeyPair loadKeyPair(KeyPaths paths) throws Exception {
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(paths.privateKey())));
        PublicKey publicKey = rsa.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(paths.publicKey())));
        return new KeyPair(publicKey, privateKey);
    }

    private record KeyPaths(Path privateKey, Path publicKey) {}

    private static class KeyLoadException extends RuntimeException {
        KeyLoadException(Throwable cause) { super(cause); }
    }
}
