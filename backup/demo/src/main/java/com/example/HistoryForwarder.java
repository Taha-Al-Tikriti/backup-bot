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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class HistoryForwarder implements AutoCloseable {
    private static final int HISTORY_PAGE_SIZE = 100;
    private static final long REQUEST_TIMEOUT_MINUTES = 2;

    private final SimpleTelegramClientFactory clientFactory;
    private final SimpleTelegramClient client;

    public HistoryForwarder(String apiId, String apiHash) throws Exception {
        Init.init();
        APIToken apiToken = new APIToken(Integer.parseInt(apiId.trim()), apiHash.trim());
        TDLibSettings settings = TDLibSettings.create(apiToken);
        Path sessionPath = Path.of(System.getProperty("user.home"), ".0_bot", "tdlight-session");
        settings.setDatabaseDirectoryPath(sessionPath.resolve("data"));
        settings.setDownloadedFilesDirectoryPath(sessionPath.resolve("downloads"));

        clientFactory = new SimpleTelegramClientFactory();
        SimpleTelegramClientBuilder clientBuilder = clientFactory.builder(settings);
        client = clientBuilder.build(AuthenticationSupplier.consoleLogin());
        client.getMeAsync().get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    public ChannelHistory readMessageIds(String publicUsername, BooleanSupplier shouldContinue) throws Exception {
        PublicChat source = findPublicChat(publicUsername);
        Set<Long> newestFirst = new LinkedHashSet<>();
        long fromMessageId = 0;

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

            int previousSize = newestFirst.size();
            for (TdApi.Message message : page.messages) {
                if (message.id > 0) {
                    newestFirst.add(message.id);
                }
            }
            if (newestFirst.size() == previousSize) {
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

    public PublicChat createChannelAndPromoteBot(String title, long botUserId) throws Exception {
        TdApi.CreateNewSupergroupChat create = new TdApi.CreateNewSupergroupChat();
        create.title = title;
        create.isChannel = true;
        create.description = "";
        TdApi.Chat chat = client.send(create).get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        TdApi.FailedToAddMembers addResult = client.send(new TdApi.AddChatMember(chat.id, botUserId, 0))
                .get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (addResult.failedToAddMembers.length > 0) {
            throw new IllegalStateException("Telegram could not add the bot to the new channel.");
        }

        TdApi.ChatAdministratorRights rights = new TdApi.ChatAdministratorRights();
        rights.canManageChat = true;
        rights.canPostMessages = true;
        rights.canEditMessages = true;
        rights.canDeleteMessages = true;
        rights.canPinMessages = true;
        TdApi.SetChatMemberStatus promote = new TdApi.SetChatMemberStatus(
                chat.id, new TdApi.MessageSenderUser(botUserId),
                new TdApi.ChatMemberStatusAdministrator(false, rights));
        client.send(promote).get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        return new PublicChat(chat.id, chat.title);
    }

    public ForwardResult forwardMessages(long sourceChatId, long destinationChatId, List<Long> messageIds)
            throws Exception {
        TdApi.ForwardMessages request = new TdApi.ForwardMessages();
        request.chatId = destinationChatId;
        request.fromChatId = sourceChatId;
        request.messageIds = messageIds.stream().mapToLong(Long::longValue).toArray();
        request.options = new TdApi.MessageSendOptions();
        TdApi.Messages result = client.send(request).get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        List<Long> forwardedSourceMessageIds = new ArrayList<>();
        for (int index = 0; index < result.messages.length && index < messageIds.size(); index++) {
            TdApi.Message message = result.messages[index];
            if (message != null) {
                forwardedSourceMessageIds.add(messageIds.get(index));
            }
        }
        return new ForwardResult(List.copyOf(forwardedSourceMessageIds),
                messageIds.size() - forwardedSourceMessageIds.size());
    }

    public Set<Long> findForwardedSourceMessageIds(long sourceChatId, long destinationChatId,
                                                   List<Long> expectedMessageIds,
                                                   BooleanSupplier shouldContinue) throws Exception {
        Set<Long> expected = new HashSet<>(expectedMessageIds);
        Set<Long> found = new HashSet<>();
        long fromMessageId = 0;

        while (found.size() < expected.size()) {
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
            if (nextFromMessageId == fromMessageId) {
                break;
            }
            for (TdApi.Message message : page.messages) {
                if (message.forwardInfo != null
                        && message.forwardInfo.origin instanceof TdApi.MessageOriginChannel origin
                        && origin.chatId == sourceChatId
                        && expected.contains(origin.messageId)) {
                    found.add(origin.messageId);
                }
            }
            fromMessageId = nextFromMessageId;
        }
        return Set.copyOf(found);
    }

    public record ForwardResult(List<Long> forwardedSourceMessageIds, int skipped) {
        public ForwardResult {
            forwardedSourceMessageIds = List.copyOf(forwardedSourceMessageIds);
        }

        public int forwarded() {
            return forwardedSourceMessageIds.size();
        }
    }

    public record PublicChat(long chatId, String title) {
    }

    public record ChannelHistory(String title, long sourceChatId, List<Long> messageIds) {
    }

    @Override
    public void close() throws Exception {
        client.close();
        clientFactory.close();
    }
}