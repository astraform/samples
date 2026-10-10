package banking.simulator.remote.domain;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class BankingPolicyWindTunnelProviderIdentity {
    private static final String REVISION_ENV = "ASTRAFORM_PROVIDER_ARTIFACT_DIGEST";
    private static final Pattern IMMUTABLE_REVISION = Pattern.compile(
            "^(?:build|oci)-sha256:[0-9a-f]{64}$");
    private static final String REVISION = resolveRevision();

    private BankingPolicyWindTunnelProviderIdentity() {
    }

    static String revision() {
        return REVISION;
    }

    private static String resolveRevision() {
        String configured = trim(System.getenv(REVISION_ENV));
        if (!configured.isBlank()) {
            if (!IMMUTABLE_REVISION.matcher(configured).matches()) {
                throw new IllegalStateException(
                        REVISION_ENV + " must be build-sha256:<64 lowercase hex> "
                                + "or oci-sha256:<64 lowercase hex>");
            }
            return configured;
        }
        return "build-sha256:" + digestCodeSource();
    }

    private static String digestCodeSource() {
        MessageDigest digest = sha256();
        try {
            Path codeSource = Path.of(BankingPolicyWindTunnelProviderIdentity.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            if (Files.isRegularFile(codeSource)) {
                updateFile(digest, codeSource.getFileName().toString(), codeSource);
            } else {
                List<Path> files;
                try (Stream<Path> paths = Files.walk(codeSource)) {
                    files = paths.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(path -> codeSource.relativize(path).toString()))
                            .toList();
                }
                for (Path file : files) {
                    updateFile(digest, codeSource.relativize(file).toString(), file);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | URISyntaxException unavailableCodeSource) {
            return digestPrimaryClass(digest);
        }
    }

    private static void updateFile(MessageDigest digest, String relativePath, Path file) throws IOException {
        digest.update(relativePath.replace('\\', '/').getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (InputStream input = Files.newInputStream(file)) {
            input.transferTo(new java.security.DigestOutputStream(
                    java.io.OutputStream.nullOutputStream(), digest));
        }
        digest.update((byte) 0);
    }

    private static String digestPrimaryClass(MessageDigest digest) {
        try (InputStream input = BankingPolicyWindTunnelProviderIdentity.class.getResourceAsStream(
                "/banking/simulator/remote/domain/BankingRemotePolicyWindTunnelService.class")) {
            if (input == null) {
                throw new IllegalStateException("Banking provider build identity could not be resolved");
            }
            input.transferTo(new java.security.DigestOutputStream(
                    java.io.OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new IllegalStateException("Banking provider build identity could not be resolved", error);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
