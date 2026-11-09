package com.alpha.assistant.tools

import android.content.Context
import com.alpha.assistant.GallerySamples
import com.alpha.assistant.GalleryToneLabels
import com.alpha.assistant.R
import com.alpha.assistant.capabilities.MedicineContract
import com.alpha.assistant.capabilities.NavigationContract
import com.alpha.assistant.capabilities.OwnedCalendarContract

internal data class GalleryCaptureCheck(
    val body: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
    val disabled: List<String> = emptyList(),
    val graphics: List<String> = emptyList(),
    val editable: String? = null,
    val adjust: String? = null,
    val unclipped: List<String> = emptyList(),
    val next: GalleryCaptureStep? = null,
)

internal data class GalleryCaptureStep(
    val id: String,
    val check: GalleryCaptureCheck,
    val click: String? = null,
    val reveal: String? = null,
    val back: Boolean = false,
    val dismiss: String? = null,
    val platform: Boolean = false,
    val expandSheet: Boolean = false,
)

internal fun galleryCaptureChecks(context: Context): Map<String, GalleryCaptureCheck> {
    fun close(title: String) = context.getString(R.string.tool_generated_dialog_close, title)
    fun apply(label: String) = context.getString(R.string.tool_generated_apply, label)
    val cancel = context.getString(R.string.cancel)
    fun platformPicker(label: String) = GalleryCaptureCheck(targets = listOf(label),
        next = GalleryCaptureStep("open", GalleryCaptureCheck(), click = label, platform = true))
    fun dropdown(title: String) = GalleryCaptureCheck(body = listOf(title), targets = listOf("Неделя"),
        next = GalleryCaptureStep("open", GalleryCaptureCheck(targets = listOf("Месяц")), click = "Неделя", dismiss = "Месяц"))
    fun capabilityNode(section: String) = GallerySamples.getValue("device").spec(section).ui.getJSONArray("children").getJSONObject(0)
    val calendar = OwnedCalendarContract.label(OwnedCalendarContract.request(capabilityNode("calendar")))
    val medicine = MedicineContract.label(MedicineContract.request(capabilityNode("medicine")))
    val navigation = NavigationContract.label(NavigationContract.request(capabilityNode("navigation")))
    val albumNext = context.getString(R.string.tool_page_next, "Альбом")
    val albumPrevious = context.getString(R.string.tool_page_previous, "Альбом")
    val routeBack = context.getString(R.string.tool_route_back)
    val rows = "title: Заметка из поездки · group: Июнь"
    val dateRange = "Дни доставки: 2028-06-03 – 2028-06-05"
    return linkedMapOf(
        "roles/swatches" to GalleryCaptureCheck(ToolUiCatalog.tones.toList(), listOf("Проверить роли")),
        "roles/motion" to GalleryCaptureCheck(targets = listOf("Проверить движение"),
            next = GalleryCaptureStep("complete", GalleryCaptureCheck(body = listOf("Движение готово")), click = "Проверить движение")),
        "catalog/containers" to GalleryCaptureCheck(GalleryToneLabels.keys.toList()),
        "catalog/dialog" to GalleryCaptureCheck(targets = listOf("Открыть диалог"),
            next = GalleryCaptureStep("open", GalleryCaptureCheck(listOf("Данные сохранены"), listOf(close("Проверка"))),
                click = "Открыть диалог", dismiss = close("Проверка"))),
        "catalog/disabled" to GalleryCaptureCheck(listOf("Маршрут пока недоступен"), disabled = listOf("Закрытый маршрут")),
        "catalog/layout" to GalleryCaptureCheck(listOf("Поездка", "Дней", "Суббота"), listOf("Календарь поездки")),
        "catalog/itinerary" to GalleryCaptureCheck(listOf("Набережная и музей", "Встреча на вокзале", "09:00")),
        "catalog/facts" to GalleryCaptureCheck(listOf("Месяц", "Июнь")),
        "catalog/banner" to GalleryCaptureCheck(listOf("Напоминание", "Возьмите билеты")),
        "catalog/empty" to GalleryCaptureCheck(listOf("Покупок пока нет", "Добавьте покупку")),
        "forms/checks" to GalleryCaptureCheck(listOf("Покупки на неделю", "Включить напоминание"),
            listOf("Купить хлеб"), listOf("Включить напоминание")),
        "forms/input" to GalleryCaptureCheck(listOf("Продукт"), disabled = listOf(apply("Продукт")), editable = "Продукт"),
        "forms/search" to GalleryCaptureCheck(listOf("Поиск продуктов"),
            targets = listOf(context.getString(R.string.tool_generated_search, "Поиск продуктов")), editable = "Поиск продуктов"),
        "forms/number" to GalleryCaptureCheck(listOf("Количество"), disabled = listOf(apply("Количество")), editable = "Количество"),
        "forms/slider" to GalleryCaptureCheck(listOf("Лимит: 2.0"), disabled = listOf(apply("Лимит")), adjust = "Лимит"),
        "forms/range" to GalleryCaptureCheck(listOf("Диапазон цены: 2.0 – 8.0"),
            disabled = listOf(apply("Диапазон цены")), adjust = "Диапазон цены"),
        "forms/date" to platformPicker("День покупки: 2028-06-03"),
        "forms/time" to platformPicker("Время покупки: 09:00"),
        "forms/timer" to GalleryCaptureCheck(listOf(context.getString(R.string.tool_timer_value,
            "Таймер покупок", context.getString(R.string.tool_timer_idle)))),
        "forms/date-range" to GalleryCaptureCheck(targets = listOf(dateRange),
            next = GalleryCaptureStep("open", GalleryCaptureCheck(listOf("Дни доставки"), listOf(cancel, apply("Дни доставки"))),
                click = dateRange, dismiss = cancel)),
        "choices/tabs" to GalleryCaptureCheck(listOf("Дневник привычек"), listOf("Месяц")),
        "choices/chips" to GalleryCaptureCheck(targets = listOf("Добавить привычку"), disabled = listOf("Отмечено")),
        "choices/select" to dropdown("Фильтр"),
        "choices/single" to GalleryCaptureCheck(listOf("Вид отчёта"), listOf("Месяц")),
        "choices/multi" to GalleryCaptureCheck(listOf("Сравнить периоды"), listOf("Месяц")),
        "choices/segmented" to GalleryCaptureCheck(listOf("Вид дневника"), listOf("Месяц")),
        "choices/menu" to dropdown("Меню отчёта"),
        "choices/bar" to GalleryCaptureCheck(listOf("Страницы дневника"), listOf("Месяц")),
        "choices/rail" to GalleryCaptureCheck(listOf("Разделы"), listOf("Месяц")),
        "records/table" to GalleryCaptureCheck(listOf("Бюджет поездки", "Статья"), listOf("Заметка из поездки")),
        "records/chart" to GalleryCaptureCheck(listOf("Расходы по дням"),
            graphics = listOf("Расходы по дням. Дневные расходы: Пн 2; Вт 4; Ср 3; "),
            next = GalleryCaptureStep("legend", GalleryCaptureCheck(listOf("Дневные расходы")), reveal = "Дневные расходы")),
        "records/progress" to GalleryCaptureCheck(listOf("Недельный лимит: 2", "Оплачено: 2"), graphics = listOf("Недельный лимит", "Оплачено")),
        "records/list" to GalleryCaptureCheck(listOf("Заметки", "Заметка из поездки")),
        "records/grouped" to GalleryCaptureCheck(listOf("По месяцам", "Июнь", rows)),
        "records/lazy-list" to GalleryCaptureCheck(listOf("Журнал", rows)),
        "records/lazy-grid" to GalleryCaptureCheck(listOf("Карточки", rows), unclipped = listOf(rows)),
        "presentation/sheet" to GalleryCaptureCheck(targets = listOf("Открыть панель"),
            next = GalleryCaptureStep("open", GalleryCaptureCheck(listOf("Без внешних действий"), listOf(close("Панель образца"))),
                click = "Открыть панель", dismiss = close("Панель образца"), expandSheet = true)),
        "presentation/bars" to GalleryCaptureCheck(listOf("Медиа"), listOf("Фото", "Информация")),
        "presentation/pager" to GalleryCaptureCheck(listOf("Первый кадр"), listOf(albumNext), listOf(albumPrevious),
            next = GalleryCaptureStep("second", GalleryCaptureCheck(listOf("Второй кадр"), listOf(albumPrevious), listOf(albumNext)), click = albumNext)),
        "presentation/transition" to GalleryCaptureCheck(listOf("Мягкое появление")),
        "presentation/stage" to GalleryCaptureCheck(listOf("Слой")),
        "presentation/image" to GalleryCaptureCheck(listOf(context.getString(R.string.tool_image_unavailable, "Фото в архиве"))),
        "presentation/video" to GalleryCaptureCheck(listOf("Видео из поездки", context.getString(R.string.tool_video_open_app))),
        "presentation/loading" to GalleryCaptureCheck(graphics = listOf("Каркас изображения", "Загрузка снимка")),
        "presentation/snackbar" to GalleryCaptureCheck(listOf("Запись добавлена"), listOf(context.getString(R.string.tool_dismiss))),
        "device/calendar" to GalleryCaptureCheck(disabled = listOf(calendar)),
        "device/medicine" to GalleryCaptureCheck(disabled = listOf(medicine)),
        "device/navigation" to GalleryCaptureCheck(disabled = listOf(navigation)),
        "routes/stack" to GalleryCaptureCheck(targets = listOf("Открыть остановку"),
            next = GalleryCaptureStep("detail", GalleryCaptureCheck(listOf("Музей у набережной"), listOf(routeBack)),
                click = "Открыть остановку", dismiss = routeBack)),
        "canvas" to GalleryCaptureCheck(listOf(context.getString(R.string.tool_game_ready)),
            listOf(context.getString(R.string.tool_game_start), context.getString(R.string.tool_game_left), context.getString(R.string.tool_game_right)),
            graphics = listOf("Проверка холста. Графика. ")),
    )
}
