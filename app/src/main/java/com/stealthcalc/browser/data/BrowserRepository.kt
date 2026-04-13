package com.stealthcalc.browser.data

import com.stealthcalc.browser.model.LinkCollection
import com.stealthcalc.browser.model.SavedLink
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserRepository @Inject constructor(
    private val linkDao: SavedLinkDao,
    private val collectionDao: LinkCollectionDao
) {
    // --- Links ---

    fun getAllLinks(): Flow<List<SavedLink>> = linkDao.getAllLinks()

    fun getLinksByCollection(collectionId: String): Flow<List<SavedLink>> =
        linkDao.getLinksByCollection(collectionId)

    fun searchLinks(query: String): Flow<List<SavedLink>> = linkDao.searchLinks(query)

    suspend fun saveLink(link: SavedLink) = linkDao.insertLink(link)

    suspend fun updateLink(link: SavedLink) = linkDao.updateLink(link)

    suspend fun deleteLink(link: SavedLink) = linkDao.deleteLink(link)

    suspend fun saveLinkFromBrowser(url: String, title: String, collectionId: String? = null): SavedLink {
        val link = SavedLink(url = url, title = title, collectionId = collectionId)
        linkDao.insertLink(link)
        return link
    }

    // --- Collections ---

    fun getAllCollections(): Flow<List<LinkCollection>> = collectionDao.getAllCollections()

    suspend fun createCollection(name: String, color: Int? = null): LinkCollection {
        val collection = LinkCollection(name = name, color = color)
        collectionDao.insertCollection(collection)
        return collection
    }

    suspend fun updateCollection(collection: LinkCollection) = collectionDao.updateCollection(collection)

    suspend fun deleteCollection(collection: LinkCollection) = collectionDao.deleteCollection(collection)
}
