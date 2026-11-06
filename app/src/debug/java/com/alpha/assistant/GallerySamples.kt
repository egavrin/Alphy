package com.alpha.assistant

import com.alpha.assistant.tools.ToolSpec
import com.alpha.assistant.tools.ToolUiCatalog
import org.json.JSONArray
import org.json.JSONObject

/** A section keeps related renderer states together and fits a capture viewport. */
internal data class GallerySection(val id: String, val content: String)

internal data class GallerySample(val id: String, val title: String, val sections: List<GallerySection>,
    val capabilities: List<String> = emptyList()) {
    fun spec(section: String? = null): ToolSpec {
        val selected = if (section == null) sections else listOf(sections.single { it.id == section })
        val state = JSONObject(GalleryState).put("handlingBack", section == "back")
        return gallerySpec(column(selected.joinToString(",") { it.content }), state.toString(), GallerySchema, title, capabilities)
    }

    fun componentTypes(section: String? = null): Set<String> = buildSet {
        fun visit(node: JSONObject) {
            add(node.getString("type"))
            node.optJSONArray("children")?.let { children ->
                repeat(children.length()) { visit(children.getJSONObject(it)) }
            }
            node.optJSONObject("rowTemplate")?.let(::visit)
        }
        visit(spec(section).ui)
    }
}

private const val GallerySchema = """{
    "active":"boolean","tick":"number","done":"boolean","transition":"boolean",
    "name":"string","count":"number","date":"string","minutes":"number","deadline":"number","video":"string",
    "dialog":"boolean","sheet":"boolean","mode":"string","page":"number","handlingBack":"boolean","returned":"boolean",
    "range":{"type":"array","maxItems":2,"items":"number"},
    "dates":{"type":"array","maxItems":2,"items":"string"},
    "routePath":{"type":"array","maxItems":4,"items":"string"},
    "tags":{"type":"array","maxItems":4,"items":"string"},
    "rows":{"type":"list","maxItems":10,"fields":{"id":"string","title":"string","group":"string"}},
    "days":{"type":"array","maxItems":10,"items":"string"},
    "values":{"type":"array","maxItems":10,"items":"number"}
}"""

private const val GalleryState = """{
    "active":false,"tick":0,"done":false,"transition":true,
    "name":"Июнь","count":2,"date":"2028-06-03","minutes":540,"deadline":0,"video":"",
    "dialog":false,"sheet":false,"mode":"week","page":0,"handlingBack":false,"returned":false,
    "range":[2,8],"dates":["2028-06-03","2028-06-05"],"tags":["week"],
    "routePath":["overview"],
    "rows":[{"id":"one","title":"Заметка из поездки","group":"Июнь"}],
    "days":["Пн","Вт","Ср"],"values":[2,4,3]
}"""

internal val GalleryToneLabels = linkedMapOf(
    "Форма" to "accent",
    "Группа" to "success",
    "Свойства" to "warning",
    "Лента" to "error",
)

private val RolesUi = JSONObject().put("type", "theme").put("preset", "clean")
    .put("style", JSONObject().put("gap", "small")).put("children", JSONArray()
        .put(JSONObject().put("type", "grid").put("columns", 4).put("minCellWidth", 136)
            .put("style", JSONObject().put("gap", "small")).put("children", JSONArray().apply {
                for (role in ToolUiCatalog.tones) put(JSONObject().put("type", "card").put("style", JSONObject().put("tone", role))
                    .put("children", JSONArray().put(JSONObject().put("type", "text").put("text", role))))
            }))
        .put(JSONObject("""{"type":"button","text":"Проверить роли","action":"active","icon":"Check"}"""))
        .put(JSONObject("""{"type":"motion","preset":"fade","trigger":"tick","children":[{"type":"badge","text":"Роли проверены","visibleBind":"active","style":{"tone":"success"}}]}""")))
    .toString()

private fun column(children: String) = """{"type":"column","style":{"gap":"small"},"children":[$children]}"""
private fun button(text: String, id: String) = """{"type":"button","text":"$text","action":"$id"}"""
private fun label(text: String) = """{"type":"text","text":"$text"}"""
private const val WeekOptions = """[{"text":"Неделя","value":"week"},{"text":"Месяц","value":"month"}]"""

private fun section(id: String, content: String) = GallerySection(id, content.trimIndent())
private fun choice(type: String, text: String, id: String, bind: String = "mode") =
    """{"type":"$type","text":"$text","bind":"$bind","action":"$id","options":$WeekOptions}"""
private fun recordList(type: String, text: String, options: String = "") =
    """{"type":"$type","text":"$text","bind":"rows"$options}"""

internal val GallerySamples = listOf(
    GallerySample("roles", "Роли", listOf(
        section("swatches", RolesUi),
        section("motion", """
            ${button("Проверить движение", "animate")},
            {"type":"motion","preset":"fade","trigger":"tick","children":[
                {"type":"badge","text":"Движение готово","visibleBind":"active","style":{"tone":"success"}}
            ]}
        """),
    )),
    GallerySample("catalog", "Поездка", listOf(
        section("containers", """
            {"type":"grid","columns":4,"minCellWidth":136,"style":{"gap":"small"},"children":[
                {"type":"form","style":{"tone":"accent"},"children":[${label("Форма")}]},
                {"type":"listGroup","style":{"tone":"success"},"children":[${label("Группа")}]},
                {"type":"facts","style":{"tone":"warning"},"children":[${label("Свойства")}]},
                {"type":"timeline","style":{"tone":"error"},"children":[${label("Лента")}]}
            ]}
        """),
        section("dialog", """
            {"type":"flowRow","style":{"gap":"small"},"children":[${button("Открыть диалог", "show")}]},
            {"type":"dialog","text":"Проверка","visibleBind":"dialog","dismissAction":"hide","children":[${label("Данные сохранены")}]}
        """),
        section("disabled", """
            {"type":"form","enabled":false,"children":[
                ${label("Маршрут пока недоступен")},
                ${button("Закрытый маршрут", "closed")}
            ]}
        """),
        section("layout", """
            {"type":"section","text":"Поездка","style":{"tone":"accent"},"children":[
                {"type":"row","style":{"gap":"small"},"children":[
                    {"type":"icon","name":"Calendar","text":"Календарь поездки","action":"icon"},
                    {"type":"metric","text":"Дней","bind":"count"}
                ]},
                {"type":"divider"},
                {"type":"box","children":[{"type":"badge","text":"Суббота","style":{"tone":"accent"}}]},
                {"type":"spacer","size":6}
            ]}
        """),
        section("itinerary", """
            {"type":"listItem","text":"Маршрут","style":{"tone":"success"},"children":[${label("Набережная и музей")}]},
            {"type":"timelineItem","text":"Отправление","time":"09:00","detail":"Встреча на вокзале"}
        """),
        section("facts", """{"type":"fact","text":"Месяц","bind":"name"}"""),
        section("banner", """{"type":"banner","text":"Напоминание","style":{"tone":"warning"},"children":[${label("Возьмите билеты")}]}"""),
        section("empty", """{"type":"emptyState","text":"Покупок пока нет","children":[${label("Добавьте покупку")}]}"""),
    )),
    GallerySample("forms", "Покупки", listOf(
        section("checks", """
            ${label("Покупки на неделю")},
            {"type":"checkbox","text":"Купить хлеб","bind":"done","action":"done"},
            {"type":"toggle","text":"Включить напоминание","bind":"done","action":"done_toggle","enabled":false}
        """),
        section("input", """{"type":"input","text":"Продукт","bind":"name","action":"name"}"""),
        section("search", """{"type":"search","text":"Поиск продуктов","bind":"name","action":"name_search"}"""),
        section("number", """{"type":"numberInput","text":"Количество","bind":"count","action":"count","min":0,"max":10}"""),
        section("slider", """{"type":"slider","text":"Лимит","bind":"count","action":"count_slider","min":0,"max":10}"""),
        section("range", """{"type":"rangeSlider","text":"Диапазон цены","bind":"range","action":"range","min":0,"max":10}"""),
        section("date", """{"type":"dateInput","text":"День покупки","bind":"date","action":"date"}"""),
        section("time", """{"type":"timeInput","text":"Время покупки","bind":"minutes","action":"minutes"}"""),
        section("timer", """{"type":"timer","text":"Таймер покупок","bind":"deadline","mode":"countdown"}"""),
        section("date-range", """{"type":"dateRangeInput","text":"Дни доставки","bind":"dates","action":"dates"}"""),
    )),
    GallerySample("choices", "Привычки", listOf(
        section("tabs", choice("tabs", "Дневник привычек", "mode_tabs")),
        section("chips", """
            {"type":"chip","text":"Отмечено","bind":"done","action":"done","enabled":false},
            {"type":"actionChip","text":"Добавить привычку","action":"add"}
        """),
        section("select", choice("select", "Фильтр", "mode_select")),
        section("single", choice("singleChoice", "Вид отчёта", "mode_radio")),
        section("multi", choice("multiChoice", "Сравнить периоды", "tags", "tags")),
        section("segmented", choice("segmented", "Вид дневника", "mode_segment")),
        section("menu", choice("menu", "Меню отчёта", "mode_menu")),
        section("bar", choice("navigationBar", "Страницы дневника", "mode_bar")),
        section("rail", choice("navigationRail", "Разделы", "mode_rail")),
    )),
    GallerySample("records", "Записи", listOf(
        section("table", recordList("table", "Бюджет поездки", ""","action":"record","columns":[{"text":"Статья","field":"title"}]""")),
        section("chart", """{"type":"chart","text":"Расходы по дням","kind":"bar","categories":"days","series":[{"text":"Дневные расходы","bind":"values"}]}"""),
        section("progress", """
            {"type":"progress","text":"Недельный лимит","bind":"count","min":0,"max":5},
            {"type":"progressRing","text":"Оплачено","bind":"count","min":0,"max":5}
        """),
        section("list", recordList("list", "Заметки", ""","columns":[{"text":"Заметка","field":"title"}]""")),
        section("grouped", recordList("groupedList", "По месяцам", ",\"groupBy\":\"group\"")),
        section("lazy-list", recordList("lazyList", "Журнал", ""","viewportHeight":100""")),
        section("lazy-grid", recordList("lazyGrid", "Карточки", ""","viewportHeight":176,"columns":2,"minCellWidth":240""")),
    )),
    GallerySample("presentation", "Экран и слои", listOf(
        section("sheet", """
            ${button("Открыть панель", "showSheet")},
            {"type":"bottomSheet","text":"Панель образца","visibleBind":"sheet","dismissAction":"hideSheet","children":[${label("Без внешних действий")}]}
        """),
        section("bars", """
            {"type":"appBar","text":"Медиа","children":[{"type":"icon","name":"Camera","text":"Фото","action":"photo"}]},
            {"type":"bottomBar","children":[${button("Информация", "info")}]}
        """),
        section("pager", """{"type":"pager","text":"Альбом","bind":"page","action":"page","viewportHeight":96,"children":[${label("Первый кадр")},${label("Второй кадр")}]}"""),
        section("transition", """{"type":"transition","bind":"transition","preset":"fade","children":[${label("Мягкое появление")}]}"""),
        section("stage", """{"type":"stage","height":128,"floor":true,"children":[
            {"type":"transform","pose":[0,0,0,0,0,0,1,1],"size":100,"children":[${label("Слой")}]}
        ]}"""),
        section("image", """{"type":"scroll","children":[{"type":"image","text":"Фото в архиве","resourceId":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]}"""),
        section("video", """{"type":"video","text":"Видео из поездки","bind":"video","action":"videoPlay","enabled":false}"""),
        section("loading", """{"type":"skeleton","text":"Каркас изображения"},{"type":"spinner","text":"Загрузка снимка"}"""),
        section("snackbar", """{"type":"snackbar","text":"Запись добавлена","action":"snack"}"""),
        section("back", """
            ${label("Образец кнопки Назад")},
            {"type":"backHandler","action":"back","enabledBind":"handlingBack"},
            {"type":"text","text":"Назад обработано","visibleBind":"returned"}
        """),
    )),
    GallerySample("device", "Действия Android", listOf(
        section("calendar", """{"type":"calendar","action":"calendar","capability":"calendar.select","args":{"version":1},"enabled":false}"""),
        section("medicine", """{"type":"medicine","action":"medicine","capability":"medicine.history","args":{"version":1,"from":1830297600000,"to":1830384000000,"cursor":0},"enabled":false}"""),
        section("navigation", """{"type":"navigation","action":"navigation","capability":"maps.markReached","args":{"version":1,"stopId":"museum","label":"Музей","latE6":55755000,"lonE6":37615000,"coordinateSystem":"wgs84"},"enabled":false}"""),
    ), capabilities = listOf("calendar.select", "medicine.history", "maps.markReached")),
    GallerySample("routes", "Маршрут", listOf(
        section("stack", """
            {"type":"routeStack","text":"Маршрут","bind":"routePath","action":"routeBack",
             "routes":["overview","detail"],"children":[${button("Открыть остановку", "routePush")},${label("Музей у набережной")}]}
        """),
    )),
).associateBy(GallerySample::id)
