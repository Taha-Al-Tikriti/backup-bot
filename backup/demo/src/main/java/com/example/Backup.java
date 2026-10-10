package com.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.ForwardMessages;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class Backup extends TelegramLongPollingBot implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(Backup.class.getName());
    private static final int FORWARD_BATCH_SIZE = 100;
    private static final long BATCH_DELAY_MILLIS = 4000;

    private final String botUsername;
    private final HistoryForwarder historyForwarder;
    private final Map<Long, BackSession> sessions = new ConcurrentHashMap<>();
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
        if (update.getMessage() == null || update.getMessage().getText() == null
                || update.getMessage().getFrom() == null) {
            return;
        }

        long chatId = update.getMessage().getChatId();
        long userId = update.getMessage().getFrom().getId();
        String text = update.getMessage().getText().trim();
        if (chatId != userId) {
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
                    && (currentSession.stage == Stage.LOADING || currentSession.stage == Stage.FORWARDING)) {
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

        if ("/confirm".equals(text) && session.stage == Stage.CONFIRM) {
            session.stage = Stage.FORWARDING;
            sendText(chatId, "Forwarding started. Send /cancel to stop.");
            jobExecutor.execute(() -> forwardHistory(chatId, session));
            return;
        }

        if (session.stage == Stage.SOURCE) {
            try {
                session.sourceUsername = parsePublicUsername(text);
                session.stage = Stage.DESTINATION;
                sendText(chatId,
                        "Now send the destination channel username or t.me link. The bot must be an admin with Post Messages and Edit Messages permissions.");
            } catch (IllegalArgumentException exception) {
                sendText(chatId, exception.getMessage());
            }
            return;
        }

        if (session.stage == Stage.DESTINATION) {
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
            List<Integer> messageIds = history.messageIds();
            if (messageIds.isEmpty()) {
                sessions.remove(chatId, session);
                sendText(chatId, "No forwardable posts were found in that source channel.");
                return;
            }
            session.sourceTitle = history.title();
            session.messageIds = messageIds;
            session.stage = Stage.CONFIRM;
            sendText(chatId, "Found " + messageIds.size()
                    + " posts. Reply /confirm to forward them oldest-first, or /cancel to stop.");
        } catch (java.util.concurrent.CancellationException exception) {
            return;
        } catch (Exception exception) {
            sessions.remove(chatId, session);
            sendText(chatId,
                    "Could not read that channel history. Check that it is public and available to the signed-in Telegram account.");
            LOGGER.warning("History loading failed: " + exception.getClass().getSimpleName());
        }
    }

    private void forwardHistory(long chatId, BackSession session) {
        int forwarded = 0;
        try {
            if (sessions.get(chatId) != session) {
                return;
            }
            Message heading = execute(SendMessage.builder()
                    .chatId(session.destinationUsername)
                    .text("Contents of: (\"" + session.sourceTitle + "\")")
                    .build());
            PinChatMessage pinHeading = new PinChatMessage();
            pinHeading.setChatId(session.destinationUsername);
            pinHeading.setMessageId(heading.getMessageId());
            execute(pinHeading);

            for (int start = 0; start < session.messageIds.size(); start += FORWARD_BATCH_SIZE) {
                if (sessions.get(chatId) != session) {
                    return;
                }

                int end = Math.min(start + FORWARD_BATCH_SIZE, session.messageIds.size());
                ForwardMessages request = new ForwardMessages();
                request.setChatId(session.destinationUsername);
                request.setFromChatId("@" + session.sourceUsername);
                request.setMessageIds(new ArrayList<>(session.messageIds.subList(start, end)));
                forwarded += execute(request).size();

                if (end < session.messageIds.size()) {
                    Thread.sleep(BATCH_DELAY_MILLIS);
                }
                if (forwarded > 0 && forwarded % 1000 < FORWARD_BATCH_SIZE) {
                    sendText(chatId, "Forwarded " + forwarded + " of " + session.messageIds.size() + " posts.");
                }
            }
            sendText(chatId, "Finished. Forwarded " + forwarded + " of " + session.messageIds.size()
                    + " posts. Telegram may skip deleted or protected posts.");
        } catch (Exception exception) {
            sendText(chatId, "Forwarding stopped after " + forwarded
                    + " posts. Check the source and destination permissions, then start again.");
            LOGGER.warning("History forwarding failed: " + exception.getClass().getSimpleName());
        } finally {
            sessions.remove(chatId, session);
        }
    }

    private void sendText(long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .text(text)
                    .build());
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
        LOADING,
        CONFIRM,
        FORWARDING
    }

    private static final class BackSession {
        private volatile Stage stage = Stage.SOURCE;
        private String sourceUsername;
        private volatile String sourceTitle;
        private String destinationUsername;
        private volatile List<Integer> messageIds = List.of();
    }
}