# Alphy UI design, version 1

Revision 2 records the design that the user approved on 2026-11-03.
Revision 3 incorporates the approved mini-app work after prerelease `v0.1.0-alpha.2`.
The [roadmap](../plans/2026-11-03-alphy-ui-roadmap.md) assigns the work and gives the release evidence.

Repository path: `/Users/egavrin/Documents/alpha`. Code paths without a root are relative to
`app/src/main/java/com/alpha/assistant/`.

## 0. How to read this document

Each decision has one of these labels:

- **(User)**: a Requirement. The user stated it, the brief states it, or it is part of an option that the user chose in the design session of 2026-11-03. A sample screen of a chosen option counts for what it shows. Its exact words are a sample, not fixed strings.
- **(Claude)**: an Implementation choice or a guess that Claude told the user in the session, before the first draft. The user did not object.
- **(Claude, new)**: an Implementation choice that first appeared in revision 2. Section 10 records the original choices and the approved mini-app changes.
- **(Builder)**: an Implementation choice that this document leaves to the builder.
- **Assumption**: a statement without direct evidence. Each one has a basis and a test.

Terms in this document:

| Term | Meaning |
| --- | --- |
| mini-app | A generated item that has state and controls. UI text: «мини-приложение». The code calls it "tool" or "artifact". |
| course | A generated sequence of lessons for one topic, for example «Испанский» with 10 lessons. |
| lesson | One part of a course: a plan of about 8 slides. UI text: «урок». The mode is «Обучение». |
| automation | A rule with a trigger, conditions, and actions. UI text: «автоматизация». The code calls it "сценарий" today. |
| desktop | The home screen inside Alphy: the «Сейчас» strip and the «Мой стол» grid. |
| tile | One item on the desktop grid. |
| home-screen widget | An Android launcher widget. UI text: «Виджет на экран». |
| card | One item inside the chat. |
| `ToolSpec` | The JSON spec of a mini-app, format version 3 (`tools/ToolSpec.kt`). |
| renderer | The Compose code that draws a `ToolSpec` UI tree (`ToolContent` in `tools/NativeToolUi.kt`). |
| route | The one generation path for chat, mini-apps, lessons, and automations (section 6). |
| part | An Alphy-owned piece of a `ToolSpec`: a UI subtree, its state fields, its JavaScript handlers, and typed slots. deal-studio-ohos calls these "composer parts" and "catalogue sections". |
| hole | A need that no part covers. DeepSeek writes the code for it. deal-studio-ohos uses this term (`AgentHole.ets`). |
| needs plan | A JSON list of what the user needs, with no code. DeepSeek writes it first (deal-studio-ohos `NeedsPlan.ets`). |
| selector | The step that picks parts and values from closed lists of choices. Jev does this job in deal-studio-ohos. |
| host | A place that shows a mini-app: a chat card, the full view, a tile, a widget, or the half sheet. |

## 1. Intent

The Alphy prototype works. It has cloud chat with web search, generated mini-apps in a sandbox,
home-screen widgets, automations, Android function calls, on-device voice, and a system assistant entry.

Its UI does not match its functions:

- The UI mixes Russian and English, and it shows technical data such as IDs, URIs, and schema keys.
- Actions sit in rows of text buttons under each message.
- Generated mini-apps look foreign to the shell, and widgets ignore the theme and dark mode.
- No code acts as a local agent.

This design gives Alphy a simple chat-first UI, close to ChatGPT. The UI makes three types of generated
items first-class: mini-apps, lessons, and automations. Generation moves to the parts-first route of
deal-studio-ohos, so that generated screens look designed and, by Assumption, arrive faster (6.8).

The user's words (Requirement):

- About the mock: «Only a start». The mock is only the first step, and the design goes further.
- «Мне бы хотелось интерфейс чуть проще, ближе к чатгпт + расширить миниприложениями, обучалками и автоматизациями.»
- The brief asks for a full UI, «возможно полнее чем у тех приложек», with the same Jev generation as deal-studio-ohos.

Success, as Claude understands it (Assumption, not stated by the user):

- A new user enters the key, asks for something, and gets a mini-app card that works and looks good, in seconds.
- (User) One item works the same in the chat card, on the desktop tile, and in the home-screen widget, with one state.
- A lesson feels personal, because each slide uses the earlier answers.
- The local agent shows one plan, requests one approval, and keeps a stop button visible during changes.
- No screen shows technical text or mixed languages.

## 2. Goals, non-goals, anti-goals

### 2.1 Goals for version 1

1. (User) A chat-first shell close to ChatGPT, with an interactive desktop as the home screen.
2. (User) Mini-apps: the user creates and changes them in the chat. Each one has a live card, a desktop tile, and a home-screen widget.
3. (User) Lessons: generated, personal slides in a full-screen player.
4. (User) Automations: an editable card with «Когда», «Если», and «Что сделать».
5. (User) A local agent that acts on the phone after one plan approval.
6. (User) The system assistant button opens a half sheet over the current app.
7. (User) One generation route for chat and mini-apps: the deal-studio-ohos parts-first route on top of `ToolSpec`.
8. (User) A Material 3 Expressive look with wallpaper colors.
9. (User, brief) The UI uses Kotlin and Compose. The brief says «видимо» (probably). The prototype already uses both.
10. (User, brief) Cloud only: DeepSeek does chat, search, and the Jev role («дисик вместо jev»).
11. (User, brief) The DeepSeek key is stored as in the offline assistant and DEAL Studio.
12. (User, brief) Alphy calls Android system APIs, has its own local agent, and has a full UI.

### 2.2 Non-goals for version 1

- (User, brief) On-device language models, and the mode selector «Авто / На устройстве / С облаком» from the mock.
- (User, brief) Real Jev calls in release builds. DeepSeek does the Jev role.
- (User) A confirmation before each cloud message. The user chose one consent at key entry.
- (User) Trust for one function, such as «Всегда разрешать изменения в календаре», with an undo toast. The user chose one plan approval instead.
- (User) A mandatory «Применить» or «Отмена» step for generated changes. The user chose changes through the chat. Section 5.3 defines the live preview and manual edit.
- (User) A port of VERA. The user chose route 1. Claude also dropped the DEAL Studio renderer, Google A2UI, and Remote Compose widgets.
- (Claude, new) Generated UI inside chat answers. A chat answer is text. A search answer shows in the shared card frame with its sources (5.2).
- (Claude, new) Contacts, calls, SMS, and medicine functions for the agent. Mini-apps keep the medicine functions.
- (Claude, new) PDF and office files in the «Файл» chip.
- (Claude, new) Cost or token displays, accounts, and sync between devices. Section 5.3 covers explicit mini-app share, export, and import.
- (Claude, new) Live Updates notifications for long agent runs. They can come later.
- (Claude, new) A review schedule for lessons and an embedding model for memory.

### 2.3 Anti-goals

These results are failures, even when the code works:

- (User) A UI that is not simpler than the mock, or not close to ChatGPT («чуть проще, ближе к чатгпт»).
  - The page of the chosen desktop option called it "less minimal than ChatGPT", so the desktop itself is not a failure.
  - (Claude) A plain question and its answer must stay as simple as in ChatGPT.
- (User) Local agent changes without plan approval, or automation runs without «Включить». Section 5.3 defines the separate mini-app rules.
- (User) Chat, local agent, or automation uploads of private content without their explicit steps (5.9). Section 5.9 also defines separate mini-app data rules.
- (User) Mini-app network access without its network grant, or access to device data outside Android permissions and picker scope.
- (User) Generated items that look different from Alphy. Mini-apps, lessons, and widgets take the app colors and shapes.
- (Claude, new) Technical data on user screens: IDs, URIs, schema keys, raw coordinates, ISO times, or English diagnostics.
- (Claude, new) A dead end: a "could not build" screen with no next step.

## 3. Constraints

Project requirements:

- `AGENTS.md` permits breaking changes and prohibits compatibility code or migrations solely for earlier development versions.
- `AGENTS.md` prohibits silent deletion of user data or credentials.
- Generated JavaScript runs in AndroidX `JavaScriptSandbox` (`sandbox/AndroidJsRuntime.kt`). It has access only to validated host interfaces.
- The host mediates network, files, media, AI, and device operations. Generated code has no direct Android APIs, raw sandbox privileges, or access to provider credentials. The host does not execute generated Kotlin.
- `ToolUiCatalog` validates visual types and tokens. Generated code cannot call arbitrary composables or use arbitrary imports, modifiers, or host calls.
- Each mini-app has an `ArtifactCoordinator` that orders events across hosts and checks the revision and state version.
- Alphy bounds memory, messages, execution time, storage, and work queues. It measures supported loads before choosing the new ceilings (5.3, 6.6, 9).
- If an event times out, Alphy closes its isolate and rejects its result. Alphy does not replay the event or commit its state or effects.
- Watchdogs measure time from submission to result. Frame rate is a separate measurement.
- Only `button` and `toggle` run inside a widget. Widget actions require an unlocked device and action tokens (`surfaces/ToolWidgets.kt`).
- Android permission dialogs, pickers, background limits, and system access restrictions apply to mini-apps.

Platform baseline (sources in section 13):

- The release SDK levels are minSdk 29, targetSdk 37, and compileSdk 37 (`app/build.gradle.kts`).
- Dynamic color needs API 31 or later.
- Edge-to-edge and predictive back are on for targetSdk 36 and later.
- With targetSdk 37, Android 17 limits the bitmap memory of RemoteViews to 6 × width × height bytes.
- The launcher decides about a widget pin. A denial gives no callback, and the caller must be in the foreground.
- AOSP marks the assistant role as not requestable (`requestable="false"`). Alphy opens the Android settings for that role.

Acceptance requirements ([Compose acceptance procedure](../../parity/compose-acceptance.md), sections 6.8 and 9):

- Each request needs three independent real generations. A correction stays in its original generation.
- ADB commands go only to `emulator-5556`. `emulator-5554` stays untouched.
- The runner grants no mini-app network access or Android permissions automatically. It does not tap generated controls automatically. Workflows use test data and explicit operation of Android dialogs and pickers.
- The `DEEPSEEK_API_KEY` rules in the Compose acceptance procedure apply.

Historical baseline from the 2026-11-04 code comparison of Alpha `7ae425a` with deal-studio-ohos `7ee06af1`:

- Alpha had one retained isolate and one transient isolate. An occupied slot returned `BUSY`.
- The earlier sandbox used a 16 MiB heap, 64 KiB messages, and 32 KiB drawing results. The watchdogs used 500 ms for discrete events and 100 ms for frame, pointer, timer, and control events.
- The earlier spec limited state to 48 KiB, source and UI to 32 KiB each, UI to 256 nodes and depth 8, images to 4, and drawing to 512 commands per frame. It had no bitmap drawing.
- Alpha had no mini-app network, file content, media, or AI access. Its device functions used separate grants and Alphy confirmations.
- Mini-app generation used 4,096 tokens, disabled thinking, and capped the response at 65,536 bytes. It failed on a provider `length` result without correction.
- The [roadmap](../plans/2026-11-03-alphy-ui-roadmap.md) records the later release baseline and assigns the work to replace these limits.

## 4. Product shape in one view

| Area | What the user sees | Section |
| --- | --- | --- |
| Desktop | «Сейчас» strip, «Мой стол» grid of live tiles, one composer | 5.1 |
| Chat | Text answers, search answers with sources, live mini-app cards, plan cards, automation cards | 5.2 |
| Mini-app | Live card in chat, full view, tile, home-screen widget, versions | 5.3, 5.4 |
| Course and lesson | Course outline in chat, full-screen player, tutor bar | 5.5 |
| Automation | Editable card, tile with switch, line in the menu list, run log | 5.6 |
| Agent | Plan card with before and after, steps card with stop | 5.7 |
| Assistant button | Half sheet over any app, with the «Экран» chip | 5.8 |
| Menu | «Поиск», «Новый чат», «Мини-приложения», «Обучение», «Автоматизации», recent chats | 5.11 |

## 5. The experience

### 5.1 Shell and desktop

- (User) The home screen is the interactive desktop.
- (User) A ChatGPT-like shell frames it: a menu button, a new-chat button, and one composer at the bottom.
- (User) The top of the desktop is the «Сейчас» strip, with one or two suggestions from Alphy.
- (User) Below the strip is «Мой стол», the user's own grid of tiles. Only the user changes it. The grid has an «Изменить» button.
- (User) Each tile is live. The user ticks a task, adds a glass of water, or continues a lesson from the tile.
- (Claude) A sent message opens the chat over the desktop. «Новый чат» and Back return to the desktop.
- (Claude) A long press on a tile removes it from the desktop. The item stays in the library.
- (Claude, new) «Изменить» opens the grid edit mode: remove and reorder.
- (Claude, new) The «Сейчас» strip uses local data only and sends nothing to DeepSeek. Examples:
  - the next calendar event, when the calendar permission exists
  - the next or unfinished lesson («Пора повторить»)
  - an automation that failed
  - start suggestions for a new user
- (Claude, new) The greeting can use the user's name, for example «Доброе утро, Евгений». The name comes from «Память» or from «Настройки».
- (Builder) Tile sizes, grid columns for each width class, the reorder gesture, and the empty desktop.

Sample from the chosen screens: «Сейчас» shows «Через 25 минут · Обсудить задачи команды · 11:00» and
«Пора повторить · Испанский, урок 4». «Мой стол» shows the tiles «Мой день 1 из 3», «Вода 5/8 +1»,
«Испанский 4/10», and «Автоматизации 2 активны · Вечерний обзор · 21:00».

### 5.2 Chat

- (User) One composer has text, voice, and «+». The placeholder in the samples is «Спросите что-нибудь».
- (Claude, new) «+» opens a sheet with two groups:
  - context chips: Камера, Фото, Файл, Календарь
  - creation shortcuts: Мини-приложение, Обучение, Автоматизация
- (Claude, new) «Файл» accepts JPEG, PNG, GIF, and WebP, as today, and UTF-8 plain text up to 8,000 characters. Other types get a short message that names the limit.
- (Claude, new) Answer text appears in parts as it arrives. Today the text arrives all at once.
- (Claude, new) TalkBack reads the answer when it is complete, not after each part.
- (Claude, new) Search stays automatic. The model decides `none`, `optional`, or `required`, as today (`cloud/ChatToolReply.kt`).
- (Claude) All generated items use one card frame with different content inside. A search answer shows in this frame with its sources.
- (Claude, new) Sources show as compact chips with the site name and the site icon from DeepSeek Search. The English source disclaimer becomes one short Russian line.
- (Claude, new) The send button becomes a stop button during a request. A stopped answer keeps the text that arrived. Today the stop text claims a saved part that does not exist.
- (Claude, new) Message actions move from the rows of text buttons into a long-press menu, for example Копировать and Повторить.
- (Claude, new) The chat history keeps its current functions: search, rename, and delete.
- (Builder) The method that delivers text in parts. The decision JSON (`answer|create|refine|clarify`) must still arrive complete.

### 5.3 Mini-apps in the chat

Decided by the user:

- A new mini-app appears in the chat as a live card that works. The user can use it in the card.
- Under the card are «Открыть» and «⋯».
- The «⋯» menu has «На рабочий стол», «Виджет на экран», «Версии», and «Удалить».
- The user changes a mini-app through the chat, for example «сделай кнопку крупнее».
- The new version shows as v2 in the same card, with «Вернуть v1».
- Generated chat changes have no separate review screen or Apply step. The card shows the live preview as the app builds.

Sample from the chosen screen: «Сделай трекер воды на 8 стаканов» → «Готово. Отмечайте стаканы прямо здесь.» → a card
«Вода · сегодня · v1» with «5 из 8 стаканов» and «+1 стакан».

The target of a change (Claude, new):

- The host fixes the target. The model never selects it.
- Only mini-app cards in the current chat are possible targets. Only their titles go to DeepSeek.
- With one card, or with a request that names the full title of a card, that card is the target.
- With several cards and no name, Alphy asks one short question with the card titles as buttons. deal-studio-ohos does the same ("Never guess a target", `VeraChat.ets`).
- «Изменить» in the full view opens the last chat of the mini-app, or a new chat when that chat was deleted. It puts the card there and passes the target through `hostTargetId` of `ChatToolReply.parse`.
- The composer then opens empty under the card. The earlier shell added the technical draft `Update tool [<id>]: ` (`ui/ShellViewModel.kt`).

Versions (Claude, new, unless marked):

- (Claude) The version list is the undo.
- «Версии» lists each version with its change label and time.
- «Вернуть vN» makes a new version from the UI and the source of vN, with the current state schema and collections. So the current data stays.
  - The current schema always contains the schema of vN, because a refinement only adds fields.
  - The current render check stays, and it must leave the state unchanged.
  - When the code of vN fails that check, «Версии» shows a short reason for that version in place of «Вернуть».
  - The earlier store required the old schema to accept the current state.
- When a new version comes from a change or from «Вернуть vN», each automation that writes to the mini-app stops. This is the current approval rule (`docs/automations.md`).
  - The new card names each stopped automation, with a button that opens its card for «Включить».
  - The automation tile and its run log show the same reason.

Save and delete (Claude, new):

- Each new mini-app is saved at once and appears in the library («Мини-приложения»).
- A mini-app outlives the chat that created it. A deleted chat keeps its mini-apps in the library.
- Every copy of a card in the chats shows the newest version and the one shared state.
- «Удалить» asks for confirmation. The dialog names what goes with the mini-app: its data, versions, tiles, and widgets.
- After a delete, each automation that uses the mini-app stops and shows the reason.

Mini-app network, files, and web content (User):

- The user can enable one network grant per mini-app. It covers all public and local destinations, redirects, and web subresources.
- The host applies the grant to every network request. Revocation stops new requests and cancels in-flight network work.
- A granted mini-app can send any data that Android makes accessible to it. Alphy requests no confirmation for individual transfers.
- The host supports file access through Android permissions and pickers, plus bounded transfers, web content, and URL images.
- Web content follows the same network grant. The host isolates web content from Android APIs, provider credentials, and other apps' state.
- «Доступ и функции» explains the full scope, shows the grant status, and lets the user revoke it (5.11).

Mini-app device, media, and AI operations (User):

- Mini-apps use declared, validated host interfaces for device operations. Alphy adds no device-operation grant or confirmation.
- Android still presents its required permission dialogs, pickers, and other system UI. If an operation needs that UI from a widget, the widget opens Alphy.
- Device functions cover a read-only device status snapshot, location, sensors, audio, and video. Audio includes short tones. The host bounds samples, media resources, and effects.
- Foreground timer events can request declared effects, including `vibration.play` and `notification.post`. The host validates and rate-limits those effects.
- A mini-app can pass Android-accessible device data to host AI without an Alphy prompt. Host AI uses the existing key consent (5.9).
- General generated code stops when the mini-app goes into the background. Only explicitly activated scheduled actions can run there under Android limits.
- When a schedule changes, the previous activation expires. The user must activate the changed schedule before it can run.
- «Удалить» still requires confirmation.

Hosts and interaction (User):

- Several mini-apps can stay open and active. The host bounds concurrent work and isolates their state, resource use, and errors.
- All hosts for the same mini-app show one state. `ArtifactCoordinator` orders their events. The host coordinates frame ownership for each mini-app.
- A countdown or stopwatch can show time from a stored start or end time through a native catalog element. This works on a tile without JavaScript.
- Text fields and sliders can send live input. Alphy bounds the event rate and retains the Apply path for controls that use it.
- A drawing area appears in the live preview before «Apply changes». Generation and edits show usable previews as soon as validation permits.
- The catalog supports Markdown, drag reorder, a route stack, and more than line and bar charts. It supports bounded bitmap drawing.
- Mini-app host controls use Russian UI text. Generated mini-apps use Russian for Russian requests. The workflows verify that controls perform meaningful actions.

Share, export, import, and manual edit (User):

- An explicit export action lets the user choose which app data to include. The user can share the export through Android.
- The export excludes provider credentials and access grants. Alphy validates imports before it changes any stored data.
- Import preserves existing data when a name or ID conflicts, or when validation fails. An imported app has no network grant.
- The library offers manual editing with validation before save. A failed or canceled edit preserves the last good version and its data.
- Chat remains the entry point for generated refinements. Manual editing is a separate library action.

Other rules:

- (Claude) The live card replaces the metadata-only card. The test assertion "Chat must not render rows or image nodes" in `MiniAppPresentationWorkflowTest.kt` changes with it.
- (Builder) The maximum card height, the compact form of each part, and the full view that «Открыть» opens.

### 5.4 Desktop tiles and home-screen widgets

- (User) The chat card, the desktop tile, and the home-screen widget show one live state.
- (User) Widgets take the app colors and shapes. Light and dark follow the system.
- (Claude) Widgets move to Glance 1.2.0. Today they use RemoteViews XML with fixed light colors (`res/layout/tool_widget.xml`).
- The widget shows the projection of the mini-app: text, numbers, progress, buttons, and toggles. `widgetNodes` in `ToolUiCatalog` defines it.
- Only buttons and toggles run in the widget, as today. Other content opens Alphy.
- (Claude, new) A widget shows an end time or whole minutes, not a clock that changes each second.
- «Виджет на экран» starts the pin flow with a preview. The text says that Android confirms the pin.
- (User) An automation has a tile with an on/off switch.
- (Claude) The automation tile shows the last run and its result. A failure shows on the tile and in a notification.
- (User) A course tile shows the progress, for example «Испанский 4/10».
- (Builder) Widget sizes, generated previews on API 35, and whether charts and images draw as bitmaps within the Android 17 limit.
- (Builder) Whether the pin sheet still offers a shortcut («Ярлык») next to the widget.

### 5.5 Courses and lessons

Decided by the user:

- Lesson mode generates UI during the lesson. The same generation route makes a personal slide or interactive page.
- The lesson player is full screen, with one slide at a time, as in stories.
- It has a progress strip at the top, «Дальше» at the bottom, and a «Спросить учителя» bar. The chat stays behind it.
- A slide uses the earlier answers. A short note says why the slide changed for the user.
- The samples show a course with numbered lessons: «Испанский · урок 4 из 10», and a lesson at slide 3 of 8.

Sample from the chosen screen: «Испанский · урок 4», progress 3/8, the exercise «Pretérito или imperfecto?» with the choices
«comí», «comía», and «como». The note says «Вы путали эти формы в 2 из 3 заданий, поэтому я добавил пример с «ayer».»

Claude's choices (Claude, played back, unless marked):

- A course starts from the chat («Научи меня…») or from «+» → Обучение.
- Alphy asks about the level and the goal. Then it makes a plan, and the chat shows it before the start.
- (Claude, new) The plan is a course outline with lesson titles. A narrow topic can have one lesson. Each lesson is a plan of about 8 slides.
- (Claude, new) The user changes the outline through the chat.
- Each slide is generated when the user reaches it, with the same parts as mini-apps.
- (Claude, new) The input of a slide is the lesson plan, the earlier answers, and the errors in this course.
- (Claude, new) Lesson parts are in the same library: explanation, example, exercise (choice, fill-in, order), check with feedback, and summary.
- (Claude, new) When a slide uses facts from search, it shows its sources.
- Progress shows in «Сейчас» and on the course tile.
- On a Fold, the tutor chat sits beside the slide.
- (Claude) A lesson card in the chat has «Продолжить».

Left to the builder:

- Whether the next slide is generated in advance, and when an answer makes that slide invalid.
- Where the tutor answer shows: in a sheet over the slide, or as a changed next step.
- The end of a lesson and of a course: a summary and the next actions.

### 5.6 Automations

Decided by the user:

- The user can start an automation from a chat message.
- An automation is an editable card with three fields: «Когда», «Если», «Что сделать».
- The user taps a field to change it. «Добавить действие» adds an action.
- «Проверить» runs it once now, so the user sees the result before the user turns it on.
- «Включить» turns it on.
- The automation then gets a tile on the desktop, with a switch, and a line in the menu list.

Sample from the chosen screen: «Каждый вечер в 21:00 присылай итоги дня» → the card «Вечерний обзор» with
«Когда: Каждый день, 21:00», «Если: Не в режиме «Не беспокоить»», and «Что сделать: Собрать итоги дня, Прислать уведомление».

Claude's choices (Claude, played back, unless marked):

- «Включить» approves the listed actions. Later runs do not ask.
- Android asks for the permissions that the automation needs, such as calendar or location, at «Включить». It does not ask later during a run.
- An automation that sends data to DeepSeek shows that data in its card. «Собрать итоги дня» is an example.
  - Today no automation can request cloud access (`docs/automations.md`). This choice changes that rule. Section 10 lists it.
- The menu line shows the last run and its result.
- When a run fails, the tile and a notification say so, and the card offers to fix the problem in the chat.
- (Claude, new) When an action changes data on the phone, «Проверить» first shows the plan of changes, as the agent does (5.7).
- (Claude, new) The route fills the card from the chat request (section 6). The card appears in the chat.
- (Claude, new) Fields use native pickers for time, days, places, and apps. Today they are raw text fields.
- (Claude, new) A change makes a new version, and the new version needs «Включить» again. This keeps the current approval rule.
- (Claude, new) The switch pauses at once. It resumes an approved, unchanged version at once. If the version or the Android access changed, the switch opens the card for «Включить». Today each resume needs a new review.
- (Claude, new) «Удалить» exists, with confirmation. Today the store has no delete for rules.
- (Claude, new) New conditions come as the cards need them, for example «Не в режиме «Не беспокоить»».

Constraints that stay: the trigger types (manual, schedule, interval, charging, geofence, notification), 1 to 3 actions,
50 rules, the exact-schedule rules, and the 30-day log (`automations/AutomationContract.kt`, `automations/AutomationStore.kt`).

Left to the builder: the run log screen, the failure notification, and the list of automation actions beyond the agent functions in 5.7.

### 5.7 Local agent

Decided by the user:

- The agent acts on the phone. It calls Android functions, runs automations, and keeps memory. DeepSeek plans and thinks.
- The agent does not ask before it reads data on the phone.
- Before changes, one plan card lists every change, with the value before and after.
- «Выполнить» approves the whole plan once. The card also has «Изменить».
- A stop button stays visible during the run.

Sample from the chosen screen: «Перенеси встречу с Сашей на завтра в 15:00 и напомни за час» → «План: 2 изменения · календарь»:
«Перенести «Созвон с Сашей»: Сегодня 17:00 → Завтра 15:00» and «Создать напоминание: Завтра 14:00», with «Изменить» and «Выполнить».

Claude's choices (Claude, played back, unless marked):

- «Изменить» adjusts the plan in the chat.
- During the run, a steps card shows the progress and the stop button.
- The agent stops at a failed step and reports which steps ran.
- The plan marks a change that Alphy cannot undo with «нельзя отменить».
- Android permissions are asked at the first need. A short reason shows before the system dialog.

What goes to DeepSeek (Claude, new). This rule follows the user's chips rule in 5.9:

- Agent reads stay on the phone. No read result goes to DeepSeek.
- DeepSeek gets the request text, the function list, the related memory facts (5.10), and the chips that the user adds.
- DeepSeek returns a plan with match criteria, such as title words and a date range, and the new values.
- The phone finds the items, puts the real values in the plan card, and runs the plan after «Выполнить».
- If several items match, the plan card shows them, and the user picks one.
- When a task needs DeepSeek to read calendar contents, Alphy shows a dashed «Календарь» chip, and the user adds it.
- Local functions, such as a search for a free time slot, are permitted.

Calendar changes (Claude, new):

- The agent may change an event in a calendar with contributor access or higher, when the user's account is the organizer.
- When the event has other attendees, the plan line names them and says that they get an update.
- An invitation from another organizer stays read-only, and the plan says so.
- A change to one instance of a series writes an exception for that instance only.
- «Создать напоминание» is an alert on the moved event, here 60 minutes before it. It needs only WRITE_CALENDAR, which the manifest already declares.
- Today only events that Alphy created are writable, and `calendar.write` is unavailable (`capabilities/CapabilityContract.kt`). The new function removes that limit for the agent only.

The version 1 agent functions (Claude, new):

| Group | Functions |
| --- | --- |
| Current, from `capabilities/CapabilityContract.kt` | `notification.post`, `calendar.read` (local result only), `calendar.create` (system form), the app-owned calendar functions (`OwnedCalendarContract`), `maps.open`, `maps.route`, `link.open`, `app.open`, `flashlight.set`, `vibration.play` |
| New | calendar event change with an alert (in place of the unavailable `calendar.write`), alarm and timer through the system clock app, memory read and write, mini-app state set (the planned `artifact.data`), automation create, pause, and resume |
| Not for the agent | `medicine.*` (`MedicineContract`), `photo.pick`, `file.pick`, `camera.capture` (the user adds these as chips), contacts, calls, SMS |

Left to the builder: the tool-call loop with DeepSeek, its round limits, the plan data format, and the function IDs of the new functions.

### 5.8 Assistant button

Decided by the user:

- A long press on the power button, or the Quick Settings tile (`AlphaTileService` in `surfaces/SurfaceEntry.kt`), opens a half sheet over the current app.
- The sheet has the composer with text and voice («Спросите Alphy»).
- «Экран» adds the current screen. The chip is dashed until the user adds it, for example «+ Экран: «Что посмотреть в Казани»».
- Screen content leaves the phone only after the user adds it and sends.
- The answer appears in the sheet. «Открыть» continues the same chat in Alphy.

Claude's choices (Claude, played back, unless marked):

- A mini-app or a search answer appears in the sheet as the same live card as in the chat.
- The sheet grows to full height when the answer is long.
- Chats from the sheet go to the chat history.
- (Claude, new) Alphy keeps the activity layer: `setUiEnabled(false)` and then `startAssistantActivity` (`surfaces/AssistantSession.kt`).
- (Claude, new) Alphy keeps `FLAG_SECURE`, the close on screen off, and no start from the lock screen.
- (Claude, new) The service declares the Android 17 attributes `usesAssistData`, `usesAssistScreenshots`, and `usesAssistStructureScreenContent`, and the READ_ASSIST_STRUCTURE_SCREEN_CONTENT permission.
- (Claude, new) The screen capture stays in memory until the user adds «Экран». It is cleared when the sheet closes or the device locks. This is the current rule.
- (Claude, new) «Настройки» open the Android assistant settings directly, because the role request shows no dialog (section 3). The text tells the user to pick Alphy there.

Left to the builder: the sheet heights, the locked-device state, and the voice states in the sheet.

### 5.9 Cloud consent and private content

- (User) The user gives consent one time, at DeepSeek key entry. No message asks again.
- (User) Chips are the explicit step for private content: «Экран», photos, and calendar.
- (Claude) The consent text names messages, search queries, and chip content.
- (Claude, new) The consent text also names these items. Each one has its own explicit step:
  - the data that an enabled automation lists in its card («Включить»)
  - the memory facts that the user saved and that relate to a request («Запомни…» or «Запомнить?»)
  - the code and the declared UI of a mini-app that the user asks to change (the change request)
- (Claude, new) The «Файл» chip follows the same rule as the other chips.
- (User) These chat, local agent, and automation rules continue to apply to their own requests.
- (User) A mini-app with its network grant can transfer Android-accessible device data. Alphy requests no separate consent for each transfer.
- (User) Host AI for mini-apps works independently of the mini-app network grant. It uses the existing provider key consent and requests no mini-app AI confirmation.
- (User) A mini-app can pass Android-accessible device data to host AI. The host owns the provider credentials and does not expose them to generated code or exports.
- (Claude, new) «Настройки» shows the consent text again. «Удалить ключ» stops functions that depend on the provider key. The mini-app network grant is separate.
- (Claude, new) The README and `docs/cloud-integration.md` describe these consent boundaries.

### 5.10 Memory

- (User) The agent keeps memory.
- (Claude) Memory stays on the phone. The «Память» page in the menu lists the facts. The user can edit and delete each one.
- (Claude) Alphy adds the facts that relate to a request, with no chip. The user sees them on «Память».
- (Claude, new) Alphy saves a fact only when the user asks («Запомни…») or accepts the suggestion «Запомнить?». A fact from a chip or an agent read is not saved without that step.
- (Claude, new) The match is local. Alphy compares lowercase Russian word stems, with «ё» read as «е», and adds at most 5 matched facts.
- (Claude, new) Facts go only with chat requests (the decision call and the answer) and with agent plan requests. Mini-app generation, lesson slides, and automation steps get no facts.
- (Claude, new) DeepSeek never gets the full fact list, and it never selects from it.
- Limit: the local match can miss an indirect fact, such as an allergy for a dinner question.

### 5.11 Menu, library, and settings

- (User) The menu opens from the left, as in ChatGPT. It has «Поиск», «Новый чат», and the three extensions «Мини-приложения», «Обучение», and «Автоматизации». The recent chats come below them. The user profile is at the bottom.
- (Claude, new) The menu also has «Память», «Доступ и функции», and «Настройки».
- (Claude, new) The library lists mini-apps with search. Each row shows a live thumbnail, the version, and where the item is placed. The thumbnail method comes from DEAL Studio (`GeneratedAppStudioScreen.kt`).
- (Claude, new) «Доступ и функции» shows Android access in plain words, with no capability IDs or API levels.
- (User) The page shows each mini-app's network grant and how to revoke it. It explains access to public and local destinations, redirects, subresources, and data transfers.
- (User) The page distinguishes the network grant from Android permissions and host AI access under the provider key consent.
- (Claude, new) «Настройки»: the key with the consent text, the assistant settings link, voice, and the user's name. There is no theme switch, because light and dark follow the system.
- (User) The app name stays «Alphy» (`AndroidManifest.xml` label, and the brief: «Documents/Alpha alphy»).
- (User) The UI uses the polite form, as the chosen samples show («Спросите что-нибудь», «Отмечайте стаканы прямо здесь»).
- (Claude, new) All UI text is Russian and lives in Android string resources. Today no `strings.xml` exists.
- (Claude, new) Alphy sets its app locale to Russian, so that Material component labels are Russian too. Today they follow the device locale.

### 5.12 Visual style

- (User) Style C: Material 3 Expressive, colors from the wallpaper, tinted tiles, rounder shapes, and bolder headings.
- (User) Generated mini-apps, lessons, and widgets take the same colors and shapes. Light and dark follow the system.
- (Claude) Alphy uses material3 1.5.0-beta02 for `MaterialExpressiveTheme`, `MotionScheme`, and the Expressive components.
  - Limit: beta APIs can change. When a stable 1.5.0 is available, Alphy moves to it.
  - `LoadingIndicator` and `MaterialShapes` are still experimental in 1.5.0-beta02. Use them only with the opt-in annotation.
  - The stable BOM 2026.09.00 maps material3 1.4.0, so the build sets the material3 version explicitly.
- (Claude) On API 29 and 30, Alphy uses a fixed palette from the mock purple `#7652df`.
- (Claude) Mini-apps choose color roles only (`tone`: default, muted, accent, success, warning, error). The `theme` node loses `palette` and `mode`.
- (Claude, new) A saved mini-app whose `theme` node has `palette` or `mode` then fails validation (`validateTheme` in `tools/ToolUiCatalog.kt`). Alphy tells the user, and the user deletes it explicitly. No compatibility code is added (`AGENTS.md`).
- (Claude, new) A drawing color can be a role name or a hex value, as in deal-studio-ohos (`VeraGfx.ets`). The canvas background takes a role color in place of the fixed `#101827` (`tools/NativeToolSurface.kt`). The guidance prefers roles for the frame of a scene. Hex stays permitted for objects inside a scene.
- (Claude) All generated items use the same card frame.
- (Builder) Whether the six `preset` values stay. If they stay, each one is a variation of the wallpaper scheme, never a custom palette.
- (Builder) One icon set for the whole app. Text glyphs such as «↑», «■», and «⋯» go. The 21 glyph names in saved mini-apps are part of saved data.
- (Builder) Fonts. Compose UI 1.12.0 supports variable Google Fonts.

Design rules from DEAL Studio (`docs/design/deal-studio-v1/README.md` in deal-studio):

- One primary action for each state.
- Lists are rows with dividers.
- A status shows with color, icon, and text together.
- The renderer owns the pixels. Generated data owns structure, bindings, tone, and actions.
- One title for each screen. The renderer hides a card heading that repeats the app title.

### 5.13 Large screens and Fold

- (Claude) On a wide screen, the chat sits beside the open mini-app, and the tutor chat sits beside the slide.
- (Claude, new) Alphy uses Navigation 3 1.2.0 and material3-adaptive 1.3.0 with its list-detail and supporting-pane scenes. WindowManager 1.5.1 gives the fold posture.
- (Builder) The tabletop posture and the narrow fallback.

## 6. Generation route

(User) Route 1: Alphy keeps `ToolSpec`, its one catalog (`ToolUiCatalog`), the renderer, and `JavaScriptSandbox`.
Alphy adds the deal-studio-ohos parts-first steps before DeepSeek writes code.

### 6.1 Steps for a new item

1. **Decide.** The chat decision step returns `answer`, `create`, `refine`, or `clarify`, with search `none`, `optional`, or `required` (`cloud/ChatToolReply.kt`).
   - (Claude, new) It gets new kinds for a course, an agent task, and an automation.
   - (Claude, new) It gets a surface flag for a request that draws, moves by itself, or takes touches anywhere, such as a game.
   - (Builder) The exact schema.
2. **Needs plan.** DeepSeek writes a plan of needs, with no code.
   - It does not see the parts or the catalog, as in deal-studio-ohos (`entry/src/main/ets/vera/NeedsPlan.ets`).
   - The plan has a title, inputs, constants, needs of known types, and at most 2 questions.
3. **Select.** The selector picks a part for each need, picks catalog sections, and fills slot values.
   - It also checks claims and picks the questions that block the build, as in deal-studio-ohos (`entry/src/main/ets/vera/agent/`).
4. **Assemble.** Code builds the `ToolSpec` from the parts.
   - The UI subtrees go into one tree. The state fields go into one schema.
   - The handler fragments go into the one JavaScript source body.
   - Each part root node carries its part ID.
   - The result passes the catalog validation and the DEAL structure rules (6.6).
5. **Holes.** DeepSeek writes code only for needs that no part covers.
   - A hole is a JavaScript function, a UI subtree, or both, inside the `ToolSpec` limits.
   - A hole can also declare the top-level `surfaces` and `presentation` fields. A drawn scene inside a screen is a hole with its own surface.
   - If no part fits the request, the whole screen is one hole, as in deal-studio-ohos.
6. **Test run and repair.** The current generation test (`tools/ToolGenerationSmoke.kt`) runs the result, and repair edits follow on failure.
   - If repair fails, the card shows a short reason and a next step (6.7).

The parts-first route covers games and scenes inside screens. It can use a whole-screen hole when no part fits a game.
The guidance includes a game other than Arkanoid. It also shows how to embed an interactive drawing area in a screen.

(Claude) The card shows validated assembled parts as soon as step 4 ends. Holes show as placeholders until step 5 ends.
The preview shows a validated drawing area before «Apply changes». The first UI and the completed UI have separate measurements (6.8).

### 6.2 Outputs of the one route

(User) The route option that the user chose said: one route makes chat answers, mini-apps, desktop tiles, home widgets,
lesson slides, and automation cards. This document makes it concrete (Claude, new):

| Output | What the route makes | Where it shows |
| --- | --- | --- |
| Chat answer | The decision and the answer text. In version 1, an answer has no generated UI (2.2). | Chat, half sheet |
| Mini-app | A `ToolSpec` with source and state | Chat card, full view, tile, widget |
| Lesson slide | A `ToolSpec` with lesson context: the plan, the answers, the errors | Lesson player |
| Automation | A rule in the automation contract (`automations/AutomationContract.kt`). The same plan, select, and fill method applies to triggers, conditions, and actions. | Automation card, tile |

- Mini-app tiles and widgets are views of the mini-app. The route does not make them separately.
- The course tile and the automation tile are native views of their data.
- The automation card is native UI. Only its rule data comes from the route.

### 6.3 Change requests

(Claude, new) A change follows deal-studio-ohos, where a change to a program goes to the model as a refine (`docs/agent.md` in deal-studio-ohos):

- A change is a DeepSeek refine of the current `ToolSpec` through the current `ToolEngine.generate` path. It has no new needs plan and no new part selection.
- `ToolSpec.stateForRevision` keeps every state field and type in a generated refinement.
- The refine guidance keeps the part IDs. Unchanged parts keep their compact form and widget projection. A changed subtree uses the generic compact form and `widgetNodes`.
- The request carries the spec, declared UI, and initial values. It excludes live state. Section 5.9 defines the separate host AI interface.
- The target rules are in 5.3.
- The change time is a separate row in the measurement (6.8).

### 6.4 Parts

- (Claude, new) A part is Alphy-owned. It has a UI subtree, state fields, JavaScript handlers, typed slots, and a short description for the selector.
- (Claude, new) Each part has a compact form for cards and tiles, a full form, and a widget projection.
- (Claude, new) The parts library comes from a Russian request set: the session samples and new Russian requests.
- (Claude, new) Lesson parts are in the same library (5.5).
- (Builder) The part format, the file layout, and the list of parts.

### 6.5 Selector in the Jev role

- (User, brief) DeepSeek does the Jev role in version 1.
- (User) The selector asks Jev-style questions: a closed list of choices, with one answer and its probabilities. Later, the real Jev model (`jev-latest`) can replace DeepSeek, with no other change to the route.
- (Claude) TypeSafe has no official Kotlin library, so a direct HTTP client is necessary. The "JEVify" name in the brief belongs to a community skill, not to a TypeSafe product.
- (Claude, new) The question types follow deal-studio-ohos (`entry/src/main/ets/vera/JevSelector.ets`):
  - `noul`: a yes or no question. The answer is a probability.
  - `choice`: a closed list of choices. The answer has the choice, a confidence, and probabilities.
- (Claude, new) One function type carries all calls: `JevAsk(body) -> answers JSON`, with an empty result on failure. Any backend can sit behind it.
- (Claude, new) The DeepSeek backend turns off thinking for selector calls, as `JevProxy.ets` does.
- (Claude, new) A debug-only setting holds a TypeSafe key, for a later test of the real Jev (`POST https://api.typesafe.ai/v1/systemone`). DEAL Studio has a Kotlin client with no production caller: `generatedapp/JevChoiceClient.kt`.
- (Builder) Pattern (a) or pattern (b), chosen by measurement:
  - (a) one call for each question, with a letter answer and `top_logprobs`, 4 calls in parallel, as `JevProxy.ets` does
  - (b) one JSON call for all questions, as `system-one-adapter` does
- Rules for both patterns:
  - The letter scheme permits at most 26 choices in one question. Ask about sections first, then about parts.
  - The thresholds KEEP_ABOVE 0.2 and PICK_ABOVE 0.25 were tuned for Jev. Calibrate them again on DeepSeek.
  - Check whether DeepSeek accepts a strict `json_schema`. The DEAL Studio connector sends one to `api.deepseek.com/responses` (`deepseek-connector/.../DeepSeekGenerationClient.kt`). No live result confirms it.

### 6.6 Validation

- The catalog validation stays the one source of truth (`ToolUiCatalog`).
- (Claude) Port the DEAL Studio structure rules into that validation: no nested cards, no second hero, no invalid segmented controls, and no placeholder icons (`generatedapp/CanonicalDealUiRuntime.kt` in deal-studio).
- (Claude, new) An unknown icon fails validation. It does not fall back to a placeholder.
- (Claude, new) No technical text on the app surface, and no repeated derived text.
- Mini-app generation uses a larger measured output budget. Its implementation plan records the token and response-byte bounds and the evidence for them.
- The validator enforces measured limits on UI size, source, state, resources, and drawing. Generation and saved-app execution apply the same contract.
- Treat a provider `length` result as eligible for the existing single bounded correction. Reject incomplete output before save or execution.
- `ToolGenerationSmoke` checks meaningful interactions. It checks frame execution and a state or drawing change for games and embedded scenes.
- For each game example, including the non-Arkanoid example, define the expected rules before testing.
- Check applicable scoring, collision or other result logic, terminal conditions, and restart. Verify that restart restores the expected initial state.
- The device tests check accessible controls, input, and visible results. A surface declaration or a screenshot alone does not prove interaction.

### 6.7 Progress and failure

- (Claude, new) The card status names the current step in plain words. The builder picks the words.
- (Claude, new) A failure never ends at a dead screen. The card gives a short reason and a next step, for example «Повторить» or a simpler version from parts only.
- (Claude, new) A stop during generation keeps the last good version.
- Tests cover cancellation, provider failure, truncated or invalid output, and runtime failure. They check recovery and preservation of the last good version and its state.

### 6.8 Measurement

Historical result: deal-studio-ohos reported a median of 2.0 s for 88 parts-only builds on an earlier host route.
That route used a sketch step and the real Jev. The result does not establish a baseline for the needs plan with DeepSeek.

Requirement from the approved roadmap:

- Pin the exact runnable deal-studio-ohos revision, workflow inputs, and execution configuration before testing. Record the exact Alphy revision and configuration too.
- Use the same requests, interaction checks, and success criteria in both products. Identify device and provider differences before comparing results.
- Use the 121 English requests from the three corpus files in section 13 at deal-studio-ohos `7ee06af1`. Keep the 45 `test` prompts in `compose-fresh.tsv` out of tuning. Report their results separately.
- Include Russian requests and extra workflows for the approved mini-app capabilities. Freeze their inputs and expected interactions before the final comparison.
- For each game workflow, define the expected rules from its request. Test applicable scoring, collision or other result logic, terminal conditions, and restart in both products.
- Run three independent real generations for every request in each product. The English matrix has `121 × 3 × 2 = 726` generations. Track provider calls separately.
- Use fresh state and record each outcome. Keep failed generations in the results. A model correction belongs to its original generation.
- Compare functional behavior, blind visual ratings, successful completions, time to first UI, time to completed UI, frame performance, and failure recovery. Hide product labels from visual reviewers.
- Measure both UI times from send. First UI is the first visible generated UI. Completed UI is the fully usable mini-app after validation.
- Report route stages, parts-only rate, failure rate, and change time. Keep latency and frame evidence separate from provider and device conditions.
- Count game-rule checks in the functional results and apply the no-regression gate to them. Require no functional or visual regression in any category.
- A critical interaction, data-loss, or permissions defect fails the gate.
- Require no latency regression under comparable conditions. Report incomparable conditions and withhold a latency pass for them.

Assumption: the parts-first route can reduce build time. The model writes code only for holes. The matched comparison tests this assumption.

Assumption: `deepseek-flash` returns `top_logprobs` when thinking is off.
Test it with `logprobs: true`, `top_logprobs: 20`, and `thinking: {type: "disabled"}` before selecting that pattern.

## 7. Platform and dependencies

| Item | Choice | Label |
| --- | --- | --- |
| Material 3 | `material3` 1.5.0-beta02, with Compose 1.12.0, set explicitly | (Claude) |
| Widgets | Glance 1.2.0 | (Claude) |
| Navigation | Navigation 3 1.2.0 | (Claude, new) |
| Adaptive layout | material3-adaptive 1.3.0, WindowManager 1.5.1 | (Claude, new) |
| Back | Predictive back with NavigationEvent 1.2.0 or activity 1.12+ | (Claude, new) |
| compileSdk | Stays 37. No move to 37.1, so no `material3-a2ui` | (Claude, new) |
| Key storage | The `EncryptedApiKeyStore` pattern: AES/GCM, a key in AndroidKeyStore, private SharedPreferences | (User, brief) |
| Key providers | One store for each provider, DeepSeek now and a debug-only TypeSafe slot, as in DEAL Studio | (Claude, new) |
| Debug credentials | Fixture keys for offline tests. The acceptance runner's one-use channel supplies real test keys. BuildConfig contains no keys. | Requirement |
| Voice | On-device T-One recognition and Silero synthesis stay | Assumption |

- Assumption: "cloud only" in the brief covers the language model, search, and the Jev role, but not speech.
  - Basis: the brief names only DeepSeek services, and the prototype already has on-device voice.
  - Test: ask the user (section 10).
- Assumption: material3 1.5.0-beta02 builds with compileSdk 37 and AGP 9.2.1.
  - Basis: its Compose 1.12.0 dependencies need compileSdk 37 and AGP 9.2.0.
  - Test: add the dependency, then run `checkAarMetadata`.
- (Builder) The Compose BOM version, lifecycle-viewmodel-compose, ui-tooling-preview, and a version catalog.
- (User) Network, file, web, device, media, and AI access use host interfaces. Android system permissions, pickers, and background execution limits continue to apply.
- (User) Only explicitly activated scheduled actions can execute in the background. The host enforces Android limits and records the active schedule version.

## 8. Data and storage changes

- `ToolRevision` gets a change label and a time. The earlier `ToolStore` kept neither value. The new fields require a database version change.
- Part root nodes in a `ToolSpec` carry their part IDs (6.1).
- The tool store gets a delete. It removes the data, versions, tiles, and widget bindings of the confirmed mini-app. Dependent automations and its scheduled actions stop.
- The automation store gets a delete.
- New stores: the desktop layout, courses (outline, lessons, slides, answers, progress), memory facts, and the consent record.
- The chat keeps references from cards to items and versions. A mini-app records its last chat, for «Изменить» in the full view.
- The renderer requires the current `theme` node (5.12). A change to the icon set can also change the accepted glyph names.
- Store one network grant per mini-app and the current activation for each scheduled action. Keep grants and provider credentials separate from exported app data.
- Save imports and manual edits only after validation. An import, edit, or cancellation cannot silently remove or replace existing user data.
- Alphy opens the existing mini-app library and creates any missing parts of the current storage structure in one transaction. Existing records, chats, and credentials stay available.
- If the library cannot open, Alphy shows a plain error and preserves the original files. An existing library selection continues to isolate widgets and scheduled actions by library identity.

## 9. Quality and verification

Tests:

- The instrumented tests match exact text and content descriptions on `AccessibilityNodeInfo`. They use no Compose test rule and no test tags (`androidTest/diagnostics/UiFailureDiagnostics.kt`).
- The new UI changes that text and layout, so these tests change with it. Examples:
  - The header rule wants «Меню» and «История» as text nodes at the top (`ShellWorkflowTest.kt`). The new shell has a menu button and a new-chat button.
  - The chat-card rule forbids rows and images in the chat (`MiniAppPresentationWorkflowTest.kt`).
  - The window must have no CheckBox node, but a live card can contain a `checkbox`.
- (Claude, new) New tests find nodes through string resources or test tags, not through literal text.
- Behavior changes get black-box tests of the user flows.
- Each test run records the revision, the command, the inputs, the expected result, and the observed result.
- Section 3 applies to the acceptance runner. Section 6.8 defines the final comparison across products.
- Capacity tests cover memory, response size, event delays, frame performance, and several active mini-apps. Record the load and results that justify each ceiling.
- Interaction tests cover live input, games, embedded scenes, navigation, drag reorder, preview, and Russian UI text. Check the observable result of each control.
- Test the network grant, public and local requests, redirects, subresources, and revocation. Test data transfer with test-owned files and endpoints.
- Test system permission and picker denial, foreground timer effects, background suspension, and scheduled actions after activation and schedule changes.
- Test host AI with and without the network grant, and with no provider key. Verify that credentials stay in the host.
- Test export selection, share, a valid import, invalid input, collisions, and manual edit failure. Verify that existing data and grants remain intact.

Devices:

- The project test device is an OPPO CPH2765 with Android 17, API 37 (`docs/evidence/shell/run.md`).
- OPPO blocks shell permission grants, so the tests use the Android permission dialog (`docs/voice-integration.md`).
- `FLAG_SECURE` blocks screenshots of «Настройки» and the half sheet. (Builder) A method for visual checks of these screens.

Gallery (Claude, new):

- A gallery shows every part, sample mini-apps, and sample slides in these states:
  - light and dark, with dynamic schemes from several wallpapers, and the fixed API 29 palette
  - font scale 100% and 200%
  - compact and expanded width
- Screenshots come from the production renderer (DEAL Studio rule).

Accessibility:

- Body text contrast of 4.5:1 or more in every gallery scheme. Today the user text is about 3.8:1.
- Touch targets of 48 dp or more, TalkBack labels, reduced motion, and text sizes in sp.
- No live region that announces each part of a streamed answer.

Reports and docs:

- The matched comparison report from 6.8. Record the source identities, provider and device conditions, independent generation results, and evidence for each category.
- Docs updates:
  - README and `docs/cloud-integration.md`: consent once, streamed text, automatic search
  - `docs/engine-choice.md`: androidx.javascriptengine, not QuickJS
  - `docs/tool-integration.md` and `docs/parity/compose-coverage.md`: the route and the validated catalog
  - `docs/native-capabilities.md` and `docs/parity/compose-acceptance.md`: mini-app permission boundaries and acceptance rules
  - `docs/automations.md`: the DeepSeek step, the resume rule, and the delete

## 10. Design choices

Items 1–13 record the choices from revision 2. Items 14–19 record requirements from the approved mini-app roadmap.

1. Agent reads stay on the phone. DeepSeek plans from the request, and the phone puts the real values in the plan card. A task that needs DeepSeek to read the calendar shows a dashed «Календарь» chip (5.7).
2. An automation may send the data that its card lists to DeepSeek, for example for «Собрать итоги дня». Today no automation can request cloud access (5.6).
3. When a mini-app gets a new version, its automations stop. The new card names them, with a button for «Включить» (5.3).
4. The «Сейчас» strip uses local data only (5.1).
5. The agent function set, with no medicine, contacts, calls, or SMS. The agent may change events where the user is the organizer, and attendees get an update (5.7).
6. A change request without a name changes the only card in the chat. With several cards, Alphy asks which one (5.3).
7. Memory: a local word match, at most 5 facts, only for chat and agent plan requests (5.10).
8. Mini-apps are saved at once and outlive their chats. «Удалить» removes everything that belongs to the item (5.3).
9. Saved mini-apps with `palette` or `mode` fail validation, and the user deletes them explicitly (5.12).
10. A course has numbered lessons of about 8 slides each (5.5).
11. The automation switch resumes an approved, unchanged version without a dialog (5.6).
12. On-device voice stays in version 1, by the Assumption in section 7.
13. Russian string resources, a Russian app locale, and Navigation 3 (5.11, 7).
14. One revocable network grant covers public and local destinations, redirects, subresources, and transfers of Android-accessible data (5.3, 5.9, 5.11).
15. Mini-app device and AI operations require no Alphy prompts. Android permissions and pickers apply. Host AI uses the existing provider key consent independently of network access (5.3, 5.9).
16. General generated code stops in the background. Only explicitly activated scheduled actions can run there. Changed schedules require reactivation (5.3, 7).
17. Share uses an explicit export with data selection. Exports exclude grants and credentials. Imports and manual edits validate before save and preserve existing data (5.3, 8).
18. Generation uses the parts-first route, measured budgets, and bounded correction. The matched comparison includes English, held-out, Russian, and extra workflows (6).
19. Mini-app capacity and interactive UI expand under measured limits. Mini-app deletion keeps its confirmation (3, 5.3, 9).

## 11. Left to the builder

- The selector pattern, the calibration, and the groups of questions (6.5).
- The part format, the file layout, and the parts list (6.4).
- Layout sizes: card heights, the tile grid, the sheet heights.
- The method that delivers answer text in parts (5.2).
- The icon set, the fonts, and the presets (5.12).
- Slide generation in advance, and the tutor answer placement (5.5).
- Widget charts and images, previews, and the shortcut option (5.4).
- The navigation graph and the deep links, for example `alpha://tool/<id>`.
- The tool-call loop of the agent and the IDs of new functions (5.7).
- A method for visual checks of `FLAG_SECURE` screens (9).
- The test structure, within section 9.
- Exact capacity ceilings, from the measurements in sections 6.6 and 9.
- The supported capability and archive formats within the boundaries in sections 5.3 and 8.

## 12. Risks

- The historical benchmark used a different route and provider. The comparison must pin both implementations and report conditions that prevent a direct latency comparison (6.8).
- DeepSeek selector answers are calibrated differently from Jev. On bounded-loop cases, Flash scored 25 of 30 against 30 of 30 for Jev. The letter scheme has a 26-choice limit.
- Beta APIs in material3 1.5.0 can change. Late alphas changed Slider, SplitButton, ButtonGroupScope, and PolygonShape.
- Assumption: without the three attributes, Android 17 withholds assist data and screenshots.
  - Basis: `res/xml/voice_interaction_service.xml` does not declare them, and the session reads both.
  - Test: compare the received data on the test device with and without the attributes.
- The plan with match criteria (5.7) can fail on vague requests. The plan card then shows several matches, or Alphy asks.
- Concurrent sessions, larger state, and graphics can increase memory use and event delays. Capacity tests establish bounds on the supported device (9).
- Network revocation can race with requests, redirects, and subresources. Test revocation during a transfer, including on local networks (5.3, 9).
- OS permission denial and background execution limits can prevent scheduled device effects. Report the failed action and verify that an obsolete schedule cannot run.
- Untrusted imports or invalid edits can damage stored data. Validate first and test preservation of the last good state (8, 9).
- Changes to text and layout can break tests that use exact labels. Update their selectors with the UI (9).
- The voice models are for ARM64 only and take about 281 MB.

## 13. Sources

Projects:

- Alphy prototype: `/Users/egavrin/Documents/alpha`
- deal-studio-ohos: `/Users/egavrin/Documents/deal-studio-ohos`
  - route and agent: `entry/src/main/ets/vera/agent/` (`VeraBuildRoute.ets`, `VeraAgent.ets`, `AgentHole.ets`, `AgentSurface.ets`)
  - plan and selector: `entry/src/main/ets/vera/` (`NeedsPlan.ets`, `JevSelector.ets`, `JevProxy.ets`, `VeraWidgetPreview.ets`, `VeraChat.ets`, `VeraGfx.ets`)
  - measurements and prompts: `docs/agent.md`, `tools/vera-harness/compose-fresh.tsv`, `tools/vera-harness/compose-prompts.tsv`, `tools/vera-bench/prompts.tsv`
- DEAL Studio for Android: `/Users/egavrin/Documents/deal-studio`
  - `app/src/main/java/com/offlineassistant/app/generatedapp/` (`GeneratedAppTheme.kt`, `CanonicalDealUiRuntime.kt`, `GeneratedAppStudioScreen.kt`, `JevChoiceClient.kt`)
  - design rules: `docs/design/deal-studio-v1/README.md`
  - DeepSeek connector: `deepseek-connector/src/main/kotlin/com/offlineassistant/deepseek/DeepSeekGenerationClient.kt`
- Offline assistant: `/Users/egavrin/Documents/android-offline-assistant-poc` (`app/src/main/java/com/offlineassistant/app/settings/EncryptedApiKeyStore.kt`)
- jev-use: `/Users/egavrin/Documents/jev-use`

Platform references:

- Material 3 releases: https://developer.android.com/jetpack/androidx/releases/compose-material3
- Material 3 adaptive: https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
- Navigation 3: https://developer.android.com/jetpack/androidx/releases/navigation3
- Glance: https://developer.android.com/jetpack/androidx/releases/glance
- AppWidgetManager: https://developer.android.com/reference/android/appwidget/AppWidgetManager
- VoiceInteractionSession: https://developer.android.com/reference/android/service/voice/VoiceInteractionSession
- AOSP roles: https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/Permission/PermissionController/res/xml/roles.xml
- DeepSeek API: https://api-docs.deepseek.com/ and https://api-docs.deepseek.com/guides/search
