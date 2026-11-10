package com.alpha.assistant.courses

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import org.json.JSONObject
import java.util.UUID

/** Course storage is independent of the conversation and mini-app databases. */
class CourseStore(context: Context, name: String = "alpha-courses.db") : Closeable {
    private val helper = CourseDatabase(context.applicationContext, name)
    private val imageCleanup = com.alpha.assistant.tools.three.ThreeSceneAssetCleanup(context.applicationContext)
    private val lifecycleLock = Any()
    @Volatile private var closeRequested = false

    /** Reject new operations immediately. Call close to release the database. */
    fun beginClose() {
        closeRequested = true
    }

    /** Finish the current operation and close this store permanently. */
    override fun close() {
        beginClose()
        synchronized(lifecycleLock) { helper.close() }
    }

    fun create(setup: CourseSetup, outline: CourseOutline): CourseSnapshot = withOpenStore {
        setup.validate()
        val encoded = outline.encode()
        transaction {
            val id = UUID.randomUUID().toString()
            insertOrThrow("courses", null, ContentValues().apply {
                put("id", id)
                put("topic", setup.topic)
                put("level", setup.level)
                put("goal", setup.goal)
                put("conversation_id", setup.originatingConversationId)
                put("outline", encoded)
                put("revision", 1L)
                put("outline_revision", 1L)
                put("completed_slides", 0)
            })
            requireCourse(id)
        }
    }

    fun course(id: String): CourseSnapshot? = read { readCourse(id) }

    internal fun courseAt(id: String, revision: Long): CourseSnapshot = read { requireRevision(id, revision) }

    fun courses(): List<CourseSnapshot> = read {
        rawQuery("SELECT $COURSE_COLUMNS FROM courses ORDER BY rowid DESC", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.courseSnapshot()) }
        }
    }

    fun slide(courseId: String, slideId: String): CourseSlide? = read {
        rawQuery(
            """SELECT outline_revision, definition, ui, state, sources, adaptation_note FROM slides
                WHERE course_id=? AND slide_id=?""",
            arrayOf(courseId, slideId),
        ).use { row ->
            if (!row.moveToFirst()) return@use null
            CourseSlide(slideId, row.getLong(0), CourseSlideContent(
                definitionJson = row.getString(1),
                uiJson = row.getString(2),
                stateJson = row.getString(3),
                sources = decodeCourseSources(row.getString(4)),
                adaptationNote = row.getString(5),
            ))
        }
    }

    internal fun replaceReachedDefinition(
        courseId: String, outlineRevision: Long, slideId: String, expected: String, updated: String,
        authorized: () -> Boolean,
    ): Boolean = write {
        val course = readCourse(courseId) ?: return@write false
        if (course.outlineRevision != outlineRevision || course.progress.currentSlideId != slideId || !authorized())
            return@write false
        update("slides", ContentValues().apply { put("definition", updated) },
            "course_id=? AND slide_id=? AND outline_revision=? AND definition=?",
            arrayOf(courseId, slideId, outlineRevision.toString(), expected)) == 1
    }

    internal data class SceneSettings(val version: Long, val values: String)

    internal fun sceneSettings(courseId: String, slideId: String, outlineRevision: Long, definitionHash: String): SceneSettings =
        read { readSceneSettings(courseId, slideId, outlineRevision, definitionHash) }

    internal fun saveSceneSettings(courseId: String, courseRevision: Long, slideId: String, outlineRevision: Long,
        definition: String, definitionHash: String, expected: Long, values: String): SceneSettings = write {
        val course = requireRevision(courseId, courseRevision)
        require(course.requireReached(slideId).kind.allowsScene)
        val scene = rawQuery("SELECT definition FROM slides WHERE course_id=? AND slide_id=? AND outline_revision=?",
            arrayOf(courseId, slideId, outlineRevision.toString())).use { row ->
            if (row.moveToFirst()) courseSceneDefinition(row.getString(0)) else null
        }
        require(scene == courseSceneDefinition(definition)) { "The slide definition changed." }
        validateCourseState(values)
        val old = readSceneSettings(courseId, slideId, outlineRevision, definitionHash)
        check(old.version == expected) { "The slide settings changed." }
        val next = SceneSettings(Math.incrementExact(expected), values)
        val row = ContentValues().apply {
            put("course_id", courseId); put("slide_id", slideId)
            put("outline_revision", outlineRevision); put("definition_hash", definitionHash)
            put("version", next.version); put("settings", next.values)
        }
        check(insertWithOnConflict("slide_scene_settings", null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
        next
    }

    private fun SQLiteDatabase.readSceneSettings(courseId: String, slideId: String, outlineRevision: Long,
        definitionHash: String): SceneSettings = rawQuery(
        "SELECT version, settings FROM slide_scene_settings WHERE course_id=? AND slide_id=? AND outline_revision=? AND definition_hash=?",
        arrayOf(courseId, slideId, outlineRevision.toString(), definitionHash),
    ).use { if (it.moveToFirst()) SceneSettings(it.getLong(0), it.getString(1)) else SceneSettings(0, JSONObject().toString()) }

    /** Attempts are never trimmed. Read successive pages with the last returned sequence. */
    fun answers(courseId: String, afterSequence: Long = 0, limit: Int = 100): List<CourseAnswerAttempt> = withOpenStore {
        require(afterSequence >= 0) { "The answer sequence must be nonnegative." }
        require(limit in 1..200) { "Read 1 to 200 answers per page." }
        readableDatabase.rawQuery(
            """SELECT sequence, operation_id, slide_id, kind, value, correct FROM answers
                WHERE course_id=? AND sequence>? ORDER BY sequence LIMIT ?""",
            arrayOf(courseId, afterSequence.toString(), limit.toString()),
        ).use { rows -> buildList {
            while (rows.moveToNext()) add(CourseAnswerAttempt(
                sequence = rows.getLong(0),
                operationId = rows.getString(1),
                slideId = rows.getString(2),
                answer = decodeCourseAnswer(CourseSlideKind.valueOf(rows.getString(3)), rows.getString(4)),
                correct = rows.getInt(5) == 1,
            ))
        } }
    }

    /** An explicit retry replaces an interrupted operation at the current course revision. */
    fun beginGeneration(courseId: String, expectedRevision: Long, slideId: String): CourseGenerationOperation = write {
        val course = requireRevision(courseId, expectedRevision)
        course.requireReached(slideId)
        if (slideExists(courseId, slideId)) reject(CourseRejection.SLIDE_ALREADY_GENERATED)
        val updated = updateCourse(course, ContentValues().apply {
            put("generation_id", UUID.randomUUID().toString())
            putNull("generation_failure")
        })
        checkNotNull(updated.generation)
    }

    fun commitGeneration(operation: CourseGenerationOperation, content: CourseSlideContent): CourseSnapshot = write {
        val course = requireGeneration(operation)
        insertSlide(course, operation.slideId, content)
        updateCourse(course, ContentValues().apply { putNull("generation_id") })
    }

    internal fun adoptNext(request: CourseNextRequest, content: CourseSlideContent?): CourseSnapshot = write {
        val course = requireRevision(request.courseId, request.revision)
        val target = course.nextTarget(request.visibleSlideId) ?: reject(CourseRejection.SLIDE_NOT_REACHED)
        val slideId = target.step.slide.id
        if (!slideExists(course.id, request.visibleSlideId)) reject(CourseRejection.SLIDE_NOT_GENERATED)
        if (content == null) {
            if (!slideExists(course.id, slideId)) reject(CourseRejection.SLIDE_NOT_GENERATED)
            if (target.index == course.progress.completedSlides) return@write course
        } else {
            if (slideExists(course.id, slideId)) reject(CourseRejection.SLIDE_ALREADY_GENERATED)
            insertSlide(course, slideId, content)
        }
        updateCourse(course, ContentValues().apply {
            put("completed_slides", target.index)
            putNull("generation_id")
            putNull("generation_failure")
        })
    }

    private fun SQLiteDatabase.insertSlide(course: CourseSnapshot, slideId: String, content: CourseSlideContent) {
        val sources = content.encodeSources()
        insertOrThrow("slides", null, ContentValues().apply {
            put("course_id", course.id)
            put("slide_id", slideId)
            put("outline_revision", course.outlineRevision)
            put("definition", content.definitionJson)
            put("ui", content.uiJson)
            put("state", content.stateJson)
            put("sources", sources)
            put("adaptation_note", content.adaptationNote)
        })
    }

    fun failGeneration(operation: CourseGenerationOperation, reason: CourseGenerationFailure): CourseSnapshot = write {
        val course = requireGeneration(operation)
        updateCourse(course, ContentValues().apply {
            putNull("generation_id")
            put("generation_failure", reason.name)
        })
    }

    fun commitAnswer(courseId: String, submission: CourseAnswerSubmission): CourseSnapshot = write {
        if (exists("SELECT 1 FROM answers WHERE course_id=? AND operation_id=?", courseId, submission.operationId)) {
            reject(CourseRejection.DUPLICATE_ANSWER)
        }
        val course = requireRevision(courseId, submission.expectedRevision)
        val slide = course.requireReached(submission.slideId)
        if (slide.kind != submission.answer.kind) reject(CourseRejection.ANSWER_TYPE_MISMATCH)
        if (!slideExists(courseId, slide.id)) reject(CourseRejection.SLIDE_NOT_GENERATED)
        courseText(submission.operationId, 128, "Answer operation ID")
        val encoded = submission.answer.encode()
        validateCourseState(submission.resultingStateJson)
        submission.resultingUiJson?.let(::validateCourseUi)
        insertOrThrow("answers", null, ContentValues().apply {
            put("course_id", course.id)
            put("operation_id", submission.operationId)
            put("slide_id", slide.id)
            put("kind", slide.kind.name)
            put("value", encoded)
            put("correct", submission.correct)
        })
        update("slides", ContentValues().apply {
            put("state", submission.resultingStateJson)
            submission.resultingUiJson?.let { put("ui", it) }
        }, "course_id=? AND slide_id=?", arrayOf(course.id, slide.id))
        updateCourse(course, ContentValues().apply {
            val completed = course.progress.completedSlides
            put("completed_slides", if (submission.correct) completed + 1 else completed)
        })
    }

    fun advance(courseId: String, expectedRevision: Long, slideId: String): CourseSnapshot = write {
        val course = requireRevision(courseId, expectedRevision)
        val slide = course.requireReached(slideId)
        if (slide.kind.requiresAnswer) reject(CourseRejection.ANSWER_REQUIRED)
        if (!slideExists(courseId, slideId)) reject(CourseRejection.SLIDE_NOT_GENERATED)
        updateCourse(course, ContentValues().apply { put("completed_slides", course.progress.completedSlides + 1) })
    }

    fun editOutline(courseId: String, expectedRevision: Long, outline: CourseOutline): CourseSnapshot = withOpenStore {
        val encoded = outline.encode()
        transaction {
            val course = requireRevision(courseId, expectedRevision)
            if (outline == course.outline) return@transaction course
            val reached = course.progress.currentSlideId
            val attempted = reached != null && rawQuery(
                "SELECT slide_id FROM answers WHERE course_id=? ORDER BY sequence DESC LIMIT 1", arrayOf(courseId),
            ).use { it.moveToFirst() && it.getString(0) == reached }
            val protected = course.progress.completedSlides + if (attempted) 1 else 0
            if (course.outline.steps().take(protected) != outline.steps().take(protected)) {
                reject(CourseRejection.PROTECTED_PROGRESS)
            }
            if (reached != null && !attempted) delete("slides", "course_id=? AND slide_id=?", arrayOf(courseId, reached))
            updateCourse(course, ContentValues().apply {
                put("outline", encoded)
                put("outline_revision", Math.incrementExact(course.outlineRevision))
                putNull("generation_id")
                putNull("generation_failure")
            })
        }.also { imageCleanup.request() }
    }

    private fun SQLiteDatabase.readCourse(id: String): CourseSnapshot? = rawQuery(
        "SELECT $COURSE_COLUMNS FROM courses WHERE id=?", arrayOf(id),
    ).use { if (it.moveToFirst()) it.courseSnapshot() else null }

    private fun SQLiteDatabase.requireCourse(id: String): CourseSnapshot =
        readCourse(id) ?: reject(CourseRejection.COURSE_NOT_FOUND)

    private fun SQLiteDatabase.requireRevision(id: String, revision: Long): CourseSnapshot {
        val course = requireCourse(id)
        if (course.revision != revision) reject(CourseRejection.STALE_REVISION)
        return course
    }

    private fun SQLiteDatabase.requireGeneration(operation: CourseGenerationOperation): CourseSnapshot {
        val course = requireCourse(operation.courseId)
        if (course.generation != operation) reject(CourseRejection.STALE_GENERATION)
        return course
    }

    private fun CourseSnapshot.requireReached(slideId: String): CourseSlidePlan {
        if (progress.currentSlideId != slideId) reject(CourseRejection.SLIDE_NOT_REACHED)
        return outline.lessons.first { it.id == progress.currentLessonId }.slides.first { it.id == slideId }
    }

    private fun SQLiteDatabase.slideExists(courseId: String, slideId: String): Boolean =
        exists("SELECT 1 FROM slides WHERE course_id=? AND slide_id=?", courseId, slideId)

    private fun SQLiteDatabase.exists(sql: String, vararg args: String): Boolean =
        rawQuery(sql, args).use { it.moveToFirst() }

    private fun SQLiteDatabase.updateCourse(course: CourseSnapshot, values: ContentValues): CourseSnapshot {
        values.put("revision", Math.incrementExact(course.revision))
        check(update("courses", values, "id=?", arrayOf(course.id)) == 1) { "The course update failed." }
        return requireCourse(course.id)
    }

    private fun Cursor.courseSnapshot(): CourseSnapshot {
        val id = getString(0)
        val revision = getLong(5)
        val outline = decodeCourseOutline(getString(7))
        val steps = outline.steps()
        val completed = getInt(8)
        check(completed in 0..steps.size) { "Course progress is outside the outline." }
        val current = steps.getOrNull(completed)
        return CourseSnapshot(
            id = id,
            setup = CourseSetup(getString(1), getString(2), getString(3), getString(4)),
            revision = revision,
            outlineRevision = getLong(6),
            outline = outline,
            progress = CourseProgress(completed, steps.size, current?.lessonId, current?.slide?.id),
            generation = getString(9)?.let { CourseGenerationOperation(it, id, checkNotNull(current).slide.id, revision) },
            generationFailure = getString(10)?.let(CourseGenerationFailure::valueOf),
        )
    }

    private fun reject(reason: CourseRejection): Nothing = throw CourseStoreException(reason)

    private fun checkOpen() {
        check(!closeRequested) { "The course store is closed." }
    }

    private fun <T> withOpenStore(block: CourseDatabase.() -> T): T {
        checkOpen()
        return synchronized(lifecycleLock) {
            checkOpen()
            helper.block()
        }
    }

    private fun <T> read(block: SQLiteDatabase.() -> T): T = withOpenStore { readableDatabase.block() }

    private fun <T> write(block: SQLiteDatabase.() -> T): T = withOpenStore { transaction(block) }

    private fun <T> CourseDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = db.block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    // Android's default corruption handler deletes the database file.
    private class CourseDatabase(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 2, DatabaseErrorHandler { }) {
        override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

        override fun onCreate(db: SQLiteDatabase) {
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE name <> 'android_metadata' AND substr(name, 1, 7) <> 'sqlite_' LIMIT 1", null).use {
                check(!it.moveToFirst()) { "Unknown course database version. Course data was kept." }
            }
            db.execSQL("""CREATE TABLE courses(
                id TEXT PRIMARY KEY NOT NULL,
                topic TEXT NOT NULL,
                level TEXT NOT NULL,
                goal TEXT NOT NULL,
                conversation_id TEXT,
                revision INTEGER NOT NULL,
                outline_revision INTEGER NOT NULL,
                outline TEXT NOT NULL,
                completed_slides INTEGER NOT NULL,
                generation_id TEXT,
                generation_failure TEXT
            )""")
            db.execSQL("""CREATE TABLE slides(
                course_id TEXT NOT NULL REFERENCES courses(id),
                slide_id TEXT NOT NULL,
                outline_revision INTEGER NOT NULL,
                definition TEXT NOT NULL,
                ui TEXT NOT NULL,
                state TEXT NOT NULL,
                sources TEXT NOT NULL,
                adaptation_note TEXT,
                PRIMARY KEY(course_id, slide_id)
            )""")
            db.execSQL("""CREATE TABLE answers(
                sequence INTEGER PRIMARY KEY,
                course_id TEXT NOT NULL,
                operation_id TEXT NOT NULL,
                slide_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                value TEXT NOT NULL,
                correct INTEGER NOT NULL,
                UNIQUE(course_id, operation_id),
                FOREIGN KEY(course_id, slide_id) REFERENCES slides(course_id, slide_id)
            )""")
            db.execSQL("CREATE INDEX answers_course_sequence ON answers(course_id, sequence)")
            createSceneSettings(db)
        }

        private fun createSceneSettings(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE slide_scene_settings(
                course_id TEXT NOT NULL,
                slide_id TEXT NOT NULL,
                outline_revision INTEGER NOT NULL,
                definition_hash TEXT NOT NULL,
                version INTEGER NOT NULL,
                settings TEXT NOT NULL,
                PRIMARY KEY(course_id, slide_id),
                FOREIGN KEY(course_id, slide_id) REFERENCES slides(course_id, slide_id) ON DELETE CASCADE
            )""")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 1 && newVersion == 2) createSceneSettings(db)
            else unknownVersion(oldVersion)
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = unknownVersion(oldVersion)

        private fun unknownVersion(version: Int): Unit = error("Unknown course database version $version. Course data was kept.")
    }

    private companion object {
        const val COURSE_COLUMNS = "id, topic, level, goal, conversation_id, revision, outline_revision, outline, completed_slides, generation_id, generation_failure"
    }
}
