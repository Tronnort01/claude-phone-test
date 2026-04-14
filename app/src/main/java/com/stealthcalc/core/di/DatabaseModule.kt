package com.stealthcalc.core.di

import android.content.Context
import androidx.room.Room
import com.stealthcalc.core.data.StealthDatabase
import com.stealthcalc.core.encryption.KeyStoreManager
import com.stealthcalc.vault.data.VaultDao
import com.stealthcalc.browser.data.LinkCollectionDao
import com.stealthcalc.browser.data.SavedLinkDao
import com.stealthcalc.recorder.data.RecordingDao
import com.stealthcalc.notes.data.NoteDao
import com.stealthcalc.notes.data.NoteFolderDao
import com.stealthcalc.notes.data.NoteTagDao
import com.stealthcalc.tasks.data.GoalDao
import com.stealthcalc.tasks.data.HabitDao
import com.stealthcalc.tasks.data.TaskDao
import com.stealthcalc.tasks.data.TaskListDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyStoreManager: KeyStoreManager
    ): StealthDatabase {
        val passphrase = keyStoreManager.getDatabasePassphrase()
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            StealthDatabase::class.java,
            "stealth.db"
        )
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideNoteDao(db: StealthDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideNoteFolderDao(db: StealthDatabase): NoteFolderDao = db.noteFolderDao()

    @Provides
    fun provideNoteTagDao(db: StealthDatabase): NoteTagDao = db.noteTagDao()

    @Provides
    fun provideTaskDao(db: StealthDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideTaskListDao(db: StealthDatabase): TaskListDao = db.taskListDao()

    @Provides
    fun provideHabitDao(db: StealthDatabase): HabitDao = db.habitDao()

    @Provides
    fun provideGoalDao(db: StealthDatabase): GoalDao = db.goalDao()

    @Provides
    fun provideRecordingDao(db: StealthDatabase): RecordingDao = db.recordingDao()

    @Provides
    fun provideSavedLinkDao(db: StealthDatabase): SavedLinkDao = db.savedLinkDao()

    @Provides
    fun provideLinkCollectionDao(db: StealthDatabase): LinkCollectionDao = db.linkCollectionDao()

    @Provides
    fun provideVaultDao(db: StealthDatabase): VaultDao = db.vaultDao()
}
