package com.alpha.assistant.courses

import com.alpha.assistant.cloud.CloudFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal class ReadingCourse(
    val setup: CourseSetup,
    val outline: CourseOutline,
    val slides: Map<String, CourseSlideContent>,
) {
    init {
        setup.validate()
        outline.encode()
        val steps = outline.steps()
        require(slides.keys == steps.map { it.slide.id }.toSet())
        for ((_, _, step) in steps) {
            val content = slides.getValue(step.id)
            val spec = parseCourseSpec(content, step.kind)
            val exercise = CourseExercise.read(spec, step.kind)
            checkedCourseUi(spec, content.uiJson, content.stateJson, exercise, step.kind)
            require(content.narration != null)
        }
    }
}

/** Selects eligible material, validates model choices, and saves a complete practice session. */
internal class ReadingProgram(
    private val store: CourseStore,
    private val generate: suspend (JSONObject) -> String,
) {
    fun openCore(): CourseSnapshot = store.saveReading(ReadingMaterial.core)

    suspend fun next(courseId: String): CourseSnapshot {
        val chain = store.readingChain(courseId)
        val last = chain.last()
        if (!last.progress.isComplete) return last
        val pastWords = mutableMapOf<String, Int>()
        chain.drop(1).forEachIndexed { index, course ->
            course.outline.steps().forEach { pastWords[it.slide.goal] = index + 1 }
        }
        val recent = pastWords.filterValues { it >= chain.size - 3 }.keys
        val missed = chain.takeLast(4).flatMap { course ->
            val goals = course.outline.steps().associate { it.slide.id to it.slide.goal }
            store.answerAttempts(course.id).filterNot { it.correct }.mapNotNull { goals[it.slideId]?.firstOrNull()?.lowercaseChar() }
        }.groupingBy { it }.eachCount()
        val ranked = ReadingMaterial.vocabulary.filterNot { it.word in recent }
            .sortedWith(compareBy<ReadingWord> { pastWords[it.word] ?: 0 }
                .thenByDescending { missed[it.word.first()] ?: 0 }.thenBy { it.word })
        val offered = ranked.take(30).associateBy { it.word }
        val request = JSONObject().put("words", JSONArray(offered.keys.toList()))
            .put("reviewLetters", JSONArray(missed.entries.sortedByDescending { it.value }.take(8).map { it.key.toString() }))
            .put("orderable", JSONArray(offered.values.filter { it.orderable }.map { it.word }))
        var batch: List<ReadingItem>? = null
        for (attempt in 0..1) {
            currentCoroutineContext().ensureActive()
            try {
                batch = decode(generate(request), offered)
                break
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                val reason = (failure as? CloudFailure)?.code ?: failure.javaClass.simpleName
                android.util.Log.i("AlphyReading", "practice attempt=${attempt + 1} rejected=$reason")
                request.put("correction", "Return one JSON object with six distinct offered words. Use only word or order. Follow the exact shape.")
            }
        }
        val review = batch == null
        val items = batch ?: ranked.take(6).map { ReadingItem(it, it.orderable) }
        currentCoroutineContext().ensureActive()
        return store.saveReading(ReadingMaterial.practice(items, chain.size, review), last)
    }

    private fun decode(raw: String, offered: Map<String, ReadingWord>): List<ReadingItem> {
        require(raw.length <= 4096)
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == setOf("exercises"))
        val rows = root.getJSONArray("exercises")
        require(rows.length() == 6)
        val used = mutableSetOf<String>()
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("word", "type"))
            val word = offered.getValue(row.getString("word"))
            require(used.add(word.word))
            val type = row.getString("type")
            require(type == "word" || type == "order" && word.orderable)
            ReadingItem(word, type == "order")
        }
    }

    companion object {
        const val INSTRUCTIONS = "Select six distinct Russian reading exercises from the offered words. " +
            "Use reviewLetters when possible. Each type must be exactly word or order. " +
            "Return one JSON object shaped as {\"exercises\":[{\"type\":\"word\",\"word\":\"exact offered word\"}]}, with six entries. " +
            "Use order only for words in orderable. Do not add answers, text, stress marks, or fields."
    }
}

internal data class ReadingWord(val parts: List<String>) {
    val spoken = parts.joinToString("")
    val word = spoken.replace("\u0301", "")
    val tiles = parts.map { it.replace("\u0301", "").uppercase(java.util.Locale.ROOT) }
    val orderable = tiles.size >= 2 && tiles.distinct().size == tiles.size && tiles.dropLast(1).all { part ->
        part.length <= 2 && part.last() in "АЕЁИОУЫЭЮЯ"
    }

    init {
        val vowels = "аеёиоуыэюя"
        require(parts.all { part -> part.count { it in vowels } == 1 }) { "Invalid syllables in $word" }
        require(!spoken.contains('́') || spoken.indexOf('́') > 0 &&
            spoken[spoken.indexOf('́') - 1] in vowels && spoken.count { it == '́' } == 1) {
            "Invalid stress in $word"
        }
    }
}

internal data class ReadingItem(val word: ReadingWord, val order: Boolean)

internal object ReadingMaterial {
    const val TITLE = "Мастерская слов"
    private const val ALPHABET = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    private const val LEVEL = "Начинающий читатель"
    private const val GOAL = "Звуки, буквы, слоги и слова"
    private val russian = java.util.Locale.forLanguageTag("ru")
    private val names = "а|бэ|вэ|гэ|дэ|е|ё|жэ|зэ|и|и краткое|ка|эль|эм|эн|о|пэ|эр|эс|тэ|у|эф|ха|це|че|ша|ща|твёрдый знак|ы|мягкий знак|э|ю|я".split('|')
    private val examples = "а́ист|ба́нка|ва́за|гу́сь|до́м|е́ль|ёлка|жу́к|зо́нт|и́ва|йо́гурт|ко́т|луна́|ма́ма|но́с|о́стров|па́па|ра́ма|со́м|то́рт|у́тка|фла́г|хле́б|ца́пля|ча́шка|ша́р|щу́ка|съёмка|сы́р|ко́нь|э́хо|юла́|я́блоко".split('|')

    // Spellings and accents were checked against the OpenRussian nouns dataset.
    // Only simple open syllables qualify as interior order tiles.
    internal val vocabulary = """
        а́з-бу-ка а́д-рес а́ст-ра ба́-ня ба-ра-ба́н ба-то́н 
        бе-рё-за бе-се́-да би-ле́т бли́н  бо-ло́-то бу́б-лик бу-ке́т
        бу-ма́-га бу-ра́н  ва-ре́-нье ве́-ник  ве-сна́ ве́т-ка
        ве́-чер ви́ш-ня во-да́  во-ро́-на во-ро-бе́й га-зе́-та га́л-ка
        гвоздь ги-та́-ра гло́-бус го́-лубь го-ра́ го́-род гор-шо́к гру́-ша
        да́-ча две́рь де́-ре-во  ди-ва́н до-ска́ до́ч-ка дру́ж-ба
        ду-га́ ду́д-ка е-жи́-ха е-но́т ё-жик ёл-ка жа-ра́ 
        жи-ра́ф жук за-бо́р за-га́д-ка за́й-ка за́-яц зве-зда́ зе́-бра
        зер-но́ зи-ма́ зо́-ло-то иг-ла́ иг-ра́ иг-ру́ш-ка ин-дю́к йо́-гурт
        ка-ба́н ка́-ме-шек ка-мы́ш ка-на́т ка-пу́с-та кар-ти́-на ка́с-ка
         ка-ту́ш-ка ка́-ша ке́-ды ки́с-точ-ка кла́д клуб-ни́-ка
        кни́-га ко-ра́бль ко́-шка кра́с-ка кре́с-ло кро́т ку́-бик ку́к-ла
        ла́в-ка ла́м-па ла́с-точ-ка ле́-бедь ле́й-ка ле́-то ли-мо́н ли-са́
        ло́д-ка ло́-жка лу́-жа лу́-па ма-ли́-на ма-я́к ме́л мет-ла́
        меч-та́ ми́-ска мо́-ре мо-ло-ко́ мо́ст му-зе́й му́-ха мя́ч
        ни́т-ка но-га́ но́-та но́чь о-бе́д о́б-ла-ко о-кно́ о-ле́нь
        о-ре́х о-са́ о́-сень о́-че-редь па́л-ка  па́р-та пе́с-ня
        пе-ро́ пе-со́к пе-ту́х пе́ч-ка пи-ро́г пи-сьмо́ пла́-тье по-да́-рок
        по́-езд по́-ле по́л-ка по-ми-до́р по́ч-та пра́зд-ник пру́д пу́-го-ви-ца
        пче-ла́ ра́-ду-га ра-ке́-та ра-ку́ш-ка ре-ка́ ре́-па ри́с ри-су́-нок
        ро́-за  ро-ма́ш-ка ру-ка́ ры́-ба ры́сь рюк-за́к са-ла́т
        са-мо-лёт са-по́г са́-хар све-ча́ се-мья́ се́-но се́т-ка си-ни́-ца
        ска́з-ка ска-ме́й-ка ско-во-ро-да́ сле́д слон снег сне-го-ви́к со-ба́-ка
        со́лн-це со-ро́-ка со-сна́ ста-ка́н сту́л су́м-ка  та-ре́л-ка
        те-ле-фо́н  те-ре-мо́к ти́гр ти-ши-на́ то-по́р тра-ва́
        трам-ва́й тру-ба́ ту́-ча у́-гол у-го-лёк  у́-зел у́-ли-ца
        у-ли́т-ка у́т-ро фо-на́рь фа-со́ль фе́-я фло́т  фру́кт
        ха-ла́т хле́б хо-ро-во́д хо-мя́к ца́-пля цве-то́к це́пь ци́ф-ра
        ча́й-ка ча́й-ник че-ре-па́-ха чер-ни́-ла че-шу-я́ чи́-жик чу́-до ша́п-ка
          ши́ш-ка шко́-ла шко́ль-ник шну́р шо-ко-ла́д шу́-ба
        щёт-ка щу́-ка эк-ра́н экс-ка-ва́-тор э́-хо ю́-бка ю-ла́ ю́н-га
        я́-го-да я́-щик я́-корь я́-ма я́-ще-ри-ца
    """.trimIndent().split(Regex("\\s+")).map { ReadingWord(it.split('-')) }
        .filter { it.word.length in 3..12 && it.parts.size >= 2 }.distinctBy { it.word }
        .filterNot { it.word in examples.map { word -> word.replace("\u0301", "") } }

    val core: ReadingCourse by lazy {
        val builder = Builder()
        for ((index, letter) in ALPHABET.withIndex()) {
            val name = names[index]
            val word = examples[index]
            val symbol = letter.toString()
            val explanation = when (letter) {
                'А' -> "Это буква А. Открой рот и произнеси звук а. Послушай, как начинается слово аист."
                'Ё' -> "Это буква ё. В начале слова ёлка мы слышим два звука. После согласной ё часто показывает её мягкость."
                'Е', 'Ю', 'Я' -> "Это буква $name. В начале слова она обозначает два звука. После согласной она часто показывает её мягкость."
                'Ъ' -> "Твёрдый знак сам не звучит. В слове съёмка он отделяет согласную от буквы ё."
                'Ь' -> "Мягкий знак сам не звучит. В слове конь он показывает, что последний согласный мягкий."
                'Ы' -> "Это буква ы. Послушай гласный звук в середине слова сыр."
                'Й' -> "Это буква и краткое. Её звук короткий, как в начале слова йогурт."
                in "ОУЭИ" -> "Это буква $name. Послушай её название и слово, в котором слышен этот гласный."
                else -> "Это буква $name. У буквы есть имя. Послушай согласный звук в начале слова."
            }
            builder.lesson("letter-$index", "Буква $symbol") {
                val lead = if (index == 0) "Привет! Я робот Буквик. Давай научимся читать. " else ""
                val sound = if (letter in "ЪЬ") CourseCue("$word.", word.unstressed()) else
                    CourseCue("$word.", "Звук в слове «${word.unstressed()}»", "letter-$index")
                scene("letter-$index-show", symbol, explanation,
                    CourseNarration(CourseCue(lead + explanation), listOf(CourseCue("Буква $name.", symbol), sound)))
                val alternate = ALPHABET.filter { it != letter }
                val options = listOf(symbol, alternate[(index + 2) % alternate.length].toString(),
                    alternate[(index + 6) % alternate.length].toString())
                val prompt = when (letter) {
                    'Ъ' -> "Какой знак пропущен в слове «с_ёмка»?"
                    'Ь' -> "Какой знак пропущен в слове «кон_»?"
                    'Ы' -> "Какой гласной не хватает в слове «с_р»?"
                    else -> "Послушай слово и выбери его первую букву."
                }
                val quizExamples = if (letter in "ЪЬЫ") emptyList() else listOf(CourseCue("$word."))
                choice("letter-$index-match", symbol.lowercase(russian), prompt, options, CourseNarration(
                    CourseCue(prompt), quizExamples, CourseCue("Буква $name. $word.", symbol)))
                when (letter) {
                    'Б' -> blend("blend-ba", "БА", "Б", "А")
                    'М' -> blend("blend-ma", "МА", "М", "А")
                    'О' -> blend("blend-mo", "МО", "М", "О")
                    'У' -> blend("blend-lu", "ЛУ", "Л", "У")
                }
            }
        }
        builder.lesson("word-workshop", "Читаем слова") {
            scene("word-workshop-show", "РАМА", "Прочитай два слога, затем слово целиком.",
                CourseNarration(CourseCue("Прочитай два слога, затем слово целиком."),
                    listOf(CourseCue("Ра.", "РА"), CourseCue("Ма.", "МА"), CourseCue("Ра́ма.", "РАМА"))))
            choice("word-workshop-match", "рама", "Послушай слово. Найди его среди карточек.",
                listOf("РАМА", "ЛУНА", "МАМА"), CourseNarration(CourseCue("Послушай слово. Найди его среди карточек."),
                    listOf(CourseCue("Ра́ма.")), CourseCue("Ра́ма.", "РАМА")))
            order("word-workshop-order", ReadingWord(listOf("лу", "на́")))
        }
        builder.build(TITLE)
    }

    fun practice(items: List<ReadingItem>, number: Int, review: Boolean): ReadingCourse = Builder().apply {
        lesson("practice", if (review) "Повторение" else "Новые задания") {
            for ((index, item) in items.withIndex()) {
                val word = item.word
                if (item.order) order("practice-$index", word)
                else {
                    val distractors = vocabulary.filter { it.word != word.word && it.word.length == word.word.length }
                        .take(2).map { it.word.uppercase(russian) }
                    val prompt = "Послушай слово. Выбери карточку, на которой оно написано."
                    choice("practice-$index", word.word, prompt, listOf(word.word.uppercase(russian)) + distractors,
                        CourseNarration(CourseCue(prompt), listOf(CourseCue("${word.spoken}.")),
                            CourseCue("${word.spoken}.", word.word.uppercase(russian))))
                }
            }
        }
    }.build(if (review) "Повторение $number" else "Практика $number")

    private fun String.unstressed() = replace("\u0301", "")

    private class Builder {
        private val lessons = mutableListOf<CourseLessonPlan>()
        private val slides = linkedMapOf<String, CourseSlideContent>()
        private val current = mutableListOf<CourseSlidePlan>()

        fun lesson(id: String, title: String, block: Builder.() -> Unit) {
            block()
            lessons += CourseLessonPlan(id, title, current.toList())
            current.clear()
        }

        fun build(title: String) = ReadingCourse(CourseSetup(title, LEVEL, GOAL), CourseOutline(lessons.toList()), slides.toMap())

        fun blend(id: String, syllable: String, consonant: String, vowel: String) {
            val instruction = "Соедини $consonant и $vowel. Скажи слог вместе с Буквиком."
            scene(id, syllable, instruction, CourseNarration(CourseCue(instruction), listOf(CourseCue("${syllable.lowercase(russian)}.", syllable))))
        }

        fun scene(id: String, letters: String, text: String, audio: CourseNarration) {
            val ui = JSONObject().put("type", "column").put("children", JSONArray()
                .put(JSONObject().put("type", "text").put("text", text))
                .put(JSONObject().put("type", "canvas").put("text", "Буквик показывает $letters").put("surfaces", JSONArray().put("workshop")))
                .put(JSONObject().put("type", "button").put("text", "Помаши Буквику").put("action", "wave")))
            val spec = definition("Буквик", ui, JSONObject().put("wave", "boolean"), JSONObject().put("wave", false),
                sceneSource(letters)).put("surfaces", JSONArray().put(JSONObject().put("id", "workshop").put("width", 352).put("height", 220)))
            add(id, CourseSlideKind.EXPLANATION, text, spec, audio)
        }

        fun choice(id: String, skill: String, question: String, labels: List<String>, audio: CourseNarration) {
            require(labels.size >= 2 && labels.distinct().size == labels.size)
            exercise(id, skill, question, labels, CourseSlideKind.CHOICE, audio)
        }

        fun order(id: String, word: ReadingWord) {
            check(word.orderable)
            val prompt = "Собери слово из слогов. Нажимай на карточки по порядку."
            exercise(id, word.word, prompt, word.tiles, CourseSlideKind.ORDER,
                CourseNarration(CourseCue(prompt), listOf(CourseCue("${word.spoken}.")),
                    CourseCue("${word.spoken}.", word.word.uppercase(russian))))
        }

        private fun exercise(id: String, skill: String, question: String, labels: List<String>, kind: CourseSlideKind, audio: CourseNarration) {
            val options = labels.mapIndexed { index, label -> JSONObject().put("id", "tile$index").put("label", label) }
            val shift = (id.hashCode() and Int.MAX_VALUE) % options.size
            val displayed = if (kind == CourseSlideKind.ORDER) options.asReversed() else options.drop(shift) + options.take(shift)
            val key = JSONObject().put("kind", kind.name).put("question", question).put("options", JSONArray(displayed))
            if (kind == CourseSlideKind.ORDER) key.put("correctOrder", JSONArray(options.map { it.getString("id") }))
            else key.put("correct", "tile0")
            val spec = definition("Карточки Буквика", JSONObject().put("type", "text").put("text", "Помоги Буквику найти ответ."),
                JSONObject().put(EXERCISE_FIELD, "string"), JSONObject().put(EXERCISE_FIELD, key.toString()),
                "return {state: input.state};")
            add(id, kind, skill, spec, audio)
        }

        private fun add(id: String, kind: CourseSlideKind, goal: String, spec: JSONObject, audio: CourseNarration) {
            current += CourseSlidePlan(id, kind, goal)
            slides[id] = CourseSlideContent(spec.toString(), spec.getJSONObject("ui").toString(),
                spec.getJSONObject("initialState").toString(), narration = audio)
        }

        private fun definition(title: String, ui: JSONObject, schema: JSONObject, initial: JSONObject, source: String) =
            JSONObject().put("formatVersion", 3).put("title", title).put("description", "")
                .put("capabilities", JSONArray()).put("stateSchema", schema).put("initialState", initial)
                .put("ui", ui).put("source", source)

        private fun sceneSource(letters: String) = """
            const state = input.state;
            const event = input.event;
            if (event.type === 'pointer' && event.phase === 'up' ||
                event.type === 'action' && event.actionId === 'wave') state.wave = !state.wave;
            const hand = state.wave ? 77 : 158;
            return {state, surfaces:{workshop:{commands:[
                {type:'roundRect',x:0,y:0,width:352,height:220,radius:20,
                    gradient:{type:'linear',x:0,y:0,x2:352,y2:220,stops:[
                        {offset:0,color:'#172342'},{offset:1,color:'#39305A'}]}},
                {type:'circle',x:27,y:34,radius:2,color:'#ABC0EC'},
                {type:'circle',x:184,y:27,radius:3,color:'#FFD187'},
                {type:'circle',x:316,y:32,radius:2,color:'#ABC0EC'},
                {type:'circle',x:315,y:183,radius:3,color:'#FFD187'},
                {type:'roundRect',x:154,y:44,width:174,height:132,radius:20,color:'#FBF1DC'},
                {type:'text',x:${if (letters.length == 1) 207 else if (letters.length == 2) 192 else 171},
                    y:136,size:${if (letters.length <= 2) 66 else 48},text:${JSONObject.quote(letters)},color:'#36274A'},
                {type:'line',x:86,y:75,x2:86,y2:54,stroke:4,color:'#9CDDD9'},
                {type:'circle',x:86,y:50,radius:7,color:'#FFD187'},
                {type:'line',x:119,y:121,x2:139,y2:hand,stroke:7,color:'#9CDDD9'},
                {type:'circle',x:139,y:hand,radius:6,color:'#FFD187'},
                {type:'roundRect',x:41,y:76,width:89,height:73,radius:19,color:'#9CDDD9'},
                {type:'roundRect',x:51,y:86,width:69,height:48,radius:12,color:'#223654'},
                {type:'circle',x:71,y:102,radius:5,color:'#BDF1EE'},
                {type:'circle',x:99,y:102,radius:5,color:'#BDF1EE'},
                {type:'arc',x:73,y:105,width:25,height:18,start:0,sweep:180,stroke:3,color:'#FFD187'},
                {type:'roundRect',x:59,y:152,width:54,height:27,radius:10,color:'#7797C6'},
                {type:'text',x:56,y:202,size:14,text:'БУКВИК',color:'#E8EFFF'}
            ]}}};
        """.trimIndent()
    }
}
