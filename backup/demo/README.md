# مساعد نسخ احتياطي لقنوات تيليجرام

(English below)

تطبيق بوت بلغة Java يعمل على Windows لنسخ منشورات قناة تيليجرام عامة إلى قناة موجودة أو جديدة. يقرأ التطبيق المنشورات ويعيد توجيهها باستخدام حساب تيليجرام الشخصي المسجل، بينما يتولى حساب البوت استقبال الأوامر ونشر عنوان النسخة الاحتياطية.

> **ملاحظة:** تم إنشاء الشيفرة البرمجية لهذا المشروع بمساعدة الذكاء الاصطناعي.

## المتطلبات

- Windows
- Java 17 أو أحدث
- Apache Maven
- حساب تيليجرام يمكنه قراءة القناة المصدر والنشر في قناة الوجهة
- بوت تيليجرام أنشأته باستخدام BotFather
- بيانات Telegram API (`api_id` و`api_hash`) للحساب الذي سيستخدم TDLib

## إنشاء بوت والحصول على الرمز

1. افتح [@BotFather](https://t.me/BotFather) في تيليجرام.
2. أرسل `/newbot` واتبع التعليمات لاختيار اسم عرض واسم مستخدم.
3. انسخ رمز البوت الذي يعرضه BotFather. تعامل معه ككلمة مرور؛ فمن يحصل عليه يستطيع التحكم بالبوت.
4. احتفظ أيضًا باسم مستخدم البوت (`@...`) الذي عيّنه BotFather؛ فهو ليس اسم العرض.
5. افتح محادثة خاصة مع البوت الجديد وأرسل `/start` مرة واحدة. يشترط تيليجرام أن تبدأ المحادثة الخاصة قبل أن يتمكن البوت من مراسلتك فيها.

## الحصول على Telegram API ID وAPI Hash

تُستخدم هذه البيانات لاتصال حساب المستخدم عبر TDLib، وهي منفصلة عن رمز البوت.

1. سجّل الدخول إلى [my.telegram.org](https://my.telegram.org) باستخدام رقم هاتف حساب تيليجرام الذي سيقرأ منشورات القناة ويعيد توجيهها.
2. افتح **API development tools**. إذا طُلب منك إنشاء تطبيق، أدخل بيانات التطبيق المطلوبة مثل العنوان والاسم المختصر والرابط والمنصة.
3. انسخ قيمة `api_id` الرقمية و`api_hash` الظاهرة في صفحة التطبيق.
4. حافظ على سرية `api_hash`. لا تنشره ولا تضعه في المستودع ولا ترسله ضمن سجلات أو لقطات شاشة.

بيانات التطبيق في my.telegram.org ليست متغيرات بيئة؛ يحتاج البرنامج إلى API ID وAPI Hash الناتجين فقط.

## إعداد متغيرات البيئة في Windows

يقرأ البرنامج متغيرات البيئة الخمسة التالية:

| المتغير | القيمة |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | رمز البوت من BotFather. |
| `TELEGRAM_BOT_USERNAME` | اسم مستخدم البوت الذي عيّنه BotFather، مع `@` في البداية أو بدونه. |
| `TELEGRAM_API_ID` | API ID الرقمي من my.telegram.org. |
| `TELEGRAM_API_HASH` | API Hash الظاهر بجانب API ID. حافظ على سريته. |
| `BACKUP_ADMIN_USER_ID` | رقم مستخدمك في تيليجرام. هذا الرقم مخوّل لبدء النسخ الاحتياطي وتأكيد حذف الرسائل. |

لحفظها كمتغيرات بيئة لمستخدم Windows:

1. افتح قائمة Start وابحث عن **Edit environment variables for your account**.
2. ضمن **User variables**، اختر **New** لكل متغير مما سبق.
3. أدخل اسم المتغير كما هو وقيمته، ثم احفظ.
4. أغلق PowerShell أو طرفية VS Code وافتحها من جديد لتقرأ المتغيرات المحدّثة.

للعثور على رقم مستخدمك، أعدّ المتغيرات الأربعة الأولى وشغّل التطبيق، ثم أرسل `/id` إلى البوت في محادثة خاصة. أضف الرقم الذي يعيده البوت كقيمة `BACKUP_ADMIN_USER_ID`، ثم أعد فتح الطرفية وشغّل التطبيق مجددًا.

يمكنك بدلًا من ذلك ضبط المتغيرات لنافذة PowerShell الحالية فقط (استبدل القيم النموذجية، ولا تكتب الأقواس الزاوية):

```powershell
$env:TELEGRAM_BOT_TOKEN = "token-from-botfather"
$env:TELEGRAM_BOT_USERNAME = "your_bot_username"
$env:TELEGRAM_API_ID = "numeric-api-id"
$env:TELEGRAM_API_HASH = "api-hash"
$env:BACKUP_ADMIN_USER_ID = "your-numeric-telegram-user-id"
```

تبقى قيم `$env:` صالحة في نافذة PowerShell الحالية فقط، وترثها البرامج التي تشغّلها من خلالها. لا تضع بيانات الاعتماد الحقيقية في ملفات الشيفرة ولا تشاركها.

## البناء والتشغيل

من PowerShell، شغّل التطبيق من مجلد هذا المشروع الفرعي:

```powershell
cd C:\path\to\backup-bot\backup\demo
mvn clean compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.example.Backup"
```

استبدل `C:\path\to\backup-bot` بمسار المستودع على جهازك. اترك الطرفية مفتوحة أثناء عمل البوت، واضغط **Ctrl+C** لإيقافه. في أول تشغيل، قد يطلب TDLib في الطرفية رقم هاتف الحساب ورمز تسجيل الدخول إلى تيليجرام؛ أكمل التعليمات هناك.

تُحفظ جلسة TDLib في `%USERPROFILE%\.0_bot\tdlight-session`. احمِ هذا المجلد كما تحمي جلسة تسجيل دخول تيليجرام. وتُحفظ نقاط استئناف النسخ في `%USERPROFILE%\.0_bot\backup-checkpoints`.

## نسخ قناة احتياطيًا

استخدم البوت في **محادثة خاصة**. أرسل كل خطوة في رسالة مستقلة:

1. أرسل `/back`.
2. أرسل اسم المستخدم العام للقناة المصدر (مثل `@sourcechannel`) أو رابط `t.me` عامًا.
3. اختر وجهة النسخ:
   - **قناة موجودة:** أرسل اسم المستخدم العام أو رابط `t.me` عامًا. لا تدعم آلية الإدخال الحالية روابط الدعوات الخاصة. يجب أن يكون البوت مشرفًا في القناة مع صلاحيتَي **Post Messages** و**Edit Messages**، كما يجب أن يتمكن حساب المستخدم المسجل من النشر فيها.
   - **قناة جديدة:** أرسل `/new`. سينشئ حساب المستخدم المسجل قناة باسم `مخزن - <اسم القناة المصدر>`. لا تسمح Telegram لهذه الأداة بإضافة البوت عبر TDLib؛ لذا أضف البوت يدويًا إلى **Administrators** في القناة الجديدة وامنحه صلاحيتَي **Post Messages** و**Edit Messages**، ثم أرسل `/ready` إلى البوت في المحادثة الخاصة.
4. يقرأ البوت سجل القناة المصدر ويعرض عدد المنشورات التي وجدها.
5. أرسل `/confirm` لبدء النسخ أو `/cancel` لإلغائه.

يجب أن يستطيع حساب تيليجرام المسجل الوصول إلى سجل المصدر والنشر في الوجهة. ينشر البوت العنوان ويثبّته. تُعاد توجيه المنشورات من الأقدم إلى الأحدث؛ وقد يتعذر توجيه المنشورات المحذوفة أو المحمية أو غير المتاحة لسبب آخر.

### مزامنة نسخة احتياطية موجودة

استخدم `/sync` في المحادثة الخاصة مع البوت لإعادة مقارنة قناة مصدر بقناة وجهة موجودة وإضافة المنشورات التي لا يجدها في الوجهة فقط:

1. أرسل `/sync`، ثم أرسل اسم المستخدم العام للمصدر أو رابط `t.me` عامًا.
2. أرسل اسم المستخدم العام للوجهة أو رابطها العام. يجب أن تكون الوجهة موجودة وأن يملك البوت وحساب المستخدم المسجل صلاحيات الوصول والنشر المطلوبة؛ لا ينشئ `/sync` قناة جديدة.
3. بعد مراجعة عدد منشورات المصدر، أرسل `/confirm` لبدء المقارنة والإصلاح أو `/cancel` للإلغاء.

يقارن البوت معرّفات المنشورات المصدرية ببيانات مصدر التوجيه في سجل الوجهة. تُضاف المنشورات المفقودة بترتيبها الأصلي من الأقدم إلى الأحدث، بعد المنشورات الموجودة حاليًا؛ لا يمكن لتيليجرام إدراجها بين منشورات الوجهة المنشورة سابقًا. لا ينشئ `/sync` عنوانًا جديدًا أو يثبّته. إذا تعذر التحقق من منشورات مفقودة، يعرض البوت بعض معرّفاتها ويحتفظ بنقطة الاستئناف.

للمزامنة مباشرةً مع وجهة خاصة أو غير مدرجة باسم مستخدم عام، أرسل في قناة الوجهة `/sync @sourcechannel` (أو رابط المصدر العام). يرسل البوت طلب تأكيد خاصًا إلى المستخدم المحدد في `BACKUP_ADMIN_USER_ID`؛ أرسل `/confirm_sync <token>` للمتابعة أو `/cancel_sync <token>` للإلغاء. يجب أن يكون البوت مشرفًا في قناة الوجهة وأن تكون المحادثة الخاصة مع البوت قد بدأت مسبقًا.

### الاستئناف والتحقق

يحفظ البرنامج نقطة استئناف بعد كل دفعة. إذا انقطع النسخ، أعد تشغيل التطبيق، وأرسل `/back`، ثم أدخل المصدر والوجهة نفسيهما. عندما يعثر البوت على نقطة استئناف محفوظة، تعرض الرسالة موضع المعالجة المحفوظ؛ هذا الموضع لا يبيّن عدد المنشورات الموجودة في الوجهة. أرسل `/resume` لإعادة فحص الوجهة ومحاولة المنشورات الناقصة مع الإبقاء على العنوان الحالي، أو `/confirm` لبدء عملية نسخ جديدة وإنشاء عنوان جديد. في الحالتين يفحص التطبيق الوجهة ويتجنب المنشورات الموجودة التي يمكنه التعرّف على مصدرها.

بعد الانتهاء، يفحص التطبيق سجل الوجهة بحثًا عن المنشورات المعاد توجيهها من المصدر ويبلغك بما إذا أمكن التحقق من جميع معرّفات المنشورات. إذا كانت هناك منشورات مفقودة، يحتفظ بنقطة الاستئناف لتتمكن من المحاولة مجددًا باستخدام `/resume`. يعتمد التحقق على احتفاظ تيليجرام ببيانات مصدر الرسالة المعاد توجيهها.

## حذف مجموعة من رسائل القناة

يجب أن يكون البوت مشرفًا في القناة مع صلاحية **Delete Messages**. أرسل الأمر التالي في القناة:

```text
/delete <first_message_id> <last_message_id>
```

مثلًا، يطلب `/delete 120 150` حذف معرّفات الرسائل من 120 إلى 150 شاملةً. معرّف الرسالة هو الجزء الرقمي الأخير من رابط منشور القناة، مثل `https://t.me/channelname/120`.

يجب على المستخدم المحدد في `BACKUP_ADMIN_USER_ID` تأكيد الطلب على الخاص خلال 10 دقائق باستخدام الرمز الذي يرسله البوت:

```text
/confirm_delete <token>
```

للإلغاء بدلًا من ذلك، أرسل `/cancel_delete <token>`. الحد الأقصى للطلب هو 10,000 معرّف رسالة. قد يتعذر حذف المنشورات المحذوفة مسبقًا أو التي لا تسمح تيليجرام بحذفها.

## أوامر أخرى

- `/id` — عرض رقم مستخدم تيليجرام الخاص بالمرسل.
- `/sync` — بدء مقارنة المصدر والوجهة في المحادثة الخاصة؛ أو استخدم `/sync @sourcechannel` داخل قناة الوجهة.
- `/confirm_sync <token>` — تأكيد طلب مزامنة أُرسل من قناة.
- `/cancel_sync <token>` — إلغاء طلب مزامنة أُرسل من قناة.
- `/cancel` — إلغاء عملية النسخ الحالية.
- `/start` — بدء المحادثة الخاصة مع البوت؛ يشترط تيليجرام ذلك حتى يتمكن البوت من مراسلتك على الخاص.

## استكشاف الأخطاء وإصلاحها

- **خطأ بسبب متغير بيئة مفقود:** تحقق من كتابة أسماء المتغيرات بدقة، وافتح طرفية جديدة بعد تعديل متغيرات Windows.
- **ظهور مطالبة الوجهة القديمة:** أوقف عملية البوت القديمة عبر Ctrl+C، ثم شغّل أمر البناء والتشغيل أعلاه من مجلد `backup\demo`.
- **ظهور `USER_BOT` عند إنشاء قناة:** رفض تيليجرام محاولة إضافة بوت عبر واجهة حساب المستخدم. يطلب مسار `/new` في التطبيق الآن إضافة البوت وترقيته يدويًا، ثم إرسال `/ready`.
- **أخطاء الوصول إلى السجل أو إعادة التوجيه:** تحقق من أن الحساب المسجل يستطيع قراءة المصدر والنشر في الوجهة. قد يتعذر توجيه المنشورات المحمية.
- **رسالة SLF4J التي تفيد بعدم العثور على providers:** هذا تحذير متعلق بمزوّد التسجيل، ولا يعني وحده أن تشغيل البوت فشل.
- لا تشارك رمز البوت أو API Hash أو رمز تسجيل الدخول أو مجلد جلسة TDLib عند طلب المساعدة.

---

# Telegram Channel Backup Bot

A Windows Java application that backs up posts from a public Telegram channel to an existing or newly created channel. It reads and forwards posts through your signed-in Telegram user account, while the bot account handles commands and posts the backup heading.

> **Note:** The source code for this project was generated with the assistance of AI.

## Requirements

- Windows
- Java 17 or newer
- Apache Maven
- A Telegram account that can read the source channel and post in the destination
- A Telegram bot created with BotFather
- Telegram API credentials (`api_id` and `api_hash`) for the account used to access Telegram through TDLib

## Create a bot and get its token

1. Open [@BotFather](https://t.me/BotFather) in Telegram.
2. Send `/newbot` and follow the prompts to choose a display name and username.
3. Copy the bot token returned by BotFather. Treat it like a password; anyone who has it can control your bot.
4. Keep the bot username assigned by BotFather (`@...`); it is not the display name.
5. Open a private chat with the new bot and send `/start` once. Telegram requires you to initiate the private chat before the bot can message you there.

## Get a Telegram API ID and hash

These credentials are used for the TDLib user-account connection and are separate from the bot token.

1. Sign in to [my.telegram.org](https://my.telegram.org) with the phone number for the Telegram account that will read and forward channel posts.
2. Open **API development tools**. If prompted to create an application, provide the requested details, such as the app title, short name, URL, and platform.
3. Copy the numeric `api_id` and the `api_hash` shown on the application page.
4. Keep the `api_hash` private. Do not publish it, commit it to the repository, or share it in logs or screenshots.

The application details entered at my.telegram.org are not environment variables; the program only needs the resulting API ID and hash.

## Configure Windows environment variables

The program reads these five environment variables:

| Variable | Value |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | The token from BotFather. |
| `TELEGRAM_BOT_USERNAME` | The bot username assigned by BotFather, with or without the leading `@`. |
| `TELEGRAM_API_ID` | The numeric API ID from my.telegram.org. |
| `TELEGRAM_API_HASH` | The API hash shown with the API ID. Keep it secret. |
| `BACKUP_ADMIN_USER_ID` | Your numeric Telegram user ID. This ID is authorized to start backups and confirm message deletion. |

To save them as Windows **user** environment variables:

1. Open Start and search for **Edit environment variables for your account**.
2. Under **User variables**, choose **New** for each variable above.
3. Enter the exact variable name and its value, then save.
4. Close and reopen PowerShell or the VS Code terminal so it inherits the updated variables.

To find your numeric Telegram user ID, configure the first four variables, start the app, and send `/id` to the bot privately. Add the returned number as `BACKUP_ADMIN_USER_ID`, then reopen the terminal and restart the app.

Alternatively, set them for the current PowerShell window only (replace the sample values; do not include angle brackets):

```powershell
$env:TELEGRAM_BOT_TOKEN = "token-from-botfather"
$env:TELEGRAM_BOT_USERNAME = "your_bot_username"
$env:TELEGRAM_API_ID = "numeric-api-id"
$env:TELEGRAM_API_HASH = "api-hash"
$env:BACKUP_ADMIN_USER_ID = "your-numeric-telegram-user-id"
```

These `$env:` values last only for the current PowerShell window and are inherited by programs started from it. Do not put real credentials in source files or share them.

## Build and run

From PowerShell, run the application from this demo project's directory:

```powershell
cd C:\path\to\backup-bot\backup\demo
mvn clean compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.example.Backup"
```

Replace `C:\path\to\backup-bot` with the path to your local repository. Keep the terminal open while the bot is running; press **Ctrl+C** to stop it. On first startup, TDLib may prompt in the terminal for the account's phone number and Telegram login code. Complete the prompts there.

The TDLib login session is stored in `%USERPROFILE%\.0_bot\tdlight-session`. Protect this directory like a Telegram login session. Backup checkpoints are stored in `%USERPROFILE%\.0_bot\backup-checkpoints`.

## Back up a channel

Use the bot in a **private chat**. Send each step as a separate message:

1. Send `/back`.
2. Send the source channel's public username (for example, `@sourcechannel`) or a public `t.me` link.
3. Choose a destination:
   - **Existing channel:** send its public username or public `t.me` link. Private invite links are not supported by the current input flow. The bot must already be an administrator there with **Post Messages** and **Edit Messages** permissions. The signed-in user account must also be able to post there.
   - **New channel:** send `/new`. The signed-in user account creates a channel named `مخزن - <source channel name>`. Telegram does not allow this app to add a bot through TDLib, so manually add the bot to **Administrators** in the new channel, grant **Post Messages** and **Edit Messages**, then send `/ready` to the bot in the private chat.
4. The bot reads the source history and reports how many posts it found.
5. Send `/confirm` to start or `/cancel` to cancel.

The signed-in Telegram account must be able to access the source history and post in the destination. The bot account posts and pins the heading. Posts are forwarded oldest first; deleted, protected, or otherwise unavailable posts may not be forwardable.

### Sync an existing backup

Use `/sync` in the bot's private chat to compare a source channel with an existing destination and add only posts that are missing:

1. Send `/sync`, then send the source's public username or public `t.me` link.
2. Send the destination's public username or public link. The destination must already exist, and both the bot and signed-in user account need the required access and posting permissions. `/sync` does not create a channel.
3. After reviewing the number of source posts found, send `/confirm` to compare and repair, or `/cancel` to stop.

The bot compares source post IDs against forward-origin metadata in the destination history. Missing posts are appended in their original oldest-to-newest order, after posts already in the destination; Telegram does not let the app insert them between previously published destination posts. `/sync` does not create or pin another heading. If some missing posts cannot be verified, the bot reports sample IDs and retains the checkpoint.

To sync directly from a private or otherwise unlisted destination, post `/sync @sourcechannel` (or the public source link) in the destination channel. The bot sends a private confirmation request to the user configured by `BACKUP_ADMIN_USER_ID`; reply with `/confirm_sync <token>` to proceed or `/cancel_sync <token>` to cancel. The bot must be an administrator in the destination, and you must have started a private chat with it beforehand.

### Resume and verification

Progress is checkpointed after each batch. If a backup is interrupted, restart the app, send `/back`, and enter the same source and destination. If a saved checkpoint is found, the prompt shows its processing cursor; this does not tell you how many posts are in the destination. Send `/resume` to recheck the destination and retry missing posts while keeping the existing heading, or `/confirm` to start a new backup run with a new heading. Either way, the app checks the destination and skips posts it can identify as already forwarded.

At the end, the app scans destination history for forwards from the source and reports whether all source post IDs could be verified. If posts are missing, it retains the checkpoint so you can retry with `/resume`. Verification depends on Telegram retaining the forwarded-message origin metadata.

## Delete a range of channel messages

The bot must be an administrator in the channel with **Delete Messages** permission. Send this command in the channel:

```text
/delete <first_message_id> <last_message_id>
```

For example, `/delete 120 150` requests deletion of message IDs 120 through 150, inclusive. The message ID is the numeric final part of a channel post link, such as `https://t.me/channelname/120`.

The user configured in `BACKUP_ADMIN_USER_ID` must confirm privately within 10 minutes using the token sent by the bot:

```text
/confirm_delete <token>
```

To cancel instead, send `/cancel_delete <token>`. A request is limited to 10,000 message IDs. Telegram may skip posts that have already been deleted or cannot be deleted.

## Other commands

- `/id` — show the sender's numeric Telegram user ID.
- `/sync` — start a source/destination comparison in private chat, or use `/sync @sourcechannel` inside the destination.
- `/confirm_sync <token>` — confirm a sync request submitted in a channel.
- `/cancel_sync <token>` — cancel a sync request submitted in a channel.
- `/cancel` — cancel the current backup workflow.
- `/start` — initiate the bot's private chat; Telegram requires this before the bot can message you privately.

## Troubleshooting

- **Missing environment-variable startup error:** check the variable spelling and open a new terminal after changing Windows environment variables.
- **The old destination prompt appears:** stop the old bot process with Ctrl+C, then run the build/start command above from `backup\demo`.
- **`USER_BOT` while creating a channel:** Telegram rejected an attempt to add a bot through the user-account API. The app's `/new` flow now asks you to add and promote the bot manually, then send `/ready`.
- **History access or forwarding errors:** verify that the signed-in account can read the source and post in the destination. Protected posts may not be forwardable.
- **SLF4J reports that no providers were found:** this is a logging-provider warning; by itself, it does not mean the bot failed to start.
- Do not share the bot token, API hash, login code, or TDLib session directory when asking for help.
