package com.stealthcalc.browser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.browser.data.BrowserRepository
import com.stealthcalc.browser.model.LinkCollection
import com.stealthcalc.browser.model.SavedLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LinkVaultState(
    val links: List<SavedLink> = emptyList(),
    val collections: List<LinkCollection> = emptyList(),
    val selectedCollectionId: String? = null,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
)

@HiltViewModel
class LinkVaultViewModel @Inject constructor(
    private val repository: BrowserRepository
) : ViewModel() {

    private val _selectedCollectionId = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _isSearchActive = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val links = combine(_selectedCollectionId, _searchQuery) { collectionId, query ->
        Pair(collectionId, query)
    }.flatMapLatest { (collectionId, query) ->
        when {
            query.isNotBlank() -> repository.searchLinks(query)
            collectionId != null -> repository.getLinksByCollection(collectionId)
            else -> repository.getAllLinks()
        }
    }

    val state: StateFlow<LinkVaultState> = combine(
        links,
        repository.getAllCollections(),
        _selectedCollectionId,
        _searchQuery,
        _isSearchActive,
    ) { links, collections, selectedId, query, searchActive ->
        LinkVaultState(
            links = links,
            collections = collections,
            selectedCollectionId = selectedId,
            searchQuery = query,
            isSearchActive = searchActive,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LinkVaultState()
    )

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }
    fun toggleSearch() {
        _isSearchActive.value = !_isSearchActive.value
        if (!_isSearchActive.value) _searchQuery.value = ""
    }
    fun selectCollection(id: String?) { _selectedCollectionId.value = id }

    fun createCollection(name: String) {
        viewModelScope.launch { repository.createCollection(name) }
    }

    fun deleteLink(link: SavedLink) {
        viewModelScope.launch { repository.deleteLink(link) }
    }

    fun deleteCollection(collection: LinkCollection) {
        viewModelScope.launch { repository.deleteCollection(collection) }
    }
}
