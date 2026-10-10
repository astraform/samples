package banking.simulator.remote.domain;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;

/** Child-process half of the shared durable-storage readiness handshake. */
public final class BankingRemotePolicyWindTunnelStorageProbeProcess {
    private BankingRemotePolicyWindTunnelStorageProbeProcess() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Shared-storage probe requires five arguments");
        }
        Path lockPath = Path.of(args[0]);
        Path published = Path.of(args[1]);
        Path attempted = Path.of(args[2]);
        byte[] expectedMarker = Base64.getUrlDecoder().decode(args[3]);
        byte[] replacementMarker = Base64.getUrlDecoder().decode(args[4]);

        writeAndForce(attempted, "attempted".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        try (FileChannel lockChannel = FileChannel.open(
                        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = lockChannel.lock()) {
            if (!Arrays.equals(expectedMarker, Files.readAllBytes(published))) {
                throw new IllegalStateException("Shared-storage parent marker changed before child takeover");
            }
            Path pending = Files.createTempFile(published.getParent(), "probe.", ".pending");
            try {
                writeAndForce(pending, replacementMarker);
                Files.move(
                        pending,
                        published,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                forceDirectory(published.getParent());
                if (!Arrays.equals(replacementMarker, Files.readAllBytes(published))) {
                    throw new IllegalStateException("Shared-storage child marker was not durable");
                }
            } finally {
                Files.deleteIfExists(pending);
            }
        }
    }

    private static void writeAndForce(Path path, byte[] content) throws Exception {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(content));
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) throws Exception {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
