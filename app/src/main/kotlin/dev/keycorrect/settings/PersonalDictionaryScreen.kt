package dev.keycorrect.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.keycorrect.AppContainer
import dev.keycorrect.R
import dev.keycorrect.core.data.UserWord
import dev.keycorrect.core.data.Validation
import java.util.Locale
import kotlinx.coroutines.launch

/** Lists, searches, adds and deletes (with undo) the words in the personal dictionary. */
@Composable
fun PersonalDictionaryScreen(contentPadding: PaddingValues, container: AppContainer) {
    val store = container.userDictionary
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var attempt by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }

    val state = rememberLoadState(attempt) { store.all() }
    val saveFailed = resources.getString(R.string.data_save_failed)
    fun reportFailure() {
        scope.launch { snackbar.showSnackbar(saveFailed) }
    }

    PersonalDataScaffold(
        outerPadding = contentPadding,
        addLabel = stringResource(R.string.dictionary_add),
        onAdd = { adding = true }.takeIf { state is LoadState.Loaded },
        snackbarHostState = snackbar,
    ) { inner ->
        when (state) {
            LoadState.Loading -> Box(Modifier.fillMaxSize().padding(inner))
            is LoadState.Failed ->
                DataErrorState(
                    error = state.cause,
                    onRetry = { attempt++ },
                    onReset = { container.resetPersonalData { attempt++ } },
                )
            is LoadState.Loaded -> {
                val words = state.value
                val filtered by
                    remember(words, query) {
                        derivedStateOf {
                            val q = query.trim().lowercase(Locale.getDefault())
                            if (q.isEmpty()) words
                            else words.filter { it.word.lowercase(Locale.getDefault()).contains(q) }
                        }
                    }
                WordList(
                    padding = inner,
                    allWords = words,
                    shown = filtered,
                    query = query,
                    onQueryChange = { query = it },
                    onDelete = { word ->
                        container.write(::reportFailure) {
                            store.remove(word.word)
                            // Tell the learning system so it does not promote the word again.
                            container.learningHost.onUserWordRemoved(word.word)
                        }
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val result =
                                snackbar.showSnackbar(
                                    message =
                                        resources.getString(R.string.dictionary_removed, word.word),
                                    actionLabel = resources.getString(R.string.data_action_undo),
                                    duration = SnackbarDuration.Long,
                                    withDismissAction = true,
                                )
                            if (result == SnackbarResult.ActionPerformed) {
                                container.write(::reportFailure) {
                                    store.add(word.word, word.frequency, word.locale)
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    if (adding) {
        AddWordDialog(
            onDismiss = { adding = false },
            onConfirm = { word ->
                adding = false
                container.write(::reportFailure) { store.add(word) }
            },
        )
    }
}

@Composable
private fun WordList(
    padding: PaddingValues,
    allWords: List<UserWord>,
    shown: List<UserWord>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDelete: (UserWord) -> Unit,
) {
    if (allWords.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.dictionary_empty_title),
            body = stringResource(R.string.dictionary_empty_body),
        )
        return
    }
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxWidth(),
            // Leave room for the floating action button below the last row.
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.dictionary_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            if (shown.isEmpty()) {
                item(key = "no-matches") {
                    Text(
                        stringResource(R.string.dictionary_no_matches),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(shown, key = { it.word }) { word ->
                ListItem(
                    headlineContent = { Text(word.word) },
                    trailingContent = {
                        DeleteButton(
                            description =
                                stringResource(R.string.dictionary_delete_description, word.word),
                            onClick = { onDelete(word) },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun AddWordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val error = Validation.checkWord(text)
    val save = {
        submitted = true
        if (error == null) onConfirm(Validation.normalizeWord(text))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dictionary_add_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.dictionary_word_label)) },
                isError = submitted && error != null,
                supportingText = {
                    Text(
                        if (submitted && error != null) validationMessage(error)
                        else stringResource(R.string.dictionary_word_hint)
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text(stringResource(R.string.data_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.data_action_cancel)) }
        },
    )
}
