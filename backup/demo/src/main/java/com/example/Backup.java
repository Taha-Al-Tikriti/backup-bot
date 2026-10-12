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
    private final Map<String, PendingSync> pendingSyncs = new ConcurrentHashMap<>();
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
            Message channelPost = update.getChannelPost();
            handleChannelSyncCommand(channelPost);
            handleChannelDeleteCommand(channelPost);
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

        if (text.startsWith("/confirm_sync ")) {
            confirmSync(chatId, userId, text.substring("/confirm_sync ".length()).trim());
            return;
        }
        if (text.startsWith("/cancel_sync ")) {
            cancelSync(chatId, userId, text.substring("/cancel_sync ".length()).trim());
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

        if ("/sync".equals(text)) {
            Long allowedUserId = configuredAdminId();
            if (allowedUserId == null) {
                sendText(chatId, "Set BACKUP_ADMIN_USER_ID first. Use /id to find your Telegram user ID.");
                return;
            }
            if (allowedUserId != userId) {
                sendText(chatId, "You are not allowed to sync backups.");
                return;
            }

            BackSession currentSession = sessions.get(chatId);
            if (currentSession != null
                    && (currentSession.stage == Stage.CREATING || currentSession.stage == Stage.LOADING
                    || currentSession.stage == Stage.FORWARDING)) {
                sendText(chatId, "A backup or sync is already running. Send /cancel before starting another.");
                return;
            }

            BackSession session = new BackSession();
            session.syncOnly = true;
            sessions.put(chatId, session);
            sendText(chatId, "Send the public source channel username or t.me link to compare with the backup.");
            return;
        }

        BackSession session = sessions.get(chatId);
        if (session == null) {
            return;
        }

        if (("/confirm".equals(text) || "/resume".equals(text)) && session.stage == Stage.CONFIRM) {
            boolean resume = "/resume".equals(text);
            if (session.syncOnly && resume) {
                sendText(chatId, "This is a sync, not a checkpoint resume. Reply /confirm to compare the destination and add missing posts, or /cancel to stop.");
                return;
            }
            if (resume && session.checkpoint == null) {
                sendText(chatId, "There is no saved backup checkpoint to resume. Reply /confirm to start a new backup, or /cancel to stop.");
                return;
            }
            session.stage = Stage.FORWARDING;
            String startMessage;
            if (session.syncOnly) {
                startMessage = "Sync started. I’m checking destination history and will append missing source posts oldest to newest. No new heading will be posted. Send /cancel to stop.";
            } else if (resume) {
                startMessage = "Backup resume started. I’m checking the destination, skipping source posts already found, and retrying missing posts in source order. The existing heading will be kept. Send /cancel to stop.";
            } else {
                startMessage = "Backup started. I’m checking the destination and forwarding posts not already found, in source order. A new heading will be posted. Send /cancel to stop.";
            }
            sendText(chatId, startMessage);
            jobExecutor.execute(() -> forwardHistory(chatId, session, resume));
            return;
        }

        if ("/ready".equalsIgnoreCase(text) && session.stage == Stage.CHANNEL_SETUP) {
            try {
                validateDestination(session.destinationUsername);
                session.stage = Stage.LOADING;
                sendText(chatId, "Bot permissions verified. Reading source history now.");
                jobExecutor.execute(() -> loadHistory(chatId, session));
            } catch (TelegramApiException exception) {
                sendText(chatId,
                        "I can't access that new channel yet. Add the bot as an administrator with Post Messages and Edit Messages permissions, then send /ready again.");
                LOGGER.log(Level.WARNING, "Could not verify bot access to the newly created channel.", exception);
            }
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
                if (session.syncOnly) {
                    sendText(chatId, "Sync requires an existing destination channel. Send its public username or t.me link.");
                    return;
                }
                session.stage = Stage.CREATING;
                sendText(chatId, "Creating the new channel. This may take a moment.");
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
            String title = channelTitle("مخزن - " + session.sourceTitle);
            HistoryForwarder.PublicChat destination =
                    historyForwarder.createChannel(title);
            session.destinationChatId = destination.chatId();
            session.destinationUsername = Long.toString(destination.chatId());
            if (sessions.get(chatId) != session) {
                sendText(chatId, "Created channel \"" + title
                        + "\", but this backup was cancelled before it could start.");
                return;
            }
            session.stage = Stage.CHANNEL_SETUP;
            sendText(chatId, "Created \"" + title + "\". Telegram requires you to add the bot manually: "
                    + "open the channel's Administrators settings, add @" + botUsername
                    + ", and grant Post Messages and Edit Messages. Then send /ready here.");
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

    private void handleChannelSyncCommand(Message message) {
        String text = message.getText();
        if (text == null || message.getChat() == null || !message.getChat().isChannelChat()
                || !text.matches("(?is)^/sync(?:@" + botUsername + ")?(?:\\s|$).*")) {
            return;
        }

        String[] parts = text.trim().split("\\s+");
        if (parts.length != 2) {
            sendText(message.getChatId(), "Usage: /sync <public_source_username_or_t.me_link>.");
            return;
        }

        Long configuredAdmin = configuredAdminId();
        if (configuredAdmin == null) {
            sendText(message.getChatId(), "Sync is disabled: set BACKUP_ADMIN_USER_ID first.");
            return;
        }

        String sourceUsername;
        try {
            sourceUsername = parsePublicUsername(parts[1]);
        } catch (IllegalArgumentException exception) {
            sendText(message.getChatId(), exception.getMessage());
            return;
        }

        String token = UUID.randomUUID().toString();
        pendingSyncs.put(token, new PendingSync(message.getChatId(), sourceUsername,
                System.currentTimeMillis() + DELETE_CONFIRMATION_TTL_MILLIS));
        pendingSyncs.entrySet().removeIf(entry ->
                entry.getValue().expiresAtMillis() < System.currentTimeMillis());
        try {
            sendTextChecked(configuredAdmin,
                    "A sync was requested in " + message.getChat().getTitle() + " for source @"
                            + sourceUsername + ". Confirm within 10 minutes with:\n/confirm_sync " + token
                            + "\nCancel with:\n/cancel_sync " + token);
            sendText(message.getChatId(), "Sync request sent to the configured admin for private confirmation.");
        } catch (TelegramApiException exception) {
            pendingSyncs.remove(token);
            sendText(message.getChatId(),
                    "Could not notify the configured admin. They must start the bot in a private chat first.");
            LOGGER.log(Level.WARNING, "Could not send a channel sync request to the configured admin.", exception);
        }
    }

    private void confirmSync(long chatId, long userId, String token) {
        Long allowedUserId = configuredAdminId();
        if (allowedUserId == null || allowedUserId != userId) {
            sendText(chatId, "You are not allowed to confirm sync requests.");
            return;
        }
        BackSession currentSession = sessions.get(chatId);
        if (currentSession != null
                && (currentSession.stage == Stage.CREATING || currentSession.stage == Stage.LOADING
                || currentSession.stage == Stage.FORWARDING)) {
            sendText(chatId, "A backup or sync is already running. Send /cancel before starting another.");
            return;
        }
        PendingSync pending = pendingSyncs.remove(token);
        if (pending == null || pending.expiresAtMillis() < System.currentTimeMillis()) {
            sendText(chatId, "That sync request is missing or has expired. Submit the channel command again.");
            return;
        }

        BackSession session = new BackSession();
        session.syncOnly = true;
        session.sourceUsername = pending.sourceUsername();
        session.destinationChatId = pending.destinationChatId();
        session.destinationUsername = Long.toString(pending.destinationChatId());
        session.stage = Stage.LOADING;
        try {
            validateDestination(session.destinationUsername);
            sessions.put(chatId, session);
            sendText(chatId, "Sync confirmed. Comparing the source with the channel and preparing to add missing posts.");
            jobExecutor.execute(() -> loadHistory(chatId, session));
        } catch (IllegalArgumentException exception) {
            sendText(chatId, exception.getMessage());
        } catch (TelegramApiException exception) {
            sendText(chatId,
                    "Could not access the destination channel. Verify that the bot is an administrator with Post Messages and Edit Messages permissions.");
            LOGGER.log(Level.WARNING, "Could not validate a channel sync destination.", exception);
        }
    }

    private void cancelSync(long chatId, long userId, String token) {
        Long allowedUserId = configuredAdminId();
        if (allowedUserId == null || allowedUserId != userId) {
            sendText(chatId, "You are not allowed to cancel sync requests.");
            return;
        }
        PendingSync pending = pendingSyncs.remove(token);
        sendText(chatId, pending == null ? "That sync request is missing or has expired."
                : "Sync request cancelled.");
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
            if (session.destinationChatId != 0 && history.sourceChatId() == session.destinationChatId) {
                sessions.remove(chatId, session);
                sendText(chatId, "Source and destination must be different channels.");
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
            Optional<BackupCheckpointStore.Checkpoint> savedCheckpoint = Optional.empty();
            if (session.syncOnly) {
                session.messageIds = messageIds;
            } else {
                savedCheckpoint = checkpointStore.load(session.sourceChatId, session.destinationChatId);
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
            }
            session.stage = Stage.CONFIRM;
            String nextAction;
            if (session.syncOnly) {
                nextAction = " The destination will be checked, and only missing posts will be appended oldest to newest. Reply /confirm to run the sync, or /cancel to stop.";
            } else if (session.checkpoint != null) {
                nextAction = " Saved checkpoint cursor: " + session.checkpoint.nextIndex() + " of "
                        + session.messageIds.size() + " source posts. This is processing progress only; it does not indicate how many posts are in the destination. Reply /resume to recheck the destination and retry missing posts while keeping the existing heading, /confirm to start a new backup run with a new heading, or /cancel to stop.";
            } else {
                nextAction = " Reply /confirm to start the backup, or /cancel to stop.";
            }
            sendText(chatId, "Found " + session.messageIds.size() + " source posts." + nextAction);
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
            BackupCheckpointStore.Checkpoint checkpoint;
            if (resume) {
                checkpoint = session.checkpoint;
            } else if (session.syncOnly) {
                Optional<BackupCheckpointStore.Checkpoint> saved = checkpointStore.load(
                        session.sourceChatId, session.destinationChatId);
                long headingMessageId = saved.map(BackupCheckpointStore.Checkpoint::headingMessageId).orElse(0L);
                checkpoint = new BackupCheckpointStore.Checkpoint(session.sourceChatId,
                        session.destinationChatId, session.messageIds, 0, headingMessageId);
            } else {
                checkpoint = new BackupCheckpointStore.Checkpoint(session.sourceChatId, session.destinationChatId,
                        session.messageIds, 0, 0);
            }
            if (!resume) {
                checkpointStore.save(checkpoint);
            }

            if (!session.syncOnly && checkpoint.headingMessageId() == 0) {
                Message heading = execute(SendMessage.builder()
                        .chatId(session.destinationUsername)
                        .text("Contents of: (\"" + session.sourceTitle + "\")")
                        .build());
                checkpoint = checkpoint.withProgress(checkpoint.nextIndex(), heading.getMessageId());
                checkpointStore.save(checkpoint);
            }
            if (!session.syncOnly) {
                PinChatMessage pinHeading = new PinChatMessage();
                pinHeading.setChatId(session.destinationUsername);
                pinHeading.setMessageId(Math.toIntExact(checkpoint.headingMessageId()));
                execute(pinHeading);
            }

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
                if (session.syncOnly) {
                    sendText(chatId, "Sync complete and verified: all " + session.messageIds.size()
                            + " source posts are present in the destination. Added " + forwarded
                            + " missing posts in source order; Telegram could not forward " + skipped + ".");
                } else {
                    sendText(chatId, "Finished and verified: all " + session.messageIds.size()
                            + " source posts are present in the destination. Newly forwarded: " + forwarded
                            + "; Telegram could not forward " + skipped + ".");
                }
            } else {
                checkpointStore.save(checkpoint.withProgress(session.messageIds.size(),
                        checkpoint.headingMessageId()));
                String missingSample = missingIds.stream().limit(10).map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", "));
                String retryInstructions = session.syncOnly
                        ? " Run /sync again with this source and destination to retry."
                        : " Send /back to this channel pair and /resume to retry missing posts.";
                sendText(chatId, (session.syncOnly ? "Sync finished" : "Finished")
                        + " with verification incomplete: " + verifiedAfterRun.size()
                        + " of " + session.messageIds.size() + " source posts are present; "
                        + missingIds.size() + " are missing. First missing source IDs: " + missingSample
                        + "." + retryInstructions);
            }
        } catch (Exception exception) {
            sendText(chatId, (session.syncOnly ? "Sync" : "Backup forwarding") + " stopped after " + forwarded
                    + " posts were forwarded during this run. Check source and destination permissions, then retry.");
            LOGGER.log(Level.WARNING, (session.syncOnly ? "History sync" : "History forwarding")
                    + " failed after " + forwarded + " posts.", exception);
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
        CHANNEL_SETUP,
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
        private boolean syncOnly;
    }

    private record PendingDelete(long chatId, int firstMessageId, int lastMessageId, long expiresAtMillis) {
    }

    private record PendingSync(long destinationChatId, String sourceUsername, long expiresAtMillis) {
    }
}