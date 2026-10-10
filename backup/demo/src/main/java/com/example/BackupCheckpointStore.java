package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

public final class BackupCheckpointStore {
    private final Path directory = Path.of(System.getProperty("user.home"), ".0_bot", "backup-checkpoints");

    public Optional<Checkpoint> load(long sourceChatId, long destinationChatId) throws IOException {
        Path checkpointPath = checkpointPath(sourceChatId, destinationChatId);
        if (!Files.exists(checkpointPath)) {
            return Optional.empty();
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(checkpointPath)) {
            properties.load(input);
        }

        try {
            long savedSourceChatId = Long.parseLong(required(properties, "sourceChatId"));
            long savedDestinationChatId = Long.parseLong(required(properties, "destinationChatId"));
            int nextIndex = Integer.parseInt(required(properties, "nextIndex"));
            long headingMessageId = Long.parseLong(required(properties, "headingMessageId"));
            String serializedIds = required(properties, "messageIds");
            List<Long> messageIds = new ArrayList<>();
            for (String id : serializedIds.split(",")) {
                messageIds.add(Long.parseLong(id));
            }
            Checkpoint checkpoint = new Checkpoint(savedSourceChatId, savedDestinationChatId,
                    List.copyOf(messageIds), nextIndex, headingMessageId);
            if (checkpoint.sourceChatId() != sourceChatId
                    || checkpoint.destinationChatId() != destinationChatId
                    || nextIndex < 0 || nextIndex > messageIds.size() || headingMessageId < 0) {
                throw new IllegalArgumentException("Checkpoint values do not match their file.");
            }
            return Optional.of(checkpoint);
        } catch (RuntimeException exception) {
            throw new IOException("Backup checkpoint is invalid: " + checkpointPath, exception);
        }
    }

    public void save(Checkpoint checkpoint) throws IOException {
        Files.createDirectories(directory);
        Properties properties = new Properties();
        properties.setProperty("sourceChatId", Long.toString(checkpoint.sourceChatId()));
        properties.setProperty("destinationChatId", Long.toString(checkpoint.destinationChatId()));
        properties.setProperty("nextIndex", Integer.toString(checkpoint.nextIndex()));
        properties.setProperty("headingMessageId", Long.toString(checkpoint.headingMessageId()));
        properties.setProperty("messageIds",
                String.join(",", checkpoint.messageIds().stream().map(String::valueOf).toList()));

        Path temporaryPath = Files.createTempFile(directory, "backup-checkpoint-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporaryPath)) {
                properties.store(output, null);
            }
            try {
                Files.move(temporaryPath, checkpointPath(checkpoint.sourceChatId(),
                        checkpoint.destinationChatId()), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, checkpointPath(checkpoint.sourceChatId(),
                        checkpoint.destinationChatId()), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
    }

    public void delete(long sourceChatId, long destinationChatId) throws IOException {
        Files.deleteIfExists(checkpointPath(sourceChatId, destinationChatId));
    }

    private Path checkpointPath(long sourceChatId, long destinationChatId) {
        return directory.resolve(sourceChatId + "-" + destinationChatId + ".properties");
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing checkpoint property: " + key);
        }
        return value;
    }

    public record Checkpoint(long sourceChatId, long destinationChatId, List<Long> messageIds,
                             int nextIndex, long headingMessageId) {
        public Checkpoint {
            messageIds = List.copyOf(messageIds);
        }

        public Checkpoint withProgress(int newNextIndex, long newHeadingMessageId) {
            return new Checkpoint(sourceChatId, destinationChatId, messageIds,
                    newNextIndex, newHeadingMessageId);
        }
    }
}
