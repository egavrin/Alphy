# Alphy first-run onboarding

This document records the design of the first-run onboarding and four related fixes.
The UI/UX review of the Alpha 12 release found these problems.
The [Alphy UI design](2026-11-03-alphy-ui-design.md) stays the approved UI specification.

## Intent

A new user installs Alphy and opens the desktop.
In Alpha 12, nothing tells this user that Alphy needs a DeepSeek key.
The user finds out only after the first message fails.
Alpha 4 removed the «Подключите DeepSeek» card from «Сейчас» on purpose.

- **Requirement** (user): a full onboarding flow for the first run («Полноценный онбординг»).
- **Requirement** (user): the approved fix list: tappable example prompts, a «Где взять ключ» link on the key page, and a visible «Повторить» after a key error.
- **Requirement** (spec 5.9): the user gives cloud consent one time, at key entry.
- **Requirement** (spec 5.11): the UI uses the polite form.

## Goals

- A new user knows what Alphy does, connects DeepSeek, and sends a first request in one short flow.
- The user can skip the key and use the app without a key.
- The flow shows one time only.

## Non-goals and anti-goals

- No status card on the desktop for a missing key. Alpha 4 removed it on purpose.
- No consent screen apart from key entry. The key panel keeps the consent text.
- No tour of each feature. The flow has three short steps.
- No second onboarding after a key deletion.

## Gate

**Implementation choice:** the view model owns the gate.

- The process-wide model of `MainActivity` offers onboarding. Test models do not offer it, unless a test asks for it.
- At load, the model shows onboarding when two conditions are true:
  - The `settings` table has no `onboarded` row.
  - The key status is `Missing`.
- A saved key, a key without consent, and an unreadable key do not start onboarding.
- The model decides one time at load. A key saved during onboarding does not close the flow.
- The model writes `onboarded` = `1` when the flow ends. The flow then never shows again.

## Flow

One screen shows one step at a time, with «Шаг N из 3». Back returns to the previous step.
On the first step, Back leaves the app, as on the desktop.

1. **Welcome.** The mascot, «Здравствуйте, я Alphy», and three lines about what Alphy does.
   An optional field «Ваше имя» sets the name for the desktop greeting. Button: «Далее».
2. **Key.** «Подключите DeepSeek», a short reason, and the same key panel as «Настройки» → «DeepSeek».
   The panel has the status, the key field, «Где взять ключ», the consent text, and «Согласиться и сохранить».
   Buttons: «Далее» (enabled after the key is saved) and «Позже». «Позже» ends the flow and opens the desktop.
3. **Try.** «Всё готово» and three example requests as chips. Button: «Начать».
   A chip ends the flow and puts its text in the desktop composer. «Начать» ends the flow with an empty composer.

## Related fixes

- The desktop example requests become chips. A chip adds its text to the composer draft.
  An existing draft keeps its text, and the example goes on a new line.
- The empty desktop text uses the polite form and the library term: «Здесь появятся ваши мини-приложения».
- The key panel has «Где взять ключ». It is a link to `https://platform.deepseek.com/api_keys`.
  The settings page and the onboarding use the same panel.
- A chat message with a key error shows «Повторить запрос» next to «Открыть настройки».

## Verification

Black-box workflow tests on an emulator, written before the code:

- First run: the name, the key, and an example chip lead to the desktop with the greeting and the filled composer.
- «Позже» opens the desktop. A second launch with the same data shows the desktop, not onboarding.
- A saved key skips onboarding.
- Back on step 2 returns to step 1.
- The key panel has the «Где взять ключ» link with the DeepSeek URL.
- A desktop example chip fills the composer.
- After a key error, «Повторить запрос» is visible. After the key is saved, it sends the request again.

## Left to the builder

- Visual details: spacing, chip style, and the step indicator style.
- The mascot follows the desktop rule: no mascot below 320 dp width or at a font scale of 1.5 or more.
