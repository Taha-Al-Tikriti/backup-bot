package com.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessages;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Backup extends TelegramLongPollingBot implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(Backup.class.getName());
    private static final int FORWARD_BATCH_SIZE = 100;
    private static final int DELETE_BATCH_SIZE = 100;
    private static final int MAX_DELETE_RANGE = 10_000;
    private static final long DELETE_CONFIRMATION_TTL_MILLIS = 10 * 60 * 1000;
    private static final long BATCH_DELAY_MILLIS = 4000;

    private final String botUsername;
    private final HistoryForwarder historyForwarder;
    private final BackupCheckpointStore checkpointStore = new BackupCheckpointStore();
    private final Map<Long, BackSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();
    private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "telegram-backup-job");
        thread.setDaemon(true);
        return thread;
    });

    public Backup(String botToken, String botUsername, HistoryForwarder historyForwarder) {
        super(botToken);
        this.botUsername = botUsername;
        this.historyForwarder = historyForwarder;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.getChannelPost() != null) {
            handleChannelDeleteCommand(update.getChannelPost());
            return;
        }

        Message message = update.getMessage();
        if (message == null || message.getText() == null || message.getFrom() == null) {
            return;
        }

        long chatId = message.getChatId();
        long userId = message.getFrom().getId();
        String text = message.getText().trim();
        if (chatId != userId) {
            return;
        }

        if (text.startsWith("/confirm_delete ")) {
            confirmDelete(chatId, userId, text.substring("/confirm_delete ".length()).trim());
            return;
        }
        if (text.startsWith("/cancel_delete ")) {
            cancelDelete(chatId, userId, text.substring("/cancel_delete ".length()).trim());
            return;
        }

        if ("/id".equals(text)) {
            sendText(chatId, "Your Telegram user ID is " + userId + ".");
            return;
        }

        if ("/cancel".equals(text)) {
            sessions.remove(chatId);
            sendText(chatId, "Backup cancelled.");
            return;
        }

        if ("/back".equals(text)) {
            Long allowedUserId = configuredAdminId();
            if (allowedUserId == null) {
                sendText(chatId, "Set BACKUP_ADMIN_USER_ID first. Use /id to find your Telegram user ID.");
                return;
            }
            if (allowedUserId != userId) {
                sendText(chatId, "You are not allowed to start backups.");
                return;
            }

            BackSession currentSession = sessions.get(chatId);
            if (currentSession != null
                    && (currentSession.stage == Stage.CREATING || currentSession.stage == Stage.LOADING
                    || currentSession.stage == Stage.FORWARDING)) {
                sendText(chatId, "A backup is already running. Send /cancel before starting another.");
                return;
            }

            sessions.put(chatId, new BackSession());
            sendText(chatId, "Send the public source channel username or t.me link.");
            return;
        }

        BackSession session = sessions.get(chatId);
        if (session == null) {
            return;
        }

        if (("/confirm".equals(text) || "/resume".equals(text)) && session.stage == Stage.CONFIRM) {
            boolean resume = "/resume".equals(text);
            if (resume && session.checkpoint == null) {
                sendText(chatId, "There is no compatible saved backup to resume. Reply /confirm to start from the beginning.");
                return;
            }
            session.stage = Stage.FORWARDING;
            sendText(chatId, resume ? "Resuming backup from its saved checkpoint."
                    : "Forwarding started from the oldest post. Send /cancel to stop.");
            jobExecutor.execute(() -> forwardHistory(chatId, session, resume));
            return;
        }

        if (session.stage == Stage.SOURCE) {
            try {
                session.sourceUsername = parsePublicUsername(text);
                HistoryForwarder.PublicChat source = historyForwarder.findPublicChat(session.sourceUsername);
                session.sourceTitle = source.title();
                session.sourceChatId = source.chatId();
                session.stage = Stage.DESTINATION;
                sendText(chatId,
                        "Send an existing destination channel username or t.me link, or send /new to create a channel named \"مخزن - "
                                + session.sourceTitle + "\".");
            } catch (IllegalArgumentException exception) {
                sendText(chatId, exception.getMessage());
            } catch (Exception exception) {
                sendText(chatId, "Could not find that public source channel.");
                LOGGER.log(Level.WARNING, "Could not look up source channel @" + session.sourceUsername + ".", exception);
            }
            return;
        }

        if (session.stage == Stage.DESTINATION) {
            if ("/new".equalsIgnoreCase(text)) {
                session.stage = Stage.CREATING;
                sendText(chatId, "Creating the new channel and adding the bot. This may take a moment.");
                jobExecutor.execute(() -> createDestinationAndLoad(chatId, session));
                return;
            }
            try {
                session.destinationUsername = "@" + parsePublicUsername(text);
                if (session.destinationUsername.equalsIgnoreCase("@" + session.sourceUsername)) {
                    throw new IllegalArgumentException("Source and destination must be different channels.");
                }
                validateDestination(session.destinationUsername);
                session.stage = Stage.LOADING;
                sendText(chatId, "Reading source history. This can take a while for large channels.");
                jobExecutor.execute(() -> loadHistory(chatId, session));
            } catch (IllegalArgumentException exception) {
                sendText(chatId, exception.getMessage());
            } catch (TelegramApiException exception) {
                sendText(chatId,
                        "Could not verify the destination. Make sure it is public and the bot is an admin with Post Messages and Edit Messages permissions.");
            }
        }
    }

    private void createDestinationAndLoad(long chatId, BackSession session) {
        try {
            if (sessions.get(chatId) != session) {
                return;
            }
            User bot = execute(new GetMe());
            String title = channelTitle("مخزن - " + session.sourceTitle);
            HistoryForwarder.PublicChat destination =
                    historyForwarder.createChannelAndPromoteBot(title, bot.getId());
            session.destinationChatId = destination.chatId();
            session.destinationUsername = Long.toString(destination.chatId());
            if (sessions.get(chatId) != session) {
                sendText(chatId, "Created channel \"" + title
                        + "\", but this backup was cancelled before it could start.");
                return;
            }
            validateDestination(session.destinationUsername);
            sendText(chatId, "Created \"" + title + "\" and added the bot. Reading source history now.");
            session.stage = Stage.LOADING;
            loadHistory(chatId, session);
        } catch (Exception exception) {
            sessions.remove(chatId, session);
            sendText(chatId, "Could not create the destination channel or configure the bot. See the terminal for details.");
            LOGGER.log(Level.WARNING, "Could not create and configure a destination channel.", exception);
        }
    }

    private static String channelTitle(String title) {
        int maximumCodePoints = 128;
        int codePointCount = title.codePointCount(0, title.length());
        if (codePointCount <= maximumCodePoints) {
            return title;
        }
        return title.substring(0, title.offsetByCodePoints(0, maximumCodePoints));
    }

    private void handleChannelDeleteCommand(Message message) {
        String text = message.getText();
        if (text == null || !text.matches("(?is)^/delete(?:@" + botUsername + ")?(?:\\s|$).*")) {
            return;
        }

        if (message.getChat() == null || !message.getChat().isChannelChat()) {
            return;
        }
        if (!text.matches("(?is)^/delete(?:@" + botUsername + ")?\\s+\\d+\\s+\\d+$")) {
            sendText(message.getChatId(), "Usage: /delete <first_message_id> <last_message_id>.");
            return;
        }

        Long configuredAdmin = configuredAdminId();
        if (configuredAdmin == null) {
            sendText(message.getChatId(), "Deletion is disabled: set BACKUP_ADMIN_USER_ID first.");
            return;
        }

        String token = null;
        try {
            String[] parts = text.trim().split("\\s+");
            long firstId = Long.parseLong(parts[1]);
            long lastId = Long.parseLong(parts[2]);
            long count = lastId - firstId + 1;
            if (firstId <= 0 || lastId < firstId || lastId > Integer.MAX_VALUE
                    || count > MAX_DELETE_RANGE) {
                sendText(message.getChatId(), "Use /delete <first_id> <last_id>; the inclusive range must be 1 to "
                        + MAX_DELETE_RANGE + " messages.");
                return;
            }

            validateCanDelete(message.getChat());
            token = UUID.randomUUID().toString();
            pendingDeletes.put(token, new PendingDelete(message.getChatId(), (int) firstId,
                    (int) lastId, System.currentTimeMillis() + DELETE_CONFIRMATION_TTL_MILLIS));
            pendingDeletes.entrySet().removeIf(entry ->
                    entry.getValue().expiresAtMillis() < System.currentTimeMillis());
            sendTextChecked(configuredAdmin,
                    "Deletion requested in " + message.getChat().getTitle() + " for message IDs "
                            + firstId + " through " + lastId + " (" + count + " messages).\n"
                            + "Confirm within 10 minutes with:\n/confirm_delete " + token
                            + "\nCancel with:\n/cancel_delete " + token);
            sendText(message.getChatId(), "Deletion request sent to the configured admin for private confirmation.");
        } catch (NumberFormatException exception) {
            sendText(message.getChatId(), "Message IDs must be whole numbers.");
        } catch (IllegalArgumentException exception) {
            sendText(message.getChatId(), exception.getMessage());
        } catch (TelegramApiException exception) {
            if (token != null) {
                pendingDeletes.remove(token);
            }
            sendText(message.getChatId(),
                    "Could not queue deletion. Confirm the bot is an administrator with Delete Messages permission and that the configured admin has opened its private chat.");
            LOGGER.log(Level.WARNING, "Could not queue a channel deletion request.", exception);
        }
    }

    private void validateCanDelete(Chat chat) throws TelegramApiException {
        User bot = execute(new GetMe());
        GetChatMember getMember = new GetChatMember();
        getMember.setChatId(Long.toString(chat.getId()));
        getMember.setUserId(bot.getId());
        ChatMember member = execute(getMember);
        if (!(member instanceof ChatMemberAdministrator administrator)
                || !Boolean.TRUE.equals(administrator.getCanDeleteMessages())) {
            throw new IllegalArgumentException("The bot must be a channel administrator with Delete Messages permission.");
        }
    }

    private void confirmDelete(long chatId, long userId, String token) {
        Long allowedUserId = configuredAdminId();
        if (allowedUserId == null || allowedUserId != userId) {
            sendText(chatId, "You are not allowed to confirm deletions.");
            return;
        }
        PendingDelete pending = pendingDeletes.remove(token);
        if (pending == null || pending.expiresAtMillis() < System.currentTimeMillis()) {
            sendText(chatId, "That deletion request is missing or has expired. Submit the channel command again.");
            return;
        }
        sendText(chatId, "Deleting the requested message range.");
        jobExecutor.execute(() -> deleteMessageRange(chatId, pending));
    }

    private void cancelDelete(long chatId, long userId, String token) {
        Long allowedUserId = configuredAdminId();
        if (allowedUserId == null || allowedUserId != userId) {
            sendText(chatId, "You are not allowed to cancel deletion requests.");
            return;
        }
        PendingDelete pending = pendingDeletes.remove(token);
        sendText(chatId, pending == null ? "That deletion request is missing or has expired."
                : "Deletion request cancelled.");
    }

    private void deleteMessageRange(long adminChatId, PendingDelete pending) {
        int deletedInSuccessfulBatches = 0;
        try {
            for (long start = pending.firstMessageId(); start <= pending.lastMessageId();
                    start += DELETE_BATCH_SIZE) {
                int end = (int) Math.min(start + DELETE_BATCH_SIZE - 1L, pending.lastMessageId());
                List<Integer> messageIds = new ArrayList<>(end - (int) start + 1);
                for (int messageId = (int) start; messageId <= end; messageId++) {
                    messageIds.add(messageId);
                }
                DeleteMessages request = new DeleteMessages();
                request.setChatId(Long.toString(pending.chatId()));
                request.setMessageIds(messageIds);
                if (!Boolean.TRUE.equals(execute(request))) {
                    throw new TelegramApiException("Telegram did not confirm the delete request.");
                }
                deletedInSuccessfulBatches += messageIds.size();
            }
            sendText(adminChatId, "Telegram accepted delete requests for "
                    + deletedInSuccessfulBatches + " message IDs. Messages unavailable or not deletable may be skipped.");
        } catch (Exception exception) {
            sendText(adminChatId, "Deletion stopped after successful requests covering "
                    + deletedInSuccessfulBatches + " message IDs. Check the channel and permissions, then retry.");
            LOGGER.log(Level.WARNING, "Channel message deletion failed after "
                    + deletedInSuccessfulBatches + " requested IDs.", exception);
        }
    }

    private void sendTextChecked(long chatId, String text) throws TelegramApiException {
        execute(SendMessage.builder().chatId(Long.toString(chatId)).text(text).build());
    }

    private void validateDestination(String destination) throws TelegramApiException {
        GetChat getChat = new GetChat();
        getChat.setChatId(destination);
        Chat chat = execute(getChat);
        if (!chat.isChannelChat()) {
            throw new IllegalArgumentException(
                    "The destination must be a channel. Send its public @username or t.me link.");
        }

        User bot = execute(new GetMe());
        GetChatMember getMember = new GetChatMember();
        getMember.setChatId(destination);
        getMember.setUserId(bot.getId());
        ChatMember member = execute(getMember);
        if (!(member instanceof ChatMemberAdministrator administrator)
                || !Boolean.TRUE.equals(administrator.getCanPostMessages())
                || !Boolean.TRUE.equals(administrator.getCanEditMessages())) {
            throw new IllegalArgumentException(
                    "The bot must be a channel administrator with Post Messages and Edit Messages permissions to post and pin the heading.");
        }
    }

    private void loadHistory(long chatId, BackSession session) {
        try {
            HistoryForwarder.ChannelHistory history = historyForwarder.readMessageIds(
                    session.sourceUsername, () -> sessions.get(chatId) == session);
            if (sessions.get(chatId) != session) {
                return;
            }
            List<Long> messageIds = history.messageIds();
            if (messageIds.isEmpty()) {
                sessions.remove(chatId, session);
                sendText(chatId, "No forwardable posts were found in that source channel.");
                return;
            }
            session.sourceTitle = history.title();
            session.sourceChatId = history.sourceChatId();
            if (session.destinationChatId == 0) {
                session.destinationChatId = historyForwarder.findPublicChatId(session.destinationUsername);
            }
            Optional<BackupCheckpointStore.Checkpoint> savedCheckpoint = checkpointStore.load(
                    session.sourceChatId, session.destinationChatId);
            if (savedCheckpoint.isPresent()) {
                BackupCheckpointStore.Checkpoint saved = savedCheckpoint.get();
                TreeSet<Long> allMessageIds = new TreeSet<>(saved.messageIds());
                allMessageIds.addAll(messageIds);
                session.messageIds = List.copyOf(allMessageIds);
                session.checkpoint = new BackupCheckpointStore.Checkpoint(
                        saved.sourceChatId(), saved.destinationChatId(), session.messageIds,
                        Math.min(saved.nextIndex(), session.messageIds.size()), saved.headingMessageId());
            } else {
                session.messageIds = messageIds;
            }
            session.stage = Stage.CONFIRM;
            String resumeInstruction = session.checkpoint == null ? ""
                    : " A saved checkpoint exists at " + session.checkpoint.nextIndex() + " of "
                    + session.messageIds.size() + "; reply /resume to continue it, or /confirm to start over.";
            sendText(chatId, "Found " + messageIds.size()
                    + " posts." + resumeInstruction
                    + " Reply /confirm to start from the oldest post, or /cancel to stop.");
        } catch (java.util.concurrent.CancellationException exception) {
            return;
        } catch (Exception exception) {
            sessions.remove(chatId, session);
            sendText(chatId,
                    "Could not read that channel history. Check that it is public and that the bot can access it.");
            LOGGER.log(Level.WARNING, "History loading failed for @" + session.sourceUsername + ".", exception);
        }
    }

    private void forwardHistory(long chatId, BackSession session, boolean resume) {
        int forwarded = 0;
        int skipped = 0;
        try {
            if (sessions.get(chatId) != session) {
                return;
            }
            BackupCheckpointStore.Checkpoint checkpoint = resume ? session.checkpoint
                    : new BackupCheckpointStore.Checkpoint(session.sourceChatId, session.destinationChatId,
                    session.messageIds, 0, 0);
            if (!resume) {
                checkpointStore.save(checkpoint);
            }

            if (checkpoint.headingMessageId() == 0) {
                Message heading = execute(SendMessage.builder()
                        .chatId(session.destinationUsername)
                        .text("Contents of: (\"" + session.sourceTitle + "\")")
                        .build());
                checkpoint = checkpoint.withProgress(checkpoint.nextIndex(), heading.getMessageId());
                checkpointStore.save(checkpoint);
            }
            PinChatMessage pinHeading = new PinChatMessage();
            pinHeading.setChatId(session.destinationUsername);
            pinHeading.setMessageId(Math.toIntExact(checkpoint.headingMessageId()));
            execute(pinHeading);

            Set<Long> verifiedSourceIds = new HashSet<>(historyForwarder.findForwardedSourceMessageIds(
                    session.sourceChatId, session.destinationChatId, session.messageIds,
                    () -> sessions.get(chatId) == session));
            sendText(chatId, "Checked destination: " + verifiedSourceIds.size() + " of "
                    + session.messageIds.size() + " source posts are already present.");
            for (int start = 0; start < session.messageIds.size(); start += FORWARD_BATCH_SIZE) {
                if (sessions.get(chatId) != session) {
                    return;
                }

                int end = Math.min(start + FORWARD_BATCH_SIZE, session.messageIds.size());
                List<Long> batch = session.messageIds.subList(start, end);
                List<Long> missing = batch.stream().filter(id -> !verifiedSourceIds.contains(id)).toList();
                if (!missing.isEmpty()) {
                    HistoryForwarder.ForwardResult result = historyForwarder.forwardMessages(
                            session.sourceChatId, session.destinationChatId, missing);
                    forwarded += result.forwarded();
                    skipped += result.skipped();
                    verifiedSourceIds.addAll(result.forwardedSourceMessageIds());
                }
                checkpoint = checkpoint.withProgress(end, checkpoint.headingMessageId());
                checkpointStore.save(checkpoint);

                if (end < session.messageIds.size()) {
                    Thread.sleep(BATCH_DELAY_MILLIS);
                }
                if (forwarded > 0 && forwarded % 1000 < FORWARD_BATCH_SIZE) {
                    sendText(chatId, "Forwarded " + forwarded + " of " + session.messageIds.size() + " posts.");
                }
            }
            Set<Long> verifiedAfterRun = historyForwarder.findForwardedSourceMessageIds(
                    session.sourceChatId, session.destinationChatId, session.messageIds,
                    () -> sessions.get(chatId) == session);
            List<Long> missingIds = session.messageIds.stream()
                    .filter(id -> !verifiedAfterRun.contains(id)).toList();
            if (missingIds.isEmpty()) {
                checkpointStore.delete(session.sourceChatId, session.destinationChatId);
                sendText(chatId, "Finished and verified: all " + session.messageIds.size()
                        + " source posts are present in the destination. Newly forwarded: " + forwarded
                        + "; Telegram could not forward " + skipped + ".");
            } else {
                checkpointStore.save(checkpoint.withProgress(session.messageIds.size(),
                        checkpoint.headingMessageId()));
                String missingSample = missingIds.stream().limit(10).map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", "));
                sendText(chatId, "Finished with verification incomplete: " + verifiedAfterRun.size()
                        + " of " + session.messageIds.size() + " source posts are present; "
                        + missingIds.size() + " are missing. First missing source IDs: " + missingSample
                        + ". Send /back to this channel pair and /resume to retry missing posts.");
            }
        } catch (Exception exception) {
            sendText(chatId, "Forwarding stopped after " + forwarded
                    + " posts. Check the source and destination permissions, then start again.");
            LOGGER.log(Level.WARNING, "History forwarding failed after " + forwarded + " posts.", exception);
        } finally {
            sessions.remove(chatId, session);
        }
    }

    private void sendText(long chatId, String text) {
        try {
            sendTextChecked(chatId, text);
        } catch (TelegramApiException exception) {
            LOGGER.warning("Could not send a bot reply: " + exception.getClass().getSimpleName());
        }
    }

    private static Long configuredAdminId() {
        String value = System.getenv("BACKUP_ADMIN_USER_ID");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String parsePublicUsername(String input) {
        String value = input.trim();
        if (value.startsWith("@")) {
            value = value.substring(1);
        } else if (value.startsWith("http://") || value.startsWith("https://")) {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("t.me") || host.equalsIgnoreCase("telegram.me"))) {
                throw new IllegalArgumentException("Use a public channel username or a t.me link.");
            }
            String[] parts = uri.getPath().split("/");
            int index = parts.length > 1 && parts[1].equalsIgnoreCase("s") ? 2 : 1;
            if (index >= parts.length) {
                throw new IllegalArgumentException("That t.me link does not contain a public channel username.");
            }
            value = parts[index];
        }

        if (!value.matches("[A-Za-z][A-Za-z0-9_]{4,31}")) {
            throw new IllegalArgumentException(
                    "Use a public channel username (at least 5 characters) or a t.me link. Private invite links are not supported.");
        }
        return value;
    }

    public static void main(String[] args) throws Exception {
        String botToken = System.getenv("TELEGRAM_BOT_TOKEN");
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException("Set the TELEGRAM_BOT_TOKEN environment variable.");
        }
        String botUsername = System.getenv("TELEGRAM_BOT_USERNAME");
        if (botUsername == null || botUsername.isBlank()) {
            throw new IllegalStateException(
                    "Set TELEGRAM_BOT_USERNAME to the bot username assigned by BotFather, without @.");
        }
        botUsername = botUsername.trim().replaceFirst("^@", "");

        String apiId = System.getenv("TELEGRAM_API_ID");
        String apiHash = System.getenv("TELEGRAM_API_HASH");
        if (apiId == null || apiId.isBlank() || apiHash == null || apiHash.isBlank()) {
            throw new IllegalStateException(
                    "Set TELEGRAM_API_ID and TELEGRAM_API_HASH from my.telegram.org for history access.");
        }

        try (HistoryForwarder historyForwarder = new HistoryForwarder(apiId, apiHash)) {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            try (Backup backup = new Backup(botToken, botUsername, historyForwarder)) {
                User actualBot = backup.execute(new GetMe());
                if (!botUsername.equalsIgnoreCase(actualBot.getUserName())) {
                    throw new IllegalStateException("TELEGRAM_BOT_USERNAME does not match the bot token.");
                }
                botsApi.registerBot(backup);
                new java.util.concurrent.CountDownLatch(1).await();
            }
        }
    }

    @Override
    public void close() {
        jobExecutor.shutdownNow();
    }

    private enum Stage {
        SOURCE,
        DESTINATION,
        CREATING,
        LOADING,
        CONFIRM,
        FORWARDING
    }

    private static final class BackSession {
        private volatile Stage stage = Stage.SOURCE;
        private String sourceUsername;
        private volatile String sourceTitle;
        private String destinationUsername;
        private long sourceChatId;
        private long destinationChatId;
        private volatile List<Long> messageIds = List.of();
        private BackupCheckpointStore.Checkpoint checkpoint;
    }

    private record PendingDelete(long chatId, int firstMessageId, int lastMessageId, long expiresAtMillis) {
    }
}