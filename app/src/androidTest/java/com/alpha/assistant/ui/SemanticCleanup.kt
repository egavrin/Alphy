package com.alpha.assistant.ui

import android.app.appsearch.AppSearchManager
import android.app.appsearch.AppSearchResult
import android.app.appsearch.AppSearchSession
import android.app.appsearch.SetSchemaRequest
import android.app.appsearch.SetSchemaResponse
import android.content.Context
import android.util.Log
import com.alpha.assistant.semantic.semanticRevision
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Removes the semantic indexes that a fixture opened. Each index is one AppSearch database that the platform keeps after the test.
 * Without removal, the runs fill the shared store and every new index fails with "No more space left".
 * An empty schema with force override deletes every document and type of that one database.
 * Teardown calls this, so it never throws: a failed removal must not replace the failure of the test or skip later cleanup.
 * It tries every index and logs the index name and the error class. The runner guard catches the orphans that a failure leaves.
 */
internal fun removeSemanticIndexes(context: Context, vararg indexes: String) {
    val direct = Executor { it.run() }
    fun <T> await(call: (Consumer<AppSearchResult<T>>) -> Unit): T {
        val result = CompletableFuture<AppSearchResult<T>>().also { future -> call(Consumer { future.complete(it) }) }.get(15, TimeUnit.SECONDS)
        check(result.isSuccess) { "AppSearch cleanup failed: ${result.errorMessage}" }
        return checkNotNull(result.resultValue)
    }
    for (index in indexes) {
        try {
            val manager = checkNotNull(context.getSystemService(AppSearchManager::class.java))
            await<AppSearchSession> { manager.createSearchSession(
                AppSearchManager.SearchContext.Builder("semantic-" + semanticRevision(index)).build(), direct, it) }.use { session ->
                await<SetSchemaResponse> { session.setSchema(SetSchemaRequest.Builder().setForceOverride(true).build(), direct, direct, it) }
            }
        } catch (failure: Exception) {
            Log.w("AlphySemanticCleanup", "Index removal failed for $index: ${failure.javaClass.simpleName}")
        }
    }
}

/** Removes the three indexes of a [ShellViewModel] on shell database [database]: HistorySearch, ToolController, and MemoryStore (their names are built in main code). */
internal fun removeShellIndexes(context: Context, database: String) =
    removeSemanticIndexes(context, "history:$database", "apps:$database-tools.db", "memory:$database-memory.db")
