package com.example;

import it.tdlight.Init;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HistoryForwarder implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(HistoryForwarder.class.getName());
    private static final int HISTORY_PAGE_SIZE = 100;
    private static final long REQUEST_TIMEOUT_MINUTES = 2;
    private static final long FORWARD_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final Pattern RETRY_AFTER_PATTERN =
            Pattern.compile("(?i)retry after\\s+(\\d+)");

    private final SimpleTelegramClientFactory clientFactory;
    private final SimpleTelegramClient client;
    private final ConcurrentMap<MessageKey, CompletableFuture<SendOutcome>> sendWaiters =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<MessageKey, SendOutcome> earlySendOutcomes = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AtomicInteger> activeSendChats = new ConcurrentHashMap<>();
    private long lastForwardRequestNanos;

    public HistoryForwarder(String apiId, String apiHash) throws Exception {
        Init.init();
        APIToken apiToken = new APIToken(Integer.parseInt(apiId.trim()), apiHash.trim());
        TDLibSettings settings = TDLibSettings.create(apiToken);
        Path sessionPath = Path.of(System.getProperty("user.home"), ".0_bot", "tdlight-session");
        settings.setDatabaseDirectoryPath(sessionPath.resolve("data"));
        settings.setDownloadedFilesDirectoryPath(sessionPath.resolve("downloads"));

        clientFactory = new SimpleTelegramClientFactory();
        SimpleTelegramClientBuilder clientBuilder = clientFactory.builder(settings);
        clientBuilder.addUpdateHandler(TdApi.UpdateMessageSendSucceeded.class, update ->
                recordSendOutcome(update.message.chatId, update.oldMessageId,
                        new SendOutcome(update.message, null)));
        clientBuilder.addUpdateHandler(TdApi.UpdateMessageSendFailed.class, update ->
                recordSendOutcome(update.message.chatId, update.oldMessageId,
                        new SendOutcome(update.message, update.error)));
        client = clientBuilder.build(AuthenticationSupplier.consoleLogin());
        client.getMeAsync().get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    private void recordSendOutcome(long chatId, long oldMessageId, SendOutcome outcome) {
        MessageKey key = new MessageKey(chatId, oldMessageId);
        CompletableFuture<SendOutcome> waiter = sendWaiters.remove(key);
        if (waiter != null) {
            waiter.complete(outcome);
        } else if (activeSendChats.containsKey(chatId)) {
            earlySendOutcomes.put(key, outcome);
        }
    }

    private void beginSendTracking(long chatId) {
        activeSendChats.compute(chatId, (ignored, active) -> {
            if (active == null) {
                return new AtomicInteger(1);
            }
            active.incrementAndGet();
            return active;
        });
    }

    private void endSendTracking(long chatId) {
        activeSendChats.computeIfPresent(chatId, (ignored, active) -> {
            if (active.decrementAndGet() == 0) {
                earlySendOutcomes.keySet().removeIf(key -> key.chatId() == chatId);
                return null;
            }
            return active;
        });
    }

    public ChannelHistory readMessageIds(String publicUsername, Long startMessageId,
                                         BooleanSupplier shouldContinue) throws Exception {
        PublicChat source = findPublicChat(publicUsername);
        Set<Long> newestFirst = new LinkedHashSet<>();
        long fromMessageId = 0;
        long newestMessageId = 0;

        while (true) {
            if (!shouldContinue.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            TdApi.Messages page = client.send(new TdApi.GetChatHistory(
                    source.chatId(), fromMessageId, 0, HISTORY_PAGE_SIZE, false))
                    .get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (page.messages.length == 0) {
                break;
            }

            if (newestMessageId == 0) {
                newestMessageId = page.messages[0].id;
                if (startMessageId != null && startMessageId > newestMessageId) {
                    throw new IllegalArgumentException(
                            "The starting message ID is newer than the latest post in the source channel.");
                }
            }
            int previousSize = newestFirst.size();
            for (TdApi.Message message : page.messages) {
                if (message.id > 0 && (startMessageId == null || message.id >= startMessageId)) {
                    newestFirst.add(message.id);
                }
            }
            if (newestFirst.size() == previousSize
                    || (startMessageId != null
                    && page.messages[page.messages.length - 1].id <= startMessageId)) {
                break;
            }
            fromMessageId = page.messages[page.messages.length - 1].id;
        }
        List<Long> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        return new ChannelHistory(source.title(), source.chatId(), List.copyOf(oldestFirst));
    }

    public long findPublicChatId(String publicUsername) throws Exception {
        return findPublicChat(publicUsername).chatId();
    }

    public PublicChat findPublicChat(String publicUsername) throws Exception {
        TdApi.Chat chat = client.send(new TdApi.SearchPublicChat(publicUsername))
                .get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        String title = chat.title == null || chat.title.isBlank() ? "@" + publicUsername : chat.title;
        return new PublicChat(chat.id, title);
    }

    public PublicChat createChannel(String title) throws Exception {
        TdApi.CreateNewSupergroupChat create = new TdApi.CreateNewSupergroupChat();
        create.title = title;
        create.isChannel = true;
        create.description = "";
        TdApi.Chat chat = client.send(create).get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        return new PublicChat(chat.id, chat.title);
    }

    public ForwardResult forwardMessages(long sourceChatId, long destinationChatId, List<Long> messageIds)
            throws Exception {
        beginSendTracking(destinationChatId);
        try {
            List<Long> forwardedSourceMessageIds = new ArrayList<>();
            for (long sourceMessageId : messageIds) {
                paceForwardRequest();
                TdApi.ForwardMessages request = new TdApi.ForwardMessages();
                request.chatId = destinationChatId;
                request.fromChatId = sourceChatId;
                request.messageIds = new long[] {sourceMessageId};
                request.options = new TdApi.MessageSendOptions();

                TdApi.Messages result;
                try {
                    result = sendForwardMessages(request);
                } catch (RateLimitException exception) {
                    throw waitForRateLimit(exception, sourceMessageId);
                }
                for (TdApi.Message message : result.messages) {
                    if (message != null && message.chatId == destinationChatId && message.id != 0) {
                        try {
                            awaitMessageSent(destinationChatId, message);
                        } catch (RateLimitException exception) {
                            throw waitForRateLimit(exception, sourceMessageId);
                        }
                        forwardedSourceMessageIds.add(sourceMessageId);
                        break;
                    }
                }
            }
            return new ForwardResult(List.copyOf(forwardedSourceMessageIds),
                    messageIds.size() - forwardedSourceMessageIds.size());
        } finally {
            endSendTracking(destinationChatId);
        }
    }

    private synchronized void paceForwardRequest() throws InterruptedException {
        long remainingNanos = FORWARD_INTERVAL_NANOS - (System.nanoTime() - lastForwardRequestNanos);
        if (lastForwardRequestNanos != 0 && remainingNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(remainingNanos);
        }
        lastForwardRequestNanos = System.nanoTime();
    }

    private TdApi.Message awaitMessageSent(long destinationChatId, TdApi.Message pendingMessage) throws Exception {
        if (pendingMessage.sendingState == null) {
            return pendingMessage;
        }
        if (!(pendingMessage.sendingState instanceof TdApi.MessageSendingStatePending)) {
            throw new IllegalStateException("Telegram returned a message that is not pending or confirmed.");
        }

        MessageKey key = new MessageKey(destinationChatId, pendingMessage.id);
        SendOutcome outcome = earlySendOutcomes.remove(key);
        if (outcome == null) {
            CompletableFuture<SendOutcome> waiter = new CompletableFuture<>();
            CompletableFuture<SendOutcome> existing = sendWaiters.putIfAbsent(key, waiter);
            if (existing != null) {
                waiter = existing;
            }
            outcome = earlySendOutcomes.remove(key);
            if (outcome == null) {
                try {
                    outcome = waiter.get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                } catch (TimeoutException exception) {
                    sendWaiters.remove(key, waiter);
                    throw new TimeoutException("Telegram did not confirm message " + pendingMessage.id
                            + " in destination chat " + destinationChatId + ".");
                } catch (ExecutionException exception) {
                    throw new IllegalStateException("Could not observe Telegram's send result for message "
                            + pendingMessage.id + ".", exception.getCause());
                }
            }
        }
        if (outcome.error() != null) {
            long retryAfterSeconds = retryAfterSeconds(outcome.error().message);
            if (retryAfterSeconds > 0) {
                throw new RateLimitException(retryAfterSeconds, outcome.error().message);
            }
            throw new IllegalStateException("Telegram failed to send destination message: "
                    + outcome.error().message);
        }
        TdApi.Message confirmedMessage = outcome.message();
        if (confirmedMessage.chatId != destinationChatId || confirmedMessage.id <= 0
                || confirmedMessage.sendingState != null) {
            throw new IllegalStateException("Telegram's send-success update did not contain a confirmed "
                    + "server message for destination chat " + destinationChatId + ".");
        }
        return confirmedMessage;
    }

    private RateLimitWaitCompletedException waitForRateLimit(RateLimitException exception, long sourceMessageId)
            throws InterruptedException {
        long waitSeconds = exception.retryAfterSeconds() + 1;
        LOGGER.warning("Telegram rate-limited source message " + sourceMessageId
                + "; waiting " + waitSeconds + " seconds before pausing for user confirmation.");
        TimeUnit.SECONDS.sleep(waitSeconds);
        return new RateLimitWaitCompletedException(sourceMessageId);
    }

    private long retryAfterSeconds(String errorMessage) {
        if (errorMessage == null) {
            return 0;
        }
        Matcher matcher = RETRY_AFTER_PATTERN.matcher(errorMessage);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private TdApi.Messages sendForwardMessages(TdApi.ForwardMessages request) throws Exception {
        try {
            return client.send(request).get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (ExecutionException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                long retryAfterSeconds = retryAfterSeconds(cause.getMessage());
                if (retryAfterSeconds > 0) {
                    throw new RateLimitException(retryAfterSeconds, cause.getMessage());
                }
            }
            throw exception;
        }
    }

    public String describeChat(long chatId) throws Exception {
        TdApi.Chat chat = client.send(new TdApi.GetChat(chatId))
                .get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        return chat.title + " (TDLib chat ID " + chat.id + ")";
    }

    public Set<Long> findForwardedSourceMessageIds(long sourceChatId, long destinationChatId,
                                                   List<Long> expectedMessageIds,
                                                   BooleanSupplier shouldContinue) throws Exception {
        Set<Long> expected = new HashSet<>(expectedMessageIds);
        Set<Long> forwardedSourceIds = new HashSet<>();
        long fromMessageId = 0;

        while (true) {
            if (!shouldContinue.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            TdApi.Messages page = client.send(new TdApi.GetChatHistory(
                    destinationChatId, fromMessageId, 0, HISTORY_PAGE_SIZE, false))
                    .get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (page.messages.length == 0) {
                break;
            }

            long nextFromMessageId = page.messages[page.messages.length - 1].id;
            for (TdApi.Message message : page.messages) {
                if (message.forwardInfo != null
                        && message.sendingState == null
                        && message.forwardInfo.origin instanceof TdApi.MessageOriginChannel origin
                        && origin.chatId == sourceChatId
                        && expected.contains(origin.messageId)) {
                    forwardedSourceIds.add(origin.messageId);
                }
            }
            if (nextFromMessageId == fromMessageId) {
                break;
            }
            fromMessageId = nextFromMessageId;
        }

        return Set.copyOf(forwardedSourceIds);
    }

    public RepeatedForwardScan scanRepeatedForwardMessages(long destinationChatId) throws Exception {
        Map<SourceMessageKey, List<Long>> destinationMessageIdsBySource = new LinkedHashMap<>();
        long scannedMessages = 0;
        long fromMessageId = 0;

        while (true) {
            TdApi.Messages page = client.send(new TdApi.GetChatHistory(
                    destinationChatId, fromMessageId, 0, HISTORY_PAGE_SIZE, false))
                    .get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (page.messages.length == 0) {
                break;
            }

            long nextFromMessageId = page.messages[page.messages.length - 1].id;
            for (TdApi.Message message : page.messages) {
                scannedMessages++;
                if (message.forwardInfo != null
                        && message.forwardInfo.origin instanceof TdApi.MessageOriginChannel origin) {
                    SourceMessageKey source = new SourceMessageKey(origin.chatId, origin.messageId);
                    destinationMessageIdsBySource.computeIfAbsent(source, ignored -> new ArrayList<>())
                            .add(message.id);
                }
            }
            if (nextFromMessageId == fromMessageId) {
                break;
            }
            fromMessageId = nextFromMessageId;
        }

        List<RepeatedForward> repeatedForwards = destinationMessageIdsBySource.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> new RepeatedForward(entry.getKey().sourceChatId(),
                        entry.getKey().sourceMessageId(), entry.getValue()))
                .toList();
        return new RepeatedForwardScan(scannedMessages, repeatedForwards);
    }

    public record ForwardResult(List<Long> forwardedSourceMessageIds, int skipped) {
        public ForwardResult {
            forwardedSourceMessageIds = List.copyOf(forwardedSourceMessageIds);
        }

        public int forwarded() {
            return forwardedSourceMessageIds.size();
        }
    }

    public record RepeatedForwardScan(long scannedMessages, List<RepeatedForward> repeatedForwards) {
        public RepeatedForwardScan {
            repeatedForwards = List.copyOf(repeatedForwards);
        }
    }

    public record RepeatedForward(long sourceChatId, long sourceMessageId,
                                  List<Long> destinationMessageIds) {
        public RepeatedForward {
            destinationMessageIds = List.copyOf(destinationMessageIds);
        }
    }

    public record PublicChat(long chatId, String title) {
    }

    public record ChannelHistory(String title, long sourceChatId, List<Long> messageIds) {
        public ChannelHistory {
            messageIds = List.copyOf(messageIds);
        }
    }

    private record MessageKey(long chatId, long messageId) {
    }

    private record SourceMessageKey(long sourceChatId, long sourceMessageId) {
    }

    private record SendOutcome(TdApi.Message message, TdApi.Error error) {
    }

    private static final class RateLimitException extends Exception {
        private final long retryAfterSeconds;

        private RateLimitException(long retryAfterSeconds, String errorMessage) {
            super(errorMessage);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        private long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    static final class RateLimitWaitCompletedException extends Exception {
        private RateLimitWaitCompletedException(long sourceMessageId) {
            super("Telegram's rate-limit wait completed for source message " + sourceMessageId + ".");
        }
    }

    @Override
    public void close() throws Exception {
        client.close();
        clientFactory.close();
    }
}