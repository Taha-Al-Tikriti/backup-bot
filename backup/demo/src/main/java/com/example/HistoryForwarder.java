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
        TdApi.Chat source = client.send(new TdApi.SearchPublicChat(publicUsername))
                .get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        Set<Integer> newestFirst = new LinkedHashSet<>();
        long fromMessageId = 0;
        int offset = 0;

        while (true) {
            if (!shouldContinue.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            TdApi.Messages page = client.send(new TdApi.GetChatHistory(
                    source.id, fromMessageId, offset, HISTORY_PAGE_SIZE, false))
                    .get(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (page.messages.length == 0) {
                break;
            }

            int previousSize = newestFirst.size();
            for (TdApi.Message message : page.messages) {
                if (message.id > 0) {
                    newestFirst.add(Math.toIntExact(message.id));
                }
            }
            if (newestFirst.size() == previousSize || page.messages.length < HISTORY_PAGE_SIZE) {
                break;
            }

            fromMessageId = page.messages[page.messages.length - 1].id;
            offset = -1;
        }

        List<Integer> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        String title = source.title == null || source.title.isBlank() ? "@" + publicUsername : source.title;
        return new ChannelHistory(title, List.copyOf(oldestFirst));
    }

    public record ChannelHistory(String title, List<Integer> messageIds) {
    }

    @Override
    public void close() throws Exception {
        client.close();
        clientFactory.close();
    }
}