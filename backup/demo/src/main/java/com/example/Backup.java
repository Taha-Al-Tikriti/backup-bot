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
    private static final int FORWARD_BATCH_SIZE = 25;
    private static final int DELETE_BATCH_SIZE = 100;
    private static final int MAX_DELETE_RANGE = 10_000;
    private static final long DELETE_CONFIRMATION_TTL_MILLIS = 10 * 60 * 1000;
    private static final int DUPLICATE_SCAN_REPORT_LIMIT = 3500;

    private final String botUsername;
    private final HistoryForwarder historyForwarder;
    private final BackupCheckpointStore checkpointStore = new BackupCheckpointStore();
    private final Map<Long, BackSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();
    private final Map<String, PendingChannelRequest> pendingChannelRequests = new ConcurrentHashMap<>();
    private final Map<String, PendingDuplicateScan> pendingDuplicateScans = new ConcurrentHashMap<>();
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
            handleChannelBackupCommand(channelPost);
            handleChannelSyncCommand(channelPost);
            handleChannelDuplicateScanCommand(channelPost);
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
        if (text.startsWith("/cancel_back ")) {
            cancelChannelRequest(chatId, userId, text.substring("/cancel_back ".length()).trim(), false);
            return;
        }
        if (text.startsWith("/confirm_back ")) {
            confirmChannelRequest(chatId, userId, text.substring("/confirm_back ".length()).trim(), false);
            return;
        }
        if (text.startsWith("/cancel_sync ")) {
            cancelChannelRequest(chatId, userId, text.substring("/cancel_sync ".length()).trim(), true);
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
        if (text.startsWith("/confirm_scan_duplicates ")) {
            confirmDuplicateScan(chatId, userId,
                    text.substring("/confirm_scan_duplicates ".length()).trim());
            return;
        }
        if (text.startsWith("/cancel_scan_duplicates ")) {
            cancelDuplicateScan(chatId, userId,
                    text.substring("/cancel_scan_duplicates ".length()).trim());
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
            sendText(chatId, "Send the public source channel username, a channel link, or a link to the post to start from.");
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
                startMessage = "Sync started. I’m checking destination history and appending missing posts in source order. No new heading will be posted. Send /cancel to stop.";
            } else if (resume) {
                startMessage = "Backup resume started. I’m checking the destination and forwarding missing posts in source order. The existing heading will be kept. Send /cancel to stop.";
            } else {
                startMessage = "Backup started. I’m checking the destination and forwarding missing posts in source order. A new heading will be posted. Send /cancel to stop.";
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
                SourceReference sourceReference = parseSourceReference(text);
                session.sourceUsername = sourceReference.username();
                session.startMessageId = sourceReference.messageId();
                HistoryForwarder.PublicChat source = historyForwarder.findPublicChat(session.sourceUsername);
                session.sourceTitle = source.title();
                session.sourceChatId = source.chatId();
                if (session.startMessageId == null) {
                    session.stage = Stage.SOURCE_START;
                    sendText(chatId, "Send the starting source post link or message ID, or /all to back up the full history.");
                } else {
                    promptForDestination(chatId, session);
                }
            } catch (IllegalArgumentException exception) {
                sendText(chatId, exception.getMessage());
            } catch (Exception exception) {
                sendText(chatId, "Could not find that public source channel.");
                LOGGER.log(Level.WARNING, "Could not look up source channel @" + session.sourceUsername + ".", exception);
            }
            return;
        }

        if (session.stage == Stage.SOURCE_START) {
            if (!"/all".equalsIgnoreCase(text)) {
                try {
                    session.startMessageId = parseStartMessageId(text, session.sourceUsername);
                } catch (IllegalArgumentException exception) {
                    sendText(chatId, exception.getMessage());
                    return;
                }
            }
            promptForDestination(chatId, session);
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

    private void promptForDestination(long chatId, BackSession session) {
        session.stage = Stage.DESTINATION;
        String startMessage = session.startMessageId == null
                ? "the full source history"
                : "source posts from message " + session.startMessageId + " onward";
        sendText(chatId, "I’ll back up " + startMessage + ". Send an existing destination channel username or t.me link, "
                + "or send /new to create a channel named \"مخزن - " + session.sourceTitle + "\".");
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

    private void handleChannelBackupCommand(Message message) {
        handleChannelRequest(message, false);
    }

    private void handleChannelSyncCommand(Message message) {
        handleChannelRequest(message, true);
    }

    private void handleChannelDuplicateScanCommand(Message message) {
        String text = message.getText();
        if (text == null || !text.matches("(?is)^/scan_duplicates(?:@" + botUsername + ")?(?:\\s|$).*")) {
            return;
        }
        if (message.getChat() == null || !message.getChat().isChannelChat()) {
            return;
        }
        if (!text.matches("(?is)^/scan_duplicates(?:@" + botUsername + ")?$")) {
            sendText(message.getChatId(), "Usage: /scan_duplicates.");
            return;
        }

        Long configuredAdmin = configuredAdminId();
        if (configuredAdmin == null) {
            sendText(message.getChatId(), "Duplicate scanning is disabled: set BACKUP_ADMIN_USER_ID first.");
            return;
        }

        String token = UUID.randomUUID().toString();
        pendingDuplicateScans.put(token, new PendingDuplicateScan(message.getChatId(),
                message.getChat().getTitle(),
                System.currentTimeMillis() + DELETE_CONFIRMATION_TTL_MILLIS));
        pendingDuplicateScans.entrySet().removeIf(entry ->
                entry.getValue().expiresAtMillis() < System.currentTimeMillis());
        try {
            sendTextChecked(configuredAdmin,
                    "A read-only repeated-forward scan was requested in "
                            + message.getChat().getTitle()
                            + ". It will report duplicate destination message IDs and will not modify messages."
                            + "\nConfirm within 10 minutes with:\n/confirm_scan_duplicates " + token
                            + "\nCancel with:\n/cancel_scan_duplicates " + token);
            sendText(message.getChatId(),
                    "Repeated-forward scan request sent to the configured admin for private confirmation.");
        } catch (TelegramApiException exception) {
            pendingDuplicateScans.remove(token);
            sendText(message.getChatId(),
                    "Could not notify the configured admin. They must start the bot in a private chat first.");
            LOGGER.log(Level.WARNING, "Could not send a repeated-forward scan request to the configured admin.",
                    exception);
        }
    }

    private void confirmDuplicateScan(long chatId, long userId, String token) {
        Long allowedUserId = configuredAdminId();
        if (allowedUserId == null || allowedUserId != userId) {
            sendText(chatId, "You are not allowed to confirm duplicate scans.");
            return;
        }
        PendingDuplicateScan pending = pendingDuplicateScans.get(token);
        if (pending == null || pending.expiresAtMillis() < System.currentTimeMillis()) {
            pendingDuplicateScans.remove(token);
            sendText(chatId, "That duplicate scan request is missing or has expired. Submit the channel command again.");
            return;
        }
        pendingDuplicateScans.remove(token, pending);
        sendText(chatId, "Read-only repeated-forward scan started for " + pending.destinationTitle() + ".");
        jobExecutor.execute(() -> scanDuplicateForwards(chatId, pending));
    }

    private void cancelDuplicateScan(long chatId, long userId, String token) {
        Long allowedUserId = configuredAdminId();
        if (allowedUserId == null || allowedUserId != userId) {
            sendText(chatId, "You are not allowed to cancel duplicate scans.");
            return;
        }
        PendingDuplicateScan pending = pendingDuplicateScans.remove(token);
        if (pending == null || pending.expiresAtMillis() < System.currentTimeMillis()) {
            sendText(chatId, "That duplicate scan request is missing or has expired.");
            return;
        }
        sendText(chatId, "Duplicate scan request cancelled.");
    }

    private void scanDuplicateForwards(long adminChatId, PendingDuplicateScan pending) {
        try {
            HistoryForwarder.RepeatedForwardScan scan =
                    historyForwarder.scanRepeatedForwardMessages(pending.destinationChatId());
            sendDuplicateScanReport(adminChatId, pending.destinationTitle(), scan);
        } catch (Exception exception) {
            sendText(adminChatId, "Repeated-forward scan failed for " + pending.destinationTitle()
                    + ". Reason: " + rootCauseMessage(exception));
            LOGGER.log(Level.WARNING, "Repeated-forward scan failed for destination "
                    + pending.destinationChatId() + ".", exception);
        }
    }

    private void sendDuplicateScanReport(long chatId, String destinationTitle,
                                         HistoryForwarder.RepeatedForwardScan scan)
            throws TelegramApiException {
        if (scan.repeatedForwards().isEmpty()) {
            sendTextChecked(chatId, "Repeated-forward scan complete for " + destinationTitle + ". Scanned "
                    + scan.scannedMessages() + " destination messages; no repeated forwarded channel posts found.");
            return;
        }

        StringBuilder report = new StringBuilder("Repeated-forward scan for ")
                .append(destinationTitle).append(" scanned ").append(scan.scannedMessages())
                .append(" destination messages and found ")
                .append(scan.repeatedForwards().size())
                .append(" repeated source posts. No messages were modified or deleted.\n");

        for (HistoryForwarder.RepeatedForward repeated : scan.repeatedForwards()) {
            String sourcePrefix = "Source chat " + repeated.sourceChatId() + ", message "
                    + repeated.sourceMessageId() + " is repeated at destination message IDs: ";
            if (report.length() + sourcePrefix.length() > DUPLICATE_SCAN_REPORT_LIMIT) {
                sendTextChecked(chatId, report.toString());
                report.setLength(0);
                report.append("Repeated-forward scan results (continued).\n");
            }
            report.append(sourcePrefix);
            for (int index = 0; index < repeated.destinationMessageIds().size(); index++) {
                String destinationId = (index == 0 ? "" : ", ")
                        + repeated.destinationMessageIds().get(index);
                if (report.length() + destinationId.length() > DUPLICATE_SCAN_REPORT_LIMIT) {
                    sendTextChecked(chatId, report.toString());
                    report.setLength(0);
                    report.append("Source chat ").append(repeated.sourceChatId())
                            .append(", message ").append(repeated.sourceMessageId())
                            .append(" (continued): ");
                }
                report.append(destinationId);
            }
            report.append('\n');
        }
        if (!report.isEmpty()) {
            sendTextChecked(chatId, report.toString());
        }
    }

    private void handleChannelRequest(Message message, boolean syncOnly) {
        String text = message.getText();
        String command = syncOnly ? "sync" : "back";
        if (text == null || message.getChat() == null || !message.getChat().isChannelChat()
                || !text.matches("(?is)^/" + command + "(?:@" + botUsername + ")?(?:\\s|$).*")) {
            return;
        }

        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2 || parts.length > (syncOnly ? 2 : 3)) {
            sendText(message.getChatId(), "Usage: /" + command + " <public_source_username_or_post_link>"
                    + (syncOnly ? "." : " [starting_message_id_or_link]."));
            return;
        }

        Long configuredAdmin = configuredAdminId();
        if (configuredAdmin == null) {
            sendText(message.getChatId(), (syncOnly ? "Sync" : "Backup")
                    + " is disabled: set BACKUP_ADMIN_USER_ID first.");
            return;
        }

        SourceReference sourceReference;
        try {
            sourceReference = parseSourceReference(parts[1]);
            if (syncOnly && sourceReference.messageId() != null) {
                throw new IllegalArgumentException(
                        "Sync accepts a source channel link or username, not a starting message.");
            }
            if (parts.length == 3) {
                if (sourceReference.messageId() != null) {
                    throw new IllegalArgumentException(
                            "Provide the starting post either in the source link or as the optional third argument, not both.");
                }
                sourceReference = new SourceReference(sourceReference.username(),
                        parseStartMessageId(parts[2], sourceReference.username()));
            }
        } catch (IllegalArgumentException exception) {
            sendText(message.getChatId(), exception.getMessage());
            return;
        }

        String token = UUID.randomUUID().toString();
        pendingChannelRequests.put(token, new PendingChannelRequest(message.getChatId(),
                sourceReference.username(), sourceReference.messageId(), syncOnly,
                System.currentTimeMillis() + DELETE_CONFIRMATION_TTL_MILLIS));
        pendingChannelRequests.entrySet().removeIf(entry ->
                entry.getValue().expiresAtMillis() < System.currentTimeMillis());
        try {
            sendTextChecked(configuredAdmin,
                    "A " + (syncOnly ? "sync" : "backup") + " was requested in "
                            + message.getChat().getTitle() + " for source @" + sourceReference.username()
                            + (sourceReference.messageId() == null ? ""
                            : " starting at message " + sourceReference.messageId())
                            + ". Confirm within 10 minutes with:\n/confirm_" + command + " " + token
                            + "\nCancel with:\n/cancel_" + command + " " + token);
            sendText(message.getChatId(), (syncOnly ? "Sync" : "Backup")
                    + " request sent to the configured admin for private confirmation.");
        } catch (TelegramApiException exception) {
            pendingChannelRequests.remove(token);
            sendText(message.getChatId(),
                    "Could not notify the configured admin. They must start the bot in a private chat first.");
            LOGGER.log(Level.WARNING, "Could not send a channel " + command
                    + " request to the configured admin.", exception);
        }
    }

    private void confirmSync(long chatId, long userId, String token) {
        confirmChannelRequest(chatId, userId, token, true);
    }

    private void confirmChannelRequest(long chatId, long userId, String token, boolean syncOnly) {
        Long allowedUserId = configuredAdminId();
        if (allowedUserId == null || allowedUserId != userId) {
            sendText(chatId, "You are not allowed to confirm channel " + (syncOnly ? "sync" : "backup")
                    + " requests.");
            return;
        }
        BackSession currentSession = sessions.get(chatId);
        if (currentSession != null
                && (currentSession.stage == Stage.CREATING || currentSession.stage == Stage.LOADING
                || currentSession.stage == Stage.FORWARDING)) {
            sendText(chatId, "A backup or sync is already running. Send /cancel before starting another.");
            return;
        }
        PendingChannelRequest pending = pendingChannelRequests.get(token);
        if (pending == null || pending.syncOnly() != syncOnly
                || pending.expiresAtMillis() < System.currentTimeMillis()) {
            sendText(chatId, "That channel " + (syncOnly ? "sync" : "backup")
                    + " request is missing or has expired. Submit the channel command again.");
            return;
        }
        pendingChannelRequests.remove(token, pending);

        BackSession session = new BackSession();
        session.syncOnly = syncOnly;
        session.sourceUsername = pending.sourceUsername();
        session.startMessageId = pending.startMessageId();
        session.destinationChatId = pending.destinationChatId();
        session.destinationUsername = Long.toString(pending.destinationChatId());
        session.stage = Stage.LOADING;
        try {
            validateDestination(session.destinationUsername);
            sessions.put(chatId, session);
            sendText(chatId, syncOnly
                    ? "Sync confirmed. Comparing the source with the channel and preparing to add missing posts."
                    : "Backup confirmed. Reading the source history and preparing the channel backup.");
            jobExecutor.execute(() -> loadHistory(chatId, session));
        } catch (IllegalArgumentException exception) {
            sendText(chatId, exception.getMessage());
        } catch (TelegramApiException exception) {
            sendText(chatId,
                    "Could not access the destination channel. Verify that the bot is an administrator with Post Messages and Edit Messages permissions.");
            LOGGER.log(Level.WARNING, "Could not validate a channel " + (syncOnly ? "sync" : "backup")
                    + " destination.", exception);
        }
    }

    private void cancelChannelRequest(long chatId, long userId, String token, boolean syncOnly) {
        Long allowedUserId = configuredAdminId();
        if (allowedUserId == null || allowedUserId != userId) {
            sendText(chatId, "You are not allowed to cancel channel " + (syncOnly ? "sync" : "backup")
                    + " requests.");
            return;
        }
        PendingChannelRequest pending = pendingChannelRequests.get(token);
        if (pending == null || pending.syncOnly() != syncOnly) {
            sendText(chatId, "That channel " + (syncOnly ? "sync" : "backup")
                    + " request is missing or has expired.");
            return;
        }
        pendingChannelRequests.remove(token, pending);
        sendText(chatId, (syncOnly ? "Sync" : "Backup") + " request cancelled.");
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
                    session.sourceUsername, session.startMessageId, () -> sessions.get(chatId) == session);
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
                sendText(chatId, session.startMessageId == null
                        ? "No forwardable posts were found in that source channel."
                        : "No source posts were found at or after message " + session.startMessageId + ".");
                return;
            }
            session.sourceTitle = history.title();
            session.sourceChatId = history.sourceChatId();
            if (session.destinationChatId == 0) {
                session.destinationChatId = historyForwarder.findPublicChatId(session.destinationUsername);
            }
            if (history.sourceChatId() == session.destinationChatId) {
                sessions.remove(chatId, session);
                sendText(chatId, "Source and destination must be different channels.");
                return;
            }
            Optional<BackupCheckpointStore.Checkpoint> savedCheckpoint = Optional.empty();
            if (session.syncOnly) {
                session.messageIds = messageIds;
            } else {
                savedCheckpoint = checkpointStore.load(session.sourceChatId, session.destinationChatId);
                if (savedCheckpoint.isPresent()) {
                    BackupCheckpointStore.Checkpoint saved = savedCheckpoint.get();
                    TreeSet<Long> allMessageIds = new TreeSet<>(saved.messageIds());
                    if (session.startMessageId != null) {
                        allMessageIds.removeIf(id -> id < session.startMessageId);
                    }
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
                nextAction = " The destination will be checked for missing posts. Missing posts will be appended oldest to newest. Reply /confirm to run the sync, or /cancel to stop.";
            } else if (session.checkpoint != null) {
                nextAction = " Saved checkpoint cursor: " + session.checkpoint.nextIndex() + " of "
                        + session.messageIds.size() + " source posts. This is processing progress only; it does not indicate how many posts are in the destination. Reply /resume to recheck the destination and retry missing posts while keeping the existing heading, /confirm to start a new backup run with a new heading, or /cancel to stop.";
            } else {
                nextAction = " Reply /confirm to start the backup, or /cancel to stop.";
            }
            sendText(chatId, "Found " + session.messageIds.size() + " source posts." + nextAction);
        } catch (java.util.concurrent.CancellationException exception) {
            return;
        } catch (IllegalArgumentException exception) {
            sessions.remove(chatId, session);
            sendText(chatId, exception.getMessage());
        } catch (Exception exception) {
            sessions.remove(chatId, session);
            sendText(chatId,
                    "Could not read that channel history. Check that it is public and that the bot can access it.");
            LOGGER.log(Level.WARNING, "History loading failed for @" + session.sourceUsername + ".", exception);
        }
    }

    private void forwardHistory(long chatId, BackSession session, boolean resume) {
        Set<Long> forwardedSourceIdsDuringRun = new HashSet<>();
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
            session.checkpoint = checkpoint;

            if (!session.syncOnly && checkpoint.headingMessageId() == 0) {
                Message heading = execute(SendMessage.builder()
                        .chatId(session.destinationUsername)
                        .text("Contents of: (\"" + session.sourceTitle + "\")")
                        .build());
                checkpoint = checkpoint.withProgress(checkpoint.nextIndex(), heading.getMessageId());
                checkpointStore.save(checkpoint);
                session.checkpoint = checkpoint;
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
            List<Long> missingBeforeForward = session.messageIds.stream()
                    .filter(id -> !verifiedSourceIds.contains(id)).toList();
            String startDescription = session.startMessageId == null
                    ? "the full source history"
                    : "from https://t.me/" + session.sourceUsername + "/" + session.startMessageId
                            + " (inclusive)";
            String destinationStatus = "Requested " + startDescription + ". Checked destination "
                    + historyForwarder.describeChat(session.destinationChatId) + ": "
                    + verifiedSourceIds.size() + " of " + session.messageIds.size()
                    + " selected source posts are already confirmed present and will be skipped.";
            if (missingBeforeForward.isEmpty()) {
                sendText(chatId, destinationStatus + " There are no missing posts to forward.");
            } else {
                sendText(chatId, destinationStatus + " First missing post to forward: https://t.me/"
                        + session.sourceUsername + "/" + missingBeforeForward.get(0) + ".");
            }
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
                    forwardedSourceIdsDuringRun.addAll(result.forwardedSourceMessageIds());
                    skipped += result.skipped();
                    verifiedSourceIds.addAll(result.forwardedSourceMessageIds());
                }
                checkpoint = checkpoint.withProgress(end, checkpoint.headingMessageId());
                checkpointStore.save(checkpoint);
                session.checkpoint = checkpoint;

                if (!forwardedSourceIdsDuringRun.isEmpty()
                        && forwardedSourceIdsDuringRun.size() % 1000 < FORWARD_BATCH_SIZE) {
                    sendText(chatId, "Telegram confirmed " + forwardedSourceIdsDuringRun.size() + " unique source posts sent during this run.");
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
                            + " source posts are present in the destination. Added "
                            + forwardedSourceIdsDuringRun.size()
                            + " missing posts in source order; Telegram could not forward " + skipped + ".");
                } else {
                    sendText(chatId, "Finished and verified: all " + session.messageIds.size()
                            + " source posts are present in the destination. Newly forwarded: "
                            + forwardedSourceIdsDuringRun.size()
                            + "; Telegram could not forward " + skipped + ".");
                }
            } else {
                checkpoint = checkpoint.withProgress(session.messageIds.size(), checkpoint.headingMessageId());
                checkpointStore.save(checkpoint);
                session.checkpoint = checkpoint;
                String missingSample = missingIds.stream().limit(10).map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(", "));
                String retryInstructions = session.syncOnly
                        ? " Run /sync again with this source and destination to retry."
                        : " Send /back to this channel pair and /resume to retry missing posts.";
                String missingSummary = missingIds.isEmpty()
                        ? "All source posts are present."
                        : verifiedAfterRun.size() + " of " + session.messageIds.size()
                                + " source posts are present; " + missingIds.size()
                                + " are missing. First missing source IDs: " + missingSample + ".";
                sendText(chatId, (session.syncOnly ? "Sync" : "Backup")
                        + " finished with verification incomplete. " + missingSummary
                        + retryInstructions);
            }
        } catch (HistoryForwarder.RateLimitWaitCompletedException exception) {
            if (sessions.get(chatId) == session) {
                session.stage = Stage.CONFIRM;
                if (session.syncOnly) {
                    sendText(chatId, "Telegram's rate-limit wait has ended. The sync is paused. Reply /confirm to recheck the destination and continue, or /cancel to stop.");
                } else if (session.checkpoint != null) {
                    sendText(chatId, "Telegram's rate-limit wait has ended. The backup is paused. Reply /resume to check the destination for posts already forwarded and continue with any missing posts, or /cancel to stop.");
                } else {
                    sendText(chatId, "Telegram's rate-limit wait has ended, but the backup checkpoint is unavailable. Start /back again and choose the same source and destination to retry safely.");
                }
                LOGGER.log(Level.INFO, "Telegram rate-limit wait ended; paused "
                        + (session.syncOnly ? "sync" : "backup") + " for user confirmation.", exception);
            }
        } catch (Exception exception) {
            sendText(chatId, (session.syncOnly ? "Sync" : "Backup forwarding") + " stopped. Telegram confirmed "
                    + forwardedSourceIdsDuringRun.size() + " unique source posts sent during this run; the last attempted post may also have reached the destination. "
                    + "Reason: " + rootCauseMessage(exception)
                    + " Check the destination before retrying; a resume or sync will check for posts already present.");
            LOGGER.log(Level.WARNING, (session.syncOnly ? "History sync" : "History forwarding")
                    + " stopped after " + forwardedSourceIdsDuringRun.size() + " confirmed unique posts.", exception);
        } finally {
            if (session.stage != Stage.CONFIRM) {
                sessions.remove(chatId, session);
            }
        }
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message) + ".";
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

    private static SourceReference parseSourceReference(String input) {
        String value = input.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return new SourceReference(parsePublicUsername(value), null);
        }

        URI uri = URI.create(value);
        String host = uri.getHost();
        if (host == null || !(host.equalsIgnoreCase("t.me") || host.equalsIgnoreCase("telegram.me"))) {
            throw new IllegalArgumentException("Use a public channel username or a t.me channel/post link.");
        }
        String[] parts = uri.getPath().split("/");
        int index = parts.length > 1 && parts[1].equalsIgnoreCase("s") ? 2 : 1;
        if (index >= parts.length || parts[index].isBlank()) {
            throw new IllegalArgumentException("That t.me link does not contain a public channel username.");
        }

        String username = parsePublicUsername(parts[index]);
        if (index + 1 >= parts.length || parts[index + 1].isBlank()) {
            return new SourceReference(username, null);
        }
        if (index + 2 < parts.length && !parts[index + 2].isBlank()) {
            throw new IllegalArgumentException("Use a public channel link or a link to one of its posts.");
        }
        return new SourceReference(username, parsePositiveMessageId(parts[index + 1]));
    }

    private static long parseStartMessageId(String input, String sourceUsername) {
        String value = input.trim();
        if (value.matches("\\d+")) {
            return parsePositiveMessageId(value);
        }
        SourceReference reference = parseSourceReference(value);
        if (reference.messageId() == null) {
            throw new IllegalArgumentException("Send a source post link or a positive message ID, or /all.");
        }
        if (!reference.username().equalsIgnoreCase(sourceUsername)) {
            throw new IllegalArgumentException("The starting post link must be from the selected source channel.");
        }
        return reference.messageId();
    }

    private static long parsePositiveMessageId(String value) {
        try {
            long messageId = Long.parseLong(value);
            if (messageId <= 0) {
                throw new IllegalArgumentException("The starting message ID must be a positive number.");
            }
            return messageId;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("The starting message ID must be a positive whole number.", exception);
        }
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
        SOURCE_START,
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
        private Long startMessageId;
        private volatile List<Long> messageIds = List.of();
        private BackupCheckpointStore.Checkpoint checkpoint;
        private boolean syncOnly;
    }

    private record PendingDelete(long chatId, int firstMessageId, int lastMessageId, long expiresAtMillis) {
    }

    private record PendingDuplicateScan(long destinationChatId, String destinationTitle, long expiresAtMillis) {
    }

    private record PendingChannelRequest(long destinationChatId, String sourceUsername, Long startMessageId,
                                         boolean syncOnly, long expiresAtMillis) {
    }

    private record SourceReference(String username, Long messageId) {
    }
}