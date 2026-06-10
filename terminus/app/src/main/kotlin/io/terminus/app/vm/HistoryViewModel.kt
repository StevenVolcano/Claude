package io.terminus.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.terminus.app.data.AppStorage
import io.terminus.core.persistence.MatchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Past matches from the JSON records under `filesDir/history/`
 * (ARCHITECTURE.md §1.2 `ui.history`, §5).
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = AppStorage(app)

    private val _matches = MutableStateFlow<List<MatchRecord>>(emptyList())
    val matches: StateFlow<List<MatchRecord>> = _matches.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _matches.value = storage.listMatches()
        }
    }
}
