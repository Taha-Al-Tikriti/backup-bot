# Telegram Channel Backup Bot

A Windows Java bot that backs up posts from a public Telegram channel into an existing or newly created channel. It reads and forwards posts through your signed-in Telegram user account, while the bot account handles commands and posts the backup heading.

## Requirements

- Windows
- Java 17 or newer
- Apache Maven
- A Telegram account that can read the source channel and post in the destination
- A Telegram bot created with BotFather
- Telegram API credentials (`api_id` and `api_hash`) for the account used to access Telegram through TDLib

## Create a Telegram bot and get its token

1. In Telegram, open [@BotFather](https://t.me/BotFather).
2. Send `/newbot` and follow the prompts to choose a display name and username.
3. Copy the bot token BotFather returns. Treat it like a password; anyone who has it can control your bot.
4. Keep the bot username as well. The username is the `@...` name assigned by BotFather, not the display name.
5. Open a private chat with the new bot and send `/start` once. Telegram requires you to initiate the private chat before the bot can message you there.

## Get your Telegram API ID and hash

These credentials are for TDLib's user-account connection. They are separate from the bot token.

1. Sign in to [my.telegram.org](https://my.telegram.org) with the phone number for the Telegram account that will read and forward channel posts.
2. Open **API development tools**. If prompted, create an application with the requested app title, short name, URL, and platform details.
3. Copy the numeric `api_id` and the `api_hash` shown on the application page.
4. Keep the `api_hash` private. Do not post it, commit it, or send it in logs or screenshots.

The app details on my.telegram.org are not environment variables. The program only needs the resulting API ID and hash.

## Configure Windows environment variables

The program reads these five variables:

| Variable | Value |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | The token from BotFather. |
| `TELEGRAM_BOT_USERNAME` | The bot username assigned by BotFather, with or without the leading `@`. |
| `TELEGRAM_API_ID` | The numeric API ID from my.telegram.org. |
| `TELEGRAM_API_HASH` | The API hash shown with the API ID. Keep it secret. |
| `BACKUP_ADMIN_USER_ID` | Your numeric Telegram user ID. This ID is authorized to start backups and confirm channel message deletion. |

To save them as Windows **user** environment variables:

1. Open Start and search for **Edit environment variables for your account**.
2. Under **User variables**, choose **New** for each variable above.
3. Enter the exact variable name and its value, then save.
4. Close and reopen PowerShell or the VS Code terminal so it inherits the updated variables.

To find your numeric Telegram user ID, initially configure the first four variables, start the app, and send `/id` to the bot privately. Add the returned number as `BACKUP_ADMIN_USER_ID`, then restart the terminal and app.

Alternatively, set them for the current PowerShell window only (replace the placeholder values; do not include angle brackets):

```powershell
$env:TELEGRAM_BOT_TOKEN = "token-from-botfather"
$env:TELEGRAM_BOT_USERNAME = "your_bot_username"
$env:TELEGRAM_API_ID = "numeric-api-id"
$env:TELEGRAM_API_HASH = "api-hash"
$env:BACKUP_ADMIN_USER_ID = "your-numeric-telegram-user-id"
```

Those `$env:` values last only for that PowerShell window and are inherited by programs started from it. Do not paste real credentials into source files or share them.

## Build and run

From PowerShell, run the app from this demo's directory:

```powershell
cd C:\path\to\backup-bot\backup\demo
mvn clean compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.example.Backup"
```

Use the actual location of your cloned repository in place of `C:\path\to\backup-bot`. Leave the terminal open while the bot runs; press **Ctrl+C** to stop it. On first startup, TDLib may ask in the terminal for the signed-in account's phone number and the Telegram login code. Complete the prompts there.

The TDLib login session is saved under `%USERPROFILE%\.0_bot\tdlight-session`. Protect this directory as you would a Telegram login session. Backup checkpoints are saved under `%USERPROFILE%\.0_bot\backup-checkpoints`.

## Back up a channel

Use the bot in a **private chat**. Each step is a separate message:

1. Send `/back`.
2. Send the source channel's public username (for example, `@sourcechannel`) or public `t.me` link.
3. Choose a destination:
   - **Existing channel:** send its public username or public `t.me` link. Private invite links are not supported by the current input flow. The bot must already be an administrator there with **Post Messages** and **Edit Messages** permissions. The signed-in user account must also be able to post there.
   - **New channel:** send `/new`. The signed-in user account creates a channel named `مخزن - <source channel name>`. Telegram does not allow this app to add the bot through TDLib, so add the bot manually in the new channel's **Administrators** settings and grant **Post Messages** and **Edit Messages**. Then send `/ready` to the bot in the private chat.
4. The bot reads the source history and reports the number of posts found.
5. Send `/confirm` to begin, or `/cancel` to cancel.

The signed-in Telegram account must be able to access the source history and post in the destination. The bot account sends and pins the heading. Posts are forwarded oldest first; deleted, protected, or otherwise unavailable posts may not be forwardable.

### Resume and verification

Progress is checkpointed after each batch. If a backup is interrupted, restart the app, send `/back`, and enter the same source and destination. When the bot detects a saved checkpoint, send `/resume` to continue. Send `/confirm` to start again from the beginning; already-forwarded posts with recognizable Telegram forward metadata are checked to avoid duplicates.

At the end, the app scans destination history for forwards from the source and reports whether all source post IDs could be verified there. If posts are missing, it retains the checkpoint so you can retry with `/resume`. Verification relies on Telegram retaining the forwarded-message origin metadata.

## Delete a range of channel messages

The bot must be an administrator in the channel with **Delete Messages** permission. Post this command in that channel:

```text
/delete <first_message_id> <last_message_id>
```

For example, `/delete 120 150` requests deletion of IDs 120 through 150, inclusive. Message IDs are the numeric final part of a channel post link such as `https://t.me/channelname/120`.

The configured `BACKUP_ADMIN_USER_ID` must confirm privately within 10 minutes using the token the bot sends:

```text
/confirm_delete <token>
```

To cancel instead, send `/cancel_delete <token>`. A request is limited to 10,000 message IDs. Telegram may skip posts that are already gone or cannot be deleted.

## Other commands

- `/id` — show the sender's numeric Telegram user ID.
- `/cancel` — cancel the current backup workflow.
- `/start` — initiate the bot's private chat; Telegram requires this before the bot can message you privately.

## Troubleshooting

- **Missing environment-variable startup error:** check the variable spelling and start a new terminal after changing Windows user variables.
- **Old destination prompt appears:** stop the old bot process with Ctrl+C, then run the build/start command above from this `backup\demo` directory.
- **`USER_BOT` while creating a channel:** Telegram rejected an attempt to add a bot through the user-account API. The app's `/new` flow now asks you to add and promote the bot manually, then send `/ready`.
- **History access or forwarding errors:** verify that the signed-in account can read the source and post in the destination. Protected posts may not be forwardable.
- **SLF4J says no providers were found:** this is a logging-provider warning; it does not by itself mean the bot failed to start.
- Do not share the bot token, API hash, login code, or the TDLib session directory when asking for help.
