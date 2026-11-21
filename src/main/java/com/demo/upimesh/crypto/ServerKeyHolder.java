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

@Component
public class ServerKeyHolder {
    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);
    private static final String PRIVATE_KEY_FILE = "server-private.pk8";
    private static final String PUBLIC_KEY_FILE = "server-public.spki";

    private final Path keyDirectory;
    private KeyPair keyPair;

    @Autowired
    public ServerKeyHolder(@Value("${upi.mesh.crypto.key-directory:./data/upimesh-keys}") String keyDirectory) {
        this(Path.of(keyDirectory));
    }

    public ServerKeyHolder(Path keyDirectory) {
        this.keyDirectory = keyDirectory;
    }

    @PostConstruct
    public void init() throws Exception {
        Path privateKeyPath = keyDirectory.resolve(PRIVATE_KEY_FILE);
        Path publicKeyPath = keyDirectory.resolve(PUBLIC_KEY_FILE);
        boolean privateExists = Files.exists(privateKeyPath);
        boolean publicExists = Files.exists(publicKeyPath);
        if (privateExists != publicExists) {
            throw new IllegalStateException("Server key directory contains an incomplete key pair: " + keyDirectory);
        }
        if (privateExists) {
            keyPair = loadKeyPair(privateKeyPath, publicKeyPath);
            log.info("Loaded persistent server RSA key pair from {}", keyDirectory.toAbsolutePath());
            return;
        }

        Files.createDirectories(keyDirectory);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        Files.write(privateKeyPath, keyPair.getPrivate().getEncoded());
        Files.write(publicKeyPath, keyPair.getPublic().getEncoded());
        log.info("Created persistent server RSA key pair in {}", keyDirectory.toAbsolutePath());
    }

    private KeyPair loadKeyPair(Path privateKeyPath, Path publicKeyPath) throws Exception {
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateKeyPath)));
        PublicKey publicKey = rsa.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicKeyPath)));
        return new KeyPair(publicKey, privateKey);
    }

    public PublicKey getPublicKey() { return keyPair.getPublic(); }
    public PrivateKey getPrivateKey() { return keyPair.getPrivate(); }
    public String getPublicKeyBase64() { return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()); }
}
