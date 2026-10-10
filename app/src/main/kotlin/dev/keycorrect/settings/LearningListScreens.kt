package dev.keycorrect.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.ime.LearningHost
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** How many corrections the "Corrections it learned" list asks the learning system for. */
private const val CORRECTION_LIMIT = 200

/** Words the keyboard added to the personal dictionary on its own (or that you added). */
@Composable
fun LearnedWordsScreen(contentPadding: PaddingValues, container: AppContainer) {
    val resources = LocalResources.current
    LearningListScreen(
        contentPadding = contentPadding,
        container = container,
        load = { host ->
            host.overviewFlow().map { it.userWords.sortedBy { w -> w.word.lowercase() } }
        },
        key = { it.word },
        searchText = { it.word },
        headline = { it.word },
        supporting = { reasonLabel(it.reason) },
        emptyTitle = stringResource(R.string.learning_words_empty_title),
        emptyBody = stringResource(R.string.learning_words_empty_body),
        actionLabel = stringResource(R.string.learning_action_remove),
        actionDescription = { stringResource(R.string.learning_words_remove_description, it.word) },
        perform = { host, item -> host.removeLearnedWord(item.word) },
        doneMessage = { resources.getString(R.string.learning_words_removed, it.word) },
    )
}

/** Typo → fix pairs the keyboard learned from you. */
@Composable
fun LearnedCorrectionsScreen(contentPadding: PaddingValues, container: AppContainer) {
    val resources = LocalResources.current
    LearningListScreen(
        contentPadding = contentPadding,
        container = container,
        load = { host -> host.overviewFlow().map { host.topCorrections(CORRECTION_LIMIT) } },
        key = { "${it.typed}\u0000${it.target}" },
        searchText = { "${it.typed} ${it.target}" },
        headline = { stringResource(R.string.learning_pair_corrected, it.typed, it.target) },
        supporting = {
            if (it.contextual) stringResource(R.string.learning_corrections_contextual) else null
        },
        emptyTitle = stringResource(R.string.learning_corrections_empty_title),
        emptyBody = stringResource(R.string.learning_corrections_empty_body),
        actionLabel = stringResource(R.string.learning_action_forget),
        actionDescription = {
            stringResource(R.string.learning_corrections_forget_description, it.typed, it.target)
        },
        perform = { host, item -> host.forgetCorrection(item.typed, item.target) },
        doneMessage = {
            resources.getString(R.string.learning_corrections_forgotten, it.typed, it.target)
        },
    )
}

/** Pairs the keyboard never autocorrects (typed word, what it would have become). */
@Composable
fun NeverCorrectedScreen(contentPadding: PaddingValues, container: AppContainer) {
    val resources = LocalResources.current
    LearningListScreen(
        contentPadding = contentPadding,
        container = container,
        load = { host -> host.overviewFlow().map { it.suppressedCorrections } },
        key = { "${it.first}\u0000${it.second}" },
        searchText = { "${it.first} ${it.second}" },
        headline = { stringResource(R.string.learning_pair_never, it.first, it.second) },
        supporting = { null },
        emptyTitle = stringResource(R.string.learning_never_corrected_empty_title),
        emptyBody = stringResource(R.string.learning_never_corrected_empty_body),
        actionLabel = stringResource(R.string.learning_action_allow),
        actionDescription = {
            stringResource(R.string.learning_never_corrected_allow_description, it.first, it.second)
        },
        perform = { host, item -> host.removeSuppression(item.first, item.second) },
        doneMessage = {
            resources.getString(R.string.learning_never_corrected_allowed, it.first, it.second)
        },
    )
}

/** Words that were blocked from suggestions ("Never suggest"). */
@Composable
fun NeverSuggestedScreen(contentPadding: PaddingValues, container: AppContainer) {
    val resources = LocalResources.current
    LearningListScreen(
        contentPadding = contentPadding,
        container = container,
        load = { host ->
            host.overviewFlow().map { it.blockedWords.sortedBy { w -> w.lowercase() } }
        },
        key = { it },
        searchText = { it },
        headline = { it },
        supporting = { null },
        emptyTitle = stringResource(R.string.learning_never_suggested_empty_title),
        emptyBody = stringResource(R.string.learning_never_suggested_empty_body),
        actionLabel = stringResource(R.string.learning_action_unblock),
        actionDescription = {
            stringResource(R.string.learning_never_suggested_unblock_description, it)
        },
        perform = { host, item -> host.unblock(item) },
        doneMessage = { resources.getString(R.string.learning_never_suggested_unblocked, it) },
    )
}

/**
 * One searchable list of learned items with a per-item action. Changes are made on the application
 * scope; a snackbar confirms them (the learning system has no inverse operation, so there is no
 * Undo here).
 */
@Composable
private fun <T : Any> LearningListScreen(
    contentPadding: PaddingValues,
    container: AppContainer,
    load: (LearningHost) -> Flow<List<T>>,
    key: (T) -> String,
    searchText: (T) -> String,
    headline: @Composable (T) -> String,
    supporting: @Composable (T) -> String?,
    emptyTitle: String,
    emptyBody: String,
    actionLabel: String,
    actionDescription: @Composable (T) -> String,
    perform: suspend (LearningHost, T) -> Boolean,
    doneMessage: (T) -> String,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val failed = resources.getString(R.string.data_save_failed)
    val onResetFailed: () -> Unit = { scope.launch { snackbar.showSnackbar(failed) } }

    PersonalDataScaffold(
        outerPadding = contentPadding,
        addLabel = "",
        onAdd = null,
        snackbarHostState = snackbar,
    ) { inner ->
        LearningDataGate(container, inline = false, onResetFailed = onResetFailed) { host ->
            var attempt by remember { mutableIntStateOf(0) }
            var query by rememberSaveable { mutableStateOf("") }
            when (val state = rememberLoadState(attempt) { load(host) }) {
                LoadState.Loading -> Box(Modifier.fillMaxSize().padding(inner))
                is LoadState.Failed -> LearningLoadFailed(inner) { attempt++ }
                is LoadState.Loaded -> {
                    val items = state.value
                    val shown =
                        remember(items, query) {
                            val q = query.trim().lowercase(Locale.getDefault())
                            if (q.isEmpty()) items
                            else
                                items.filter {
                                    searchText(it).lowercase(Locale.getDefault()).contains(q)
                                }
                        }
                    if (items.isEmpty()) {
                        EmptyState(emptyTitle, emptyBody)
                    } else {
                        ItemList(
                            padding = inner,
                            shown = shown,
                            query = query,
                            onQueryChange = { query = it },
                            key = key,
                            headline = headline,
                            supporting = supporting,
                            actionLabel = actionLabel,
                            actionDescription = actionDescription,
                            onAction = { item ->
                                scope.launch {
                                    val result = container.writeAndAwait { perform(host, item) }
                                    snackbar.currentSnackbarData?.dismiss()
                                    snackbar.showSnackbar(
                                        if (result.isSuccess) doneMessage(item) else failed
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T : Any> ItemList(
    padding: PaddingValues,
    shown: List<T>,
    query: String,
    onQueryChange: (String) -> Unit,
    key: (T) -> String,
    headline: @Composable (T) -> String,
    supporting: @Composable (T) -> String?,
    actionLabel: String,
    actionDescription: @Composable (T) -> String,
    onAction: (T) -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.learning_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            if (shown.isEmpty()) {
                item(key = "no-matches") {
                    Text(
                        stringResource(R.string.learning_no_matches),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(shown, key = key) { item ->
                val description = actionDescription(item)
                val support = supporting(item)
                ListItem(
                    headlineContent = { Text(headline(item)) },
                    supportingContent = support?.let { { Text(it) } },
                    trailingContent = {
                        TextButton(
                            onClick = { onAction(item) },
                            modifier = Modifier.semantics { contentDescription = description },
                        ) {
                            Text(actionLabel)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
internal fun LearningLoadFailed(padding: PaddingValues, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding)) {
        CenteredPanel {
            Text(stringResource(R.string.learning_load_failed))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.data_action_retry)) }
        }
    }
}
